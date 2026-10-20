(ns neorepl.jack-in-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [neorepl.jack-in :as jack-in]
            [neorepl.nrepl :as nrepl]
            [neorepl.test-server :refer [call-with-temp-dir free-port spit-file windows? with-client]])
  (:import (java.net InetAddress ServerSocket)))

(defn- descendants-of
  "The processes process started, and the ones they did."
  [^Process process]
  (vec (iterator-seq (.iterator (.descendants process)))))

(defn- survives-sigint? [^Process process]
  (shell/sh "kill" "-INT" (str (.pid process)))
  (Thread/sleep 200)
  (.isAlive process))

(deftest find-project-test
  (call-with-temp-dir
   (fn [dir]
     (spit-file dir "deps.edn" "{}")
     (spit-file dir "bb.edn" "{}")
     (spit-file dir "src/demo/core.clj" "(ns demo.core)")
     (spit-file dir "sub/project.clj" "(defproject sub \"0.1.0\")")
     (let [path #(.getCanonicalPath (io/file dir %))]
       (testing "the nearest project, with the first of its tools"
         (is (= {:dir (path ".") :tool "clojure"} (jack-in/find-project (io/file dir "src/demo") nil)))
         (is (= {:dir (path "sub") :tool "lein"} (jack-in/find-project (io/file dir "sub") nil))))
       (testing "or the one asked for"
         (is (= {:dir (path ".") :tool "bb"} (jack-in/find-project (io/file dir "sub") "bb"))))
       (testing "or none, saying what's missing"
         (is (nil? (jack-in/find-project dir "lein")))
         (is (= (str "No project.clj in " (path ".") " or its parents")
                (ex-message (jack-in/no-project dir "lein"))))
         (is (= "project.clj, deps.edn, bb.edn or shadow-cljs.edn" (jack-in/project-files nil))))
       (testing "shadow-cljs after the rest"
         (spit-file dir "shadow-cljs.edn" "{:builds {}}")
         (is (= "clojure" (:tool (jack-in/find-project dir nil))))
         (testing "unless it builds with them"
           (spit-file dir "shadow-cljs.edn" "{:deps {:aliases [:cljs]} :http {:port #shadow/env \"PORT\"}}")
           (is (= "shadow-cljs" (:tool (jack-in/find-project dir nil)))))
         (testing "but not when that's a project.clj that isn't there"
           (spit-file dir "shadow-cljs.edn" "{:lein true}")
           (is (= "clojure" (:tool (jack-in/find-project dir nil)))))
         (testing "or when it can't say"
           (spit-file dir "shadow-cljs.edn" "{:deps")
           (is (= "clojure" (:tool (jack-in/find-project dir nil))))))))))

(deftest command-test
  (let [{:keys [nrepl cider-nrepl piggieback]} jack-in/versions]
    (testing "the Clojure CLI, with the deps and an alias of its own"
      (let [[clojure sdeps deps & more] (jack-in/command "clojure" {:aliases ["dev" "test"] :port 7888})]
        (is (= ["clojure" "-Sdeps"] [clojure sdeps]))
        (is (= {:deps {'nrepl/nrepl {:mvn/version nrepl}
                       'cider/cider-nrepl {:mvn/version cider-nrepl}
                       'cider/piggieback {:mvn/version piggieback}}
                :aliases {:neorepl/jack-in {:main-opts ["-m" "nrepl.cmdline"
                                                        "--middleware"
                                                        "[cider.nrepl/cider-middleware cider.piggieback/wrap-cljs-repl]"]}}}
               (edn/read-string deps)))
        (testing "written out plainly, for people to read"
          (is (not (str/includes? deps "#:"))))
        (testing "and the port after it, keeping the deps the same for the CLI's cache"
          (is (= ["-M:dev:test:neorepl/jack-in" "--port" "7888"] more)))))
    (testing "Leiningen"
      (is (= ["lein"
              "update-in" ":dependencies" "conj" (str "[nrepl/nrepl \"" nrepl "\"]") "--"
              "update-in" ":dependencies" "conj" (str "[cider/piggieback \"" piggieback "\"]") "--"
              "update-in" ":repl-options:nrepl-middleware" "conj" "cider.piggieback/wrap-cljs-repl" "--"
              "update-in" ":plugins" "conj" (str "[cider/cider-nrepl \"" cider-nrepl "\"]") "--"
              "with-profile" "+dev,-test"
              "repl" ":headless" ":port" "7888"]
             (jack-in/command "lein" {:aliases ["dev" "-test"] :port 7888})))
      (is (= ["repl" ":headless"] (take-last 2 (jack-in/command "lein" {})))))
    (testing "babashka"
      (is (= ["bb" "nrepl-server" "127.0.0.1:0"] (jack-in/command "bb" {})))
      (is (= ["bb" "nrepl-server" "127.0.0.1:7888"] (jack-in/command "bb" {:port 7888}))))
    (testing "shadow-cljs, with the nREPL it comes with"
      (is (= ["npx" "shadow-cljs" "-d" (str "cider/cider-nrepl:" cider-nrepl) "server"]
             (jack-in/command "shadow-cljs" {})))
      (when-not (windows?)
        (testing "run through Node, which keeps Ctrl-C from it, see npx-test"
          (is (= "node" (first (#'jack-in/launch-command (jack-in/command "shadow-cljs" {}))))))))))

(deftest with-server-test
  (call-with-temp-dir
   (fn [dir]
     (spit-file dir "bb.edn" "{}")
     (let [lines (atom [])
           process (jack-in/with-server dir (jack-in/command "bb" {}) ".nrepl-port" #(swap! lines conj %)
                     (fn [{:keys [^Process process] :as server}]
                       (testing "says where the server listens"
                         (with-client [client (select-keys server [:host :port])]
                           (is (= ["3"] (keep :value (nrepl/request client {:op "eval" :code "(+ 1 2)"}))))))
                       (testing "passes on what it prints"
                         (is (str/starts-with? (first @lines) "Started nREPL server")))
                       (when-not (windows?)
                         (testing "with the server ignoring Ctrl-C, which is for whatever runs it"
                           (is (survives-sigint? process))))
                       process))]
       (testing "stops it after"
         (is (not (.isAlive ^Process process)))
         ;; Windows has no SIGTERM, so the server has no say in it.
         (when-not (windows?)
           (testing "the way it cleans up after itself"
             (is (not (.exists (io/file dir ".nrepl-port")))))))))))

(deftest stop-test
  (when-not (windows?)
    (testing "the processes the server started go too"
      (call-with-temp-dir
       (fn [dir]
         (let [[process children]
               (jack-in/with-server dir ["sh" "-c" "sleep 60 & sleep 60 & echo 'nREPL server started on port 1 on host x'; wait"] ".nrepl-port"
                 (fn [_])
                 (fn [{:keys [^Process process] :as server}]
                   (is (= {:host "x" :port 1} (select-keys server [:host :port])))
                   [process (descendants-of process)]))]
           (is (seq children))
           (is (not (.isAlive ^Process process)))
           (is (not-any? #(.isAlive %) children))))))
    (testing "and the port file it leaves behind"
      (call-with-temp-dir
       (fn [dir]
         (let [port (free-port)
               ;; A server on port that writes what it's given into its port file.
               start #(jack-in/with-server dir ["sh" "-c" (str "echo " % " > .nrepl-port; "
                                                              "echo 'nREPL server started on port " port " on host 127.0.0.1'; "
                                                              "sleep 60")]
                        ".nrepl-port" (fn [_]) (fn [_]))]
           (start port)
           (is (not (.exists (io/file dir ".nrepl-port"))))
           (testing "unless it's another server's"
             (start 2)
             (is (.exists (io/file dir ".nrepl-port"))))
           (testing "on the same port, by now"
             (with-open [_ (ServerSocket. port 50 (InetAddress/getByName "127.0.0.1"))]
               (start port))
             (is (.exists (io/file dir ".nrepl-port"))))))))))

(defn- process-group [pid]
  (str/trim (:out (shell/sh "ps" "-o" "pgid=" "-p" (str pid)))))

(deftest npx-test
  ;; Node is up to whoever runs the tests.
  (when (and (not (windows?)) (jack-in/on-path? "npx"))
    (call-with-temp-dir
     (fn [dir]
       (let [with-server #(jack-in/with-server dir ["npx" "-c" "echo 'nREPL server started on port 1 on host x'; sleep 60"] ".nrepl-port"
                            (fn [_])
                            %)
             gone? #(not (.isAlive (doto % (-> .onExit (deref 5000 nil)))))]
         (testing "a server npx starts gets a process group of its own, out of the terminal's reach"
           (let [[process children]
                 (with-server
                   (fn [{:keys [^Process process] :as server}]
                     (is (= {:host "x" :port 1} (select-keys server [:host :port])))
                     (testing "and what starts it ignores Ctrl-C"
                       (is (survives-sigint? process)))
                     (let [children (descendants-of process)
                           group (process-group (.pid process))]
                       (is (seq children))
                       (is (not-any? #(= group (process-group (.pid %))) children))
                       [process children])))]
             (testing "and stops with the rest"
               (is (not (.isAlive ^Process process)))
               (is (every? gone? children)))))
         (testing "it stops when what starts it gets a signal to, as on a hangup"
           (let [children (with-server
                            (fn [{:keys [^Process process]}]
                              (let [children (descendants-of process)]
                                (shell/sh "kill" "-HUP" (str (.pid process)))
                                (.waitFor process)
                                children)))]
             (is (seq children))
             (is (every? gone? children)))))))))

(deftest failed-test
  (call-with-temp-dir
   (fn [dir]
     (let [lines (atom [])]
       (testing "a server that ends before it's up"
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ended before it started, with exit status 3"
                               (jack-in/with-server dir ["bb" "-e" "(binding [*out* *err*] (println :oops)) (System/exit 3)"] ".nrepl-port"
                                 #(swap! lines conj %)
                                 (fn [_]))))
         (testing "after what it said"
           (is (= [":oops"] @lines))))
       (testing "one whose build tool isn't installed"
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^Couldn't start the server: neorepl-no-such-tool isn't installed, or isn't on the PATH$"
                               (jack-in/with-server dir ["neorepl-no-such-tool"] ".nrepl-port" (fn [_]) (fn [_])))))
       (testing "one that can't start"
         (is (= ::jack-in/failed
                (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                             (jack-in/with-server (io/file dir "nope") ["bb"] ".nrepl-port" (fn [_]) (fn [_])))))))))))))
