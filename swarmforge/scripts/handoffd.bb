#!/usr/bin/env bb

(ns handoffd
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(def poll-ms 1000)
(def wake-message
  "You have new handoff mail. If idle, run ready_for_next.sh.")

(defn usage []
  (binding [*out* *err*]
    (println "Usage: handoffd.bb <project-root>"))
  (System/exit 1))

(def project-root
  (or (first *command-line-args*) (usage)))

(def state-dir (fs/path project-root ".swarmforge"))
(def daemon-dir (fs/path state-dir "daemon"))
(def roles-file (fs/path state-dir "roles.tsv"))
(def socket-file (fs/path state-dir "tmux-socket"))
(def pid-file (fs/path daemon-dir "handoffd.pid"))
(def stop-file (fs/path daemon-dir "stop"))
(def log-file (fs/path daemon-dir "handoffd.log"))
(def stopping? (atom false))

(defn now []
  (.format (java.time.format.DateTimeFormatter/ISO_INSTANT)
           (java.time.Instant/now)))

(defn log! [& parts]
  (fs/create-dirs daemon-dir)
  (spit (str log-file)
        (str (now) " " (str/join " " parts) "\n")
        :append true))

(defn read-lines [path]
  (when (fs/exists? path)
    (str/split-lines (slurp (str path)))))

(defn load-roles []
  (into {}
        (for [line (read-lines roles-file)
              :when (not (str/blank? line))
              :let [[role worktree-name worktree-path session display agent receive-mode]
                    (str/split line #"\t")]]
          [role {:role role
                 :worktree-name worktree-name
                 :worktree-path worktree-path
                 :session session
                 :display display
                 :agent agent
                 :receive-mode (or receive-mode "task")}])))

(defn parse-message [path]
  (let [content (slurp (str path))
        [header body] (str/split content #"\n\n" 2)
        headers (into {}
                      (for [line (str/split-lines header)
                            :let [[k v] (str/split line #": " 2)]
                            :when (and k v)]
                        [k v]))]
    {:headers headers
     :body (or body "")
     :content content}))

(defn render-message [headers body]
  (let [preferred ["id" "from" "to" "recipient" "priority" "type" "role" "commit"
                   "message" "created_at" "enqueued_at" "dequeued_at" "completed_at"]
        remaining (->> (keys headers)
                       (remove (set preferred))
                       sort)
        ordered (concat preferred remaining)]
    (str (str/join "\n"
                   (for [k ordered
                         :let [v (get headers k)]
                         :when v]
                     (str k ": " v)))
         "\n\n"
         body)))

(defn add-delivery-headers [message recipient]
  (-> message
      (assoc-in [:headers "recipient"] recipient)
      (assoc-in [:headers "enqueued_at"] (now))))

(defn target-path [role-info filename]
  (fs/path (:worktree-path role-info)
           ".swarmforge" "handoffs" "inbox" "new" filename))

(defn notify! [socket session]
  (letfn [(send! [text]
            (let [r (sh "tmux" "-S" socket "send-keys" "-t" session "-l" text)]
              (when-not (zero? (:exit r))
                (throw (ex-info (str "tmux send failed: " text) r)))))
          (enter! []
            (let [r (sh "tmux" "-S" socket "send-keys" "-t" session "Enter")]
              (when-not (zero? (:exit r))
                (throw (ex-info "tmux send Enter failed" r)))))]
    (send! "/clear")
    (Thread/sleep 500)
    (enter!)
    (Thread/sleep 2000)
    (send! (str "/swarm-persona " wake-message))
    (Thread/sleep 150)
    (enter!)))

(defn move-with-collision [source target-dir]
  (fs/create-dirs target-dir)
  (let [base (fs/file-name source)
        target (fs/path target-dir base)]
    (if (fs/exists? target)
      (fs/move source
               (fs/path target-dir (str (now) "_" base))
               {:replace-existing false})
      (fs/move source target {:replace-existing false}))))

(defn fail! [path reason]
  (let [failed-dir (fs/path (fs/parent (fs/parent path)) "failed")]
    (log! "failed" (str path) reason)
    (spit (str path ".error") (str reason "\n"))
    (move-with-collision path failed-dir)))

(defn deliver! [roles socket sender-role path]
  (let [filename (fs/file-name path)
        message (parse-message path)
        headers (:headers message)
        recipients (some-> (get headers "to") (str/split #",") seq)]
    (if-not recipients
      (fail! path "missing to header")
      (do
        (doseq [recipient recipients]
          (let [role-info (get roles recipient)]
            (when-not role-info
              (throw (ex-info (str "unknown recipient " recipient) {:recipient recipient})))
            (let [target (target-path role-info filename)
                  delivered (add-delivery-headers message recipient)]
              (fs/create-dirs (fs/parent target))
              (when-not (fs/exists? target)
                (spit (str target) (render-message (:headers delivered) (:body delivered))))
              (when-not (fs/exists? (fs/path (:worktree-path role-info) ".swarmforge" "agent-running"))
                (notify! socket (:session role-info))))))
        (move-with-collision path
                             (fs/path (get-in roles [sender-role :worktree-path])
                                      ".swarmforge" "handoffs" "sent"))
        (log! "delivered" (str path))))))

(defn outbox-files [role-info]
  (let [outbox (fs/path (:worktree-path role-info) ".swarmforge" "handoffs" "outbox")]
    (when (fs/exists? outbox)
      (->> (fs/list-dir outbox)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".handoff")))
           (sort-by #(fs/file-name %))))))

(defn poll-once! []
  (let [roles (load-roles)
        socket (str/trim (slurp (str socket-file)))]
    (doseq [[role role-info] roles
            path (or (outbox-files role-info) [])]
      (try
        (deliver! roles socket role path)
        (catch Exception e
          (log! "error" (str path) (.getMessage e))
          (try
            (fail! path (.getMessage e))
            (catch Exception nested
              (log! "failed-to-archive" (str path) (.getMessage nested)))))))))

(defn shutdown! []
  (reset! stopping? true))

(defn -main []
  (fs/create-dirs daemon-dir)
  (fs/delete-if-exists stop-file)
  (spit (str pid-file) (str (.pid (java.lang.ProcessHandle/current)) "\n"))
  (.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))
  (log! "started")
  (try
    (while (and (not @stopping?) (not (fs/exists? stop-file)))
      (poll-once!)
      (Thread/sleep poll-ms))
    (finally
      (fs/delete-if-exists pid-file)
      (log! "stopped"))))

(-main)
