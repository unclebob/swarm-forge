(ns swarmforge.script-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root (fs/cwd))
(def scripts-dir (fs/path repo-root "swarmforge" "scripts"))

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn run
  [{:keys [dir env ok?]} & args]
  (let [result (apply sh/sh (concat args [:dir (str dir)
                                          :env (merge {"PATH" (System/getenv "PATH")
                                                       "GIT_CONFIG_NOSYSTEM" "1"}
                                                      env)]))]
    (when (and (not (false? ok?)) (not= 0 (:exit result)))
      (throw (ex-info (str "Command failed: " (str/join " " args))
                      (assoc result :args args))))
    result))

(defn init-repo! [root]
  (run {:dir root} "git" "init" "-q")
  (run {:dir root} "git" "config" "user.email" "test@example.com")
  (run {:dir root} "git" "config" "user.name" "Test User")
  (write-file (fs/path root "README.md") "initial\n")
  (run {:dir root} "git" "add" "README.md")
  (run {:dir root} "git" "commit" "-q" "-m" "Initial commit"))

(defn tmp-dir []
  (fs/create-temp-dir {:prefix "swarmforge-script-test."}))

(defn script [name]
  (str (fs/path scripts-dir name)))

(deftest handoff-lib-parses-and-prints-handoff-files
  (let [root (tmp-dir)
        handoff-file (fs/path root "task.handoff")]
    (try
      (write-file handoff-file
                  (str "id: 1\n"
                       "from: coder\n"
                       "to: cleaner\n"
                       "priority: 10\n"
                       "type: git_handoff\n"
                       "task: task-alpha\n"
                       "\n"
                       "merge_and_process coder abcdef1234\n"))
      (let [header (run {:dir root} (script "handoff_lib.bb") "header-field" "task.handoff" "task")
            body (run {:dir root} (script "handoff_lib.bb") "body" "task.handoff")
            task (run {:dir root} (script "handoff_lib.bb") "print-task" "task.handoff")]
        (is (str/includes? (:out header) "task-alpha"))
        (is (str/includes? (:out body) "merge_and_process coder abcdef1234"))
        (is (str/includes? (:out task) "TASK: task.handoff"))
        (is (str/includes? (:out task) "FROM: coder"))
        (is (str/includes? (:out task) "TASK_NAME: task-alpha")))
      (finally
        (fs/delete-tree root)))))

