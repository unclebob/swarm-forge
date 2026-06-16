(ns swarmforge.handoff-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(def repo-root (fs/cwd))
(def scripts-dir (fs/path repo-root "swarmforge" "scripts"))
(def temp-dirs (atom []))

(defn script [name]
  (str (fs/path scripts-dir name)))

(defn tmp-dir []
  (let [dir (fs/create-temp-dir {:prefix "swarmforge-handoff-test."})]
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

(defn read-file [path]
  (slurp (str path)))

(defn init-repo! [root]
  (run {:dir root} "git" "init" "-q")
  (run {:dir root} "git" "config" "user.email" "test@example.com")
  (run {:dir root} "git" "config" "user.name" "Test User")
  (write-file (fs/path root "README.md") "initial\n")
  (run {:dir root} "git" "add" "README.md")
  (run {:dir root} "git" "commit" "-q" "-m" "Initial commit")
  (str/trim (:out (run {:dir root} "git" "rev-parse" "--short=10" "HEAD"))))

(defn setup-project!
  ([root] (setup-project! root {"sender" "task" "receiver" "task"}))
  ([root roles]
   (doseq [dir [".swarmforge/handoffs/outbox/tmp"
                ".swarmforge/handoffs/sent"
                ".swarmforge/handoffs/failed"
                ".swarmforge/handoffs/inbox/new"
                ".swarmforge/handoffs/inbox/in_process"
                ".swarmforge/handoffs/inbox/completed"]]
     (fs/create-dirs (fs/path root dir)))
   (write-file
    (fs/path root ".swarmforge/roles.tsv")
    (apply str
           (for [[role mode] roles]
             (format "%s\tmaster\t%s\tsession\t%s\tcodex\t%s\n"
                     role root (str/capitalize role) mode))))))

(defn handoff
  [{:keys [id from to recipient priority type task commit body
           enqueued-at dequeued-at completed-at]}]
  (str "id: " id "\n"
       "from: " from "\n"
       "to: " to "\n"
       (when recipient (str "recipient: " recipient "\n"))
       "priority: " priority "\n"
       "type: " type "\n"
       (when task (str "task: " task "\n"))
       (when commit (str "commit: " commit "\n"))
       (when enqueued-at (str "enqueued_at: " enqueued-at "\n"))
       (when dequeued-at (str "dequeued_at: " dequeued-at "\n"))
       (when completed-at (str "completed_at: " completed-at "\n"))
       "\n"
       (or body (str "payload for " id)) "\n"))

(defn handoff-path [root state filename]
  (fs/path root ".swarmforge" "handoffs" "inbox" state filename))

(defn put-handoff! [root state filename attrs]
  (let [path (handoff-path root state filename)]
    (write-file path (handoff attrs))
    path))

(defn header [path field]
  (some->> (str/split-lines (read-file path))
           (take-while seq)
           (some (fn [line]
                   (let [prefix (str field ": ")]
                     (when (str/starts-with? line prefix)
                       (subs line (count prefix))))))))

(defn make-queued-handoff!
  ([root filename attrs]
   (put-handoff! root "new" filename
                 (merge {:from "sender"
                         :to "receiver"
                         :recipient "receiver"
                         :priority "50"
                         :type "git_handoff"
                         :task "task-one"
                         :commit "0123456789"
                         :body "merge_and_process sender 0123456789"}
                        attrs))))

(deftest swarm-handoff-validates-and-queues-git-handoffs
  (let [root (tmp-dir)
        commit (init-repo! root)]
    (setup-project! root)
    (testing "git_handoff requires a task name"
      (let [draft (fs/path root "tmp" "missing-task.handoff")]
        (write-file draft (format "type: git_handoff\nto: receiver\npriority: 50\ncommit: %s\n" commit))
        (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "sender"} :ok? false}
                          (script "swarm_handoff.sh") (str draft))]
          (is (= 2 (:exit result)))
          (is (str/includes? (:err result) "Missing required header 'task'"))
          (is (fs/exists? draft)))))
    (testing "valid git_handoff writes task, canonical commit, and generated payload"
      (let [draft (fs/path root "tmp" "valid.handoff")]
        (write-file draft (format "type: git_handoff\nto: receiver\npriority: 50\ntask: task-1-cave-setup\ncommit: %s\n" commit))
        (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "sender"}}
                          (script "swarm_handoff.sh") (str draft))
              queued (-> (:out result) str/trim (str/replace #"^HANDOFF QUEUED: " ""))
              content (read-file queued)]
          (is (str/includes? content "task: task-1-cave-setup\n"))
          (is (str/includes? content (str "commit: " commit "\n")))
          (is (str/includes? content (str "merge_and_process sender " commit)))
          (is (fs/exists? queued))
          (is (not (fs/exists? draft))))))))

