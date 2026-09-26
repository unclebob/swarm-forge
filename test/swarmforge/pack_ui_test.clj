(ns swarmforge.pack-ui-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests use-fixtures]]))

(def six-pack-roles ["specifier" "coder" "cleaner" "architect" "hardender" "QA"])

(def repo-root (fs/cwd))
(def scripts-dir (fs/path repo-root "swarmforge" "scripts"))
(def temp-dirs (atom []))

(use-fixtures :once
  (fn [tests]
    (try
      (tests)
      (finally
        (doseq [dir @temp-dirs]
          (fs/delete-tree dir))))))

(defn script [name]
  (str (fs/path scripts-dir name)))

(defn tmp-dir []
  (let [dir (fs/create-temp-dir {:prefix "swarmforge-pack-ui-test."})]
    (swap! temp-dirs conj dir)
    dir))

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

(defn write-file [path text]
  (fs/create-dirs (fs/parent path))
  (spit (str path) text))

(defn pack-worktree [root roles role]
  (if (= role (first roles))
    (str root)
    (str (fs/path root ".worktrees" role))))

(defn setup-pack!
  ([root] (setup-pack! root ["specifier"]))
  ([root roles] (setup-pack! root roles {}))
  ([root roles propagation]
   (write-file
    (fs/path root ".swarmforge/roles.tsv")
    (apply str
           (map-indexed
            (fn [i role]
              (format "%s\t%s\t%s\t%s\t%s\tcodex\ttask\t%s\n"
                      role
                      (if (zero? i) "master" role)
                      (pack-worktree root roles role)
                      role
                      (str/capitalize role)
                      (get propagation role "forward-only")))
            roles)))
   (doseq [role roles
           dir [".swarmforge/handoffs/outbox"
                ".swarmforge/handoffs/sent"
                ".swarmforge/handoffs/failed"
                ".swarmforge/handoffs/inbox/new"]]
     (fs/create-dirs (fs/path (pack-worktree root roles role) dir)))
   (fs/create-dirs (fs/path root ".swarmforge/handoffs/pending_approval"))))

(defn pack-board
  ([root ok? & args]
   (apply run {:dir root :ok? ok?} (script "pack_board.sh") args)))

(defn pack-web
  ([root ok? & args]
   (apply run {:dir root :ok? ok?} (script "pack_web.sh") args)))

(defn pack-web-env
  [root env & args]
  (apply run {:dir root :env env} (script "pack_web.sh") args))

