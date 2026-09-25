#!/usr/bin/env bb

(ns ready-for-next-batch
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def script-dir (fs/parent *file*))
(try
  (require 'ready-for-next-guard)
  (catch Exception _
    (load-file (str (fs/path script-dir "ready_for_next_guard.bb")))))

(defn inbox-dir []
  (fs/path (System/getProperty "user.dir") ".swarmforge" "handoffs" "inbox"))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn current-head []
  (str/trim (:out (sh/sh "git" "rev-parse" "--short=10" "HEAD"))))

(defn handoff-files [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".handoff")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn batch-dirs [dir]
  (if (fs/exists? dir)
    (->> (fs/list-dir dir)
         (filter #(and (fs/directory? %) (str/starts-with? (fs/file-name %) "batch_")))
         (sort-by #(fs/file-name %))
         vec)
    []))

(defn header-field [file field]
  (let [prefix (str field ": ")]
    (some (fn [line]
            (when (str/starts-with? line prefix)
              (subs line (count prefix))))
          (take-while (complement str/blank?) (str/split-lines (slurp (str file)))))))

(defn header-value [file field default]
  (or (header-field file field) default))

(defn body [file]
  (let [[_ body] (str/split (slurp (str file)) #"\n\n" 2)]
    (or body "")))

(defn set-header! [file field value]
  (let [lines (str/split-lines (slurp (str file)))
        prefix (str field ": ")
        tmp (fs/create-temp-file {:dir (fs/parent file) :prefix ".headers."})
        result (loop [remaining lines
                      out []
                      inserted? false
                      replaced? false]
                 (if-let [line (first remaining)]
                   (cond
                     (and (not inserted?) (str/blank? line))
                     (recur (next remaining)
                            (conj (cond-> out (not replaced?) (conj (str prefix value))) line)
                            true
                            replaced?)

                     (and (not inserted?) (str/starts-with? line prefix))
                     (recur (next remaining) (conj out (str prefix value)) inserted? true)

                     :else
                     (recur (next remaining) (conj out line) inserted? replaced?))
                   (cond-> out
                     (and (not inserted?) (not replaced?)) (conj (str prefix value)))))]
    (spit (str tmp) (str (str/join "\n" result) "\n"))
    (fs/move tmp file {:replace-existing true})))

(defn print-task [file]
  (let [task-name (header-field file "task")
        task-id (header-field file "task_id")]
    (println "TASK:" (str file))
    (println "FROM:" (header-value file "from" "unknown"))
    (println "TYPE:" (header-value file "type" "unknown"))
    (println "PRIORITY:" (header-value file "priority" "50"))
    (when task-name
      (println "TASK_NAME:" task-name))
    (when task-id
      (println "TASK_ID:" task-id))
    (println "PAYLOAD:")
    (print (body file))))

(defn print-batch [batch-dir]
  (let [files (handoff-files batch-dir)]
    (when (empty? files)
      (binding [*out* *err*]
        (println "AMBIGUOUS_TASK_STATE: batch contains no tasks:" (str batch-dir)))
      (System/exit 2))
    (println "BATCH:" (str batch-dir))
    (println "COUNT:" (count files))
    (when-let [name (header-field (first files) "task")]
      (println "TASK_NAME:" name))
    (println "PRIORITY:" (header-value (first files) "priority" "50"))
    (doseq [[index file] (map-indexed vector files)]
      (println)
      (println "BATCH_ITEM:" (inc index))
      (print-task file))))

(defn fail! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

;; The finishing step a project owns. The tooling decides when the event is true
;; and the project decides what it means, through its own
;; swarmforge/hooks/card-complete.sh; the output belongs in this pane, where the
;; role that just took the batch can read it.
(defn run-completion-hook! [file]
  (let [result (sh/sh (str (fs/path script-dir "run_hook.sh")) "card-complete"
                      "--task" (header-value file "task" "")
                      "--commit" (header-value file "commit" "")
                      "--from" (header-value file "from" "")
                      "--role" (or (ready-for-next-guard/current-role) ""))]
    (print (:out result))
    (print (:err result))
    (flush)))

(defn merge-git-handoff! [file]
  (when (= "git_handoff" (header-field file "type"))
    (let [from (header-field file "from")
          commit (header-field file "commit")]
      (when (and from commit)
        (let [fresh? (not (zero? (:exit (sh/sh "git" "merge-base" "--is-ancestor" commit "HEAD"))))
              result (sh/sh (str (fs/path script-dir "merge_and_process.sh")) from commit)]
          (when-not (zero? (:exit result))
            (fail! 1 (str/trim (str (:err result) "\n" (:out result)))))
          ;; The hook fires when this merge is what brought the card's work in, so
          ;; reading a batch that is already in process does not run a project's
          ;; finishing step again.
          (when fresh?
            (run-completion-hook! file)))))))

(defn merge-batch! [batch-dir]
  (doseq [file (handoff-files batch-dir)]
    (merge-git-handoff! file)))

(defn new-batch-dir [in-process-dir]
  (loop [suffix 1]
    (let [dir (fs/path in-process-dir (format "batch_%s_%06d" (id-timestamp) suffix))]
      (if (fs/exists? dir)
        (recur (inc suffix))
        dir))))

(defn -main []
  (let [inbox (inbox-dir)
        new-dir (fs/path inbox "new")
        in-process-dir (fs/path inbox "in_process")
        completed-dir (fs/path inbox "completed")]
    (doseq [dir [new-dir in-process-dir completed-dir]]
      (fs/create-dirs dir))
    (let [in-process-batches (batch-dirs in-process-dir)
          in-process-files (handoff-files in-process-dir)]
      (when (seq in-process-files)
        (fail! 2
               "TASK_IN_PROCESS_IS_SINGLE: use ready_for_next.sh or done_with_current.sh."
               (str/join "\n" (map #(str "- " %) in-process-files))))
      (when (> (count in-process-batches) 1)
        (fail! 2
               "AMBIGUOUS_TASK_STATE: multiple batches are already in process."
               (str/join "\n" (map #(str "- " %) in-process-batches))))
      (if (= 1 (count in-process-batches))
        (let [batch-dir (first in-process-batches)]
          (merge-batch! batch-dir)
          (print-batch batch-dir))
        (let [new-files (handoff-files new-dir)]
          (when-let [active (seq (ready-for-next-guard/active-outbound-git-files
                                  (ready-for-next-guard/current-role)))]
            (apply fail! 2 (ready-for-next-guard/wait-message active)))
          (if (empty? new-files)
            (println "NO_TASK")
            (let [batch-priority (header-value (first new-files) "priority" "50")
                  batch-dir (new-batch-dir in-process-dir)
                  selected-files (filter #(= batch-priority (header-value % "priority" "50")) new-files)]
              (fs/create-dir batch-dir)
              (doseq [source-file selected-files]
                (let [target-file (fs/path batch-dir (fs/file-name source-file))]
                  (when (fs/exists? target-file)
                    (fail! 2 (str "AMBIGUOUS_TASK_STATE: target batch file already exists: " target-file)))
                  (fs/move source-file target-file)
                  (set-header! target-file "dequeued_at" (timestamp))
                  (set-header! target-file "task_base_commit" (current-head))))
              (when (empty? selected-files)
                (fail! 2 (str "AMBIGUOUS_TASK_STATE: no tasks selected for batch priority " batch-priority ".")))
              (merge-batch! batch-dir)
              (print-batch batch-dir))))))))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (-main))
