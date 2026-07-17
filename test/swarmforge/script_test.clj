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
      (write-file (fs/path root "swarmforge/constitution/articles/engineering.prompt")
                  "project override\n")
      (let [result (run {:dir root} (script "swarmforge.bb") "--test-parse" (str root))]
        (is (str/includes? (:out result) "coder Coder"))
        (is (str/includes? (:out result) "cleaner Cleaner"))
        (is (str/includes? (:out result) "cleaner batch"))
        (is (str/includes? (:out result) "swarmforge-coder"))
        (is (str/includes? (:out result) "swarmforge-cleaner"))
        (is (fs/exists? (fs/path root ".swarmforge/tmux-socket")))
        (is (= "project override\n"
               (slurp (str (fs/path root "swarmforge/constitution/articles/engineering.prompt")))))
        (is (fs/exists? (fs/path root "swarmforge/constitution/articles/handoffs.prompt")))
        (is (fs/exists? (fs/path root "swarmforge/constitution/articles/workflow.prompt"))))
      (finally
        (fs/delete-tree root)))))

(deftest swarmforge-installs-missing-articles-into-role-worktrees
  (let [source (tmp-dir)
        target (tmp-dir)
        script-dir (fs/path source "swarmforge/scripts")]
    (try
      (write-file (fs/path script-dir "shared-articles/engineering.prompt") "shared engineering\n")
      (write-file (fs/path script-dir "shared-articles/handoffs.prompt") "shared handoffs\n")
      (write-file (fs/path source "swarmforge/constitution/articles/project.prompt") "project rules\n")
      (write-file (fs/path target "swarmforge/constitution/articles/engineering.prompt") "role override\n")
      (run {:dir target}
           (script "swarmforge.bb")
           "--test-install-shared-articles"
           (str script-dir)
           (str target))
      (is (= "role override\n"
             (slurp (str (fs/path target "swarmforge/constitution/articles/engineering.prompt")))))
      (is (= "shared handoffs\n"
             (slurp (str (fs/path target "swarmforge/constitution/articles/handoffs.prompt")))))
      (is (= "project rules\n"
             (slurp (str (fs/path target "swarmforge/constitution/articles/project.prompt")))))
      (finally
        (fs/delete-tree source)
        (fs/delete-tree target)))))

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
        (is (str/includes? command "--permission-mode acceptEdits"))
        (is (str/includes? command "--rules \"$(cat "))
        (is (str/includes? command "--verbatim \"$(cat "))
        (is (str/includes? command ".swarmforge/prompts/coder.md"))
        (is (fs/exists? (fs/path root ".swarmforge/prompts/coder.md"))))
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