(defn set-backend!
  [root backend]
  (let [file (fs/path root ".swarmforge/roles.tsv")]
    (spit (str file)
          (str/replace (slurp (str file)) #"\tcodex\t" (str "\t" backend "\t")))))

(defn read-argv [path]
  (when (fs/exists? path)
    (->> (str/split-lines (slurp (str path)))
         (remove str/blank?)
         (mapv read-string))))

(defn create-task
  ([root name lane] (create-task root name lane true))
  ([root name lane ok?]
   (pack-board root ok?
               "create"
               "--root" (str root)
               "--name" name
               "--lane" lane
               "--text" "Integrate HTW stories")))

(defn list-tasks [root]
  (pack-board root true "list" "--root" (str root)))

(defn task-row [listed name]
  (some #(when (str/starts-with? % (str name "\t")) %)
        (str/split-lines listed)))

(defn task-lane [root name]
  (let [cols (str/split (or (task-row (:out (list-tasks root)) name) "") #"\t")]
    (nth cols 1 nil)))

(defn increment-audit! [root task-id]
  (pack-board root true "increment-audit" "--root" (str root) "--task-id" task-id))

(defn queue-handoff! [root {:keys [from to task artifacts non-forwarding priority body]}]
  (let [priority (or priority "50")]
    (write-file
     (fs/path root ".swarmforge/handoffs/outbox"
              (str priority "_from_" from "_to_" (str/replace to #"," "_") ".handoff"))
     (str "from: " from "\n"
          "to: " to "\n"
          "priority: " priority "\n"
          "type: git_handoff\n"
          "task: " task "\n"
          (when artifacts (str "artifacts: " artifacts "\n"))
          (when non-forwarding "non-forwarding: true\n")
          "\n"
          (or body "payload") "\n"))))

(defn handoff-names [dir]
  (if (fs/directory? dir)
    (->> (fs/list-dir dir)
         (filter #(str/ends-with? (fs/file-name %) ".handoff"))
         (mapv #(fs/file-name %)))
    []))

(defn pending-names [root]
  (handoff-names (fs/path root ".swarmforge/handoffs/pending_approval")))

(defn write-pending-audit! [root task-id]
  (write-file
   (fs/path root ".swarmforge/handoffs/audit_pending/sender" (str task-id ".edn"))
   (str (pr-str {:candidate {:version 1
                             :sender "specifier"
                             :task-id task-id
                             :type "git_handoff"}})
        "\n")))

(defn pending-audits [root]
  (let [dir (fs/path root ".swarmforge/handoffs/audit_pending")]
    (if (fs/directory? dir)
      (vec (fs/glob dir "**/*.edn"))
      [])))

(defn pending-audit-task-ids [root]
  (->> (pending-audits root)
       (map #(get-in (edn/read-string (slurp (str %))) [:candidate :task-id]))
       set))

(defn inbox-names [root roles role]
  (handoff-names (fs/path (pack-worktree root roles role)
                          ".swarmforge/handoffs/inbox/new")))

(defn in-process-dir [root roles role]
  (fs/path (pack-worktree root roles role)
           ".swarmforge/handoffs/inbox/in_process"))

(defn put-in-process! [root roles role {:keys [from task filename]}]
  (write-file
   (fs/path (in-process-dir root roles role)
            (or filename (str "50_from_" from "_to_" role ".handoff")))
   (str "from: " from "\n"
        "to: " role "\n"
        "priority: 50\n"
        "type: git_handoff\n"
        "task: " task "\n"
        "\n"
        "payload\n")))

(defn web-state [root]
  (json/parse-string (:out (pack-web root true "--test-state" (str root))) true))

(defn task-card [root name]
  (some #(when (= name (:name %)) %) (:tasks (web-state root))))

(defn start-tmux! [root sessions]
  (let [sock (str (fs/path root "tmux.sock"))]
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (doseq [session sessions]
      (run {:dir root} "tmux" "-S" sock "new-session" "-d" "-s" session "sleep" "120"))
    sock))

(defn stop-tmux! [sock]
  (run {:dir "." :ok? false} "tmux" "-S" sock "kill-server"))

(defn handoffd-once
  ([root] (handoffd-once root nil))
  ([root env]
   (run {:dir root :env env} "bb" (script "handoffd.bb") "--once" (str root))))

(defn pane-path [root role task]
  (fs/path root ".swarmforge/sessions" role task "pane.txt"))

(defn role-pane-path [root role]
  (fs/path root ".swarmforge/sessions" role "pane.txt"))

(deftest pack-board-creates-a-task-in-the-master-lane
  ;; Given a pack with specifier on master
  ;; When New Task records name htw-console-app
  ;; Then the card sits in lane specifier
  (let [root (tmp-dir)
        _ (setup-pack! root)
        created (create-task root "htw-console-app" "specifier")
        listed (:out (list-tasks root))
        on-disk (slurp (str (fs/path root ".swarmforge/board/tasks.tsv")))
        cols (str/split (or (task-row listed "htw-console-app") "") #"\t")]
    (is (zero? (:exit created)))
    (is (= listed on-disk))
    (is (= "htw-console-app" (nth cols 0 nil)))
    (is (= "specifier" (nth cols 1 nil)))
    (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (nth cols 2 "")))
    (is (= (nth cols 2 nil) (nth cols 3 nil)))
    (is (= "0" (nth cols 5 nil)))))

(deftest new-task-writes-the-card-and-body
  ;; Given specifier is master
  ;; When create name=htw-console-app text="Integrate HTW stories…"
  ;; Then lane is specifier AND board/htw-console-app.txt has the text
  (let [root (tmp-dir)
        text "Integrate HTW stories…"]
    (write-file
     (fs/path root ".swarmforge/roles.tsv")
     (str "specifier\tmaster\t" root "\tsession\tSpecifier\tcodex\ttask\n"))
    (let [created (pack-board root true
                              "create"
                              "--root" (str root)
                              "--name" "htw-console-app"
                              "--lane" "specifier"
                              "--text" text)
          body (slurp (str (fs/path root ".swarmforge/board/htw-console-app.txt")))]
      (is (zero? (:exit created)))
      (is (= "specifier" (task-lane root "htw-console-app")))
      (is (= text body))
      (is (= (str "# htw-console-app\n\n" text "\n")
             (slurp (str (fs/path root "tasks/htw-console-app.md"))))))))

(deftest pack-board-serializes-concurrent-audit-increments
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        increments (doall (repeatedly 8 #(future (increment-audit! root task-id))))]
    (doseq [increment increments]
      @increment)
    (is (= 8 (:audit_count (task-card root "HTW"))))))

(deftest pack-board-lists-lanes-in-role-order
  ;; Given roles specifier, coder, QA
  ;; When pack_board lanes
  ;; Then it prints those roles in conf order
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier" "coder" "QA"])
        result (pack-board root true "lanes" "--root" (str root))]
    (is (= "specifier\ncoder\nQA\n" (:out result)))))

(deftest pack-board-reports-the-master-lane
  ;; Given specifier's worktree is master
  ;; When pack_board master-lane
  ;; Then it prints specifier
  (let [root (tmp-dir)]
    (write-file
     (fs/path root ".swarmforge/roles.tsv")
     (str "specifier\tmaster\t" root "\tsession\tSpecifier\tcodex\ttask\n"
          "coder\tcoder\t" root "/.worktrees/coder\tsession\tCoder\tcodex\ttask\n"))
    (let [result (pack-board root true "master-lane" "--root" (str root))]
      (is (= "specifier\n" (:out result))))))

(deftest pack-board-rejects-a-duplicate-task-name
  ;; Given a card named htw-console-app
  ;; When New Task records the same name again
  ;; Then the create is rejected and the original card is unchanged
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "htw-console-app" "specifier")
        before (:out (list-tasks root))
        duplicate (create-task root "htw-console-app" "specifier" false)
        after (:out (list-tasks root))]
    (is (not (zero? (:exit duplicate))))
    (is (str/includes? (str (:err duplicate) (:out duplicate)) "Duplicate"))
    (is (= before after))))

(deftest handoffd-moves-the-task-card-to-the-recipient
  ;; Given card htw-console-app in coder
  ;; When a git_handoff coder→cleaner for that task is delivered
  ;; Then the card lane is cleaner
  (let [root (tmp-dir)
        roles ["specifier" "coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "coder")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "cleaner" (task-lane root "htw-console-app")))
      (is (= 1 (:audit_count (task-card root "htw-console-app"))))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-marks-the-task-card-done-for-terminal-handoff
  ;; Given six-pack, card in QA (not master)
  ;; When QA queues git_handoff to every other role
  ;; Then the card lane is done
  (let [root (tmp-dir)
        to "specifier,coder,cleaner,architect,hardender"
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "QA")
                 (queue-handoff! root {:from "QA" :to to :task "htw-console-app"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (finally
        (stop-tmux! sock)))))

(def four-pack-roles ["specifier" "coder" "refactorer" "architect"])
(def reverse-structure-body
  (str "Re-read your role and constitution.\n\n"
       "merge_and_process.sh refactorer abcdef1234\n\n"
       "The inbound tree is the structure. Replay this role's current task onto that shape."))

(deftest handoffd-refactorer-back-one-does-not-done-or-hold
  ;; Given four-pack, card in refactorer, reverse copy to coder and forward to architect
  ;; When handoffd delivers
  ;; Then coder gets the 00 reverse file, lane is architect, Attention is empty, card is not Done
  (let [root (tmp-dir)
        roles four-pack-roles
        sock (do (setup-pack! root roles {"refactorer" "back-one" "architect" "back-all"})
                 (create-task root "HTW" "refactorer")
                 (write-file
                  (fs/path (pack-worktree root roles "coder")
                           ".swarmforge/handoffs/inbox/new/50_next_card.handoff")
                  (str "from: specifier\nto: coder\npriority: 50\ntype: note\n"
                       "message: next card\n\nnote\n"))
                 (queue-handoff! root {:from "refactorer" :to "architect" :task "HTW"
                                       :priority "50"})
                 (queue-handoff! root {:from "refactorer" :to "coder" :task "HTW"
                                       :priority "00" :non-forwarding true
                                       :body reverse-structure-body})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (let [coder-mail (sort (inbox-names root roles "coder"))
            delivered (slurp (str (fs/path (pack-worktree root roles "coder")
                                           ".swarmforge/handoffs/inbox/new"
                                           (first coder-mail))))]
        (is (str/starts-with? (first coder-mail) "00_"))
        (is (str/starts-with? (second coder-mail) "50_"))
        (is (str/includes? delivered "merge_and_process.sh refactorer"))
        (is (str/includes? delivered "inbound tree is the structure"))
        (is (str/includes? delivered "non-forwarding: true")))
      (is (seq (inbox-names root roles "architect")))
      (is (= [] (pending-names root)))
      (is (= "architect" (task-lane root "HTW")))
      (is (not= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-four-pack-architect-back-all-dones-because-last
  (let [root (tmp-dir)
        roles four-pack-roles
        sock (do (setup-pack! root roles {"refactorer" "back-one" "architect" "back-all"})
                 (create-task root "HTW" "architect")
                 (queue-handoff! root {:from "architect" :to "specifier" :task "HTW"
                                       :priority "50" :non-forwarding true})
                 (doseq [role ["specifier" "coder" "refactorer"]]
                   (queue-handoff! root {:from "architect" :to role :task "HTW"
                                         :priority "00" :non-forwarding true
                                         :body reverse-structure-body}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (doseq [role ["specifier" "coder" "refactorer"]]
        (is (seq (inbox-names root roles role)) role))
      (is (= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-six-pack-architect-back-all-moves-to-hardender
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles {"cleaner" "back-one"
                                          "architect" "back-all"
                                          "QA" "back-all"})
                 (create-task root "HTW" "architect")
                 (queue-handoff! root {:from "architect" :to "hardender" :task "HTW"
                                       :priority "50"})
                 (doseq [role ["specifier" "coder" "cleaner"]]
                   (queue-handoff! root {:from "architect" :to role :task "HTW"
                                         :priority "00" :non-forwarding true}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (doseq [role ["specifier" "coder" "cleaner"]]
        (is (seq (inbox-names root roles role)) role))
      (is (seq (inbox-names root roles "hardender")))
      (is (= [] (inbox-names root roles "QA")))
      (is (= "hardender" (task-lane root "HTW")))
      (is (not= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-six-pack-qa-back-all-dones-because-last
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles {"cleaner" "back-one"
                                          "architect" "back-all"
                                          "QA" "back-all"})
                 (create-task root "HTW" "QA")
                 (queue-handoff! root {:from "QA" :to "specifier" :task "HTW"
                                       :priority "50" :non-forwarding true})
                 (doseq [role ["specifier" "coder" "cleaner" "architect" "hardender"]]
                   (queue-handoff! root {:from "QA" :to role :task "HTW"
                                         :priority "00" :non-forwarding true}))
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (doseq [role ["specifier" "coder" "cleaner" "architect" "hardender"]]
        (is (seq (inbox-names root roles role)) role))
      (is (= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-two-pack-cleaner-back-one-dones-because-last
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles {"cleaner" "back-one"})
                 (create-task root "HTW" "cleaner")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"
                                       :priority "50" :non-forwarding true})
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"
                                       :priority "00" :non-forwarding true})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (seq (inbox-names root roles "coder")))
      (is (= "done" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest pack-web-exposes-dashboard-state-from-conf-and-board
  ;; Given a six-pack with specifier as master and a board card
  ;; When pack_web --test-state
  ;; Then JSON includes lanes from conf, the master display name, and the card
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        _ (create-task root "htw-console-app" "specifier")
        listed (:out (list-tasks root))
        updated (nth (str/split (or (task-row listed "htw-console-app") "") #"\t") 3 nil)
        result (pack-web root true "--test-state" (str root))
        state (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (= "specifier" (:master_role state)))
    (is (= "Specifier" (:master_display state)))
    (is (= six-pack-roles (:lanes state)))
    (let [card (first (:tasks state))]
      (is (= "htw-console-app" (:name card)))
      (is (str/starts-with? (:id card) "20"))
      (is (= "specifier" (:lane card)))
      (is (= updated (:updated_at card)))
      (is (= 0 (:audit_count card)))
      (is (= "" (:status card))))
    (is (= [] (:approvals state)))
    (is (= six-pack-roles (mapv :role (:work_in_flight state))))))







(deftest pack-web-post-task-creates-a-card-in-the-master-lane
  ;; Given a pack whose master role is coder
  ;; When POST /api/tasks records name and text
  ;; Then the card sits in lane coder with that body
  (let [root (tmp-dir)
        text "Integrate HTW stories"]
    (setup-pack! root ["coder" "cleaner"])
    (let [result (pack-web root true "--test-post-task" (str root) "htw-console-app" text)
          body (slurp (str (fs/path root ".swarmforge/board/htw-console-app.txt")))]
      (is (zero? (:exit result)))
      (is (= "coder" (task-lane root "htw-console-app")))
      (is (= text body)))))

(deftest handoffd-delivers-new-task-note-without-moving-the-card
  ;; Given a New Task note in the project outbox
  ;; When handoffd --once
  ;; Then specifier inbox has it, the card stays in specifier, and sent is on master
  (let [root (tmp-dir)
        roles six-pack-roles
        sock (do (setup-pack! root roles)
                 (pack-web root true "--test-post-task" (str root) "HTW" "Print hello")
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "specifier" (task-lane root "HTW")))
      (is (= [] (pending-names root)))
      (is (seq (inbox-names root roles "specifier")))
      (is (seq (handoff-names (fs/path root ".swarmforge/handoffs/sent"))))
      (is (empty? (handoff-names (fs/path (pack-worktree root roles "coder")
                                         ".swarmforge/handoffs/sent"))))
      (finally
        (stop-tmux! sock)))))

(deftest specifier-git-handoff-waits-for-attention
  ;; Given six-pack-shaped roles + card in specifier
  ;; When specifier→coder is queued and handoffd --once
  ;; Then file is in pending_approval, coder inbox empty, pack_web --test-state approvals has the task
  (let [root (tmp-dir)
        artifacts "features/console.feature,qa/console.md"
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"
                                       :artifacts artifacts})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (let [state (web-state root)]
        (is (= ["50_from_specifier_to_coder.handoff"] (pending-names root)))
        (is (= [] (inbox-names root six-pack-roles "coder")))
        (is (= "specifier" (task-lane root "htw-console-app")))
        (is (= 1 (:audit_count (first (:tasks state)))))
        (is (= [{:id "50_from_specifier_to_coder"
                 :gate "spec → coder"
                 :task_id "htw-console-app"
                 :task "htw-console-app"
                 :artifacts []
                 :reviews {}}]
               (:approvals state))))
      (finally
        (stop-tmux! sock)))))

(deftest two-pack-git-handoff-does-not-wait
  ;; Given coder master, cleaner next, no specifier
  ;; When coder→cleaner queued + --once
  ;; Then delivered to cleaner; approvals empty
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (seq (inbox-names root roles "cleaner")))
      (is (= [] (pending-names root)))
      (is (= "cleaner" (task-lane root "htw-console-app")))
      (is (= [] (:approvals (web-state root))))
      (finally
        (stop-tmux! sock)))))

(deftest two-pack-end-broadcast-marks-the-card-done
  ;; Given two-pack, card in cleaner
  ;; When cleaner queues git_handoff to coder (every other role)
  ;; Then the card is done and coder inbox has the file
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "cleaner")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (is (seq (inbox-names root roles "coder")))
      (is (= [] (pending-names root)))
      (finally
        (stop-tmux! sock)))))

(deftest four-pack-end-broadcast-marks-the-card-done
  ;; Given four-pack, card in architect
  ;; When architect queues git_handoff to every other role
  ;; Then the card is done
  (let [root (tmp-dir)
        roles ["specifier" "coder" "refactorer" "architect"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "architect")
                 (queue-handoff! root {:from "architect"
                                       :to "specifier,coder,refactorer"
                                       :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (is (seq (inbox-names root roles "specifier")))
      (is (seq (inbox-names root roles "coder")))
      (is (seq (inbox-names root roles "refactorer")))
      (is (= [] (pending-names root)))
      (finally
        (stop-tmux! sock)))))

(deftest four-pack-last-role-git-handoff-is-done
  ;; Given four-pack, card in architect
  ;; When architect queues git_handoff to specifier,coder (not every other role)
  ;; Then the card is done because architect is last
  (let [root (tmp-dir)
        roles ["specifier" "coder" "refactorer" "architect"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "architect")
                 (queue-handoff! root {:from "architect"
                                       :to "specifier,coder"
                                       :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (finally
        (stop-tmux! sock)))))

(deftest four-pack-one-recipient-non-forwarding-is-done
  ;; Given four-pack, card in architect
  ;; When architect queues a non-forwarding git_handoff to specifier only
  ;; Then the card is done, not moved to specifier
  (let [root (tmp-dir)
        roles ["specifier" "coder" "refactorer" "architect"]
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "architect")
                 (queue-handoff! root {:from "architect"
                                       :to "specifier"
                                       :task "HTW"
                                       :non-forwarding true})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "HTW")))
      (is (seq (inbox-names root roles "specifier")))
      (finally
        (stop-tmux! sock)))))

(deftest terminal-handoff-dones-finished-batch-cards-in-sender-lane
  ;; Given two-pack, Command syntax and validation in cleaner, those names in a
  ;; completed cleaner batch, HTW still in cleaner but not in that batch
  ;; When cleaner queues a terminal git_handoff named HTW
  ;; Then Command syntax and validation are done and HTW is done
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        batch (fs/path (pack-worktree root roles "cleaner")
                       ".swarmforge/handoffs/inbox/completed"
                       "batch_20260824T150500Z_000001")
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "cleaner")
                 (create-task root "Command syntax" "cleaner")
                 (create-task root "validation" "cleaner")
                 (write-file (fs/path batch "50_command.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: Command syntax\n\npayload\n")
                 (write-file (fs/path batch "50_validation.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: validation\n\npayload\n")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "HTW")))
      (is (= "done" (task-lane root "Command syntax")))
      (is (= "done" (task-lane root "validation")))
      (finally
        (stop-tmux! sock)))))

(deftest terminal-handoff-leaves-unfinished-lane-cards
  ;; Given two-pack, HTW finished in a completed batch, Command syntax only in the lane
  ;; When cleaner terminals with task HTW
  ;; Then HTW is done and Command syntax stays in cleaner
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        done (fs/path (pack-worktree root roles "cleaner")
                      ".swarmforge/handoffs/inbox/completed")
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "cleaner")
                 (create-task root "Command syntax" "cleaner")
                 (write-file (fs/path done "50_htw.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: HTW\n\npayload\n")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "HTW"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "HTW")))
      (is (= "cleaner" (task-lane root "Command syntax")))
      (finally
        (stop-tmux! sock)))))

(deftest terminal-handoff-dones-in-process-batch-cards
  ;; Given two-pack, one liners/validate/HHG in an in-process cleaner batch,
  ;; and Command syntax in cleaner but not in that batch
  ;; When cleaner terminals with task one liners before done_with_current
  ;; Then the three batch cards are done and Command syntax stays in cleaner
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        batch (fs/path (in-process-dir root roles "cleaner")
                       "batch_20260824T202830Z_000001")
        sock (do (setup-pack! root roles)
                 (create-task root "one liners" "cleaner")
                 (create-task root "validate" "cleaner")
                 (create-task root "Holy Hand Grenade" "cleaner")
                 (create-task root "Command syntax" "cleaner")
                 (write-file (fs/path batch "50_oneliners.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: one liners\n\npayload\n")
                 (write-file (fs/path batch "50_validate.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: validate\n\npayload\n")
                 (write-file (fs/path batch "50_hhg.handoff")
                             "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: Holy Hand Grenade\n\npayload\n")
                 (queue-handoff! root {:from "cleaner" :to "coder" :task "one liners"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "one liners")))
      (is (= "done" (task-lane root "validate")))
      (is (= "done" (task-lane root "Holy Hand Grenade")))
      (is (= "cleaner" (task-lane root "Command syntax")))
      (finally
        (stop-tmux! sock)))))

(deftest six-pack-qa-broadcast-marks-the-card-done
  ;; Given six-pack, card in QA
  ;; When QA queues git_handoff to every other role
  ;; Then the card is done
  (let [root (tmp-dir)
        others "specifier,coder,cleaner,architect,hardender"
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "QA")
                 (queue-handoff! root {:from "QA" :to others :task "htw-console-app"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (is (= "done" (task-lane root "htw-console-app")))
      (is (seq (inbox-names root six-pack-roles "specifier")))
      (is (seq (inbox-names root six-pack-roles "hardender")))
      (is (= [] (pending-names root)))
      (finally
        (stop-tmux! sock)))))

(deftest attention-approve-delivers-the-handoff
  ;; Given pending approval
  ;; When pack_web --test-approve <root> <id>
  ;; Then coder inbox has the file, card lane coder (handoffd --once after approve)
  (let [root (tmp-dir)
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"
                                       :artifacts "features/console.feature"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (let [id (:id (first (:approvals (web-state root))))]
        (pack-web root true "--test-approve" (str root) id)
        (handoffd-once root)
        (is (seq (inbox-names root six-pack-roles "coder")))
        (is (= "coder" (task-lane root "htw-console-app")))
        (is (= 1 (:audit_count (task-card root "htw-console-app"))))
        (is (= [] (pending-names root)))
        (is (= [] (:approvals (web-state root)))))
      (finally
        (stop-tmux! sock)))))

(deftest attention-reject-returns-to-master
  ;; Given pending
  ;; When --test-reject
  ;; Then the pending approval is unchanged because Reject only opens the dialog
  (let [root (tmp-dir)
        sock (do (setup-pack! root six-pack-roles)
                 (create-task root "htw-console-app" "specifier")
                 (increment-audit! root (:id (task-card root "htw-console-app")))
                 (queue-handoff! root {:from "specifier" :to "coder" :task "htw-console-app"})
                 (start-tmux! root six-pack-roles))]
    (try
      (handoffd-once root)
      (let [id (:id (first (:approvals (web-state root))))
            result (pack-web root false "--test-reject" (str root) id)]
        (is (not (zero? (:exit result))))
        (is (seq (pending-names root)))
        (is (= "specifier" (task-lane root "htw-console-app")))
        (is (= 1 (:audit_count (task-card root "htw-console-app"))))
        (is (seq (:approvals (web-state root))))
        (is (not (fs/exists? (fs/path root ".swarmforge/notify/reject-htw-console-app")))))
      (finally
        (stop-tmux! sock)))))

(deftest attention-reject-preserves-branch-and-rolls-back-head
  ;; Given a pending approval with task identity and a task base commit
  ;; When it is rejected
  ;; Then rejected work is preserved off the active branch and pending state is cleared
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (setup-pack! root six-pack-roles)
    (write-file (fs/path root "story.md") "base\n")
    (run {:dir root} "git" "add" "story.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Base")
    (create-task root "htw-console-app" "specifier")
    (let [task-id (:id (first (:tasks (web-state root))))
          base (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-pending-audit! root task-id)
      (write-pending-audit! root "unrelated-id")
      (write-file (fs/path root "story.md") "rejected\n")
      (run {:dir root} "git" "add" "story.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Rejected work")
      (let [rejected (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")]
        (write-file pending
                    (str "from: specifier\n"
                         "to: coder\n"
                         "priority: 50\n"
                         "type: git_handoff\n"
                         "task_id: " task-id "\n"
                         "task: htw-console-app\n"
                         "commit: " rejected "\n"
                         "task_base_commit: " base "\n"
                         "\n"
                         "payload\n"))
        (let [result (pack-web root false "--test-delete-approval" (str root) "50_from_specifier_to_coder")
              head (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
              branches (:out (run {:dir root} "git" "branch" "--format=%(refname:short)"))]
          (is (zero? (:exit result)))
          (is (= base head))
          (is (str/includes? branches (str "rejected/" task-id "/latest")))
          (is (str/includes? branches (str "rejected/" task-id "/1")))
          (is (not (fs/exists? pending)))
          (is (= #{"unrelated-id"} (pending-audit-task-ids root)))
          (is (fs/exists? (fs/path root ".swarmforge/rejected-tasks" task-id)))
          (is (nil? (task-lane root "htw-console-app"))))))))

(def example-task-text
  "Integrate the stories in ~/junk/htw-stories into one console application.")

(def example-task-payload
  (str "Task: htw-console-app\n\n" example-task-text))

(deftest inject-payload-formats-task-name-and-body
  ;; Given the New Task example name and body
  ;; When pack_web --test-inject-payload
  ;; Then it prints Task: name, a blank line, and the body
  (let [result (pack-web (tmp-dir) false "--test-inject-payload")]
    (is (zero? (:exit result)))
    (is (= (str example-task-payload "\n") (:out result)))))

(deftest pack-web-post-task-creates-a-card-when-tmux-is-missing
  ;; Given no tmux socket or live session
  ;; When POST /api/tasks via --test-post-task
  ;; Then inject failure is ignored and the card is still created
  (let [root (tmp-dir)
        text example-task-text]
    (setup-pack! root ["coder" "cleaner"])
    (let [result (pack-web root false "--test-post-task" (str root) "htw-console-app" text)
          body (slurp (str (fs/path root ".swarmforge/board/htw-console-app.txt")))]
      (is (zero? (:exit result)))
      (is (= "coder" (task-lane root "htw-console-app")))
      (is (= text body))
      (is (str/includes? (slurp (str (fs/path root "tasks/htw-console-app.md")))
                         text)))))

(deftest pack-web-inject-failure-logs-the-role
  (let [root (tmp-dir)]
    (setup-pack! root ["coder"])
    (let [result (pack-web root false "--test-post-chat" (str root) "hello master")]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:err result)) "inject failed"))
      (is (str/includes? (str (:err result)) "coder")))))

(deftest inject-master-records-send-keys-argv
  ;; Given master session swarmforge-specifier in roles.tsv
  ;; When --test-inject-argv records the would-be tmux argv
  ;; Then it send-keys -l the text to that session, then C-m
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        text "hello from operator"]
    (write-file
     (fs/path root ".swarmforge/roles.tsv")
     (str "specifier\tmaster\t" root "\tswarmforge-specifier\tSpecifier\tcodex\ttask\n"))
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [result (pack-web root false "--test-inject-argv" (str root) argv-file text)
          argv (read-argv argv-file)]
      (is (zero? (:exit result)))
      (is (= ["tmux" "-S" sock "send-keys" "-t" "swarmforge-specifier:Specifier.0" "-l" text]
             (first argv)))
      (is (= ["tmux" "-S" sock "send-keys" "-t" "swarmforge-specifier:Specifier.0" "C-m"]
             (second argv)))
      (is (= ["tmux" "-S" sock "send-keys" "-t" "swarmforge-specifier:Specifier.0" "C-j"]
             (nth argv 2))))))

(deftest pack-web-post-task-queues-a-note-for-master
  ;; Given a specifier pack and a tmux argv stub
  ;; When POST /api/tasks records name and text
  ;; Then the card is in specifier, a (New Task) note is in the outbox, and the pane is not injected
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        text example-task-text]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                               "--test-post-task" (str root) "htw-console-app" text)
          queued (handoff-names (fs/path root ".swarmforge/handoffs/outbox"))
          content (when (seq queued)
                    (slurp (str (fs/path root ".swarmforge/handoffs/outbox" (first queued)))))]
      (is (zero? (:exit result)))
      (is (= "specifier" (task-lane root "htw-console-app")))
      (is (= 1 (count queued)))
      (is (str/includes? (str content) "from: (New Task)\n"))
      (is (str/includes? (str content) "to: specifier\n"))
      (is (str/includes? (str content) "type: note\n"))
      (is (str/includes? (str content) "task: htw-console-app\n"))
      (is (str/includes? (str content) text))
      (is (empty? (read-argv argv-file))))))

(deftest pack-web-post-chat-injects-text-as-is
  ;; Given a tmux argv stub
  ;; When POST /api/chat {text}
  ;; Then inject-master! send-keys that text, not a Task payload
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        text "Please add a --help flag"]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                               "--test-post-chat" (str root) text)
          argv (read-argv argv-file)]
      (is (zero? (:exit result)))
      (is (str/includes? (str (last (first argv))) text))
      (is (re-find #"\[req-" (str (last (first argv)))))
      (is (not (str/starts-with? (str (last (first argv))) "Task:")))
      (is (= "C-m" (last (second argv))))
      (is (= "C-j" (last (nth argv 2)))))))

(deftest attention-reject-injects-a-message-to-master
  ;; Given a pending approval and a tmux argv stub
  ;; When retry with comments
  ;; Then master receives those comments and no New Task note is queued
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))]
    (setup-pack! root six-pack-roles)
    (create-task root "htw-console-app" "specifier")
    (let [task-id (:id (task-card root "htw-console-app"))]
      (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
      (write-file
       (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")
       (str "from: specifier\n"
            "to: coder\n"
            "priority: 50\n"
            "type: git_handoff\n"
            "task_id: " task-id "\n"
            "task: htw-console-app\n"
            "\n"
            "payload\n"))
      (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                                 "--test-retry-task" (str root)
                                 "50_from_specifier_to_coder"
                                 "use an RNG")
            argv (read-argv argv-file)
            notes (fs/list-dir (fs/path root ".swarmforge/handoffs/outbox"))]
        (is (zero? (:exit result)))
        (is (= [] (pending-names root)))
        (is (not (fs/exists? (fs/path root ".swarmforge/notify/reject-htw-console-app"))))
        (is (str/includes? (str (last (first argv))) "use an RNG"))
        (is (empty? (filter #(str/includes? (fs/file-name %) "New_Task") notes)))
        (is (= "C-m" (last (second argv))))
        (is (= "C-j" (last (nth argv 2))))))))

(deftest pack-web-lists-every-role-in-the-work-queue
  ;; Given a six-pack with no in_process mail
  ;; When pack_web --test-state
  ;; Then work_in_flight has one row per conf role
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        wif (:work_in_flight (web-state root))]
    (is (= six-pack-roles (mapv :role wif)))
    (is (every? #(= "no_session" (:state %)) wif))
    (is (every? #(= 0 (:activity %)) wif))))

(deftest pack-web-lists-in-process-work-in-flight
  ;; Given in_process handoff for coder task cave-walk
  ;; When pack_web --test-state
  ;; Then work_in_flight includes task cave-walk role coder
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (put-in-process! root roles "coder" {:from "specifier" :task "cave-walk"})
    (let [wif (:work_in_flight (web-state root))
          row (some #(when (= "coder" (:role %)) %) wif)]
      (is (= roles (mapv :role wif)))
      (is (= "cave-walk" (:task row)))
      (is (= "coder" (:role row)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (or (:updated_at row) ""))))))

(deftest pack-web-marks-in-process-roles-live-when-session-exists
  ;; Given coder in_process and live tmux sessions
  ;; When pack_web --test-state
  ;; Then coder is live with that task and specifier is idle
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        sock (do (setup-pack! root roles)
                 (put-in-process! root roles "coder" {:from "specifier" :task "cave-walk"})
                 (start-tmux! root roles))]
    (try
      (let [wif (:work_in_flight (web-state root))
            by-role (into {} (map (juxt :role identity) wif))]
        (is (= "idle" (:state (get by-role "specifier"))))
        (is (= "live" (:state (get by-role "coder"))))
        (is (= "cave-walk" (:task (get by-role "coder"))))
        (is (= "" (:task (get by-role "specifier")))))
      (finally
        (stop-tmux! sock)))))

(deftest pack-web-lists-batch-in-process-in-work-in-flight
  ;; Given a batch dir in coder in_process for task cave-walk
  ;; When pack_web --test-state
  ;; Then work_in_flight includes task cave-walk role coder
  (let [root (tmp-dir)
        roles ["specifier" "coder"]]
    (setup-pack! root roles)
    (put-in-process! root roles "coder"
                     {:from "specifier"
                      :task "cave-walk"
                      :filename "batch_20260615T000001Z_000001/50_from_specifier_to_coder.handoff"})
    (let [wif (:work_in_flight (web-state root))]
      (is (some #(and (= "cave-walk" (:task %)) (= "coder" (:role %))) wif)))))



(deftest pack-agent-page-polls-live-pane
  ;; When serving the agent session window
  ;; Then it polls /api/agents/<role>/pane
  (let [result (pack-web (tmp-dir) false "--test-agent-page" "specifier")]
    (is (zero? (:exit result)))
    (is (str/includes? (:out result) "/api/agents/specifier/pane"))
    (is (not (str/includes? (:out result) "?project=")))
    (is (str/includes? (:out result) "setInterval(refresh"))
    (is (str/includes? (:out result) "toEndSoon"))
    (is (str/includes? (:out result) "stickBottom"))))

(deftest pack-agent-page-polls-project-pane
  ;; Given a forge project agent window
  ;; When serving the agent session page
  ;; Then it polls /api/agents/<role>/pane?project=<name>
  (let [result (pack-web (tmp-dir) false "--test-agent-page" "specifier" "HTW")]
    (is (zero? (:exit result)))
    (is (str/includes? (:out result) "/api/agents/specifier/pane?project=HTW"))))

(deftest pack-web-test-pane-prints-recorded-pane
  ;; Given a recorded pane.txt for coder task cave-walk
  ;; When pack_web --test-pane
  ;; Then it prints that text
  (let [root (tmp-dir)
        text "coder pane snapshot\n"]
    (setup-pack! root ["specifier" "coder"])
    (write-file (fs/path root ".swarmforge/sessions/coder/pane.txt") text)
    (let [result (pack-web root false "--test-pane" (str root) "coder")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "coder pane snapshot")))))

(deftest pack-web-pane-capture-of-missing-session-is-quiet
  (let [root (tmp-dir)]
    (setup-pack! root ["coder"])
    (let [result (pack-web root false "--test-pane" (str root) "coder")]
      (is (zero? (:exit result)))
      (is (str/blank? (str/trim (str (:err result))))))))

(deftest pack-web-teardown-throw-is-not-a-clean-success
  (let [root (tmp-dir)]
    (setup-pack! root)
    (let [result (pack-web root false "--test-teardown-throw" (str root))]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result)) "teardown failed"))
      (is (str/includes? (str (:err result)) (str root))))))

(deftest handoffd-archives-sender-pane-when-task-moves
  ;; Given card and specifier→coder handoff (two-pack coder→cleaner to skip attention)
  ;; When delivered
  ;; Then .swarmforge/sessions/<from>/<task>/pane.txt exists
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "htw-console-app" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw-console-app"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root {"SWARMFORGE_PANE_STUB" "pane\n"})
      (let [pane (role-pane-path root "coder")]
        (is (fs/exists? pane))
        (is (= "pane\n" (slurp (str pane))))
        (is (not (fs/exists? (pane-path root "coder" "htw-console-app")))))
      (finally
        (stop-tmux! sock)))))

(deftest pack-board-archives-live-role-panes
  ;; Given a two-pack with a live card in coder and a done card
  ;; When pack_board archive-all with SWARMFORGE_PANE_STUB
  ;; Then coder's pane.txt exists and the done card is skipped
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]]
    (setup-pack! root roles)
    (create-task root "htw-console-app" "coder")
    (create-task root "already-done" "done")
    (let [result (run {:dir root :env {"SWARMFORGE_PANE_STUB" "pane\n"}}
                      (script "pack_board.sh")
                      "archive-all" "--root" (str root))]
      (is (zero? (:exit result)))
      (is (= "pane\n" (slurp (str (role-pane-path root "coder")))))
      (is (not (fs/exists? (role-pane-path root "done"))))
      (is (not (fs/exists? (pane-path root "coder" "htw-console-app")))))))