(deftest ready-for-next-task-accepts-and-resumes-single-tasks
  (let [root (tmp-dir)]
    (init-repo! root)
    (setup-project! root {"receiver" "task"})
    (testing "accepts one queued task and prints task name"
      (make-queued-handoff! root "50_20260615T000001Z_000001_from_sender_to_receiver.handoff"
                            {:id "20260615T000001Z_000001_from_sender"
                             :task "task-alpha"})
      (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "receiver"}}
                        (script "ready_for_next.sh"))
            out (:out result)
            in-process (fs/path root ".swarmforge/handoffs/inbox/in_process/50_20260615T000001Z_000001_from_sender_to_receiver.handoff")]
        (is (str/includes? out "TASK:"))
        (is (str/includes? out "TASK_NAME: task-alpha"))
        (is (fs/exists? in-process))
        (is (some? (header in-process "dequeued_at")))))
    (testing "returns existing in-process task before queued tasks"
      (make-queued-handoff! root "40_20260615T000002Z_000002_from_sender_to_receiver.handoff"
                            {:id "20260615T000002Z_000002_from_sender"
                             :priority "40"
                             :task "task-beta"})
      (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "receiver"}}
                        (script "ready_for_next.sh"))]
        (is (str/includes? (:out result) "task-alpha"))
        (is (fs/exists? (fs/path root ".swarmforge/handoffs/inbox/new/40_20260615T000002Z_000002_from_sender_to_receiver.handoff")))))))

(deftest ready-for-next-batch-groups-equal-priority-handoffs
  (let [root (tmp-dir)]
    (init-repo! root)
    (setup-project! root {"receiver" "batch"})
    (make-queued-handoff! root "10_20260615T000001Z_000001_from_sender_to_receiver.handoff"
                          {:id "20260615T000001Z_000001_from_sender" :priority "10" :task "task-a"})
    (make-queued-handoff! root "10_20260615T000002Z_000002_from_sender_to_receiver.handoff"
                          {:id "20260615T000002Z_000002_from_sender" :priority "10" :task "task-b"})
    (make-queued-handoff! root "20_20260615T000003Z_000003_from_sender_to_receiver.handoff"
                          {:id "20260615T000003Z_000003_from_sender" :priority "20" :task "task-c"})
    (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "receiver"}}
                      (script "ready_for_next.sh"))
          out (:out result)
          batch-dir (->> (str/split-lines out)
                         (filter #(str/starts-with? % "BATCH: "))
                         first
                         (#(subs % 7)))]
      (is (str/includes? out "COUNT: 2"))
      (is (str/includes? out "TASK_NAME: task-a"))
      (is (str/includes? out "TASK_NAME: task-b"))
      (is (not (str/includes? out "TASK_NAME: task-c")))
      (is (= 2 (count (fs/glob batch-dir "*.handoff"))))
      (is (fs/exists? (fs/path root ".swarmforge/handoffs/inbox/new/20_20260615T000003Z_000003_from_sender_to_receiver.handoff"))))))

