(ns neorepl.real-servers-test
  "neorepl against real servers: nREPL with cider-nrepl, started the way
  `neorepl server` starts it, and babashka's, with the bb on the PATH. The
  fakes the unit tests use behave like the cider-nrepl they were written
  against, which says nothing about the next one, or the next bb."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.jack-in :as jack-in]
            [neorepl.json :as json]
            [neorepl.test-server :as test-server :refer [call-with-temp-dir spit-file]]))

(def ^:private servers
  "The servers the tests run against, by name, with the build tool that
  starts each."
  {:cider "clojure" :bb "bb"})

(def ^:private ^:dynamic *dir*
  "A directory with each server's project, in a directory named after it,
  and neorepl's sessions, as if it was XDG_CACHE_HOME."
  nil)

(defn- project-dir [server]
  (io/file *dir* (name server)))

(def ^:private core
  "(ns demo.core)

(defn greet
  \"Says hi.\"
  [who]
  (str \"hi \" who))

(defn boom [x]
  (throw (ex-info \"boom\" {:x x})))
")

(def ^:private core-test
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

(defn- with-server
  "Calls f once server is up in a project of its own, started the way
  `neorepl server` starts it, saying what the server printed if it doesn't
  come up."
  [server f]
  (let [tool (servers server)
        {:keys [file port-file]} (jack-in/tool tool)
        dir (project-dir server)
        output (atom [])]
    (spit-file dir file "{:paths [\"src\" \"test\"]}")
    (spit-file dir "src/demo/core.clj" core)
    (spit-file dir "test/demo/core_test.clj" core-test)
    (try
      (jack-in/with-server dir (jack-in/command tool {}) port-file #(swap! output conj %) (fn [_] (f)))
      (catch clojure.lang.ExceptionInfo e
        (if (= ::jack-in/failed (:type (ex-data e)))
          (throw (ex-info (str/join "\n" (cons (ex-message e) @output)) {} e))
          (throw e))))))

(defn- run-with
  "Runs neorepl with args in server's project, finding the server by its
  port file, with stdin as standard input, as test-server/run-in does."
  [server stdin & args]
  (apply test-server/run-in {:dir (str (project-dir server))
                             :sessions-file (io/file *dir* "neorepl" "sessions.edn")}
         stdin args))

(defn- run
  "Runs neorepl with args against server."
  [server & args]
  (apply run-with server "" args))

(defn- on-each-server
  "Calls (f server) for each server, testing it under its name."
  [f]
  (doseq [server (keys servers)]
    (testing server
      (f server))))

(use-fixtures :once
  (fn [f]
    (call-with-temp-dir
     (fn [dir]
       ;; As the servers give their files' paths, without symlinks, like
       ;; macOS' /var.
       (binding [*dir* (.getCanonicalFile dir)]
         ((reduce (fn [inner server] #(with-server server inner))
                  #(do (doseq [server (keys servers)]
                         (run server "eval" "(require 'demo.core-test)"))
                       (f))
                  (keys servers))))))))

(deftest eval-test
  (on-each-server
   (fn [server]
     (is (= {:status 0 :out "out\nnil\n3\n" :err ""}
            (run server "eval" "(println \"out\") (+ 1 2)")))
     (testing "with an error"
       (let [{:keys [status out err]} (run server "eval" "(demo.core/boom 1)")]
         (is (= 1 status))
         (is (= "" out))
         (is (str/includes? err "boom"))))
     (testing "in a named session"
       (run server "eval" "-s" "eval" "(def x 42)")
       (is (= {:status 0 :out "42\n" :err ""} (run server "eval" "-s" "eval" "x")))))))

(deftest lookups-test
  (on-each-server
   (fn [server]
     (let [file (io/file (project-dir server) "src" "demo" "core.clj")]
       (is (= {:status 0 :out "#'demo.core/boom\n" :err ""} (run server "load" (str file))))
       (is (= {:status 0 :out "demo.core/greet\n([who])\n  Says hi.\n" :err ""}
              (run server "doc" "demo.core/greet")))
       (is (= {:status 0 :out "(defn greet\n  \"Says hi.\"\n  [who]\n  (str \"hi \" who))\n" :err ""}
              (run server "source" "demo.core/greet")))
       (is (= {:status 0 :out (str file ":3:1\n") :err ""} (run server "where" "demo.core/greet")))
       (is (str/starts-with? (:out (run server "complete" "demo.core/gr")) "demo.core/greet"))
       (testing "as a named session sees them"
         (run server "eval" "-s" "lookups" "(in-ns 'demo.core)")
         (is (= "demo.core/greet\n([who])\n  Says hi.\n"
                (:out (run server "doc" "-s" "lookups" "greet")))))))))

(deftest test-test
  (on-each-server
   (fn [server]
     (let [{:keys [status out]} (run server "test" "demo.core-test")
           ;; Absolute, as the project isn't in the directory neorepl runs in.
           file (io/file (project-dir server) "test" "demo" "core_test.clj")]
       (is (= 1 status))
       (is (str/includes? out (str "FAIL in demo.core-test/failing-test (" file ":10)\n")))
       ;; cider-nrepl gives the line that threw, bb the test's.
       (is (str/includes? out (str "ERROR in demo.core-test/error-test (" file ":" ({:cider 13 :bb 12} server) ")\n")))
       (is (str/includes? out "error: clojure.lang.ExceptionInfo: boom {:x 1}"))
       (is (str/ends-with? out "Ran 3 tests containing 3 assertions.\n1 failure, 1 error.\n"))
       ;; bb sends what tests print under the session's previous message,
       ;; to a connection that's gone, unless the session's new
       ;; (babashka#2265).
       (when (= :cider server)
         (is (str/includes? out "printed by failing-test\n"))))
     (testing "rerunning what didn't pass"
       (let [{:keys [status out]} (run server "retest")]
         (is (= 1 status))
         (is (str/ends-with? out "Ran 2 tests containing 2 assertions.\n1 failure, 1 error.\n"))))
     (testing "the whole project"
       (is (str/ends-with? (:out (run server "test")) "Ran 3 tests containing 3 assertions.\n1 failure, 1 error.\n")))
     (testing "a namespace that isn't there"
       (let [{:keys [status err]} (run server "test" "no.such-ns")]
         (is (= 1 status))
         (is (str/starts-with? err "neorepl: Couldn't load no.such-ns: "))))
     (testing "a test that isn't there"
       (is (= {:status 1 :out "" :err "neorepl: No test called demo.core-test/nope\n"}
              (run server "test" "demo.core-test/nope")))))))

(deftest pst-test
  (on-each-server
   (fn [server]
     (run server "eval" "-s" "pst" "(demo.core/boom 2)")
     (let [{:keys [status out]} (run server "pst" "-s" "pst")]
       (is (= 0 status))
       (is (str/starts-with? out "clojure.lang.ExceptionInfo: boom\n  {:x 2}\n    at demo.core"))
       (is (str/includes? out "core.clj:9)\n"))))))

(deftest inspect-test
  (on-each-server
   (fn [server]
     (is (str/includes? (:out (run server "inspect" "(range 3)")) "Count: 3\n"))
     (is (str/includes? (:out (run server "inspect" "--push" "1")) "Value: [1]0\n"))
     (is (= {:status 0 :out "#'user/picked\n" :err ""} (run server "inspect" "--def" "picked")))
     (is (= "0\n" (:out (run server "eval" "picked")))))))

(deftest trace-test
  (testing "cider-nrepl"
    (is (= "Tracing demo.core/greet\n" (:out (run :cider "trace" "demo.core/greet"))))
    (is (str/includes? (:out (run :cider "eval" "(demo.core/greet \"t\")")) "└─→ \"hi t\"\n"))
    (is (= "Stopped tracing demo.core/greet\n" (:out (run :cider "untrace" "demo.core/greet"))))
    (testing "a call that throws"
      (run :cider "trace" "demo.core/boom")
      (is (str/includes? (:out (run :cider "eval" "(demo.core/boom 3)")) "└─✗ clojure.lang.ExceptionInfo: boom\n"))
      (run :cider "untrace" "demo.core/boom")))
  (testing "babashka"
    (is (= {:status 1 :out "" :err "neorepl: Tracing takes cider-nrepl on the server\n"}
           (run :bb "trace" "demo.core/greet")))))

(deftest java-doc-test
  (testing "cider-nrepl, with the Javadoc"
    (is (str/includes? (:out (run :cider "doc" "java.util.ArrayList"))
                       "Resizable-array implementation of the List interface."))
    (is (str/starts-with? (:out (run :cider "doc" ".toUpperCase"))
                          "java.lang.String/toUpperCase\n([this locale] [this])\n")))
  (testing "babashka, without it"
    (is (= {:status 0 :out "java.util.ArrayList\n" :err ""} (run :bb "doc" "java.util.ArrayList")))))

(deftest reload-test
  (let [file (io/file (project-dir :cider) "src" "demo" "core.clj")]
    (run :cider "reload")
    (try
      (spit file "\n(defn added [] :added)\n" :append true)
      (is (= {:status 0 :out "Reloaded demo.core, demo.core-test\n" :err ""} (run :cider "reload")))
      (is (= ":added\n" (:out (run :cider "eval" "(demo.core/added)"))))
      (finally
        (spit file core)
        (run :cider "reload")))))

(deftest repl-test
  (on-each-server
   (fn [server]
     (let [{:keys [status out]} (run-with server ",ns demo.core\n(greet \"r\")\n,test demo.core-test/failing-test\n" "repl")]
       (is (= 0 status))
       (is (str/includes? out "demo.core=> \"hi r\"\n"))
       ;; bb's comes as output nobody asked for, which the REPL prints from
       ;; another thread, where *out* isn't this one.
       (when (= :cider server)
         (is (str/includes? out "printed by failing-test\n")))
       (is (str/includes? out "Ran 1 test containing 1 assertion.\n1 failure, 0 errors.\n"))))))

(deftest mcp-test
  (on-each-server
   (fn [server]
     (let [call {:jsonrpc "2.0" :id 1 :method "tools/call"
                 :params {:name "eval" :arguments {:code "(println \"m\") (+ 40 2)"}}}
           {:keys [status out]} (run-with server (str (json/write-str call) "\n") "mcp")]
       (is (= 0 status))
       (is (= {:status "ok" :values ["nil" "42"] :out "m\n"}
              (-> (json/read-str out) :result :structuredContent (select-keys [:status :values :out]))))))))