(deftest close-swarm-archives-live-role-panes
  ;; Given a two-pack with a live card in coder
  ;; When close-swarm
  ;; Then coder's pane.txt is archived
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]]
    (setup-pack! root roles)
    (create-task root "htw-console-app" "coder")
    (write-file (fs/path root ".swarmforge/tmux-socket")
                (str (fs/path root "tmux.sock") "\n"))
    (write-file (fs/path root ".swarmforge/window-ids") "")
    (let [result (run {:dir root
                       :env {"SWARMFORGE_TERMINAL_BACKEND" "none"
                             "SWARMFORGE_PANE_STUB" "pane\n"}}
                      (str (fs/path repo-root "close-swarm"))
                      (str root))]
      (is (zero? (:exit result)))
      (is (= "pane\n" (slurp (str (role-pane-path root "coder"))))))))

(defn wait-file [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (fs/exists? path) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

(deftest pack-web-serve-writes-dashboard-url-and-binds-localhost
  ;; Given a pack root
  ;; When pack_web --serve <root>
  ;; Then dashboard-url is a localhost URL and GET / serves the dashboard
  (let [root (tmp-dir)
        url-file (fs/path root ".swarmforge/dashboard-url")
        pb (doto (java.lang.ProcessBuilder. [(script "pack_web.sh") "--serve" (str root)])
             (.directory (java.io.File. (str root))))
        _ (doto (.environment pb)
            (.put "PATH" (System/getenv "PATH"))
            (.put "GIT_CONFIG_NOSYSTEM" "1"))
        proc (.start pb)]
    (try
      (is (wait-file url-file 5000) "dashboard-url was written")
      (let [pid-file (fs/path root ".swarmforge/pack_web.pid")]
        (is (wait-file pid-file 5000) "pack_web.pid was written")
        (is (= (str (.pid proc)) (str/trim (slurp (str pid-file))))))
      (when (fs/exists? url-file)
        (let [url (str/trim (slurp (str url-file)))
              html (slurp url)]
          (is (re-find #"^http://127\.0\.0\.1:\d+$" url))
          (is (str/includes? html "New Task"))))
      (finally
        (.destroyForcibly proc)
        (.waitFor proc)))))

(deftest pack-web-thermometer-heat-is-per-agent
  ;; Given two pack roots each with a specifier
  ;; When one pane changes and the other stays put
  ;; Then only the changed agent's thermometer rises
  (let [root-a (tmp-dir)
        root-b (tmp-dir)
        _ (setup-pack! root-a ["specifier"])
        _ (setup-pack! root-b ["specifier"])
        result (pack-web root-a false "--test-heat-isolation" (str root-a) (str root-b))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (pos? (:changed body)))
    (is (zero? (:stable body)))))

(deftest pack-web-thermometer-heat-rises-when-pane-changes
  ;; Given a specifier row
  ;; When --test-heat samples two different pane texts
  ;; Then activity after is greater than activity before
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))
    (is (<= 0 (:before body)))
    (is (<= (:after body) 6))))

(deftest pack-web-thermometer-heats-on-codex-working-timer
  ;; Given a Codex specifier pane whose only change is the working timer
  ;; When --test-heat-codex samples both
  ;; Then activity rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-codex" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))

(deftest pack-web-teardown-requires-confirm
  ;; Given a pack root
  ;; When POST /api/teardown without confirm
  ;; Then it is rejected
  (let [root (tmp-dir)
        result (pack-web root false "--test-teardown" (str root))]
    (is (= 2 (:exit result)))
    (is (str/includes? (str (:err result) (:out result)) "TEARDOWN"))))

(deftest pack-web-teardown-kills-sessions-and-handoffd
  ;; Given a live tmux session and a fake handoffd pid
  ;; When teardown is confirmed
  ;; Then the tmux server is dead and the daemon pid is gone
  (let [root (tmp-dir)
        _ (setup-pack! root ["coder" "cleaner"])
        sock (start-tmux! root ["coder" "cleaner"])
        daemon (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        pid (str (.pid daemon))
        pack-web-proc (.start (java.lang.ProcessBuilder. ["sleep" "120"]))
        pack-web-pid (str (.pid pack-web-proc))]
    (try
      (write-file (fs/path root ".swarmforge/daemon/handoffd.pid") (str pid "\n"))
      (write-file (fs/path root ".swarmforge/pack_web.pid") (str pack-web-pid "\n"))
      (let [result (pack-web root false "--test-teardown" (str root) "TEARDOWN")]
        (is (zero? (:exit result)))
        (is (str/includes? (:out result) "teardown_started"))
        (is (not= 0 (:exit (run {:dir root :ok? false} "tmux" "-S" sock "list-sessions"))))
        (is (false? (.isAlive daemon)))
        (is (false? (.isAlive pack-web-proc)))
        (is (not (fs/exists? (fs/path root ".swarmforge/daemon/handoffd.pid"))))
        (is (not (fs/exists? (fs/path root ".swarmforge/pack_web.pid")))))
      (finally
        (when (.isAlive daemon)
          (.destroyForcibly daemon))
        (when (.isAlive pack-web-proc)
          (.destroyForcibly pack-web-proc))
        (stop-tmux! sock)))))

(deftest pack-board-move-matches-task-name-ignoring-case
  ;; Given board card HTW
  ;; When pack_board move --name htw --lane coder
  ;; Then the card HTW is in coder
  (let [root (tmp-dir)]
    (setup-pack! root)
    (create-task root "HTW" "specifier")
    (pack-board root true "move" "--root" (str root) "--name" "htw" "--lane" "coder")
    (is (= "coder" (task-lane root "HTW")))))

(deftest handoffd-moves-card-when-handoff-task-case-differs
  ;; Given card HTW in coder
  ;; When git_handoff coder→cleaner task htw is delivered
  ;; Then HTW is in cleaner
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "htw"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "cleaner" (task-lane root "HTW")))
      (finally
        (stop-tmux! sock)))))

