(ns neorepl.cljs-servers-test
  "neorepl in ClojureScript sessions on real servers, with a Node runtime:
  piggieback, which jack-in adds to a deps.edn project with ClojureScript,
  and shadow-cljs. They take Node and npm."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.servers :as servers :refer [project-dir run run-with]]))

(def ^:private untouched
  "Namespaces and their tests that only the REPL test loads, from the
  namespaces they test, for each way it loads them: switching to the tests
  and running them. ClojureScript's REPL makes the namespace it's in depend
  on those it requires, so requiring them there would be a circular
  dependency, which needs them not loaded yet to show."
  (into {} (for [n ["a" "b"]]
             {(str "src/demo/" n ".cljc") (format "(ns demo.%s)\n\n(defn f [] :%s)\n" n n)
              (str "test/demo/" n "_test.cljc")
              (format "(ns demo.%1$s-test\n  (:require [clojure.test :refer [deftest is]]\n            [demo.%1$s :as %1$s]))\n\n(deftest f-test\n  (is (= :%1$s (%1$s/f))))\n" n)})))

(def ^:private servers
  "The servers the tests run against, by name, with the build tool that
  starts each, the project file it gets, the :node-repl code that puts a
  session in ClojureScript, in a Node runtime, and the :clojure-ns a
  session starts in."
  (let [files (merge (servers/sources "cljc") untouched)]
    {:piggieback {:tool "clojure"
                  :project "{:paths [\"src\" \"test\"] :deps {org.clojure/clojurescript {:mvn/version \"1.12.134\"}}}"
                  :files files
                  :node-repl "(require 'cljs.repl.node) (cider.piggieback/cljs-repl (cljs.repl.node/repl-env))"
                  :clojure-ns "user"}
     :shadow-cljs {:tool "shadow-cljs"
                   :project "{:source-paths [\"src\" \"test\"] :builds {}}"
                   :files (assoc files "package.json" "{\"private\": true, \"devDependencies\": {\"shadow-cljs\": \"3.5.7\"}}")
                   :node-repl "(shadow.cljs.devtools.api/node-repl)"
                   :clojure-ns "shadow.user"}}))

(def ^:private on-each-server
  "Calls (f server) for each server, testing it under its name."
  (partial servers/on-each-server servers))

(defn- run-cljs
  "Runs neorepl command with args in the cljs session on server."
  [server command & args]
  (apply run server command "-s" "cljs" args))

(defn- await-runtime
  "Waits for the cljs session's runtime to answer, for 30s at most, and
  returns its last answer. shadow-cljs's node-repl returns before Node is
  up, and until then an evaluation only gets \"No available JS runtime\" on
  stderr. It asks what only ClojureScript answers, as a session the REPL
  didn't start in stays in Clojure, which answers (+ 1 2) too."
  [server]
  (loop [tries 150]
    (let [answer (run-cljs server "eval" "(js/parseInt \"3\")")]
      (if (or (= "3\n" (:out answer)) (= 1 tries))
        answer
        (do (Thread/sleep 200)
            (recur (dec tries)))))))

(use-fixtures :once
  (fn [f]
    ;; The cljs session, in ClojureScript from here on, is the only one
    ;; with a Node runtime: piggieback's share a queue for their results,
    ;; so two would get each other's.
    (servers/call-with-servers servers
                               (fn [server]
                                 (let [started (run-cljs server "eval" (get-in servers [server :node-repl]))
                                       answer (await-runtime server)]
                                   ;; Without a runtime every test fails, in
                                   ;; Clojure, so this says why, once.
                                   (is (= "3\n" (:out answer))
                                       (str (name server) "'s ClojureScript REPL didn't start: "
                                            (pr-str started) ", then " (pr-str answer))))
                                 (run-cljs server "eval" "(require 'demo.core-test)"))
                               f)))

(deftest eval-test
  (on-each-server
   (fn [server]
     ;; Every form, though piggieback only evaluates an eval's first.
     (is (= {:status 0 :out "3\n42\n" :err ""} (run-cljs server "eval" "(+ 1 2) (js/parseInt \"42\")")))
     (testing "with an error"
       (let [{:keys [status err]} (run-cljs server "eval" "(demo.core/boom 1)")]
         (is (= 1 status))
         (is (str/includes? err "boom")))))))

(deftest lookups-test
  (on-each-server
   (fn [server]
     (is (= {:status 0 :out "demo.core/greet\n([who])\n  Says hi.\n" :err ""}
            (run-cljs server "doc" "demo.core/greet")))
     (is (str/starts-with? (:out (run-cljs server "doc" "clj->js")) "cljs.core/clj->js\n"))
     (is (= {:status 0 :out (str (io/file (project-dir server) "src" "demo" "core.cljc") ":3:1\n") :err ""}
            (run-cljs server "where" "demo.core/greet")))
     (is (str/starts-with? (:out (run-cljs server "complete" "demo.core/gr")) "demo.core/greet"))
     (is (= {:status 0 :out "(let [t (cljs.core/js-this)] t)\n" :err ""}
            (run-cljs server "macroexpand" "(this-as t t)"))))))

(deftest test-test
  (on-each-server
   (fn [server]
     (let [{:keys [status out]} (run-cljs server "test" "demo.core-test")
           file (io/file (project-dir server) "test" "demo" "core_test.cljc")]
       (is (= 1 status))
       (is (str/includes? out (str "FAIL in demo.core-test/failing-test (" file ":10)\n")))
       ;; At the test's line, ClojureScript knowing none for an error
       ;; outside an assertion.
       (is (str/includes? out (str "ERROR in demo.core-test/error-test (" file ":12)\n")))
       (is (str/includes? out "printed by failing-test\n"))
       (is (str/ends-with? out "Ran 3 tests containing 3 assertions.\n1 failure, 1 error.\n")))
     (testing "rerunning what didn't pass"
       (let [{:keys [status out]} (run-cljs server "retest")]
         (is (= 1 status))
         (is (str/ends-with? out "Ran 2 tests containing 2 assertions.\n1 failure, 1 error.\n")))))))

(deftest repl-test
  (on-each-server
   (fn [server]
     (let [{:keys [status out]} (run-with server (str ",sessions cljs\n(js/parseInt \"7\")\n"
                                                      ",set print-length 3\n(range 10)\n,set pretty off\n(range 10)\n"
                                                      ",ns demo.core\n(greet \"r\")\n"
                                                      ",ns demo.a\n,ns demo.a-test\n(a/f)\n"
                                                      ",ns demo.b\n,test demo.b-test\n"
                                                      ",ns cljs.user\n,sessions -\n(+ 1 2)\n")
                                          "repl")]
       (is (= 0 status))
       (is (str/includes? out "cljs.user=> 7\n"))
       ;; Printed again on the JVM, which takes the limit.
       (is (str/includes? out "cljs:cljs.user=> (0 1 2 ...)\ncljs:cljs.user=> pretty off\ncljs:cljs.user=> (0 1 2 ...)\n"))
       (is (str/includes? out "demo.core=> \"hi r\"\n"))
       (is (str/includes? out "demo.a-test=> :a\n"))
       (is (str/includes? out "demo.b=> Ran 1 test containing 1 assertion.\n0 failures, 0 errors.\n"))
       ;; Back in the REPL's own session, in Clojure.
       (is (str/includes? out (str (get-in servers [server :clojure-ns]) "=> 3\n")))))))

(deftest system-output-test
  ;; What threads no session started print, on the JVM that runs
  ;; ClojureScript's tools.
  (testing "piggieback"
    (is (servers/forwards-output? :piggieback "clojure")))
  (testing "but not shadow-cljs, which brings nREPL 1.3.1, from before nREPL forwarded it"
    (is (not (servers/forwards-output? :shadow-cljs "shadow-cljs")))))
