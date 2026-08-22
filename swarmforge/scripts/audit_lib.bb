#!/usr/bin/env bb

(ns audit-lib
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn now []
  (.format (java.time.format.DateTimeFormatter/ISO_INSTANT)
           (java.time.Instant/now)))

(defn audit-log-file [project-root]
  (fs/path project-root ".swarmforge" "daemon" "audit.jsonl"))

(defn write-audit-event! [project-root event-data]
  (let [log-path (audit-log-file project-root)]
    (fs/create-dirs (fs/parent log-path))
    (let [enriched (merge {:timestamp (now)} event-data)]
      (let [json-str (str "{"
                          (str/join ","
                                    (for [[k v] enriched]
                                      (str "\"" (name k) "\":\"" (str v) "\"")))
                          "}\n")]
        (spit (str log-path) json-str :append true)))))
