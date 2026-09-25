#!/usr/bin/env bb

(ns run-hook
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def usage-text
  (str "Usage:\n"
       "  run_hook.sh <event> [--task <name>] [--commit <sha>] [--from <role>] [--role <role>]\n"
       "\n"
       "Runs <project>/swarmforge/hooks/<event>.sh when the project provides one.\n"
       "The hook owns what the event means; the tooling only decides when it fires.\n"))

(defn usage []
  (binding [*out* *err*]
    (println usage-text))
  (System/exit 1))

(defn flag-value [args name]
  (some (fn [[option value]] (when (= option name) value))
        (partition 2 1 args)))

(defn command [& args]
  (apply process/sh (concat [{:continue true}] args)))

(defn canonical [path]
  (try
    (str (fs/canonicalize (fs/path path)))
    (catch Exception _ (str (fs/absolutize (fs/path path))))))

(defn project-root []
  (let [result (command "git" "rev-parse" "--git-common-dir")]
    (when (zero? (:exit result))
      (let [path (str/trim (:out result))
            abs (if (fs/absolute? path) path (str (fs/absolutize path)))]
        (str (fs/parent (fs/path abs)))))))

(defn show-toplevel []
  (let [result (command "git" "rev-parse" "--show-toplevel")]
    (when (zero? (:exit result))
      (str/trim (:out result)))))

;; A card's finished work lands on master through the worktree that holds master
;; (the specifier's), so that is the only worktree where this event fires. Every
;; other role's merge is work arriving on its own branch, not on master.
(defn master-worktree? [root]
  (when-let [toplevel (show-toplevel)]
    (= (canonical toplevel) (canonical root))))

(defn lane-of [root task]
  (let [file (fs/path root ".swarmforge" "board" "tasks.tsv")]
    (when (and (fs/regular-file? file) (not (str/blank? task)))
      (some (fn [line]
              (let [[name lane] (str/split line #"\t")]
                (when (= name task) lane)))
            (str/split-lines (slurp (str file)))))))

(defn banner [event task commit from]
  (str "--- hook " event
       (when-not (str/blank? task) (str " (card " task ")"))
       (when-not (str/blank? from) (str " from " from))
       (when-not (str/blank? commit) (str " " commit))
       " ---"))

(defn run-hook! [root event task commit from role]
  (let [hook (fs/path root "swarmforge" "hooks" (str event ".sh"))
        env {"SWARMFORGE_EVENT" event
             "SWARMFORGE_PROJECT" root
             "SWARMFORGE_TASK" (or task "")
             "SWARMFORGE_COMMIT" (or commit "")
             "SWARMFORGE_FROM" (or from "")
             "SWARMFORGE_ROLE" (or role "")
             "SWARMFORGE_HOOK" (str hook)}]
    (cond
      ;; A project that provides no hook for the event has nothing to run, and
      ;; saying so every time a card merges would only be noise.
      (not (fs/exists? hook))
      nil

      (not (fs/executable? hook))
      (println (str "HOOK_IGNORED " event ": " hook " is not executable"))

      :else
      (do
        (println (banner event task commit from))
        (flush)
        (let [proc (process/process [(str hook)]
                                    {:dir (str root)
                                     :extra-env env
                                     :out :inherit
                                     :err :inherit})
              exit (:exit @proc)]
          (println (str "--- hook " event " exit " exit " ---"))
          ;; The merge has already happened, so a failing finishing step reports
          ;; and leaves the card where it is; it never un-merges the work.
          (when-not (zero? exit)
            (println (str "HOOK_FAILED " event
                          (when-not (str/blank? task) (str " " task))
                          " (exit " exit ")"))))))))

(defn -main [& args]
  (let [event (first args)
        task (flag-value args "--task")
        commit (flag-value args "--commit")
        from (flag-value args "--from")
        role (flag-value args "--role")]
    (when (or (str/blank? event) (str/starts-with? event "--"))
      (usage))
    (when-let [root (project-root)]
      (when (master-worktree? root)
        (let [lane (lane-of root task)]
          (if (and (not (str/blank? task)) (not= lane "done"))
            ;; The specifier also receives a card's earlier copies (back-one,
            ;; back-all merges). Only the board row's own "done" says the pack
            ;; finished the card, so the finishing step waits for that.
            (println (str "HOOK_DEFERRED " event " " task
                          " (card lane: " (or lane "no board row") ")"))
            (run-hook! root event task commit from role))))))
  (System/exit 0))

(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
