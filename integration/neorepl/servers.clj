(ns neorepl.servers
  "Real servers for the integration tests, each started the way `neorepl
  server` starts it, in a project of its own, and neorepl run against them."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [testing]]
            [neorepl.jack-in :as jack-in]
            [neorepl.test-server :as test-server :refer [call-with-temp-dir spit-file]]))

(def ^:dynamic *dir*
  "A directory with each server's project, in a directory named after it,
  and neorepl's sessions, as if it was XDG_CACHE_HOME."
  nil)

(defn project-dir [server]
  (io/file *dir* (name server)))

(defn sessions-file []
  (io/file *dir* "neorepl" "sessions.edn"))

(def core
  "(ns demo.core)

(defn greet
  \"Says hi.\"
  [who]
  (str \"hi \" who))

(defn boom [x]
  (throw (ex-info \"boom\" {:x x})))

(defn welcome [who]
  (println (greet who)))
")

(def core-test
  "(ns demo.core-test
  (:require [clojure.test :refer [deftest is]]
            [demo.core :as core]))

(deftest greet-test
  (is (= \"hi you\" (core/greet \"you\"))))

(deftest failing-test
  (println \"printed by failing-test\")
  (is (= \"hi\" (core/greet \"you\"))))

(deftest error-test
  (core/boom 1))
")

(defn sources
  "The demo project's source and test files, as files with extension ext."
  [ext]
  {(str "src/demo/core." ext) core
   (str "test/demo/core_test." ext) core-test})

(defn- sh!
  "Runs command in dir, throwing with what it printed unless it works."
  [dir & command]
  (let [{:keys [exit out err]} (apply shell/sh (concat command [:dir (str dir)]))]
    (when-not (zero? exit)
      (throw (ex-info (str (str/join " " command) " failed in " dir ":\n" out err) {})))))

(defn- server-spec
  "What with-server starts for spec: the :command, the :port-file it writes,
  .nrepl-port unless it says, and the :files, with the project file for a
  build tool's."
  [{:keys [tool project] :as spec}]
  (if tool
    (let [{:keys [file port-file]} (jack-in/tool tool)]
      (-> spec
          (assoc :command (jack-in/command tool {}) :port-file port-file)
          (update :files assoc file project)))
    (merge {:port-file ".nrepl-port"} spec)))

(defn- with-server
  "Calls f once server is up in a project of its own, with the :files,
  paths to their contents, saying what the server printed if it doesn't
  come up. It's started the way `neorepl server` starts it, with the build
  tool :tool, which reads the :project file, or for a server jack-in
  doesn't start, with the :command, run in the project, which writes the
  :port-file. A package.json gets installed first, and a requirements.txt
  in a venv, .venv."
  [server spec f]
  (let [{:keys [command port-file files]} (server-spec spec)
        dir (project-dir server)
        output (atom [])]
    (doseq [[path content] files]
      (spit-file dir path content))
    (when (contains? files "package.json")
      (sh! dir "npm" "install" "--no-audit" "--no-fund"))
    (when (contains? files "requirements.txt")
      (sh! dir "python3" "-m" "venv" ".venv")
      (sh! dir ".venv/bin/pip" "install" "--quiet" "--disable-pip-version-check" "-r" "requirements.txt"))
    (try
      (jack-in/with-server dir command port-file #(swap! output conj %) (fn [_] (f)))
      (catch clojure.lang.ExceptionInfo e
        (if (= ::jack-in/failed (:type (ex-data e)))
          (throw (ex-info (str/join "\n" (cons (ex-message e) @output)) {} e))
          (throw e))))))

(defn call-with-servers
  "Calls f with servers, a map of names to what with-server takes, up, in a
  fresh *dir*, after calling (prepare server) for each."
  [servers prepare f]
  (call-with-temp-dir
   (fn [dir]
     ;; As the servers give their files' paths, without symlinks, like
     ;; macOS' /var.
     (binding [*dir* (.getCanonicalFile dir)]
       ((reduce (fn [inner [server spec]] #(with-server server spec inner))
                #(do (run! prepare (keys servers))
                     (f))
                servers))))))

(defn on-each-server
  "Calls (f server) for each of servers' names, testing it under the name."
  [servers f]
  (doseq [server (keys servers)]
    (testing server
      (f server))))

(defn run-with
  "Runs neorepl with args in server's project, finding the server by its
  port file, with stdin as standard input, as test-server/run-in does."
  [server stdin & args]
  (apply test-server/run-in {:dir (str (project-dir server)) :sessions-file (sessions-file)} stdin args))

(defn run
  "Runs neorepl with args against server."
  [server & args]
  (apply run-with server "" args))
