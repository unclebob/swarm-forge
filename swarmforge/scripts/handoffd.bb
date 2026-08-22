#!/usr/bin/env bb

(ns handoffd
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(load-file "swarmforge/scripts/audit_lib.bb")

(def poll-ms 1000)
(def wake-message
  "You have new handoff mail. If idle, run ready_for_next.sh.")

(defn usage []
  (binding [*out* *err*]
    (println "Usage: handoffd.bb [--once] <project-root>"))
  (System/exit 1))

(def once? (some #(= "--once" %) *command-line-args*))
(def project-root
  (or (first (remove #(= "--once" %) *command-line-args*)) (usage)))
(def script-dir (fs/parent *file*))

(def state-dir (fs/path project-root ".swarmforge"))
(def daemon-dir (fs/path state-dir "daemon"))
(def roles-file (fs/path state-dir "roles.tsv"))
(def socket-file (fs/path state-dir "tmux-socket"))
(def pid-file (fs/path daemon-dir "handoffd.pid"))
(def stop-file (fs/path daemon-dir "stop"))
(def log-file (fs/path daemon-dir "handoffd.log"))
(def stopping-flag (atom false))

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
  (let [preferred ["id" "from" "to" "recipient" "priority" "type" "role" "task" "commit"
                   "artifacts" "message" "created_at" "enqueued_at" "dequeued_at" "completed_at"]
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
  (let [send-text (sh "tmux" "-S" socket "send-keys" "-t" session "-l" wake-message)
        _ (Thread/sleep 150)
        send-carriage-return (sh "tmux" "-S" socket "send-keys" "-t" session "C-m")
        _ (Thread/sleep 50)
        send-line-feed (sh "tmux" "-S" socket "send-keys" "-t" session "C-j")]
    (when-not (zero? (:exit send-text))
      (throw (ex-info "tmux send text failed" send-text)))
    (when-not (zero? (:exit send-carriage-return))
      (throw (ex-info "tmux send carriage return failed" send-carriage-return)))
    (when-not (zero? (:exit send-line-feed))
      (throw (ex-info "tmux send line feed failed" send-line-feed)))))

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
    ((resolve 'audit-lib/write-audit-event!) project-root {:event "failed" :path (str path) :reason reason})
    (spit (str path ".error") (str reason "\n"))
    (move-with-collision path failed-dir)))

(defn recipient-list [headers]
  (some->> (get headers "to")
           (#(str/split % #","))
           (map str/trim)
           (remove str/blank?)
           seq))

(defn board-file []
  (fs/path project-root ".swarmforge" "board" "tasks.tsv"))

(defn pack-board! [& args]
  (let [script (str (fs/path script-dir "pack_board.sh"))
        result (apply sh (concat [script] args ["--root" (str project-root)]))]
    (when-not (zero? (:exit result))
      (log! "pack-board-failed" args (:err result) (:out result))
      (throw (ex-info (str/trim (str (:err result) "\n" (:out result))) result)))))

(defn update-board! [headers]
  (when (fs/exists? (board-file))
    (let [task (get headers "task")
          recipients (recipient-list headers)]
      (when (and (= "git_handoff" (get headers "type"))
                 (not (str/blank? task))
                 recipients)
        (if (next recipients)
          (pack-board! "done" "--name" task)
          (pack-board! "move" "--name" task "--lane" (first recipients)))))))

(defn archive-sender! [headers]
  (let [from (get headers "from")
        task (get headers "task")]
    (when (and (= "git_handoff" (get headers "type"))
               (not (str/blank? from))
               (not (str/blank? task)))
      (pack-board! "archive" "--role" from "--name" task))))

(defn master-role-name [roles]
  (some (fn [[role info]]
          (when (= "master" (:worktree-name info))
            role))
        roles))

(defn specifier-pack? [roles]
  (contains? roles "specifier"))

(defn from-master? [roles headers]
  (= (get headers "from") (master-role-name roles)))

(defn single-recipient? [headers]
  (let [recipients (recipient-list headers)]
    (boolean (and recipients (nil? (next recipients))))))

(defn already-approved? [headers]
  (not (str/blank? (get headers "approved"))))

(defn should-hold? [roles headers]
  (and (= "git_handoff" (get headers "type"))
       (specifier-pack? roles)
       (from-master? roles headers)
       (single-recipient? headers)
       (not (already-approved? headers))))

(defn pending-dir []
  (fs/path state-dir "handoffs" "pending_approval"))

(defn hold! [path]
  (move-with-collision path (pending-dir))
  (log! "held" (str path)))

(defn deliver! [roles socket sender-role path]
  (let [filename (fs/file-name path)
        message (parse-message path)
        headers (:headers message)
        recipients (recipient-list headers)]
    (if-not recipients
      (fail! path "missing to header")
      (do
        (update-board! headers)
        (doseq [recipient recipients]
          (let [role-info (get roles recipient)]
            (when-not role-info
              (throw (ex-info (str "unknown recipient " recipient) {:recipient recipient})))
            (let [target (target-path role-info filename)
                  delivered (add-delivery-headers message recipient)]
              (fs/create-dirs (fs/parent target))
              (when-not (fs/exists? target)
                (spit (str target) (render-message (:headers delivered) (:body delivered))))
              (notify! socket (:session role-info))
              ((resolve 'audit-lib/write-audit-event!) project-root 
               {:event "delivered" 
                :id (get headers "id") 
                :from sender-role 
                :to recipient 
                :task (get headers "task") 
                :type (get headers "type")}))))
        (move-with-collision path
                             (fs/path (get-in roles [sender-role :worktree-path])
                                      ".swarmforge" "handoffs" "sent"))
        (archive-sender! headers)
        (log! "delivered" (str path))))))

(defn outbox-files [role-info]
  (let [outbox (fs/path (:worktree-path role-info) ".swarmforge" "handoffs" "outbox")]
    (when (fs/exists? outbox)
      (->> (fs/list-dir outbox)
           (filter #(and (fs/regular-file? %)
                         (str/ends-with? (fs/file-name %) ".handoff")))
           (sort-by #(fs/file-name %))))))

(defn should-stop? []
  (or @stopping-flag (fs/exists? stop-file)))

(defn sleep-poll! [ms]
  (loop [remaining ms]
    (when (and (pos? remaining) (not (should-stop?)))
      (let [step (min remaining 100)]
        (Thread/sleep step)
        (recur (- remaining step))))))

(defn process-outbox-file! [roles socket path]
  (let [headers (:headers (parse-message path))
        from (get headers "from")]
    (if (should-hold? roles headers)
      (hold! (fs/path path))
      (deliver! roles socket (or from "") (fs/path path)))))

(defn poll-once! []
  (when-not (should-stop?)
    (let [roles (load-roles)
          socket (str/trim (slurp (str socket-file)))
          paths (->> (concat (mapcat #(or (outbox-files %) []) (vals roles))
                             (or (outbox-files {:worktree-path project-root}) []))
                     (map str)
                     distinct)]
      (doseq [path paths
              :while (not (should-stop?))]
        (try
          (process-outbox-file! roles socket path)
          (catch Exception e
            (log! "error" path (.getMessage e))
            (try
              (fail! (fs/path path) (.getMessage e))
              (catch Exception nested
                (log! "failed-to-archive" path (.getMessage nested))))))))))

(defn shutdown! []
  (reset! stopping-flag true)
  (try
    (fs/delete-if-exists pid-file)
    (log! "stopped")
    (catch Exception _ nil)))

(defn run-daemon! []
  (fs/create-dirs daemon-dir)
  (fs/delete-if-exists stop-file)
  (spit (str pid-file) (str (.pid (java.lang.ProcessHandle/current)) "\n"))
  (.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))
  (log! "started")
  (try
    (while (not (should-stop?))
      (poll-once!)
      (sleep-poll! poll-ms))
    (finally
      (fs/delete-if-exists pid-file)
      (log! "stopped"))))

(defn -main []
  (if once?
    (poll-once!)
    (run-daemon!)))

(-main)