(deftest handoff-lib-updates-headers-and-reads-role-state
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (str "coder\tmaster\t" root "\tsession\tCoder\tcodex\ttask\n"
                       "cleaner\tcleaner\t" root "/.worktrees/cleaner\tsession\tCleaner\tcodex\tbatch\n"))
      (write-file (fs/path root ".swarmforge/handoffs/inbox/new/item.handoff")
                  (str "id: 1\n"
                       "from: coder\n"
                       "to: cleaner\n"
                       "priority: 20\n"
                       "type: note\n"
                       "\n"
                       "payload\n"))
      (run {:dir root} (script "handoff_lib.bb") "role-known" "cleaner")
      (run {:dir root} (script "handoff_lib.bb") "set-header" ".swarmforge/handoffs/inbox/new/item.handoff" "dequeued_at" "2026-06-16T00:00:00Z")
      (let [mode (run {:dir root} (script "handoff_lib.bb") "role-receive-mode" "cleaner")
            worktree (run {:dir root} (script "handoff_lib.bb") "role-worktree-name" "cleaner")
            dequeued (run {:dir root} (script "handoff_lib.bb") "header-field" ".swarmforge/handoffs/inbox/new/item.handoff" "dequeued_at")
            seq-1 (run {:dir root} (script "handoff_lib.bb") "next-sequence")
            seq-2 (run {:dir root} (script "handoff_lib.bb") "next-sequence")]
        (is (str/includes? (:out mode) "batch"))
        (is (str/includes? (:out worktree) "cleaner"))
        (is (str/includes? (:out dequeued) "2026-06-16T00:00:00Z"))
        (is (str/includes? (:out seq-1) "000001"))
        (is (str/includes? (:out seq-2) "000002")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-launcher-parses-config-and-writes-state-files
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "# comment\n"
                       "window coder codex master\n"
                       "window cleaner codex cleaner batch\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (write-file (fs/path root "swarmforge/roles/cleaner.prompt") "cleaner\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "coder Coder"))
        (is (str/includes? (:out result) "cleaner Cleaner"))
        (is (str/includes? (:out result) "cleaner batch"))
        (is (str/includes? (:out result) "swarmforge-coder"))
        (is (str/includes? (:out result) "swarmforge-cleaner"))
        (is (fs/exists? (fs/path root ".swarmforge/tmux-socket"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-uses-portable-tmux-socket-dir
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window coder codex master\n")
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))
      (let [socket-path (str/trim (slurp (str (fs/path root ".swarmforge/tmux-socket"))))]
        (is (str/starts-with? socket-path "/tmp/swarmforge-"))
        (is (not (str/starts-with? socket-path "/private/tmp/"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-launcher-rejects-invalid-config
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window coder codex master\n"
                       "window coder codex other\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (let [result (run {:dir root :ok? false} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "Duplicate role 'coder'")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-parses-window-invisible
  ;; Given window-invisible specifier codex master
  ;; When --test-parse
  ;; Then specifier is listed and visible? is false
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window-invisible specifier codex master\n")
      (write-file (fs/path root "swarmforge/roles/specifier.prompt") "specifier\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "specifier"))
        (is (str/includes? (:out result) "invisible")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-required-helpers-include-pack-scripts
  ;; Given the launcher required-helpers list
  ;; When --test-required-helpers
  ;; Then pack_web.sh and pack_board.sh are listed
  (let [result (run {:dir repo-root} (script "swarmforge.bb") "--test-required-helpers")
        names (set (str/split-lines (str/trim (:out result))))]
    (is (contains? names "pack_web.sh"))
    (is (contains? names "pack_board.sh"))
    (is (contains? names "pack_dashboard_request.sh"))))

(defn write-pack-conf! [root conf]
  (write-file (fs/path root "swarmforge/constitution.prompt") "Read articles.\n")
  (write-file (fs/path root "swarmforge/swarmforge.conf") conf)
  (write-file (fs/path root "swarmforge/roles/specifier.prompt") "specifier\n")
  (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n"))

(deftest swarmforge-launch-plan-starts-pack-web-and-skips-invisible-terminals
  ;; Given window-invisible specifier and a visible coder window
  ;; When --test-launch-plan
  ;; Then pack_web starts, specifier skips Terminal, and coder still opens Terminal
  (let [root (tmp-dir)]
    (try
      (write-pack-conf! root
                        (str "window-invisible specifier codex master\n"
                             "window coder codex coder\n"))
      (let [out (:out (run {:dir root} (script "swarmforge.bb")
                           "--test-launch-plan" (str root)))]
        (is (str/includes? out "pack_web start"))
        (is (str/includes? out "skip-terminal specifier"))
        (is (str/includes? out "open-terminal coder"))
        (is (not (str/includes? out "skip-terminal coder")))
        (is (not (str/includes? out "open-terminal specifier"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-fails-without-a-master-worktree
  ;; Given only window coder codex coder
  ;; When --test-parse
  ;; Then exit 1 and error mentions master
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  "window coder codex coder\n")
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (let [result (run {:dir root :ok? false} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "master")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-fails-with-two-master-worktrees
  ;; Given two windows whose worktree is master
  ;; When --test-parse
  ;; Then exit 1 and error mentions master
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window specifier codex master\n"
                       "window coder codex master\n"))
      (write-file (fs/path root "swarmforge/roles/specifier.prompt") "specifier\n")
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (let [result (run {:dir root :ok? false} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (= 1 (:exit result)))
        (is (str/includes? (:err result) "master")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-terminal-bridge-preserves-adapter-globals
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/scripts/swarm-terminal-adapter.sh")
                  (str "load_terminal_backend() {\n"
                       "  source \"$SCRIPT_DIR/terminal-adapters/$1.sh\"\n"
                       "}\n"))
      (write-file (fs/path root "swarmforge/scripts/terminal-adapters/probe.sh")
                  (str "terminal_open_session() {\n"
                       "  printf '%s\\n' \"$WORKING_DIR|$TMUX_SOCKET|$1|$2|$3\"\n"
                       "}\n"))
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-terminal-bridge"
                        (str root)
                        "probe")]
        (is (str/includes? (:out result) (str root "|")))
        (is (str/includes? (:out result) "|swarmforge-specifier|SwarmForge Specifier|"))
        (is (not (str/includes? (:out result) "cd ''")))
        (is (not (str/includes? (:out result) "-S ''"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-agent-start-delay-is-configurable
  (let [default-result (run {:dir repo-root}
                            (script "swarmforge.bb")
                            "--test-agent-start-delay")
        configured-result (run {:dir repo-root
                                :env {"SWARMFORGE_AGENT_START_DELAY_MS" "2750"}}
                               (script "swarmforge.bb")
                               "--test-agent-start-delay")
        invalid-result (run {:dir repo-root
                             :env {"SWARMFORGE_AGENT_START_DELAY_MS" "fast"}}
                            (script "swarmforge.bb")
                            "--test-agent-start-delay")]
    (is (= "1500" (str/trim (:out default-result))))
    (is (= "2750" (str/trim (:out configured-result))))
    (is (= "1500" (str/trim (:out invalid-result))))))

(deftest swarmforge-sleep-prevention-can-be-disabled
  (let [result (run {:dir repo-root
                     :env {"SWARMFORGE_PREVENT_SLEEP" "0"}}
                    (script "swarmforge.bb")
                    "--test-sleep-inhibitor-prefix")]
    (is (= "" (str/trim (:out result))))))

(deftest swarmforge-launcher-parses-extra-cli-args
  (let [root (tmp-dir)]
    (try
      (write-file (fs/path root "swarmforge/constitution.prompt")
                  "Read articles.\n")
      (write-file (fs/path root "swarmforge/swarmforge.conf")
                  (str "window coder copilot master --yolo\n"
                       "window cleaner copilot cleaner batch --allow-all-tools\n"))
      (write-file (fs/path root "swarmforge/roles/coder.prompt") "coder\n")
      (write-file (fs/path root "swarmforge/roles/cleaner.prompt") "cleaner\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "coder Coder"))
        (is (str/includes? (:out result) "task --yolo"))
        (is (str/includes? (:out result) "batch --allow-all-tools")))
      (finally
        (fs/delete-tree root)))))

(deftest copilot-launch-command-passes-extra-cli-args
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "copilot"
                        "--yolo")
            command (:out result)]
        (is (str/includes? command "copilot -C "))
        (is (re-find #"--name 'SwarmForge Coder' --yolo -i" command)))
      (finally
        (fs/delete-tree root)))))

(deftest grok-launch-command-passes-initial-prompt
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "grok")
            command (:out result)]
        (is (str/includes? command "grok --cwd "))
        (is (str/includes? command "--permission-mode bypassPermissions"))
        (is (str/includes? command "--rules \"$(cat "))
        (is (str/includes? command "--verbatim \"$(cat "))
        (is (str/includes? command ".swarmforge/prompts/coder.md"))
        (is (fs/exists? (fs/path root ".swarmforge/prompts/coder.md"))))
      (finally
        (fs/delete-tree root)))))

(deftest grok-launch-command-uses-minimal-for-scrollback
  ;; Given a grok pack role
  ;; When SwarmForge builds the launch command
  ;; Then grok runs --minimal so finalized chatter is in tmux scrollback
  (let [root (tmp-dir)]
    (try
      (let [command (:out (run {:dir root}
                               (script "swarmforge.bb")
                               "--test-launch-command"
                               (str root)
                               "grok"))]
        (is (str/includes? command " --minimal ")))
      (finally
        (fs/delete-tree root)))))

(deftest grok-launch-command-uses-bypass-permissions-with-always-approve
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-launch-command"
                        (str root)
                        "grok"
                        "--always-approve")
            command (:out result)]
        (is (str/includes? command "--permission-mode bypassPermissions"))
        (is (str/includes? command "--always-approve"))
        (is (not (str/includes? command "--permission-mode acceptEdits"))))
      (finally
        (fs/delete-tree root)))))

(deftest launch-command-yolos-every-backend
  ;; Given a pack role with no extra-args
  ;; When --test-launch-command for each backend
  ;; Then the start command bypasses permission prompts
  (doseq [[agent needle] [["codex" "--yolo"]
                          ["copilot" "--yolo"]
                          ["claude" "--permission-mode bypassPermissions"]
                          ["grok" "--permission-mode bypassPermissions"]]]
    (let [root (tmp-dir)]
      (try
        (let [command (:out (run {:dir root}
                                 (script "swarmforge.bb")
                                 "--test-launch-command"
                                 (str root)
                                 agent))]
          (is (str/includes? command needle) agent))
        (finally
          (fs/delete-tree root))))))

(deftest launch-command-puts-project-tool-bin-on-path
  ;; Given a launched role
  ;; When the start command is built
  ;; Then `.swarmforge/bin` is on PATH so require/ensure wrappers are found
  (let [root (tmp-dir)]
    (try
      (let [command (:out (run {:dir root}
                               (script "swarmforge.bb")
                               "--test-launch-command"
                               (str root)
                               "codex"))]
        (is (str/includes? command (str ".swarmforge/bin':'"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarm-tool-require-and-ensure-install-aps-wrappers
  ;; Given a project without APS tools
  ;; When require runs, it reports missing
  ;; When ensure runs against a local APS source, wrappers land in .swarmforge/bin
  (let [root (tmp-dir)
        aps (fs/path root "aps-src")]
    (try
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (write-file (fs/path aps "bb.edn") "{:tasks {gherkin-parser identity\n  gherkin-ir-dry-checker identity}}\n")
      (let [missing (run {:dir root :ok? false}
                         (script "swarm_tool.sh") "require" "gherkin-parser")]
        (is (not= 0 (:exit missing)))
        (is (str/includes? (:err missing) "MISSING: gherkin-parser")))
      (run {:dir root
            :env {"SWARMFORGE_TOOL_SRC" (str aps)
                  "PATH" (System/getenv "PATH")
                  "GIT_CONFIG_NOSYSTEM" "1"}}
           (script "swarm_tool.sh") "ensure" "gherkin-parser")
      (run {:dir root
            :env {"SWARMFORGE_TOOL_SRC" (str aps)
                  "PATH" (System/getenv "PATH")
                  "GIT_CONFIG_NOSYSTEM" "1"}}
           (script "swarm_tool.sh") "ensure" "ir-dry-checker")
      (let [parser (fs/path root ".swarmforge/bin/gherkin-parser")
            dry (fs/path root ".swarmforge/bin/ir-dry-checker")]
        (is (fs/executable? parser))
        (is (fs/executable? dry))
        (is (zero? (:exit (run {:dir root} (script "swarm_tool.sh") "require" "gherkin-parser"))))
        (is (zero? (:exit (run {:dir root} (script "swarm_tool.sh") "require" "ir-dry-checker")))))
      (finally
        (fs/delete-tree root)))))

(deftest specifier-instruction-file-names-aps-require-ensure-and-tmp-argv
  ;; Given a specifier assignment
  ;; When SwarmForge writes the instruction file
  ;; Then Tool Startup names require/ensure and the two-arg parse/dry-check
  ;; forms into ./tmp/, and does not send the agent hunting under $HOME
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-instruction-file"
                        (str root)
                        "specifier")
            prompt (str/trim (:out result))
            path (fs/path root ".swarmforge/prompts/specifier.md")]
        (is (fs/exists? path))
        (is (str/includes? prompt "## Tool Startup"))
        (is (str/includes? prompt "swarm_tool.sh require gherkin-parser"))
        (is (str/includes? prompt "swarm_tool.sh ensure gherkin-parser"))
        (is (str/includes? prompt "swarm_tool.sh require ir-dry-checker"))
        (is (str/includes? prompt "swarm_tool.sh ensure ir-dry-checker"))
        (is (str/includes? prompt "gherkin-parser <feature> ./tmp/"))
        (is (str/includes? prompt "ir-dry-checker <ir> ./tmp/"))
        (is (str/includes? prompt "./tmp/")
            "parse, dry-check, and drafts belong in ./tmp/")
        (is (str/includes? prompt "/tmp")
            "must say not to use /tmp")
        (is (str/includes? prompt "outbox/tmp")
            "must say not to use the handoff outbox as scratch")
        (is (str/includes? prompt "$HOME")
            "must say not to search $HOME"))
      (finally
        (fs/delete-tree root)))))

(deftest tool-startup-names-board-path-and-receive-send-argv
  ;; Given a specifier assignment
  ;; When SwarmForge writes the instruction file
  ;; Then Tool Startup names .swarmforge/board/tasks.tsv, ready_for_next.sh,
  ;; and swarm_handoff.sh ./tmp/<draft>
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-instruction-file"
                        (str root)
                        "specifier")
            prompt (str/trim (:out result))]
        (is (str/includes? prompt ".swarmforge/board/tasks.tsv"))
        (is (not (str/includes? prompt "swarmforge/sessions.tsv")))
        (is (str/includes? prompt "ready_for_next.sh"))
        (is (str/includes? prompt "swarm_handoff.sh ./tmp/"))
        (is (str/includes? prompt "pack_dashboard_request.sh clarify"))
        (is (str/includes? prompt "Do not search the worktree for"))
        (is (str/includes? prompt "./swarmforge/scripts/"))
        (is (str/includes? prompt "crap4clj"))
        (is (str/includes? prompt "clj-mutate"))
        (is (str/includes? prompt "Do not invent"))
        (is (str/includes? prompt "Do not ask the operator what new feature"))
        (is (str/includes? prompt "Do not import behavior from sibling"))
        (is (str/includes? prompt "Finish the assigned"))
        (is (str/includes? prompt "one git_handoff")))
      (finally
        (fs/delete-tree root)))))

