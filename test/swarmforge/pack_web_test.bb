#!/usr/bin/env bb

(require '[babashka.fs :as fs])

(def repo-root (-> *file* fs/file fs/parent fs/parent fs/parent))
(try
  (require 'pack-web)
  (catch Exception _
    (load-file (str (fs/path repo-root "swarmforge" "scripts" "pack_web.bb")))))
(in-ns 'pack-web)

(defn test-teardown-throw! [root]
  (binding [*sync-teardown?* true]
    (with-redefs [run-teardown! (fn [_] (throw (ex-info "boom" {})))]
      (schedule-teardown! (require-root! root)))))

(defn test-cli! [& args]
  (case (first args)
    "--test-state" (test-state! (second args))
    "--test-html" (test-html!)
    "--test-post-task" (test-post-task! (second args) (nth args 2 nil) (nth args 3 nil))
    "--test-post-chat" (test-post-chat! (second args) (nth args 2 nil))
    "--test-inject-payload" (test-inject-payload! (second args) (nth args 2 nil))
    "--test-inject-argv" (test-inject-argv! (second args) (nth args 2 nil) (nth args 3 nil))
    "--test-chat-wake" (println (chat-wake (second args) (nth args 2 nil)))
    "--test-approve" (test-approval! (second args) (nth args 2 nil) "approve")
    "--test-reject" (test-approval! (second args) (nth args 2 nil) "reject")
    "--test-pane" (test-pane! (second args) (nth args 2 nil) (nth args 3 nil))
    "--test-agent-page" (test-agent-page! (second args) (nth args 2 nil))
    "--test-heat" (test-heat! (second args))
    "--test-heat-isolation" (test-heat-isolation! (second args) (nth args 2 nil))
    "--test-heat-codex" (test-heat-codex! (second args))
    "--test-heat-reorder" (test-heat-reorder! (second args))
    "--test-heat-head" (test-heat-head! (second args))
    "--test-heat-mail" (test-heat-mail! (second args))
    "--test-heat-grok" (test-heat-grok! (second args))
    "--test-heat-collapse" (test-heat-collapse! (second args))
    "--test-status-pane" (test-status-pane! (second args) (nth args 2 nil))
    "--test-status-persist" (test-status-persist! (second args) (nth args 2 nil) (nth args 3 nil))
    "--test-answer-clarification" (test-answer-clarification! (second args) (nth args 2 nil) (nth args 3 nil))
    "--test-task" (test-task! (second args) (nth args 2 nil))
    "--test-doc" (test-doc! (second args) (nth args 2 nil))
    "--test-api-doc" (test-api-doc! (second args) (nth args 2 nil) (nth args 3 nil))
    "--test-delete-task" (test-delete-task! (second args) (nth args 2 nil))
    "--test-delete-approval" (test-delete-approval! (second args) (nth args 2 nil))
    "--test-retry-task" (test-retry-task! (second args) (nth args 2 nil) (nth args 3 nil))
    "--test-save-comments" (test-save-comments! (second args) (nth args 2 nil) (nth args 3 nil) (nth args 4 nil))
    "--test-teardown" (test-teardown! (second args) (nth args 2 nil))
    "--test-teardown-throw" (test-teardown-throw! (second args))
    "--test-new-project" (test-new-project! (second args) (nth args 2 nil) (nth args 3 nil) (nth args 4 nil))
    "--test-open-project" (test-open-project! (second args) (nth args 2 nil))
    "--test-close-project" (test-close-project! (second args) (nth args 2 nil))
    "--test-inferred-name" (test-inferred-name! (second args) (nth args 2 nil))
    "--test-mission" (test-mission! (second args) (nth args 2 nil))
    (do (usage)
        (exit! 1 nil))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply test-cli! *command-line-args*)
  (System/exit 0))
