#!/usr/bin/env bb
;; Fork extension tests — verify fork.bb overrides exist and produce correct output.
;; Run: bb test/fork_runner.bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str])

;; Stubs for swarmforge.bb constants used only in install-skills! (not under test here).
(def cyan "") (def green "") (def yellow "") (def reset "")
(defn sq [v] (str "'" v "'"))

(load-file (str (fs/cwd) "/swarmforge/scripts/fork.bb"))

(def failures (atom []))

(defn check [label ok?]
  (if ok?
    (println (str "  ok  " label))
    (do (println (str "  FAIL " label))
        (swap! failures conj label))))

;;; write-agent-instruction-file!

(let [tmp (str (fs/create-temp-file {:prefix "test-instr" :suffix ".md"}))]
  (write-agent-instruction-file! "coder" tmp)
  (let [content (slurp tmp)]
    (check "agent-instruction: contains role identity"
           (str/includes? content "You are the coder in a SwarmForge multi-agent development swarm."))
    (check "agent-instruction: points to swarm-persona skill"
           (str/includes? content "swarm-persona skill"))
    (check "agent-instruction: no Invoke directive (double-load guard)"
           (not (str/includes? content "Invoke"))))
  (fs/delete (fs/path tmp)))

;;; write-worktree-settings!

(let [tmp (str (fs/create-temp-dir {:prefix "test-wt-"}))]
  (write-worktree-settings! tmp)
  (let [content (slurp (str (fs/path tmp ".claude" "settings.local.json")))]
    (check "worktree-settings: autoCompactEnabled"          (str/includes? content "autoCompactEnabled"))
    (check "worktree-settings: CLAUDE_AUTOCOMPACT_PCT_OVERRIDE" (str/includes? content "CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"))
    (check "worktree-settings: CLAUDE_CODE_AUTO_COMPACT_WINDOW" (str/includes? content "CLAUDE_CODE_AUTO_COMPACT_WINDOW"))
    (check "worktree-settings: UserPromptSubmit hook"       (str/includes? content "UserPromptSubmit"))
    (check "worktree-settings: Stop hook"                   (str/includes? content "Stop"))
    (check "worktree-settings: gh pr merge allow rule"      (str/includes? content "gh pr merge"))
    (check "worktree-settings: git reset allow rule"        (str/includes? content "git reset --hard origin/")))
  (fs/delete-tree (fs/path tmp)))

;;; write-persona-skill-file! (exercises resolve-prompt-bundle transitively)

(let [root (str (fs/create-temp-dir {:prefix "test-persona-root-"}))
      wt   (str (fs/create-temp-dir {:prefix "test-persona-wt-"}))]
  (fs/create-dirs (fs/path root "swarmforge" "constitution" "articles"))
  (spit (str (fs/path root "swarmforge" "constitution.prompt")) "# Constitution\n")
  (spit (str (fs/path root "swarmforge" "constitution" "articles" "workflow.prompt")) "# Workflow\n")
  (fs/create-dirs (fs/path root "swarmforge" "roles"))
  (spit (str (fs/path root "swarmforge" "roles" "coder.prompt")) "# Coder\n")
  (let [ctx {:working-dir (fs/path root)
             :constitution-file (fs/path root "swarmforge" "constitution.prompt")
             :roles-dir (fs/path root "swarmforge" "roles")}
        skill-file (str (fs/path wt ".claude" "skills" "swarm-persona" "SKILL.md"))]
    (write-persona-skill-file! ctx "coder" wt)
    (let [content (slurp skill-file)]
      (check "persona-skill: SKILL.md created"              (fs/exists? (fs/path skill-file)))
      (check "persona-skill: name: swarm-persona"           (str/includes? content "name: swarm-persona"))
      (check "persona-skill: bundles role file"             (str/includes? content "swarmforge/roles/coder.prompt"))
      (check "persona-skill: bundles constitution article"  (str/includes? content "swarmforge/constitution"))))
  (fs/delete-tree (fs/path root))
  (fs/delete-tree (fs/path wt)))

;;; link-curator-skills!

(let [tmp (str (fs/create-temp-dir {:prefix "test-curator-"}))]
  (fs/create-dirs (fs/path tmp ".agents" "skills" "my-skill"))
  (spit (str (fs/path tmp ".agents" "skills" "my-skill" "SKILL.md")) "test\n")
  (link-curator-skills! tmp)
  (check "link-curator: symlink created in .claude/skills/"
         (fs/exists? (fs/path tmp ".claude" "skills" "my-skill")))
  (fs/delete-tree (fs/path tmp)))

;;; Report

(println)
(if (empty? @failures)
  (do (println (str "All " "fork.bb extension tests passed.")) (System/exit 0))
  (do (println (str (count @failures) " failure(s): " (str/join ", " @failures)))
      (System/exit 1)))
