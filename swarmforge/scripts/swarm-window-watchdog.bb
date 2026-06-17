#!/usr/bin/env bb

(ns swarm-window-watchdog
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def missing-threshold 3)

(defn sq [value]
  (str "'" (str/replace (str value) #"'" "'\"'\"'") "'"))

(defn rows [window-state-file]
  (when (fs/exists? window-state-file)
    (->> (str/split-lines (slurp (str window-state-file)))
         (remove str/blank?)
         (map #(zipmap [:index :window-id :session :title]
                       (str/split % #"\t" -1)))
         vec)))

(defn write-rows! [window-state-file window-ids-file rows]
  (spit (str window-state-file)
        (apply str
               (for [{:keys [index window-id session title]} rows]
                 (format "%s\t%s\t%s\t%s\n" index window-id session title))))
  (spit (str window-ids-file)
        (apply str (for [{:keys [window-id]} rows] (str window-id "\n")))))

(defn rewrite-window-id! [window-state-file window-ids-file target-index replacement-id]
  (write-rows! window-state-file
               window-ids-file
               (mapv #(if (= (:index %) target-index)
                        (assoc % :window-id replacement-id)
                        %)
                     (rows window-state-file))))

(defn adapter-script [script-dir working-dir tmux-socket backend command & args]
  (let [script (str "SCRIPT_DIR=" (sq (str script-dir)) "\n"
                    "WORKING_DIR=" (sq (str working-dir)) "\n"
                    "TMUX_SOCKET=" (sq tmux-socket) "\n"
                    "source " (sq (str (fs/path script-dir "swarm-terminal-adapter.sh")))
                    " && load_terminal_backend " (sq backend)
                    " && " command
                    (apply str (map #(str " " (sq %)) args)))]
    ["zsh" "-c" script]))

(defn terminal-ok? [script-dir working-dir tmux-socket backend command & args]
  (zero? (:exit (apply process/sh (concat [{:continue true}]
                                          (apply adapter-script script-dir working-dir tmux-socket backend command args))))))

(defn terminal-out [script-dir working-dir tmux-socket backend command & args]
  (str/trim (:out (apply process/sh (apply adapter-script script-dir working-dir tmux-socket backend command args)))))

(defn tmux-session? [tmux-socket session]
  (zero? (:exit (process/sh {:continue true} "tmux" "-S" tmux-socket "has-session" "-t" session))))

(defn kill-session! [tmux-socket session]
  (process/sh {:continue true} "tmux" "-S" tmux-socket "kill-session" "-t" session))

(defn kill-all-sessions! [script-dir window-state-file working-dir tmux-socket backend]
  (doseq [{:keys [session]} (rows window-state-file)]
    (when-not (str/blank? session)
      (kill-session! tmux-socket session)))
  (doseq [{:keys [window-id]} (rows window-state-file)]
    (when-not (str/blank? window-id)
      (terminal-ok? script-dir working-dir tmux-socket backend "terminal_close_window" window-id))))

(defn -main [& args]
  (let [[window-state-file window-ids-file cleanup-owner-index tmux-socket working-dir backend] args
        window-state-file (fs/path window-state-file)
        window-ids-file (fs/path window-ids-file)
        backend (or backend "terminal-app")
        script-dir (fs/parent *file*)]
    (when (= "--rewrite-window-id" (first args))
      (let [[_ state ids target replacement] args]
        (rewrite-window-id! (fs/path state) (fs/path ids) target replacement)
        (System/exit 0)))
    (loop [missing-counts {}]
      (when (fs/exists? window-state-file)
        (let [current-rows (rows window-state-file)
              cleanup-row (some #(when (= cleanup-owner-index (:index %)) %) current-rows)]
          (when (and cleanup-row (tmux-session? tmux-socket (:session cleanup-row)))
            (let [cleanup-window-id (:window-id cleanup-row)]
              (if (terminal-ok? script-dir working-dir tmux-socket backend "terminal_window_exists" cleanup-window-id)
                (let [missing-counts (assoc missing-counts cleanup-owner-index 0)
                      missing-counts
                      (reduce
                       (fn [counts {:keys [index window-id session title]}]
                         (if (or (= index cleanup-owner-index)
                                 (not (tmux-session? tmux-socket session)))
                           counts
                           (if (terminal-ok? script-dir working-dir tmux-socket backend "terminal_window_exists" window-id)
                             (assoc counts index 0)
                             (let [count (inc (get counts index 0))]
                               (if (< count missing-threshold)
                                 (assoc counts index count)
                                 (let [new-window-id (terminal-out script-dir working-dir tmux-socket backend
                                                                   "terminal_open_session" session title cleanup-window-id)]
                                   (when-not (str/blank? new-window-id)
                                     (rewrite-window-id! window-state-file window-ids-file index new-window-id))
                                   (assoc counts index 0)))))))
                       missing-counts
                       current-rows)]
                  (Thread/sleep 2000)
                  (recur missing-counts))
                (let [count (inc (get missing-counts cleanup-owner-index 0))]
                  (if (>= count missing-threshold)
                    (kill-all-sessions! script-dir window-state-file working-dir tmux-socket backend)
                    (do
                      (Thread/sleep 2000)
                      (recur (assoc missing-counts cleanup-owner-index count)))))))))))))

(apply -main *command-line-args*)