(deftest handoffd-does-not-deliver-when-board-task-is-unknown
  ;; Given card HTW and a handoff for other-task
  ;; When delivered
  ;; Then coder inbox stays empty and HTW stays in specifier
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        sock (do (setup-pack! root roles)
                 (create-task root "HTW" "coder")
                 (queue-handoff! root {:from "coder" :to "cleaner" :task "other-task"})
                 (start-tmux! root roles))]
    (try
      (handoffd-once root)
      (is (= "coder" (task-lane root "HTW")))
      (is (= [] (inbox-names root roles "cleaner")))
      (finally
        (stop-tmux! sock)))))

(deftest pack-web-shows-board-card-as-live-work
  ;; Given card HTW in specifier and a live specifier session
  ;; When pack_web --test-state
  ;; Then specifier row is live with task HTW
  (let [root (tmp-dir)
        sock (do (setup-pack! root)
                 (create-task root "HTW" "specifier")
                 (start-tmux! root ["specifier"]))]
    (try
      (let [row (some #(when (= "specifier" (:role %)) %)
                      (:work_in_flight (web-state root)))]
        (is (= "HTW" (:task row)))
        (is (= "live" (:state row))))
      (finally
        (stop-tmux! sock)))))

(deftest pack-web-chat-persists-and-answers
  ;; Given a pack root
  ;; When POST /api/chat then pack_dashboard_request answer
  ;; Then /api/state chat has the body and response
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))
        answer (fs/path root "tmp" "answer.txt")]
    (setup-pack! root)
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (write-file answer "the spec is ready\nwith two documents\n")
    (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                  "--test-post-chat" (str root) "status?")
    (let [listed (run {:dir root}
                      (script "pack_dashboard_request.sh")
                      "list" "--root" (str root))
          id (first (str/split (str/trim (:out listed)) #"\t"))]
      (is (str/starts-with? id "req-"))
      (run {:dir root} (script "pack_dashboard_request.sh") "answer" id (str answer))
      (let [chat (:chat (web-state root))
            row (first chat)
            stored (slurp (str (first (fs/list-dir
                                       (fs/path root ".swarmforge/dashboard/requests/done")))))]
        (is (= "status?" (str/trim (:body row))))
        (is (= "the spec is ready\nwith two documents" (:response row)))
        (is (str/includes? stored "response: the spec is ready\\nwith two documents\n"))
        (is (= "done" (:status row)))))))

















(deftest pack-web-state-groups-in-process-batch-cards
  ;; Given two-pack and two cleaner in-process handoffs in one batch dir
  ;; When --test-state
  ;; Then those tasks share a batch id
  (let [root (tmp-dir)
        roles ["coder" "cleaner"]
        _ (setup-pack! root roles)
        _ (create-task root "Command syntax" "cleaner")
        _ (create-task root "validation" "cleaner")
        batch "batch_20260824T150500Z_000001"
        dir (fs/path (in-process-dir root roles "cleaner") batch)]
    (write-file (fs/path dir "50_command.handoff")
                "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: Command syntax\n\npayload\n")
    (write-file (fs/path dir "50_validation.handoff")
                "from: coder\nto: cleaner\npriority: 50\ntype: git_handoff\ntask: validation\n\npayload\n")
    (let [by-name (into {} (map (juxt :name identity) (:tasks (web-state root))))]
      (is (= (get-in by-name ["Command syntax" :batch])
             (get-in by-name ["validation" :batch])))
      (is (some? (get-in by-name ["Command syntax" :batch]))))))









(deftest pack-web-thermometer-ignores-reordered-tail
  ;; Given a pane whose last 20 lines are the same bag in a new order
  ;; When --test-heat-reorder samples both
  ;; Then activity does not rise
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-reorder" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (= (:before body) (:after body)))))

(deftest pack-web-thermometer-uses-last-twenty-line-bag
  ;; Given a 25-line pane whose first five lines then change
  ;; When --test-heat-head samples both
  ;; Then activity stays at the baseline (tail bag unchanged)
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-head" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (= (:before body) (:after body)))))

(deftest pack-web-card-status-is-last-im-sentence
  ;; Given a specifier card and a pane tail with an I'm sentence
  ;; When --test-state
  ;; Then that task's status is that sentence
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (let [result (pack-web-env root {} "--test-status-pane" (str root)
                               "Working on HTW.\nI'm idle, so I'm running ready_for_next.sh.\nesc to interrupt · 3s\n")
          state (json/parse-string (:out result) true)
          card (first (:tasks state))]
      (is (zero? (:exit result)))
      (is (= "HTW" (:name card)))
      (is (str/includes? (str (:status card)) "I'm idle, so I'm running ready_for_next.sh")))))

(deftest pack-web-card-status-includes-codex-summaries
  (doseq [sentence ["Received task extras from the board."
                    "The HHG rules are now settled."
                    "The operator resolved the throw messages."
                    "Completed extras and queued the coder handoff."
                    "The exact-commit audit found no remaining gaps."]]
    (let [root (tmp-dir)
          _ (setup-pack! root)
          _ (create-task root "HTW" "specifier")
          result (pack-web-env root {} "--test-status-pane" (str root)
                               (str sentence "\nesc to interrupt · 3s\n"))
          card (first (:tasks (json/parse-string (:out result) true)))]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:status card)) sentence)))))

