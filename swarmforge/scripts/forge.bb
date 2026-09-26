#!/usr/bin/env bb

(ns forge
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]))

(def pack-names ["two-pack" "four-pack" "six-pack"])
(def shared-articles ["engineering.prompt" "workflow.prompt" "handoffs.prompt"])

(defn sh [& args]
  (apply process/sh args))

(defn forge? [root]
  (fs/directory? (fs/path root "projects")))

(defn packs-dir [root]
  (fs/path root "packs"))

(defn projects-dir [root]
  (fs/path root "projects"))

(defn pack-dir [root pack]
  (fs/path (packs-dir root) pack))

(defn project-dir [root name]
  (fs/path (projects-dir root) name))

(defn inferred-name
  ([input] (inferred-name input false))
  ([input github?]
   (let [trimmed (str/trim (or input ""))]
     (if github?
       (let [trimmed (str/replace trimmed #"\.git$" "")
             trimmed (str/replace trimmed #"^https?://github.com/" "")
             trimmed (str/replace trimmed #"^git@github.com:" "")]
         (or (last (str/split trimmed #"/")) trimmed))
       trimmed))))

(defn github-clone-url [name]
  (let [base (or (not-empty (System/getenv "SWARMFORGE_GITHUB_BASE"))
                 "https://github.com/")
        name (-> name str/trim
                 (str/replace #"\.git$" "")
                 (str/replace #"^https?://github.com/" "")
                 (str/replace #"^git@github.com:" ""))]
    (if (str/starts-with? base "http")
      (str (str/replace base #"/+$" "") "/" name ".git")
      (str (fs/path (str/replace base #"/+$" "") name)))))

(defn list-pack-names [root]
  (let [dir (packs-dir root)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (filter #(fs/regular-file? (fs/path % "swarmforge" "swarmforge.conf")))
           (map fs/file-name)
           sort
           vec)
      [])))

(defn pack-conf [root pack]
  (let [file (fs/path (pack-dir root pack) "swarmforge" "swarmforge.conf")]
    (when (fs/regular-file? file)
      (slurp (str file)))))

(defn list-project-names [root]
  (let [dir (projects-dir root)]
    (if (fs/directory? dir)
      (->> (fs/list-dir dir)
           (filter fs/directory?)
           (map fs/file-name)
           (remove #(str/starts-with? % "."))
           sort
           vec)
      [])))

(defn open-projects-file [root]
  (fs/path root ".swarmforge" "open-projects"))

(defn read-open-projects [root]
  (let [file (open-projects-file root)]
    (if (fs/regular-file? file)
      (->> (str/split-lines (slurp (str file)))
           (map str/trim)
           (remove str/blank?)
           vec)
      [])))

(defn write-open-projects! [root names]
  (let [file (open-projects-file root)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (apply str (map #(str % "\n") names)))))

(defn project-open? [root name]
  (boolean (some #{name} (read-open-projects root))))

(defn mark-open! [root name]
  (write-open-projects! root (vec (distinct (conj (read-open-projects root) name)))))

(defn mark-closed! [root name]
  (write-open-projects! root (vec (remove #{name} (read-open-projects root)))))

(defn pack-name-file [project]
  (fs/path project ".swarmforge" "pack"))

(defn stored-pack [project]
  (let [file (pack-name-file project)]
    (when (fs/regular-file? file)
      (str/trim (slurp (str file))))))

(defn write-pack-name! [project pack]
  (let [file (pack-name-file project)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) (str pack "\n"))))

(defn copy-tree-replace! [src dest]
  (when (fs/exists? dest)
    (fs/delete-tree dest))
  (fs/create-dirs (fs/parent dest))
  (fs/copy-tree src dest))

(defn copy-shared-scripts! [forge dest]
  (let [src (fs/path forge "swarmforge" "scripts")]
    (when-not (fs/directory? src)
      (throw (ex-info (str "Missing host scripts at " src) {:http-status 500})))
    (copy-tree-replace! src (fs/path dest "swarmforge" "scripts"))))

(defn copy-shared-articles! [forge dest]
  (doseq [name shared-articles]
    (let [src (fs/path forge "swarmforge" "constitution" "articles" name)]
      (when (fs/regular-file? src)
        (fs/create-dirs (fs/path dest "swarmforge" "constitution" "articles"))
        (fs/copy src (fs/path dest "swarmforge" "constitution" "articles" name)
                 {:replace-existing true})))))

(defn copy-pack-local! [pack-root dest keep-conf?]
  (let [roles (fs/path pack-root "swarmforge" "roles")
        conf (fs/path pack-root "swarmforge" "swarmforge.conf")
        constitution (fs/path pack-root "swarmforge" "constitution.prompt")
        articles (fs/path pack-root "swarmforge" "constitution" "articles")]
    (when (fs/directory? roles)
      (copy-tree-replace! roles (fs/path dest "swarmforge" "roles")))
    (when (and (not keep-conf?) (fs/regular-file? conf))
      (fs/create-dirs (fs/path dest "swarmforge"))
      (fs/copy conf (fs/path dest "swarmforge" "swarmforge.conf") {:replace-existing true}))
    (when (fs/regular-file? constitution)
      (fs/copy constitution (fs/path dest "swarmforge" "constitution.prompt") {:replace-existing true}))
    (when (fs/directory? articles)
      (fs/create-dirs (fs/path dest "swarmforge" "constitution" "articles"))
      (doseq [file (fs/list-dir articles)]
        (when (fs/regular-file? file)
          (let [name (fs/file-name file)]
            (when-not (some #{name} shared-articles)
              (fs/copy file (fs/path dest "swarmforge" "constitution" "articles" name)
                       {:replace-existing true}))))))))

;; What a project is written in: the pack's default, written once into the
;; project's own `swarmforge/language.conf` and never written again. A pack's
;; `project.prompt` names that default, and the project's own file is what the
;; project is - so a project re-languaged by hand keeps its answer through every
;; refresh, and a pack can serve a language its author never wrote for.
(defn seed-pack-language! [pack-root dest]
  (let [src (fs/path pack-root "language.conf")
        file (fs/path dest "swarmforge" "language.conf")]
    (when (and (fs/regular-file? src) (not (fs/exists? file)))
      (fs/create-dirs (fs/parent file))
      (fs/copy src file))))

(defn overlay-pack! [forge dest pack keep-conf?]
  (copy-shared-scripts! forge dest)
  (copy-shared-articles! forge dest)
  (copy-pack-local! (pack-dir forge pack) dest keep-conf?)
  (seed-pack-language! (pack-dir forge pack) dest))

(defn init-git-if-needed! [dir]
  (when-not (fs/exists? (fs/path dir ".git"))
    (sh "git" "init" (str dir))
    (sh "git" "-C" (str dir) "config" "user.email" "swarmforge@local")
    (sh "git" "-C" (str dir) "config" "user.name" "SwarmForge")
    (sh "git" "-C" (str dir) "branch" "-M" "master")
    (let [gitignore (fs/path dir ".gitignore")]
      (when-not (fs/exists? gitignore)
        (spit (str gitignore) ".swarmforge/\n.worktrees/\n")))
    (sh {:continue true} "git" "-C" (str dir) "add" ".")
    (sh {:continue true} "git" "-C" (str dir) "commit" "-q" "-m" "Initial swarmforge project")))

(defn clone-github! [url dest]
  (let [result (sh "git" "clone" "--" url (str dest))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Clone failed: " (str/trim (str (:err result) "\n" (:out result))))
                      {:http-status 400 :error "clone-failed"})))
    dest))

(defn instantiate! [forge {:keys [name github pack conf mission]}]
  (let [dir-name (inferred-name name (boolean github))
        dest (project-dir forge dir-name)]
    (when (str/blank? dir-name)
      (throw (ex-info "Missing project name" {:http-status 400})))
    (when (str/blank? pack)
      (throw (ex-info "Missing pack" {:http-status 400})))
    (when-not (fs/directory? (pack-dir forge pack))
      (throw (ex-info (str "Unknown pack: " pack) {:http-status 400})))
    (when (fs/exists? dest)
      (throw (ex-info (str "Project already exists: " dir-name)
                      {:http-status 409 :error "exists"})))
    (fs/create-dirs (projects-dir forge))
    (if github
      (clone-github! (github-clone-url name) dest)
      (fs/create-dirs dest))
    (overlay-pack! forge dest pack false)
    (when-not (str/blank? conf)
      (fs/create-dirs (fs/path dest "swarmforge"))
      (spit (str (fs/path dest "swarmforge" "swarmforge.conf")) conf))
    (when-not (nil? mission)
      (spit (str (fs/path dest "mission.md"))
            (if (str/ends-with? (or mission "") "\n") mission (str mission "\n"))))
    (write-pack-name! dest pack)
    (when-not github
      (init-git-if-needed! dest))
    {:name dir-name :path (str dest)}))

(defn refresh! [forge name]
  (let [dest (project-dir forge name)
        pack (stored-pack dest)]
    (when-not (fs/directory? dest)
      (throw (ex-info (str "Unknown project: " name) {:http-status 404})))
    (when (str/blank? pack)
      (throw (ex-info (str "No pack recorded for " name) {:http-status 400})))
    (overlay-pack! forge dest pack true)
    {:name name :pack pack}))

(defn skip-start? []
  (= "1" (System/getenv "SWARMFORGE_SKIP_START")))

(defn swarmforge-bb [forge]
  (str (fs/path forge "swarmforge" "scripts" "swarmforge.bb")))

(defn start-project-runtime! [forge name]
  (let [dest (project-dir forge name)
        script (swarmforge-bb forge)
        log (fs/path dest ".swarmforge" "start.log")]
    (fs/create-dirs (fs/parent log))
    (process/process ["bb" script "--start-project" (str dest)]
                     {:out (str log) :err :out})))

(defn stop-project-runtime! [forge name]
  (let [dest (str (project-dir forge name))
        script (swarmforge-bb forge)]
    (process/sh {:continue true} "bb" script "--stop-project" dest)))

(defn open-project! [forge name]
  (when-not (fs/directory? (project-dir forge name))
    (throw (ex-info (str "Unknown project: " name) {:http-status 404})))
  (when (project-open? forge name)
    (throw (ex-info (str "Project already open: " name)
                    {:http-status 409 :error "already-open"})))
  (refresh! forge name)
  (when-not (skip-start?)
    (start-project-runtime! forge name))
  (mark-open! forge name)
  {:name name :open true})

(defn close-project! [forge name]
  (when-not (skip-start?)
    (stop-project-runtime! forge name))
  (mark-closed! forge name)
  {:name name :open false})

(defn close-all-projects! [forge]
  (doseq [name (read-open-projects forge)]
    (close-project! forge name)))
