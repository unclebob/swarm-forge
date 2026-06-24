#!/usr/bin/env bb

(ns ready-for-next-batch
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn inbox-dir []
  (fs/path (System/getProperty "user.dir") ".swarmforge" "handoffs" "inbox"))

(defn timestamp []
  (.format java.time.format.DateTimeFormatter/ISO_INSTANT
           (java.time.Instant/now)))

(defn id-timestamp []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

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
  (let [task-name (header-field file "task")]
    (println "TASK:" (str file))
    (println "FROM:" (header-value file "from" "unknown"))
    (println "TYPE:" (header-value file "type" "unknown"))
    (println "PRIORITY:" (header-value file "priority" "50"))
    (when task-name
      (println "TASK_NAME:" task-name))
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
    (println "PRIORITY:" (header-value (first files) "priority" "50"))
    (doseq [[index file] (map-indexed vector files)]
      (println)
      (println "BATCH_ITEM:" (inc index))
      (print-task file))))

(defn sync-to-trunk! []
  (let [fetch-result (sh/sh "git" "fetch" "origin")]
    (when-not (zero? (:exit fetch-result))
      (binding [*out* *err*]
        (println "WARNING: git fetch failed:" (str/trim (:err fetch-result))))))
  (let [branch-result (sh/sh "git" "symbolic-ref" "--short" "refs/remotes/origin/HEAD")
        default-branch (when (zero? (:exit branch-result))
                         (str/trim (:out branch-result)))]
    (if default-branch
      (let [reset-result (sh/sh "git" "reset" "--hard" default-branch)]
        (when-not (zero? (:exit reset-result))
          (binding [*out* *err*]
            (println "WARNING: git reset --hard" default-branch "failed:" (str/trim (:err reset-result))))))
      (binding [*out* *err*]
        (println "WARNING: could not resolve default branch; skipping trunk sync")))))

(defn fail! [status & lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line)))
  (System/exit status))

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
        (print-batch (first in-process-batches))
        (let [new-files (handoff-files new-dir)]
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
                  (set-header! target-file "dequeued_at" (timestamp))))
              (when (empty? selected-files)
                (fail! 2 (str "AMBIGUOUS_TASK_STATE: no tasks selected for batch priority " batch-priority ".")))
              (sync-to-trunk!)
              (print-batch batch-dir))))))))

(-main)
