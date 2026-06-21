;; Fork-specific extensions loaded into swarmforge namespace via (load-file ...).
;; No ns declaration — evaluated in swarmforge namespace at load time.
;; Add new ADR implementations here to minimize future swarmforge.bb merge conflicts.

(require '[cheshire.core :as json])

;;; ADR 0020 + 0012: Worktree settings (auto-compaction + advisor model)

(defn write-worktree-settings!
  "Write .claude/settings.local.json with auto-compaction keys and optional advisor model."
  ([worktree-path] (write-worktree-settings! worktree-path ""))
  ([worktree-path advisor-model]
   (let [settings-dir (fs/path worktree-path ".claude")
         settings-file (fs/path settings-dir "settings.local.json")]
     (fs/create-dirs settings-dir)
     (let [cfg (try (json/parse-string (slurp (str settings-file)) true)
                    (catch Exception _ {}))
           cfg (-> cfg
                   (assoc :autoCompactEnabled true)
                   (assoc-in [:env :CLAUDE_AUTOCOMPACT_PCT_OVERRIDE] "88")
                   (assoc-in [:env :CLAUDE_CODE_AUTO_COMPACT_WINDOW] "200000"))
           marker-path (str worktree-path "/.swarmforge/agent-running")
           cfg (-> cfg
                   (assoc-in [:hooks :UserPromptSubmit] [{:hooks [{:type "command" :command (str "touch " marker-path)}]}])
                   (assoc-in [:hooks :Stop] [{:hooks [{:type "command" :command (str "rm -f " marker-path)}]}]))
           cfg (if (seq advisor-model)
                 (assoc cfg :advisorModel advisor-model)
                 cfg)]
       (spit (str settings-file) (json/generate-string cfg {:pretty true}))))))

;;; ADR 0017: Inlined prompt bundle + swarm-persona skill

(defn resolve-prompt-bundle
  "Collect all .prompt files referenced transitively from constitution + role prompt."
  [working-dir constitution-file roles-dir role]
  (let [working-dir-str (str working-dir)]
    (loop [queue [(str constitution-file) (str (fs/path roles-dir (str role ".prompt")))]
           seen #{}
           bundle []]
      (if-let [file (first queue)]
        (let [rel (str/replace-first file (str working-dir-str "/") "")]
          (if (or (contains? seen rel) (not (fs/exists? (fs/path file))))
            (recur (rest queue) seen bundle)
            (let [content (slurp file)
                  refs (->> (re-seq #"swarmforge/[A-Za-z0-9_./-]+\.prompt" content)
                            distinct
                            (map #(str working-dir-str "/" %))
                            (remove #(contains? seen (str/replace-first % (str working-dir-str "/") ""))))
                  article-files (when (str/ends-with? file "constitution.prompt")
                                  (let [articles-dir (fs/path (str/replace file "constitution.prompt" "constitution/articles"))]
                                    (when (fs/exists? articles-dir)
                                      (->> (fs/list-dir articles-dir)
                                           (filter #(str/ends-with? (str (fs/file-name %)) ".prompt"))
                                           (map str)
                                           (remove #(contains? seen (str/replace-first % (str working-dir-str "/") "")))))))
                  new-queue (concat (rest queue) refs article-files)]
              (recur new-queue (conj seen rel) (conj bundle rel)))))
        bundle))))

(defn write-persona-skill-file!
  "Create .claude/skills/swarm-persona/SKILL.md with bundled role+constitution."
  [ctx role worktree-path]
  (let [working-dir (:working-dir ctx)
        skill-dir (fs/path worktree-path ".claude" "skills" "swarm-persona")
        skill-file (fs/path skill-dir "SKILL.md")
        bundle-files (resolve-prompt-bundle working-dir (:constitution-file ctx) (:roles-dir ctx) role)
        knowledge-files ["AGENTS.md" (str ".agents/roles/" role ".md")]]
    (fs/create-dirs skill-dir)
    (spit (str skill-file)
          (str "---\n"
               "name: swarm-persona\n"
               "description: Load this agent's SwarmForge role, constitution, and operating instructions\n"
               "---\n\n"
               "<swarmforge_agent_context role=\"" role "\">\n"
               "<instructions>\n"
               "This prompt bundle is pre-resolved. Do not open or re-read any swarmforge/*.prompt files"
               " — all relevant instructions are already included below. Project knowledge files"
               " (AGENTS.md and your role file under .agents/roles/) are included below when present.\n"
               "</instructions>\n"
               (apply str
                      (for [rel bundle-files
                            :let [abs (fs/path (str working-dir) rel)]
                            :when (fs/exists? abs)]
                        (str "<file path=\"" rel "\">\n" (slurp (str abs)) "\n</file>\n")))
               (apply str
                      (for [rel knowledge-files
                            :let [abs (fs/path (str working-dir) rel)]
                            :when (fs/exists? abs)]
                        (str "<file path=\"" rel "\">\n" (slurp (str abs)) "\n</file>\n")))
               "</swarmforge_agent_context>\n"))))

;; Override upstream's write-agent-instruction-file! to use swarm-persona skill pointer.
(defn write-agent-instruction-file! [role prompt-file]
  (spit (str prompt-file)
        (str "You are the " role " in a SwarmForge multi-agent development swarm. "
             "Your full role, constitution, and operating instructions are in your swarm-persona skill. "
             "Invoke the swarm-persona skill at the start of every session and before responding to any handoff.\n")))

;;; ADR 0006: Sparse checkout to hide QA holdout from non-QA/specifier worktrees

(defn sparse-checkout-setup!
  "Configure sparse checkout to exclude qa-holdout-path for non-QA/specifier roles."
  [worktree-path qa-holdout-path role]
  (when-not (#{"specifier" "QA"} role)
    (process/sh {:continue true} "git" "-C" (str worktree-path) "sparse-checkout" "init" "--no-cone")
    (let [git-dir-res (process/sh {:continue true} "git" "-C" (str worktree-path) "rev-parse" "--git-dir")
          git-dir (str/trim (:out git-dir-res))
          git-dir-path (if (fs/absolute? (fs/path git-dir))
                         (fs/path git-dir)
                         (fs/path worktree-path git-dir))
          sparse-file (fs/path git-dir-path "info" "sparse-checkout")]
      (fs/create-dirs (fs/parent sparse-file))
      (spit (str sparse-file) (str "/*\n!/" qa-holdout-path "/\n")))
    (process/sh {:continue true} "git" "-C" (str worktree-path) "read-tree" "-mu" "HEAD")))

;;; ADR 0018: Skill installation

(defn- parse-pins-file [pins-file]
  (into {} (for [line (str/split-lines (slurp (str pins-file)))
                 :let [line (str/trim line)]
                 :when (and (seq line)
                            (not (str/starts-with? line "#"))
                            (str/includes? line "="))
                 :let [sep (str/index-of line "=")]
                 :when sep]
             [(str/trim (subs line 0 sep)) (str/trim (subs line (inc sep)))])))

(defn install-skills!
  "Install local skills and pinned entire and mattpocock skills into .claude/skills/."
  [ctx]
  (let [pins-file (fs/path (:script-dir ctx) "install-pins.conf")]
    (when (fs/exists? pins-file)
      (let [pins (parse-pins-file pins-file)
            entire-sha (get pins "ENTIRE_SKILLS_SHA")
            mattpocock-sha (get pins "MATTPOCOCK_SKILLS_SHA")
            mattpocock-include (when-let [inc (get pins "MATTPOCOCK_SKILLS_INCLUDE")]
                                 (set (map str/trim (str/split inc #","))))
            skills-src (fs/path (:script-dir ctx) ".." "skills")
            skills-dst (fs/path (:working-dir ctx) ".claude" "skills")]
        (println (str cyan "Installing skills..." reset))
        (fs/create-dirs (:state-dir ctx))
        (fs/create-dirs skills-dst)
        (when (fs/exists? skills-src)
          (doseq [skill-dir (->> (fs/list-dir skills-src) (filter fs/directory?))]
            (let [skill-name (str (fs/file-name skill-dir))
                  dst (fs/path skills-dst skill-name)]
              (when (fs/exists? dst) (fs/delete-tree dst))
              (fs/copy-tree skill-dir dst)
              (println (str "  " green "✓" reset " " skill-name " (local)")))))
        (when entire-sha
          (let [tmp-dir (str (fs/create-temp-dir))
                url (str "https://github.com/entireio/skills/archive/" entire-sha ".tar.gz")
                result (process/sh {:continue true} "sh" "-c"
                                   (str "curl -fsSL " (sq url) " | tar -xz --strip-components=1 -C " (sq tmp-dir)))]
            (if (zero? (:exit result))
              (do
                (let [skills-extracted (fs/path tmp-dir "skills")]
                  (when (fs/exists? skills-extracted)
                    (doseq [skill-dir (->> (fs/list-dir skills-extracted) (filter fs/directory?))]
                      (let [skill-name (str (fs/file-name skill-dir))
                            dst (fs/path skills-dst skill-name)]
                        (when (fs/exists? dst) (fs/delete-tree dst))
                        (fs/copy-tree skill-dir dst)))))
                (fs/delete-tree tmp-dir)
                (println (str "  " green "✓" reset " entire skills (" (subs entire-sha 0 8) ")"))
                (spit (str (fs/path (:state-dir ctx) "skills-installed")) entire-sha))
              (do
                (fs/delete-tree tmp-dir)
                (println (str "  " yellow "⚠" reset " entire skills unavailable (no network?) — proceeding without them"))))))
        (when mattpocock-sha
          (let [tmp-dir (str (fs/create-temp-dir))
                url (str "https://github.com/mattpocock/skills/archive/" mattpocock-sha ".tar.gz")
                result (process/sh {:continue true} "sh" "-c"
                                   (str "curl -fsSL " (sq url) " | tar -xz --strip-components=1 -C " (sq tmp-dir)))]
            (if (zero? (:exit result))
              (do
                (let [skills-root (fs/path tmp-dir "skills")]
                  (when (fs/exists? skills-root)
                    (doseq [subdir (->> (fs/list-dir skills-root) (filter fs/directory?))
                            skill-dir (->> (fs/list-dir subdir) (filter fs/directory?))
                            :let [skill-name (str (fs/file-name skill-dir))]
                            :when (or (nil? mattpocock-include) (contains? mattpocock-include skill-name))]
                      (let [dst (fs/path skills-dst skill-name)]
                        (when (fs/exists? dst) (fs/delete-tree dst))
                        (fs/copy-tree skill-dir dst)))))
                (fs/delete-tree tmp-dir)
                (println (str "  " green "✓" reset " mattpocock skills (" (subs mattpocock-sha 0 8) ")"))
                (spit (str (fs/path (:state-dir ctx) "mattpocock-skills-installed")) mattpocock-sha))
              (do
                (fs/delete-tree tmp-dir)
                (println (str "  " yellow "⚠" reset " mattpocock skills unavailable (no network?) — proceeding without them"))))))))))

(defn ensure-skills-installed!
  "Install skills if pins changed or first run."
  [ctx]
  (let [pins-file (fs/path (:script-dir ctx) "install-pins.conf")]
    (when (fs/exists? pins-file)
      (let [pins (parse-pins-file pins-file)
            entire-sha (get pins "ENTIRE_SKILLS_SHA")
            mattpocock-sha (get pins "MATTPOCOCK_SKILLS_SHA")
            sentinel (fs/path (:state-dir ctx) "skills-installed")
            mattpocock-sentinel (fs/path (:state-dir ctx) "mattpocock-skills-installed")]
        (when-not (and (fs/exists? sentinel)
                       (= entire-sha (str/trim (slurp (str sentinel))))
                       (or (nil? mattpocock-sha)
                           (and (fs/exists? mattpocock-sentinel)
                                (= mattpocock-sha (str/trim (slurp (str mattpocock-sentinel)))))))
          (install-skills! ctx))))))

;;; ADR 0021: Curator skill links

(defn link-curator-skills!
  "Symlink .agents/skills/* into .claude/skills/."
  [target-path]
  (let [agents-skills-dir (fs/path target-path ".agents" "skills")
        claude-skills-dir (fs/path target-path ".claude" "skills")]
    (when (fs/exists? agents-skills-dir)
      (fs/create-dirs claude-skills-dir)
      (doseq [skill-dir (->> (fs/list-dir agents-skills-dir) (filter fs/directory?))]
        (let [skill-name (str (fs/file-name skill-dir))
              link (fs/path claude-skills-dir skill-name)]
          (when-not (fs/exists? link)
            (process/sh {:continue true} "ln" "-sfn"
                        (str "../../.agents/skills/" skill-name)
                        (str link))))))))