(deftest pack-web-card-status-ignores-tool-trace-lines
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll commit the spec.\n"
                                  "• Ran 7 commands\n"
                                  "• Edited features/003.feature\n"
                                  "• Added tmp/htw-handoff.txt\n"))
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll commit the spec"))
    (is (not (str/includes? (str (:status card)) "Ran 7")))))

(deftest pack-web-card-status-includes-continue-sentences
  ;; Given a specifier card and a pane tail whose last matching sentence uses continue
  ;; When --test-status-pane
  ;; Then that task's status is that sentence
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (let [result (pack-web-env root {} "--test-status-pane" (str root)
                               "Working on HTW.\nI'll continue with the cave map.\nesc to interrupt · 3s\n")
          state (json/parse-string (:out result) true)
          card (first (:tasks state))]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:status card)) "I'll continue with the cave map.")))))

(deftest pack-web-card-status-joins-wrapped-pane-lines
  ;; Given an I'll sentence split across two pane lines
  ;; When --test-status-pane
  ;; Then status is the full sentence with a space at the wrap
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (let [result (pack-web-env root {} "--test-status-pane" (str root)
                               "I'll continue with the\ncave map for HTW.\n")
          card (first (:tasks (json/parse-string (:out result) true)))]
      (is (zero? (:exit result)))
      (is (str/includes? (str (:status card)) "I'll continue with the cave map for HTW.")))))

(deftest pack-web-card-status-ignores-transcript-and-helper-chrome
  ;; Given an I'll sentence then a collapsed transcript line and helper audit copy
  ;; When --test-status-pane
  ;; Then status is the I'll sentence, not the chrome
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll write the cave stories.\n"
                                  "… +15 lines (ctrl + t to view transcript)\n"
                                  "Fix every finding, commit the corrections, rerun applicable checks, and repeat "
                                  "this audit against the revised candidate before running the handoff command again.\n"))
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll write the cave stories"))
    (is (not (str/includes? (str (:status card)) "view transcript")))
    (is (not (str/includes? (str (:status card)) "running the handoff command again")))))

(deftest pack-web-codex-status-is-last-work-bullet
  ;; Given a Codex pane of throwaway bullets then a work bullet with no I'll
  ;; When --test-status-pane
  ;; Then status is that work bullet
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "• You have 1 usage limit reset available. Run /usage to use one.\n"
                                  "• Ran 4 commands · ctrl + t to view transcript\n"
                                  "• Edited features/htw.feature\n"
                                  "• Added tmp/htw_handoff.txt\n"
                                  "• Searching the web\n"
                                  "• Searched the web for yob\n"
                                  "• Working (3s • esc to interrupt)\n"
                                  "• The specification now makes every random domain explicit.\n"))
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "specification now makes every random domain explicit"))
    (is (not (str/includes? (str (:status card)) "Ran 4")))
    (is (not (str/includes? (str (:status card)) "Working")))))

(deftest pack-web-non-codex-status-still-uses-ill
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (set-backend! root "grok")
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "I'll write the cave stories.\n")
        card (first (:tasks (json/parse-string (:out result) true)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll write the cave stories"))))

(deftest pack-web-card-status-ignores-handoff-mail-banner
  ;; Given an I'll sentence and a later If idle, run ready_for_next.sh banner
  ;; When --test-status-pane
  ;; Then status is the I'll sentence, not the mail line
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll commit the spec and queue the coder handoff.\n"
                                  "You have new handoff mail. If idle, run ready_for_next.sh.\n"
                                  "esc to interrupt · 1s\n"))
        state (json/parse-string (:out result) true)
        card (first (:tasks state))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll commit the spec"))
    (is (not (str/includes? (str (:status card)) "ready_for_next")))))

(deftest pack-web-grok-card-status-uses-work-not-chrome
  ;; Given a Grok pane with an I'll sentence under mail and chrome
  ;; When --test-status-pane
  ;; Then status is the I'll sentence
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (set-backend! root "grok")
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             (str "I'll commit the spec and queue the coder handoff.\n"
                                  "You have new handoff mail. If idle, run ready_for_next.sh.\n"
                                  "always-approve  shift+tab\n"
                                  "Waiting for response...\n"
                                  "enter:send  Esc:cancel\n"))
        state (json/parse-string (:out result) true)
        card (first (:tasks state))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status card)) "I'll commit the spec"))
    (is (not (str/includes? (str (:status card)) "ready_for_next")))
    (is (not (str/includes? (str (:status card)) "Waiting for response")))))

(deftest pack-web-waiting-cards-say-waiting-in-queue
  ;; Given two specifier cards and a pane I'm sentence
  ;; When --test-status-pane
  ;; Then both cards say waiting in queue and the work row is not marked as a batch
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (create-task root "Holy Hand Grenade" "specifier")
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "I'm specifying HTW.\nesc to interrupt · 1s\n")
        state (json/parse-string (:out result) true)
        by-name (into {} (map (juxt :name identity) (:tasks state)))
        specifier-row (some #(when (= "specifier" (:role %)) %)
                            (:work_in_flight state))]
    (is (zero? (:exit result)))
    (is (= "waiting in queue" (:status (get by-name "HTW"))))
    (is (= "waiting in queue" (:status (get by-name "Holy Hand Grenade"))))
    (is (= ["HTW" "Holy Hand Grenade"] (:tasks specifier-row)))
    (is (= [] (:batch_tasks specifier-row)))))

(deftest pack-web-in-process-card-gets-pane-status
  ;; Given two coder cards and in-process mail for Holy Hand Grenade
  ;; When --test-status-pane
  ;; Then Holy Hand Grenade has the pane sentence and HTW says waiting in queue
  (let [root (tmp-dir)
        roles ["specifier" "coder"]
        _ (setup-pack! root roles)
        _ (create-task root "HTW" "coder")
        _ (create-task root "Holy Hand Grenade" "coder")
        _ (put-in-process! root roles "coder"
                           {:from "specifier" :task "Holy Hand Grenade"})
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "I'm merging the grenade.\nesc to interrupt · 1s\n")
        state (json/parse-string (:out result) true)
        by-name (into {} (map (juxt :name identity) (:tasks state)))]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:status (get by-name "Holy Hand Grenade")))
                       "I'm merging the grenade"))
    (is (= "waiting in queue" (:status (get by-name "HTW"))))))

(deftest pack-web-shows-yellow-merging-card-for-handback
  ;; Given htw in architect and jump in coder, plus a reverse htw copy in coder in_process
  ;; When --test-status-pane
  ;; Then a merging card for htw is in coder with Merging refactorer, jump waits, real htw stays architect
  (let [root (tmp-dir)
        roles four-pack-roles
        _ (setup-pack! root roles)
        _ (create-task root "htw" "architect")
        _ (create-task root "jump" "coder")
        _ (write-file
           (fs/path (in-process-dir root roles "coder")
                    "00_from_refactorer_to_coder.handoff")
           (str "from: refactorer\nto: coder\npriority: 00\ntype: git_handoff\n"
                "task: htw\nnon-forwarding: true\n\nmerge\n"))
        result (pack-web-env root {} "--test-status-pane" (str root)
                             "• The reverse handoff is structurally reconciled.\n")
        state (json/parse-string (:out result) true)
        cards (:tasks state)
        merging (filterv :merging cards)
        jump-card (first (filter #(= "jump" (:name %)) cards))
        htw-card (first (filter #(and (= "htw" (:name %)) (= "architect" (:lane %))) cards))]
    (is (zero? (:exit result)))
    (is (= ["htw" "htw" "jump"] (mapv :name cards)))
    (is (= 1 (count merging)))
    (is (= "coder" (:lane (first merging))))
    (is (= "htw" (:name (first merging))))
    (is (= "Merging refactorer" (:status (first merging))))
    (is (= "waiting in queue" (:status jump-card)))
    (is (= "architect" (:lane htw-card))))
  (let [root (tmp-dir)
        roles four-pack-roles
        _ (setup-pack! root roles)
        _ (create-task root "htw" "architect")
        _ (create-task root "jump" "coder")
        state (web-state root)
        merging (filterv :merging (:tasks state))]
    (is (= [] merging))
    (is (= "architect" (task-lane root "htw")))))

(deftest pack-web-pending-approval-card-says-waiting-for-approval
  ;; Given HTW in specifier and a pending specifier→coder git_handoff for HTW
  ;; When --test-state
  ;; Then HTW status is Waiting for approval
  (let [root (tmp-dir)
        _ (setup-pack! root six-pack-roles)
        _ (create-task root "HTW" "specifier")
        _ (create-task root "Command Syntax" "specifier")]
    (write-file
     (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")
     "from: specifier\nto: coder\npriority: 50\ntype: git_handoff\ntask: HTW\n\npayload\n")
    (let [state (web-state root)
          by-name (into {} (map (juxt :name identity) (:tasks state)))]
      (is (= "Waiting for approval" (:status (get by-name "HTW"))))
      (is (= "waiting in queue" (:status (get by-name "Command Syntax")))))))

(deftest pack-web-rejected-card-says-rejected
  ;; Given HTW is rejected
  ;; When --test-state
  ;; Then HTW status is REJECTED
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")]
    (write-file (fs/path root ".swarmforge/notify/reject-HTW") "rejected\n")
    (let [card (first (:tasks (web-state root)))]
      (is (= "REJECTED" (:status card))))))

(deftest pack-web-delete-removes-a-rejected-card
  ;; Given a rejected HTW card
  ;; When POST /api/tasks/delete
  ;; Then the card is gone from the board
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        old-id (:id (task-card root "HTW"))
        _ (increment-audit! root old-id)
        _ (write-file (fs/path root ".swarmforge/notify/reject-HTW") "rejected\n")
        result (pack-web root false "--test-delete-task" (str root) "HTW")]
    (is (zero? (:exit result)))
    (is (nil? (task-lane root "HTW")))
    (is (not (fs/exists? (fs/path root ".swarmforge/board/HTW.txt"))))
    (is (fs/exists? (fs/path root "tasks/HTW.md")))
    (create-task root "HTW" "specifier")
    (let [replacement (task-card root "HTW")]
      (is (not= old-id (:id replacement)))
      (is (= 0 (:audit_count replacement))))))

(deftest pack-web-delete-rejected-purges-handoffs-into-rejected-tasks
  ;; Given a rejected HTW card with a pending git_handoff
  ;; When POST /api/tasks/delete
  ;; Then the card, notify, and handoff are gone and rejected-tasks keeps the set
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (increment-audit! root (:id (task-card root "HTW")))
        _ (write-file (fs/path root ".swarmforge/notify/reject-HTW") "rejected\n")
        pending (fs/path root ".swarmforge/handoffs/pending_approval/50_from_specifier_to_coder.handoff")
        _ (write-file pending
                      "from: specifier\nto: coder\ntype: git_handoff\ntask: HTW\n\npayload\n")
        _ (write-pending-audit! root "HTW")
        _ (write-pending-audit! root "unrelated-id")
        result (pack-web root false "--test-delete-task" (str root) "HTW")]
    (is (zero? (:exit result)))
    (is (nil? (task-lane root "HTW")))
    (is (not (fs/exists? pending)))
    (is (= #{"unrelated-id"} (pending-audit-task-ids root)))
    (is (not (fs/exists? (fs/path root ".swarmforge/notify/reject-HTW"))))
    (is (fs/exists? (fs/path root ".swarmforge/rejected-tasks")))))

(deftest pack-web-retry-moves-a-completed-retry-note-back-to-in-process
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        retry-name (str "50_retry_" (str/replace task-id #"[^A-Za-z0-9]+" "_") ".handoff")
        completed (fs/path root ".swarmforge/handoffs/inbox/completed" retry-name)
        in-process (fs/path root ".swarmforge/handoffs/inbox/in_process" retry-name)]
    (write-file completed
                (str "from: (Retry)\n"
                     "to: specifier\n"
                     "priority: 50\n"
                     "type: note\n"
                     "task_id: " task-id "\n"
                     "task: HTW\n"
                     "completed_at: 2026-08-26T22:45:36.178441Z\n"
                     "\n"
                     "Retry audit.\n"))
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.handoff")
                (str "from: specifier\nto: coder\ntype: git_handoff\n"
                     "task_id: " task-id "\ntask: HTW\n\npayload\n"))
    (let [result (pack-web root false "--test-retry-task" (str root) "50_hello" "use an RNG")]
      (is (zero? (:exit result)))
      (is (fs/exists? in-process))
      (is (not (fs/exists? completed)))
      (is (str/includes? (slurp (str in-process)) (str "task_id: " task-id))))))

(deftest pack-web-second-retry-does-not-leave-copies-in-both-inboxes
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        retry-name (str "50_retry_" (str/replace task-id #"[^A-Za-z0-9]+" "_") ".handoff")
        completed (fs/path root ".swarmforge/handoffs/inbox/completed" retry-name)
        in-process (fs/path root ".swarmforge/handoffs/inbox/in_process" retry-name)]
    (write-file completed
                (str "from: (Retry)\n"
                     "to: specifier\n"
                     "priority: 50\n"
                     "type: note\n"
                     "task_id: " task-id "\n"
                     "task: HTW\n"
                     "\n"
                     "Retry audit.\n"))
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_first.handoff")
                (str "from: specifier\nto: coder\ntype: git_handoff\n"
                     "task_id: " task-id "\ntask: HTW\n\npayload\n"))
    (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                "50_first" "first"))))
    (is (zero? (:exit (run {:dir root :env {"SWARMFORGE_ROLE" "specifier"}}
                           (script "done_with_current.sh")))))
    (is (fs/exists? completed))
    (is (not (fs/exists? in-process)))
    (write-file (fs/path root ".swarmforge/handoffs/pending_approval/50_second.handoff")
                (str "from: specifier\nto: coder\ntype: git_handoff\n"
                     "task_id: " task-id "\ntask: HTW\n\npayload\n"))
    (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                "50_second" "second"))))
    (is (fs/exists? in-process))
    (is (not (fs/exists? completed)))))