(deftest tool-startup-qa-forbids-duplicate-commit-fanout
  ;; Given a QA assignment
  ;; When SwarmForge writes the instruction file
  ;; Then Tool Startup forbids two git_handoffs of the same SHA
  (let [root (tmp-dir)]
    (try
      (let [prompt (str/trim (:out (run {:dir root}
                                        (script "swarmforge.bb")
                                        "--test-instruction-file"
                                        (str root)
                                        "QA")))]
        (is (str/includes? prompt "Do not send two git_handoffs of the same SHA")))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-start-order-opens-dashboard-before-agents
  ;; Given a pack
  ;; When --test-start-order
  ;; Then pack_web starts before agents
  (let [root (tmp-dir)]
    (try
      (write-pack-conf! root
                        (str "window-invisible specifier codex master\n"
                             "window coder codex coder\n"))
      (let [out (:out (run {:dir root} (script "swarmforge.bb")
                           "--test-start-order" (str root)))
            pack (.indexOf out "pack_web start")
            agents (.indexOf out "start-agents")]
        (is (>= pack 0))
        (is (>= agents 0))
        (is (< pack agents)))
      (finally
        (fs/delete-tree root)))))

(defn commit-body [root]
  (:out (run {:dir root} "git" "log" "-1" "--format=%B")))

(deftest commit-msg-hook-adds-missing-role-byline
  ;; Given a specifier commit whose message has no byline
  ;; When the commit-msg hook runs
  ;; Then it appends `By specifier.` and does not duplicate an existing byline
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (run {:dir root}
           (script "swarmforge.bb")
           "--test-install-hooks"
           (str root))
      (write-file (fs/path root "spec.md") "hunt\n")
      (run {:dir root} "git" "add" "spec.md")
      (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"
                            "PATH" (System/getenv "PATH")
                            "GIT_CONFIG_NOSYSTEM" "1"}}
           "git" "commit" "-q" "-m" "Specify Hunt the Wumpus console app")
      (let [body (commit-body root)]
        (is (str/includes? body "Specify Hunt the Wumpus console app"))
        (is (str/includes? body "By specifier."))
        (is (= 1 (count (re-seq #"By specifier\." body)))))
      (write-file (fs/path root "spec.md") "hunt two\n")
      (run {:dir root} "git" "add" "spec.md")
      (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"
                            "PATH" (System/getenv "PATH")
                            "GIT_CONFIG_NOSYSTEM" "1"}}
           "git" "commit" "-q" "-m" "Add a scenario\n\nBy specifier.")
      (is (= 1 (count (re-seq #"By specifier\." (commit-body root)))))
      (finally
        (fs/delete-tree root)))))

(deftest commit-msg-hook-infers-role-from-worktree
  ;; Given SWARMFORGE_ROLE is unset and roles.tsv maps this worktree to specifier
  ;; When a commit is made
  ;; Then the hook still adds `By specifier.`
  (let [root (tmp-dir)]
    (try
      (init-repo! root)
      (write-file (fs/path root ".swarmforge/roles.tsv")
                  (format "specifier\tmaster\t%s\tsession\tSpecifier\tcodex\ttask\n" root))
      (run {:dir root}
           (script "swarmforge.bb")
           "--test-install-hooks"
           (str root))
      (write-file (fs/path root "spec.md") "hunt\n")
      (run {:dir root} "git" "add" "spec.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Specify Hunt the Wumpus console app")
      (is (str/includes? (commit-body root) "By specifier."))
      (finally
        (fs/delete-tree root)))))

(deftest window-watchdog-rewrites-window-state-and-id-list
  (let [root (tmp-dir)
        state-file (fs/path root "windows.tsv")
        ids-file (fs/path root "window-ids")]
    (try
      (write-file state-file
                  (str "1\told-a\tswarmforge-coder\tSwarmForge Coder\n"
                       "2\told-b\tswarmforge-cleaner\tSwarmForge Cleaner\n"))
      (write-file ids-file "old-a\nold-b\n")
      (run {:dir root} (script "swarm-window-watchdog.bb") "--rewrite-window-id" "windows.tsv" "window-ids" "2" "new-b")
      (let [state (slurp (str state-file))
            ids (slurp (str ids-file))]
        (is (str/includes? state "1\told-a\tswarmforge-coder\tSwarmForge Coder"))
        (is (str/includes? state "2\tnew-b\tswarmforge-cleaner\tSwarmForge Cleaner"))
        (is (= "old-a\nnew-b\n" ids)))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-detects-nonzero-pane-base-index
  (let [root (tmp-dir)
        sock (str root "/test.sock")
        conf (fs/path root "tmux.conf")]
    (try
      (write-file conf "set -g base-index 1\nset -g pane-base-index 1\n")
      (run {:dir root} "tmux" "-S" sock "-f" (str conf) "new-session" "-d" "-s" "probe" "sleep" "120")
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-tmux-base-indexes"
                        sock)]
        (is (= "1 1" (str/trim (:out result)))))
      (finally
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))

(deftest role-session-keeps-tmux-scrollback
  ;; Given a tmux socket
  ;; When SwarmForge creates a role session
  ;; Then history-limit keeps thousands of lines
  (let [root (tmp-dir)
        sock (str root "/test.sock")
        conf (fs/path root "tmux.conf")]
    (try
      (write-file conf "set -g history-limit 50\n")
      (run {:dir root} "tmux" "-S" sock "-f" (str conf) "new-session" "-d" "-s" "probe" "sleep" "120")
      (let [result (run {:dir root}
                        (script "swarmforge.bb")
                        "--test-create-role-session"
                        sock
                        "swarmforge-specifier")
            limit (Long/parseLong (str/trim (:out result)))]
        (is (zero? (:exit result)))
        (is (>= limit 2000)))
      (finally
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))

(deftest swarm-cleanup-tolerates-missing-runtime-state
  (let [root (tmp-dir)
        ids-file (fs/path root ".swarmforge/window-ids")]
    (try
      (write-file ids-file "window-a\nwindow-b\n")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (str (fs/path scripts-dir "swarm-cleanup.sh"))
                        "/tmp/nonexistent.sock"
                        (str ids-file))]
        (is (= 0 (:exit result)))
        (is (= "" (:err result))))
      (finally
        (fs/delete-tree root)))))

(defn close-swarm []
  (str (fs/path repo-root "close-swarm")))

(deftest close-swarm-reports-when-no-swarm-state
  (let [root (tmp-dir)]
    (try
      (let [result (run {:dir root :ok? false
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (close-swarm)
                        (str root))]
        (is (not= 0 (:exit result)))
        (is (str/includes? (str (:err result) (:out result)) "No SwarmForge swarm")))
      (finally
        (fs/delete-tree root)))))

(deftest close-swarm-kills-tmux-sessions-and-stops-daemon
  (let [root (tmp-dir)
        sock (str (fs/path root "swarm.sock"))
        pid-file (fs/path root ".swarmforge/daemon/handoffd.pid")
        daemon (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        pid (str (.pid daemon))]
    (try
      (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
      (write-file (fs/path root ".swarmforge/sessions.tsv")
                  (str "1\tcoder\tswarmforge-coder\tCoder\tcodex\n"
                       "2\tcleaner\tswarmforge-cleaner\tCleaner\tcodex\n"))
      (write-file (fs/path root ".swarmforge/window-ids") "win-a\nwin-b\n")
      (write-file pid-file (str pid "\n"))
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-coder" "sleep" "120")
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" "swarmforge-cleaner" "sleep" "120")
      (let [result (run {:dir root
                         :env {"SWARMFORGE_TERMINAL_BACKEND" "none"}}
                        (close-swarm)
                        (str root))]
        (is (= 0 (:exit result)))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-coder"))))
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-cleaner"))))
        (is (not (fs/exists? pid-file)))
        (is (false? (.isAlive daemon))))
      (finally
        (when (.isAlive daemon)
          (.destroyForcibly daemon))
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))

;; tmux grid terminal backend

(defn grid-adapter []
  (str (fs/path scripts-dir "terminal-adapters" "tmux-grid.sh")))

(defn grid-eval
  "Load the grid backend the way swarm-terminal-adapter.sh does, then evaluate one expression."
  [{:keys [dir env]} expression]
  (str/trim (:out (run {:dir (or dir repo-root)
                        :env (merge {"SWARMFORGE_TERMINAL" ""} env)}
                       "zsh" "-c"
                       (str "has_command() { command -v \"$1\" &>/dev/null; }; "
                            "SCRIPT_DIR=" scripts-dir "; "
                            "source " scripts-dir "/swarm-terminal-adapter.sh; "
                            "source " (grid-adapter) "; "
                            expression)))))

(deftest grid-backend-declares-itself-and-opts-out-of-window-tracking
  (testing "it names itself apart from the one-window-per-role backends"
    (is (= "tmux grid" (grid-eval {} "terminal_backend_label"))))
  (testing "it opens a surface"
    (is (= "0" (grid-eval {} "terminal_backend_can_open_sessions; echo $?"))))
  (testing "tiles are not reopenable windows, so the watchdog must stay off"
    (is (= "1" (grid-eval {} "terminal_backend_tracks_windows; echo $?")))
    (is (= "1" (grid-eval {} "terminal_window_exists any-id; echo $?")))))

(deftest grid-backend-implements-the-adapter-interface
  (doseq [hook ["terminal_backend_label" "terminal_backend_can_open_sessions"
                "terminal_backend_tracks_windows" "terminal_window_exists"
                "terminal_open_session" "terminal_close_window"]]
    (is (= "yes" (grid-eval {} (str "if typeset -f " hook " >/dev/null; then echo yes; fi")))
        (str hook " must be defined by the grid backend"))))

(deftest grid-backend-never-delegates-the-window-back-to-itself
  (testing "an explicit grid choice for the inner backend degrades to no window"
    (is (= "none" (grid-eval {:env {"SWARMFORGE_GRID_TERMINAL" "tmux-grid"}} "_grid_inner_backend")))
    (is (= "none" (grid-eval {:env {"SWARMFORGE_GRID_TERMINAL" "grid"}} "_grid_inner_backend"))))
  (testing "an explicit single-window backend is honored and normalized"
    (is (= "terminal-app" (grid-eval {:env {"SWARMFORGE_GRID_TERMINAL" "Terminal.app"}} "_grid_inner_backend")))
    (is (= "none" (grid-eval {:env {"SWARMFORGE_GRID_TERMINAL" "none"}} "_grid_inner_backend")))))

(deftest grid-backend-labels-tiles-after-the-working-directory-by-default
  (let [root (tmp-dir)]
    (try
      (testing "with no configuration the label is the project directory name"
        (is (= (str (fs/file-name root))
               (grid-eval {:env {"WORKING_DIR" (str root)}} "_grid_label"))))
      (testing "a project can pin a name that sticks"
        (write-file (fs/path root ".swarmforge/label") "demo-project\n")
        (is (= "demo-project" (grid-eval {:env {"WORKING_DIR" (str root)}} "_grid_label"))))
      (testing "and the environment wins for a single run"
        (is (= "one-off"
               (grid-eval {:env {"WORKING_DIR" (str root) "SWARMFORGE_LABEL" "one-off"}}
                          "_grid_label"))))
      (finally
        (fs/delete-tree root)))))

(defn- start-role-sessions! [root sock roles]
  (doseq [role roles]
    (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" (str "swarmforge-" role) "sleep" "300"))
  (write-file (fs/path root ".swarmforge/sessions.tsv")
              (->> roles
                   (map-indexed (fn [index role]
                                  (format "%d\t%s\tswarmforge-%s\t%s\tcodex\n"
                                          (inc index) role role (str/capitalize role))))
                   (apply str))))

(deftest grid-backend-tiles-every-role-into-one-viewer-session
  (let [root (tmp-dir)
        sock (str (fs/path root "swarm.sock"))
        roles ["specifier" "coder" "cleaner" "architect" "hardender" "qa"]]
    (try
      (start-role-sessions! root sock roles)
      ;; `none` as the inner backend is the platform-independent path: build the grid, open no window.
      (grid-eval {:env {"WORKING_DIR" (str root)
                        "TMUX_SOCKET" sock
                        "SWARMFORGE_GRID_TERMINAL" "none"}}
                 "terminal_open_session swarmforge-specifier 'SwarmForge Specifier'")
      (testing "one viewer session holds one pane per role"
        (let [panes (str/split-lines
                     (str/trim (:out (run {:dir root} "tmux" "-S" sock "list-panes"
                                          "-t" "swarmforge-grid" "-F" "#{pane_title}"))))]
          (is (= (count roles) (count panes)))
          (is (= ["Specifier" "Coder" "Cleaner" "Architect" "Hardender" "Qa"]
                 (mapv #(last (str/split % #" \| ")) panes)))))
      (testing "the panes are tiled rather than stacked in one column"
        (let [geometry (->> (run {:dir root} "tmux" "-S" sock "list-panes"
                                 "-t" "swarmforge-grid" "-F" "#{pane_left},#{pane_top}")
                            :out str/trim str/split-lines
                            (map #(str/split % #","))) ]
          (is (< 1 (count (distinct (map first geometry)))) "more than one column")
          (is (< 1 (count (distinct (map second geometry)))) "more than one row")))
      (testing "every role session survives untouched, so handoff delivery still resolves"
        (doseq [role roles]
          (is (= 0 (:exit (run {:dir root :ok? false}
                               "tmux" "-S" sock "has-session" "-t" (str "swarmforge-" role)))))))
      (testing "a second role does not build a second grid"
        (grid-eval {:env {"WORKING_DIR" (str root)
                          "TMUX_SOCKET" sock
                          "SWARMFORGE_GRID_TERMINAL" "none"}}
                   "terminal_open_session swarmforge-coder 'SwarmForge Coder'")
        (is (= (count roles)
               (count (str/split-lines
                       (str/trim (:out (run {:dir root} "tmux" "-S" sock "list-panes"
                                            "-t" "swarmforge-grid" "-F" "#{pane_id}"))))))))
      (finally
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))

(deftest grid-viewer-session-dies-with-the-roles-it-was-watching
  (let [root (tmp-dir)
        sock (str (fs/path root "swarm.sock"))
        roles ["coder" "cleaner"]]
    (try
      (start-role-sessions! root sock roles)
      (grid-eval {:env {"WORKING_DIR" (str root)
                        "TMUX_SOCKET" sock
                        "SWARMFORGE_GRID_TERMINAL" "none"}}
                 "terminal_open_session swarmforge-coder 'SwarmForge Coder'")
      (is (= 0 (:exit (run {:dir root :ok? false} "tmux" "-S" sock "has-session" "-t" "swarmforge-grid"))))
      (testing "cleanup kills the role sessions, and the viewer has nothing left to attach"
        (doseq [role roles]
          (run {:dir root :ok? false} "tmux" "-S" sock "kill-session" "-t" (str "swarmforge-" role)))
        (Thread/sleep 1500)
        (is (not= 0 (:exit (run {:dir root :ok? false}
                                "tmux" "-S" sock "has-session" "-t" "swarmforge-grid")))
            "the viewer session must not outlive the swarm"))
      (finally
        (run {:dir root :ok? false} "tmux" "-S" sock "kill-server")
        (fs/delete-tree root)))))
