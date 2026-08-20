(ns swarmforge.audit-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; Load audit_lib directly
(load-file (str (fs/path (fs/cwd) "swarmforge" "scripts" "audit_lib.bb")))

(deftest audit-lib-writes-jsonl-events
  (let [root (fs/create-temp-dir {:prefix "swarmforge-audit-test."})]
    (try
      ((resolve 'audit-lib/write-audit-event!) root {:event "delivered" :from "coder" :to "cleaner" :task "task-1"})
      (let [log-file (fs/path root ".swarmforge" "daemon" "audit.jsonl")]
        (is (fs/exists? log-file))
        (let [content (slurp (str log-file))]
          (is (str/includes? content "\"event\":\"delivered\""))
          (is (str/includes? content "\"from\":\"coder\""))
          (is (str/includes? content "\"to\":\"cleaner\""))
          (is (str/includes? content "\"task\":\"task-1\""))))
      (finally
        (fs/delete-tree root)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'swarmforge.audit-test)]
    (System/exit (+ fail error))))