(deftest pack-web-retry-rejected-queues-a-master-note
  ;; Given a pending git_handoff
  ;; When POST /api/tasks/retry with comments
  ;; Then the card stays, original body is unchanged, audit_count increases, and no New Task note is queued
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        _ (increment-audit! root task-id)
        original (slurp (str (fs/path root ".swarmforge/board/HTW.txt")))
        pending (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.handoff")
        _ (write-file pending
                      (str "from: specifier\nto: coder\ntype: git_handoff\n"
                           "task_id: " task-id "\ntask: HTW\n\nold\n"))
        _ (write-pending-audit! root task-id)
        _ (write-pending-audit! root "unrelated-id")
        result (pack-web root false "--test-retry-task" (str root)
                         "50_hello" "use an RNG")
        card (first (:tasks (web-state root)))
        notes (if (fs/directory? (fs/path root ".swarmforge/handoffs/outbox"))
                (fs/list-dir (fs/path root ".swarmforge/handoffs/outbox"))
                [])]
    (is (zero? (:exit result)))
    (is (= "specifier" (:lane card)))
    (is (= 2 (:audit_count card)))
    (is (not= "REJECTED" (:status card)))
    (is (= original (slurp (str (fs/path root ".swarmforge/board/HTW.txt")))))
    (is (not (fs/exists? pending)))
    (is (= #{"unrelated-id"} (pending-audit-task-ids root)))
    (is (empty? (filter #(str/includes? (fs/file-name %) "New_Task") notes)))))

(deftest pack-web-retry-snapshots-rejected-branches-without-reset
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (setup-pack! root)
    (write-file (fs/path root "story.md") "base\n")
    (run {:dir root} "git" "add" "story.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Base")
    (create-task root "HTW" "specifier")
    (let [task-id (:id (task-card root "HTW"))
          base (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-file (fs/path root "story.md") "offer-1\n")
      (run {:dir root} "git" "add" "story.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Offer 1")
      (let [first-sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_first.handoff")]
        (write-file pending
                    (str "from: specifier\nto: coder\ntype: git_handoff\n"
                         "task_id: " task-id "\ntask: HTW\n"
                         "commit: " first-sha "\n"
                         "task_base_commit: " base "\n\n"
                         "payload\n"))
        (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                    "50_first" "first comments"))))
        (let [head (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
              branches (:out (run {:dir root} "git" "branch" "--format=%(refname:short)"))]
          (is (= first-sha head))
          (is (not= base head))
          (is (str/includes? branches (str "rejected/" task-id "/1")))
          (is (str/includes? branches (str "rejected/" task-id "/latest"))))
        (write-file (fs/path root "story.md") "offer-2\n")
        (run {:dir root} "git" "add" "story.md")
        (run {:dir root} "git" "commit" "-q" "-m" "Offer 2")
        (let [second-sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
              pending2 (fs/path root ".swarmforge/handoffs/pending_approval/50_second.handoff")]
          (write-file pending2
                      (str "from: specifier\nto: coder\ntype: git_handoff\n"
                           "task_id: " task-id "\ntask: HTW\n"
                           "commit: " second-sha "\n"
                           "task_base_commit: " base "\n\n"
                           "payload\n"))
          (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                      "50_second" "second comments"))))
          (let [head (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
                branches (:out (run {:dir root} "git" "branch" "--format=%(refname:short)"))]
            (is (= second-sha head))
            (is (str/includes? branches (str "rejected/" task-id "/1")))
            (is (str/includes? branches (str "rejected/" task-id "/2")))
            (is (str/includes? branches (str "rejected/" task-id "/latest")))
            (is (= 2 (:audit_count (task-card root "HTW"))))))))))

(deftest pack-web-retry-restores-wandered-head
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (setup-pack! root)
    (write-file (fs/path root "story.md") "base\n")
    (run {:dir root} "git" "add" "story.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Base")
    (create-task root "HTW" "specifier")
    (let [task-id (:id (task-card root "HTW"))]
      (write-file (fs/path root "story.md") "offer\n")
      (run {:dir root} "git" "add" "story.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Offer")
      (let [offer (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")]
        (write-file pending
                    (str "from: specifier\nto: coder\ntype: git_handoff\n"
                         "task_id: " task-id "\ntask: HTW\n"
                         "commit: " offer "\n\npayload\n"))
        (write-file (fs/path root "story.md") "wander\n")
        (run {:dir root} "git" "add" "story.md")
        (run {:dir root} "git" "commit" "-q" "-m" "Wander")
        (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                    "50_offer" "stay on the offer"))))
        (is (= offer (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))))))))

(deftest pack-web-retry-keeps-task-base-for-the-next-git-handoff
  (let [root (tmp-dir)]
    (run {:dir root} "git" "init" "-q")
    (run {:dir root} "git" "config" "user.email" "test@example.com")
    (run {:dir root} "git" "config" "user.name" "Test User")
    (write-file (fs/path root "README.md") "initial\n")
    (run {:dir root} "git" "add" "README.md")
    (run {:dir root} "git" "commit" "-q" "-m" "Initial")
    (setup-pack! root ["specifier" "coder"])
    (create-task root "HTW" "specifier")
    (let [task-id (:id (task-card root "HTW"))
          base (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-file (fs/path root "tasks/HTW.md") "# HTW\n\nImplement the stories.\n")
      (write-file (fs/path root "extra.md") "first offer\n")
      (run {:dir root} "git" "add" "tasks/HTW.md" "extra.md")
      (run {:dir root} "git" "commit" "-q" "-m" "Offer")
      (let [offer (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))
            pending (fs/path root ".swarmforge/handoffs/pending_approval/50_offer.handoff")]
        (write-file pending
                    (str "from: specifier\nto: coder\ntype: git_handoff\n"
                         "task_id: " task-id "\ntask: HTW\n"
                         "commit: " offer "\n"
                         "task_base_commit: " base "\n\n"
                         "payload\n"))
        (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                    "50_offer" "use an RNG"))))
        (write-file (fs/path root "more.md") "stacked\n")
        (run {:dir root} "git" "add" "more.md")
        (run {:dir root} "git" "commit" "-q" "-m" "Stacked")
        (write-file (fs/path root "tmp/retry.handoff")
                    "type: git_handoff\nto: coder\npriority: 50\ntask: HTW\n")
        (let [opts {:dir root :env {"SWARMFORGE_ROLE" "specifier"} :ok? false}
              first-call (run opts (script "swarm_handoff.sh")
                              (str (fs/path root "tmp/retry.handoff")))]
          (is (zero? (:exit first-call)))
          (is (str/includes? (:out first-call) "AUDIT_REQUIRED"))
          (let [queued (run (assoc opts :ok? true) (script "swarm_handoff.sh")
                            (str (fs/path root "tmp/retry.handoff")))
                outbox (fs/glob (fs/path root ".swarmforge/handoffs/outbox") "*.handoff")
                content (slurp (str (first outbox)))]
            (is (zero? (:exit queued)))
            (is (str/includes? content "artifacts:"))
            (is (str/includes? content "extra.md"))
            (is (str/includes? content "more.md"))
            (is (str/includes? content "tasks/HTW.md"))))))))

(deftest pack-web-serves-a-document
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web root false "--test-doc" (str root) "tasks/HTW.md")]
    (is (zero? (:exit result)))
    (is (str/includes? (:out result) "HTW"))
    (is (str/includes? (:out result) "Integrate HTW stories"))))

(defn raw-state [root]
  (json/parse-string (:out (pack-web root true "--test-state" (str root)))))

(defn write-pending-approval! [root {:keys [id task task-id artifacts body]}]
  (write-file
   (fs/path root ".swarmforge/handoffs/pending_approval" (str id ".handoff"))
   (str "from: specifier\n"
        "to: coder\n"
        "type: git_handoff\n"
        "task_id: " (or task-id task) "\n"
        "task: " task "\n"
        (when artifacts (str "artifacts: " artifacts "\n"))
        "\n"
        (or body "payload\n"))))

(deftest pack-web-saves-remedial-comments-on-the-pending-approval
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (write-file (fs/path root "features/console.feature") "Feature: cave\n")
        _ (write-pending-approval! root {:id "50_hello" :task "HTW"
                                         :artifacts "features/console.feature"})
        saved (pack-web root false "--test-save-comments" (str root)
                        "50_hello" "features/console.feature" "use an RNG")
        reviews (get (first (get (raw-state root) "approvals")) "reviews")]
    (is (zero? (:exit saved)))
    (is (= "use an RNG" (get reviews "features/console.feature")))
    (let [blanked (pack-web root false "--test-save-comments" (str root)
                            "50_hello" "features/console.feature" "  \n")
          after (get (first (get (raw-state root) "approvals")) "reviews")]
      (is (zero? (:exit blanked)))
      (is (= "" (get after "features/console.feature")))
      (is (contains? after "features/console.feature")))))