(deftest done-with-current-task-completes-and-accepts-next-task
  (let [root (tmp-dir)]
    (init-repo! root)
    (setup-project! root {"receiver" "task"})
    (put-handoff! root "in_process" "50_20260615T000001Z_000001_from_sender_to_receiver.handoff"
                  {:id "20260615T000001Z_000001_from_sender"
                   :from "sender" :to "receiver" :recipient "receiver"
                   :priority "50" :type "git_handoff" :task "task-current"
                   :commit "0123456789"})
    (make-queued-handoff! root "50_20260615T000002Z_000002_from_sender_to_receiver.handoff"
                          {:id "20260615T000002Z_000002_from_sender"
                           :task "task-next"})
    (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "receiver"}}
                      (script "done_with_current.sh"))
          completed (fs/path root ".swarmforge/handoffs/inbox/completed/50_20260615T000001Z_000001_from_sender_to_receiver.handoff")
          next-file (fs/path root ".swarmforge/handoffs/inbox/in_process/50_20260615T000002Z_000002_from_sender_to_receiver.handoff")]
      (is (str/includes? (:out result) "COMPLETED:"))
      (is (str/includes? (:out result) "TASK_NAME: task-next"))
      (is (some? (header completed "completed_at")))
      (is (some? (header next-file "dequeued_at"))))))

(deftest done-with-current-batch-completes-and-accepts-next-batch
  (let [root (tmp-dir)
        batch (fs/path root ".swarmforge/handoffs/inbox/in_process/batch_20260615T000001Z_000001")]
    (init-repo! root)
    (setup-project! root {"receiver" "batch"})
    (fs/create-dirs batch)
    (write-file (fs/path batch "10_20260615T000001Z_000001_from_sender_to_receiver.handoff")
                (handoff {:id "20260615T000001Z_000001_from_sender"
                          :from "sender" :to "receiver" :recipient "receiver"
                          :priority "10" :type "git_handoff" :task "task-a"
                          :commit "0123456789"}))
    (write-file (fs/path batch "10_20260615T000002Z_000002_from_sender_to_receiver.handoff")
                (handoff {:id "20260615T000002Z_000002_from_sender"
                          :from "sender" :to "receiver" :recipient "receiver"
                          :priority "10" :type "git_handoff" :task "task-b"
                          :commit "0123456789"}))
    (make-queued-handoff! root "20_20260615T000003Z_000003_from_sender_to_receiver.handoff"
                          {:id "20260615T000003Z_000003_from_sender"
                           :priority "20"
                           :task "task-c"})
    (let [result (run {:dir root :env {"SWARMFORGE_ROLE" "receiver"}}
                      (script "done_with_current.sh"))
          completed-batch (fs/path root ".swarmforge/handoffs/inbox/completed/batch_20260615T000001Z_000001")]
      (is (str/includes? (:out result) "COMPLETED_BATCH:"))
      (is (str/includes? (:out result) "TASK_NAME: task-c"))
      (is (= 2 (count (fs/glob completed-batch "*.handoff"))))
      (is (every? #(some? (header % "completed_at"))
                  (fs/glob completed-batch "*.handoff"))))))

(deftest helpers-refuse-wrong-current-work-shape
  (let [root (tmp-dir)
        batch (fs/path root ".swarmforge/handoffs/inbox/in_process/batch_20260615T000001Z_000001")]
    (init-repo! root)
    (setup-project! root {"receiver" "batch"})
    (fs/create-dirs batch)
    (write-file (fs/path batch "10_20260615T000001Z_000001_from_sender_to_receiver.handoff")
                (handoff {:id "20260615T000001Z_000001_from_sender"
                          :from "sender" :to "receiver" :recipient "receiver"
                          :priority "10" :type "git_handoff" :task "task-a"
                          :commit "0123456789"}))
    (testing "task helpers refuse an in-process batch"
      (let [ready (run {:dir root :env {"SWARMFORGE_ROLE" "receiver"} :ok? false}
                       (script "ready_for_next_task.sh"))
            done (run {:dir root :env {"SWARMFORGE_ROLE" "receiver"} :ok? false}
                      (script "done_with_current_task.sh"))]
        (is (= 2 (:exit ready)))
        (is (str/includes? (:err ready) "TASK_IN_PROCESS_IS_BATCH"))
        (is (= 2 (:exit done)))
        (is (str/includes? (:err done) "CURRENT_WORK_IS_BATCH"))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'swarmforge.handoff-test)]
    (doseq [dir @temp-dirs]
      (fs/delete-tree dir))
    (System/exit (+ fail error))))