(deftest pack-web-document-api-keeps-comment-history-and-last-diff
  (let [root (tmp-dir)
        _ (run {:dir root} "git" "init" "-q")
        _ (run {:dir root} "git" "config" "user.email" "test@example.com")
        _ (run {:dir root} "git" "config" "user.name" "Test User")
        _ (setup-pack! root)
        _ (write-file (fs/path root "features/console.feature") "Feature: cave\n")
        _ (run {:dir root} "git" "add" "features/console.feature")
        _ (run {:dir root} "git" "commit" "-q" "-m" "base")
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        first-sha (do (write-file (fs/path root "features/console.feature")
                                  "Feature: cave\n---\n  Scenario: one\n")
                      (run {:dir root} "git" "add" "features/console.feature")
                      (run {:dir root} "git" "commit" "-q" "-m" "offer-1")
                      (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD"))))
        _ (write-file
           (fs/path root ".swarmforge/handoffs/pending_approval/50_first.handoff")
           (str "from: specifier\nto: coder\ntype: git_handoff\n"
                "task_id: " task-id "\ntask: HTW\n"
                "commit: " first-sha "\n"
                "artifacts: features/console.feature\n\npayload\n"))
        first-doc (json/parse-string
                   (:out (pack-web root true "--test-api-doc" (str root)
                                   "features/console.feature" "50_first"))
                   true)]
    (is (false? (:has_diff first-doc)))
    (is (= [] (:history first-doc)))
    (is (str/includes? (:text first-doc) "Feature: cave"))
    (pack-web root true "--test-save-comments" (str root)
              "50_first" "features/console.feature" "needs an RNG")
    (is (zero? (:exit (pack-web root false "--test-retry-task" (str root)
                                "50_first" "retry the spec"))))
    (write-file (fs/path root "features/console.feature") "Feature: cave\n  Scenario: two\n")
    (run {:dir root} "git" "add" "features/console.feature")
    (run {:dir root} "git" "commit" "-q" "-m" "offer-2")
    (let [second-sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
      (write-file
       (fs/path root ".swarmforge/handoffs/pending_approval/50_second.handoff")
       (str "from: specifier\nto: coder\ntype: git_handoff\n"
            "task_id: " task-id "\ntask: HTW\n"
            "commit: " second-sha "\n"
            "artifacts: features/console.feature\n\npayload\n"))
      (let [doc (json/parse-string
                 (:out (pack-web root true "--test-api-doc" (str root)
                                 "features/console.feature" "50_second"))
                 true)
            hist (vec (:history doc))]
        (is (true? (:has_diff doc)))
        (is (= 2 (count hist)))
        (is (= "needs an RNG" (:text (first hist))))
        (is (= "retry the spec" (:text (second hist))))
        (is (not (str/blank? (:at (first hist)))))
        (is (not (str/blank? (:at (second hist)))))
        (is (some #(and (= "del" (:type %)) (str/includes? (str (:text %)) "one"))
                  (:lines doc)))
        (is (some #(and (= "del" (:type %)) (= "---" (:text %)))
                  (:lines doc)))
        (is (some #(and (= "add" (:type %)) (str/includes? (str (:text %)) "two"))
                  (:lines doc)))
        (is (some #(and (= "same" (:type %)) (str/includes? (str (:text %)) "Feature: cave"))
                  (:lines doc)))))
    (pack-web root true "--test-approve" (str root) "50_second")
    (is (not (fs/exists? (fs/path root ".swarmforge/rejected-tasks" task-id "reviews.json"))))))

(deftest pack-web-document-api-hides-diff-when-git-fails
  (let [root (tmp-dir)
        _ (run {:dir root} "git" "init" "-q")
        _ (run {:dir root} "git" "config" "user.email" "test@example.com")
        _ (run {:dir root} "git" "config" "user.name" "Test User")
        _ (setup-pack! root)
        _ (write-file (fs/path root "features/console.feature") "Feature: cave\n")
        _ (run {:dir root} "git" "add" "features/console.feature")
        _ (run {:dir root} "git" "commit" "-q" "-m" "base")
        _ (create-task root "HTW" "specifier")
        task-id (:id (task-card root "HTW"))
        sha (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD")))]
    (run {:dir root} "git" "branch" "-f" (str "rejected/" task-id "/latest") sha)
    (write-file
     (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.handoff")
     (str "from: specifier\nto: coder\ntype: git_handoff\n"
          "task_id: " task-id "\ntask: HTW\n"
          "commit: notacommit\n"
          "artifacts: features/console.feature\n\npayload\n"))
    (let [doc (json/parse-string
               (:out (pack-web root true "--test-api-doc" (str root)
                               "features/console.feature" "50_hello"))
               true)]
      (is (false? (:has_diff doc)))
      (is (= [] (:lines doc))))))

(deftest pack-web-approve-discards-remedial-comments
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        _ (write-pending-approval! root {:id "50_hello" :task "HTW"})
        _ (pack-web root false "--test-save-comments" (str root)
                    "50_hello" "features/console.feature" "use an RNG")
        result (pack-web root false "--test-approve" (str root) "50_hello")]
    (is (zero? (:exit result)))
    (is (= [] (pending-names root)))
    (is (not (fs/exists? (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.reviews.json"))))))

(deftest pack-web-retry-delivers-remedial-comments-to-master
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        sock (str (fs/path root "tmux.sock"))]
    (setup-pack! root)
    (create-task root "HTW" "specifier")
    (write-file (fs/path root ".swarmforge/tmux-socket") (str sock "\n"))
    (let [task-id (:id (task-card root "HTW"))]
      (write-pending-approval! root {:id "50_hello" :task "HTW" :task-id task-id})
      (pack-web root false "--test-save-comments" (str root)
                "50_hello" "features/console.feature" "use an RNG")
      (let [result (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                                 "--test-retry-task" (str root) "50_hello" "")
            argv (read-argv argv-file)
            injected (str (last (first argv)))]
        (is (zero? (:exit result)))
        (is (str/includes? injected "features/console.feature"))
        (is (str/includes? injected "use an RNG"))
        (is (not (str/includes? injected "New Task")))
        (is (not (fs/exists? (fs/path root ".swarmforge/handoffs/pending_approval/50_hello.reviews.json"))))))))

(deftest pack-web-post-task-duplicate-keeps-the-server
  ;; Given a card named HTW
  ;; When POST /api/tasks uses HTW again
  ;; Then it reports Duplicate and does not create a second card
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (pack-web root true "--test-post-task" (str root) "HTW" "first")
        duplicate (pack-web root false "--test-post-task" (str root) "HTW" "second")
        listed (:out (list-tasks root))
        htw-rows (filter #(str/starts-with? % "HTW\t") (str/split-lines listed))]
    (is (not (zero? (:exit duplicate))))
    (is (str/includes? (str (:err duplicate) (:out duplicate)) "Duplicate"))
    (is (= 1 (count htw-rows)))))

(deftest pack-web-unknown-approval-keeps-the-server
  ;; Given a pack with no pending approval
  ;; When POST /api/approvals/missing/approve
  ;; Then it reports Unknown approval and the next request still works
  (let [root (tmp-dir)
        _ (setup-pack! root)
        result (pack-web root false "--test-approve" (str root) "no-such-id")]
    (is (not (zero? (:exit result))))
    (is (str/includes? (:out result) "error"))
    (is (str/includes? (str (:err result) (:out result)) "Unknown approval"))
    (is (zero? (:exit (pack-web root false "--test-state" (str root)))))))

(deftest pack-web-unknown-clarification-keeps-the-server
  ;; Given a pack with no pending clarification
  ;; When POST /api/clarifications/missing/answer
  ;; Then it reports Unknown clarification and the next request still works
  (let [root (tmp-dir)
        _ (setup-pack! root)
        result (pack-web root false "--test-answer-clarification"
                         (str root) "no-such-id" "nope")]
    (is (not (zero? (:exit result))))
    (is (str/includes? (:out result) "error"))
    (is (str/includes? (str (:err result) (:out result)) "Unknown clarification"))
    (is (zero? (:exit (pack-web root false "--test-state" (str root)))))))

(deftest pack-web-clarification-posts-to-attention-and-answers-into-the-role
  ;; Given QA posts a clarification question
  ;; When the operator answers
  ;; Then /api/state listed it and the answer is injected into QA with the durable id
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        question (fs/path root "tmp" "question.txt")]
    (setup-pack! root ["QA"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file question "Does the bat drop to any of 20 rooms?\n")
    (let [created (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                       (script "pack_dashboard_request.sh")
                       "clarify" (str question))
          id (str/trim (:out created))
          pending (web-state root)
          item (first (:clarifications pending))]
      (is (zero? (:exit created)))
      (is (str/starts-with? id "clar-"))
      (is (= "QA" (:role item)))
      (is (str/includes? (:body item) "Does the bat drop to any of 20 rooms?"))
      (is (= "pending" (:status item)))
      (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                    "--test-answer-clarification" (str root) id "Yes, 1 to 20.\nUse all rooms.")
      (let [argv (slurp argv-file)
            done (first (:clarifications (web-state root)))
            stored (slurp (str (first (fs/list-dir
                                       (fs/path root ".swarmforge/dashboard/clarifications/done")))))]
        (is (str/includes? argv id))
        (is (str/includes? argv "Yes, 1 to 20."))
        (is (str/includes? argv "Use all rooms."))
        (is (= "done" (:status done)))
        (is (= "Yes, 1 to 20.\nUse all rooms." (:response done)))
        (is (str/includes? stored "response: Yes, 1 to 20.\\nUse all rooms.\n"))))))

(deftest pack-dashboard-request-accepts-an-already-answered-clarification
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        question (fs/path root "tmp" "question.txt")
        ack (fs/path root "tmp" "answer.txt")]
    (setup-pack! root ["QA"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file question "Does the bat drop to any of 20 rooms?\n")
    (write-file ack "ignored local ack\n")
    (let [created (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                       (script "pack_dashboard_request.sh")
                       "clarify" (str question))
          id (str/trim (:out created))]
      (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                    "--test-answer-clarification" (str root) id "Yes, 1 to 20.")
      (let [acked (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                       (script "pack_dashboard_request.sh")
                       "answer" id (str ack))
            done (first (:clarifications (web-state root)))
            pending-requests (fs/path root ".swarmforge/dashboard/requests/pending")
            done-file (first (fs/list-dir
                              (fs/path root ".swarmforge/dashboard/clarifications/done")))]
        (is (zero? (:exit acked)))
        (is (str/includes? (:out acked) (str "ANSWERED: " id)))
        (is (= "done" (:status done)))
        (is (= "Yes, 1 to 20." (:response done)))
        (is (str/includes? (slurp (str done-file)) "response: Yes, 1 to 20.\n"))
        (is (or (not (fs/directory? pending-requests))
                (empty? (fs/list-dir pending-requests)))))
      (let [unknown (run {:dir root :env {"SWARMFORGE_ROLE" "QA"} :ok? false}
                         (script "pack_dashboard_request.sh")
                         "answer" "clar-missing" (str ack))]
        (is (not (zero? (:exit unknown))))
        (is (str/includes? (str (:err unknown) (:out unknown))
                           "Unknown pending request"))))))

(deftest pack-web-serves-the-task-body
  ;; Given New Task HTW with body
  ;; When pack_web --test-task HTW
  ;; Then it prints the name and body
  (let [root (tmp-dir)
        text "Find the stories in ~/junk/htw-stories and implement them."]
    (setup-pack! root)
    (pack-board root true
                "create" "--root" (str root)
                "--name" "HTW" "--lane" "specifier" "--text" text)
    (let [result (pack-web root false "--test-task" (str root) "HTW")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "HTW"))
      (is (str/includes? (:out result) text)))))







(deftest pack-web-card-status-matches-unicode-im-and-i-keywords
  ;; Given a pane with Unicode I’m and let me
  ;; When --test-status-pane
  ;; Then those sentences can be card status
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        im (pack-web-env root {} "--test-status-pane" (str root)
                         "I’m merging the QA handoff.\nesc to interrupt · 1s\n")
        let-me (pack-web-env root {} "--test-status-pane" (str root)
                             "Let me inspect the conflicts.\nesc to interrupt · 1s\n")
        handoff (pack-web-env root {} "--test-status-pane" (str root)
                              "HANDOFF queued to cleaner.\nesc to interrupt · 1s\n")]
    (is (str/includes? (:out im) "merging the QA handoff"))
    (is (str/includes? (:out let-me) "Let me inspect the conflicts"))
    (is (str/includes? (:out handoff) "HANDOFF queued to cleaner"))))

(deftest pack-web-card-status-stays-until-replaced
  ;; Given a status sentence then a pane with no status keywords
  ;; When --test-status-persist
  ;; Then the first sentence remains
  (let [root (tmp-dir)
        _ (setup-pack! root)
        _ (create-task root "HTW" "specifier")
        result (pack-web-env root {} "--test-status-persist" (str root)
                             "I'm working on HTW.\n"
                             "esc to interrupt · 9s\n")
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (str/includes? (str (:first body)) "I'm working on HTW"))
    (is (= (:first body) (:second body)))))

(deftest pack-web-work-queue-lists-every-in-process-task
  ;; Given two in-process handoffs on architect
  ;; When --test-state
  ;; Then the row's task is the first name and tasks lists both
  (let [root (tmp-dir)
        roles ["specifier" "architect"]]
    (setup-pack! root roles)
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "HTW"
                      :filename "10_from_cleaner_htw.handoff"})
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "Command Syntax"
                      :filename "11_from_cleaner_cs.handoff"})
    (let [row (some #(when (= "architect" (:role %)) %)
                    (:work_in_flight (web-state root)))]
      (is (= "HTW" (:task row)))
      (is (= ["HTW" "Command Syntax"] (:tasks row))))))

(deftest pack-web-work-queue-marks-only-real-batches
  ;; Given a real in-process batch on architect
  ;; When --test-state
  ;; Then the batch task names are exposed for the dashboard + indicator
  (let [root (tmp-dir)
        roles ["specifier" "architect"]]
    (setup-pack! root roles)
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "HTW"
                      :filename "batch_20260615T000001Z_000001/10_from_cleaner_htw.handoff"})
    (put-in-process! root roles "architect"
                     {:from "cleaner" :task "Command Syntax"
                      :filename "batch_20260615T000001Z_000001/11_from_cleaner_cs.handoff"})
    (let [row (some #(when (= "architect" (:role %)) %)
                    (:work_in_flight (web-state root)))]
      (is (= "HTW" (:task row)))
      (is (= ["HTW" "Command Syntax"] (:tasks row)))
      (is (= ["HTW" "Command Syntax"] (:batch_tasks row))))))



(deftest pack-web-thermometer-heats-on-work-after-handoff-mail
  ;; Given a Codex pane whose only cut-point used to be an old › mail line
  ;; When later transcript lines change
  ;; Then heat rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-mail" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))

(deftest pack-web-grok-thermometer-heats-on-waiting-timer
  ;; Given a Grok pane whose only change is Waiting for response Ns
  ;; When --test-heat-grok
  ;; Then activity rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        _ (set-backend! root "grok")
        result (pack-web root false "--test-heat-grok" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))

(deftest pack-web-thermometer-heats-on-collapsed-transcript-counts
  ;; Given Codex collapsed output whose +N line changes
  ;; When --test-heat-collapse
  ;; Then heat rises
  (let [root (tmp-dir)
        _ (setup-pack! root ["specifier"])
        result (pack-web root false "--test-heat-collapse" (str root))
        body (json/parse-string (:out result) true)]
    (is (zero? (:exit result)))
    (is (< (:before body) (:after body)))))

(deftest pack-web-clarification-answer-echoes-the-question
  ;; Given QA asked a clarification
  ;; When the operator answers
  ;; Then the injected pane text includes the question and Clarification requested from
  (let [root (tmp-dir)
        argv-file (str (fs/path root "tmux.argv"))
        question (fs/path root "tmp" "question.txt")]
    (setup-pack! root ["QA"])
    (write-file (fs/path root ".swarmforge/tmux-socket") (str (fs/path root "tmux.sock") "\n"))
    (write-file question "Does the bat drop to any of 20 rooms?\n")
    (let [id (str/trim (:out (run {:dir root :env {"SWARMFORGE_ROLE" "QA"}}
                                  (script "pack_dashboard_request.sh")
                                  "clarify" (str question))))]
      (pack-web-env root {"SWARMFORGE_TMUX_STUB" argv-file}
                    "--test-answer-clarification" (str root) id "Yes, 1 to 20.")
      (let [argv (slurp argv-file)]
        (is (str/includes? argv "Clarification requested from: QA"))
        (is (str/includes? argv "Does the bat drop to any of 20 rooms?"))
        (is (str/includes? argv "Yes, 1 to 20."))))))

(defn seed-mini-forge! [root]
  (fs/create-dirs (fs/path root "projects"))
  (write-file (fs/path root "swarmforge/scripts/keep.sh") "#!/bin/sh\n")
  (write-file (fs/path root "swarmforge/constitution/articles/engineering.prompt") "eng\n")
  (write-file (fs/path root "swarmforge/constitution/articles/workflow.prompt") "wf\n")
  (write-file (fs/path root "swarmforge/constitution/articles/handoffs.prompt") "ho\n")
  (doseq [pack ["two-pack" "four-pack" "six-pack"]]
    (write-file (fs/path root "packs" pack "swarmforge/swarmforge.conf")
                "window specifier grok master\nwindow coder grok coder\n")
    (write-file (fs/path root "packs" pack "swarmforge/constitution.prompt") "pack-const\n")
    (write-file (fs/path root "packs" pack "swarmforge/roles/specifier.prompt") "spec\n")
    (write-file (fs/path root "packs" pack "swarmforge/roles/coder.prompt") "coder\n")))

(deftest forge-infers-github-project-name
  (let [result (pack-web (tmp-dir) true "--test-inferred-name" "unclebob/swarm-forge" "github")]
    (is (zero? (:exit result)))
    (is (= "swarm-forge" (str/trim (:out result))))))

(deftest forge-new-project-writes-mission-and-conf
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                               "--test-new-project" (str root) "cave" "four-pack" "Hunt the wumpus")
          dest (fs/path root "projects/cave")]
      (is (zero? (:exit result)) (:err result))
      (is (fs/directory? dest))
      (is (= "Hunt the wumpus\n" (slurp (str (fs/path dest "mission.md")))))
      (is (fs/exists? (fs/path dest "swarmforge/swarmforge.conf")))
      (is (fs/exists? (fs/path dest "swarmforge/roles/specifier.prompt")))
      (is (str/includes? (slurp (str (fs/path dest ".swarmforge/pack"))) "four-pack"))
      (is (str/includes? (slurp (str (fs/path root ".swarmforge/open-projects"))) "cave")))))

(deftest forge-new-project-takes-the-packs-language-once
  ;; Given a pack whose projects are written in one language
  ;; When a project is composed from it, re-languaged by hand, and opened again
  ;; Then the project declares the language, and its own answer survives the refresh
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (write-file (fs/path root "packs/six-pack/language.conf") "Kotlin\n")
    (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                               "--test-new-project" (str root) "cave" "six-pack" "m")
          language (fs/path root "projects/cave/swarmforge/language.conf")]
      (is (zero? (:exit result)) (:err result))
      (is (fs/exists? language))
      (is (= "Kotlin\n" (slurp (str language))))
      ;; The project's own answer wins: a refresh seeds nothing over it.
      (spit (str language) "Go\n")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-close-project" (str root) "cave")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-open-project" (str root) "cave")
      (is (= "Go\n" (slurp (str language)))))))

(deftest forge-new-project-rejects-existing-name
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "one")
    (let [result (run {:dir root :env {"SWARMFORGE_SKIP_START" "1"} :ok? false}
                      (script "pack_web.sh")
                      "--test-new-project" (str root) "cave" "two-pack" "two")]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result) (:out result)) "already exists")))))

(deftest forge-open-already-open-alerts
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (let [result (run {:dir root :env {"SWARMFORGE_SKIP_START" "1"} :ok? false}
                      (script "pack_web.sh")
                      "--test-open-project" (str root) "cave")]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result) (:out result)) "already open")))))

(deftest forge-close-leaves-the-directory
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (let [result (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                               "--test-close-project" (str root) "cave")]
      (is (zero? (:exit result)) (:err result))
      (is (fs/directory? (fs/path root "projects/cave")))
      (is (fs/exists? (fs/path root "projects/cave/mission.md")))
      (is (not (str/includes? (slurp (str (fs/path root ".swarmforge/open-projects"))) "cave"))))))

(deftest forge-open-without-pack-file-is-rejected
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (fs/create-dirs (fs/path root "projects/orphan"))
    (let [result (run {:dir root :env {"SWARMFORGE_SKIP_START" "1"} :ok? false}
                      (script "pack_web.sh")
                      "--test-open-project" (str root) "orphan")]
      (is (not (zero? (:exit result))))
      (is (str/includes? (str (:err result) (:out result)) "No pack recorded")))))

(deftest forge-refresh-keeps-mission-and-conf
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "four-pack" "keep me")
    (let [conf (fs/path root "projects/cave/swarmforge/swarmforge.conf")]
      (spit (str conf) "window specifier grok master extra-flag\n")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-close-project" (str root) "cave")
      (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                    "--test-open-project" (str root) "cave")
      (is (= "keep me\n" (slurp (str (fs/path root "projects/cave/mission.md")))))
      (is (str/includes? (slurp (str conf)) "extra-flag")))))

(deftest forge-state-tags-attention-with-project
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (write-file (fs/path root "projects/cave/.swarmforge/roles.tsv")
                (format "specifier\tmaster\t%s\tspecifier\tSpecifier\tcodex\ttask\n"
                        (fs/path root "projects/cave")))
    (fs/create-dirs (fs/path root "projects/cave/.swarmforge/board"))
    (write-file (fs/path root "projects/cave/.swarmforge/handoffs/pending_approval/50_hello.handoff")
                "from: specifier\nto: coder\ntype: git_handoff\ntask: HTW\n\npayload\n")
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)
          approval (first (:approvals state))]
      (is (true? (:forge state)))
      (is (= "cave" (:project approval)))
      (is (= ["cave"] (:open_projects state)))
      (is (= "cave" (:name (first (:projects state))))))))

(deftest forge-pane-capture-uses-project-root
  ;; Given a forge with an open project and a recorded specifier pane
  ;; When GET /api/agents/specifier/pane?project=cave
  ;; Then it prints that pane, not the host miss
  (let [root (tmp-dir)
        dest (fs/path root "projects/cave")]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "two-pack" "m")
    (write-file (fs/path dest ".swarmforge/roles.tsv")
                (format "specifier\tmaster\t%s\tswarmforge-specifier\tSpecifier\tcodex\ttask\n"
                        dest))
    (write-file (fs/path dest ".swarmforge/sessions/specifier/pane.txt")
                "cave specifier pane\n")
    (let [host (pack-web root true "--test-pane" (str root) "specifier")
          project (pack-web root true "--test-pane" (str root) "specifier" "cave")]
      (is (str/includes? (:out host) "(no pane capture for specifier)"))
      (is (str/includes? (:out project) "cave specifier pane")))))

(deftest forge-mission-reads-project-mission-md
  ;; Given an open forge project with mission.md
  ;; When GET /api/mission?project=cave
  ;; Then it prints that mission
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "four-pack" "Hunt the wumpus")
    (let [result (pack-web root true "--test-mission" (str root) "cave")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "Hunt the wumpus")))))

(deftest forge-card-status-is-per-project
  ;; Given two open projects each with a specifier card and its own pane
  ;; When --test-state
  ;; Then each card keeps the status from its own project, not the other
  (let [root (tmp-dir)
        cave (fs/path root "projects/cave")
        dice (fs/path root "projects/dice")]
    (seed-mini-forge! root)
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "cave" "four-pack" "m")
    (pack-web-env root {"SWARMFORGE_SKIP_START" "1"}
                  "--test-new-project" (str root) "dice" "four-pack" "m")
    (setup-pack! cave ["specifier"])
    (setup-pack! dice ["specifier"])
    (create-task cave "htw" "specifier")
    (create-task dice "begin" "specifier")
    (write-file (fs/path cave ".swarmforge/sessions/specifier/pane.txt")
                "I'm specifying Hunt the Wumpus.\n")
    (write-file (fs/path dice ".swarmforge/sessions/specifier/pane.txt")
                "I'll implement begin.\n")
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)
          by (into {} (map (juxt :name identity) (:projects state)))
          cave-status (:status (first (:tasks (get by "cave"))))
          dice-status (:status (first (:tasks (get by "dice"))))]
      (is (str/includes? (str cave-status) "Hunt the Wumpus"))
      (is (str/includes? (str dice-status) "I'll implement begin"))
      (is (not (str/includes? (str cave-status) "begin")))
      (is (not (str/includes? (str dice-status) "Hunt the Wumpus"))))))

(deftest forge-state-includes-lieutenant-status-lines
  ;; Given a forge lieutenant pane with two status sentences
  ;; When --test-state
  ;; Then lieutenant_status is those two lines
  (let [root (tmp-dir)]
    (seed-mini-forge! root)
    (fs/create-dirs (fs/path root "projects"))
    (write-file (fs/path root ".swarmforge/roles.tsv")
                (format "lieutenant\tmaster\t%s\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n"
                        root))
    (write-file (fs/path root ".swarmforge/sessions/lieutenant/pane.txt")
                (str "I'm listing the open projects.\n"
                     "I'll summarize HTW next.\n"))
    (let [state (json/parse-string
                 (:out (pack-web root true "--test-state" (str root)))
                 true)]
      (is (true? (:forge state)))
      (is (= ["I'm listing the open projects." "I'll summarize HTW next."]
             (:lieutenant_status state))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'swarmforge.pack-ui-test)]
    (System/exit (+ fail error))))
