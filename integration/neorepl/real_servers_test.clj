(ns neorepl.real-servers-test
  "neorepl against real servers: nREPL with cider-nrepl, started the way
  `neorepl server` starts it in a deps.edn project and in a Leiningen one,
  and babashka's, with the bb on the PATH. The fakes the unit tests use
  behave like the cider-nrepl they were written against, which says
  nothing about the next one, or the next bb."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.discovery :as discovery]
            [neorepl.features :as features]
            [neorepl.jack-in :as jack-in]
            [neorepl.json :as json]
            [neorepl.servers :as servers :refer [*dir* project-dir run run-with]]
            [neorepl.test-server :as test-server :refer [spit-file with-client]]))

(def ^:private servers
  "The servers the tests run against, by name, with the build tool that
  starts each and the project file it gets."
  (let [files (servers/sources "clj")]
    {:clojure {:tool "clojure" :project "{:paths [\"src\" \"test\"]}" :files files}
     :lein {:tool "lein" :project "(defproject demo \"0.1.0\" :dependencies [[org.clojure/clojure \"1.12.6\"]])" :files files}
     :bb {:tool "bb" :project "{:paths [\"src\" \"test\"]}" :files files}}))

(def ^:private on-each-server
  "Calls (f server) for each server, testing it under its name."
  (partial servers/on-each-server servers))

(use-fixtures :once
  (fn [f]
    (servers/call-with-servers servers #(run % "eval" "(require 'demo.core-test)") f)))

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
       (is (= {:status 0 :out "42\n" :err ""} (run server "eval" "-s" "eval" "x"))))
     (testing "in a namespace, leaving the session in its own"
       (is (= {:status 0 :out "\"hi ns\"\n" :err ""}
              (run server "eval" "-s" "in-ns" "--ns" "demo.core" "(greet \"ns\")")))
       (is (= {:status 0 :out "\"user\"\n" :err ""}
              (run server "eval" "-s" "in-ns" "(str *ns*)"))))
     (testing "the top-level form at a line of a file, in its namespace"
       ;; Off the classpath, which a whole-project reload goes over.
       (let [file (spit-file (project-dir server) "scratch/lines.clj"
                             "(ns demo.lines\n  (:require [demo.core :as core]))\n\n(defn twice [who]\n  (str (core/greet who) \"!\"))\n")]
         (is (= {:status 0 :out "#'demo.lines/twice\n" :err ""}
                (run server "eval" "-s" "lines" "-f" (str file) "--line" "5:3")))
         (is (= {:status 0 :out "\"hi x!\"\n" :err ""}
                (run server "eval" "-s" "lines" "(demo.lines/twice \"x\")")))
         (is (= {:status 0 :out (str file ":4:1\n") :err ""}
                (run server "where" "demo.lines/twice"))))))))

(deftest lookups-test
  (on-each-server
   (fn [server]
     (let [file (io/file (project-dir server) "src" "demo" "core.clj")]
       (is (= {:status 0 :out "#'demo.core/welcome\n" :err ""} (run server "load" (str file))))
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
       (is (str/includes? out (str "ERROR in demo.core-test/error-test (" file ":" (if (= :bb server) 12 13) ")\n")))
       (is (str/includes? out "error: clojure.lang.ExceptionInfo: boom {:x 1}"))
       (is (str/ends-with? out "Ran 3 tests containing 3 assertions.\n1 failure, 1 error.\n"))
       ;; bb sends what tests print under the session's previous message,
       ;; to a connection that's gone, unless the session's new
       ;; (babashka#2265).
       (when-not (= :bb server)
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
    (is (= "Tracing demo.core/greet\n" (:out (run :clojure "trace" "demo.core/greet"))))
    (is (str/includes? (:out (run :clojure "eval" "(demo.core/greet \"t\")")) "└─→ \"hi t\"\n"))
    (is (= "Stopped tracing demo.core/greet\n" (:out (run :clojure "untrace" "demo.core/greet"))))
    (testing "a call that throws"
      (run :clojure "trace" "demo.core/boom")
      (is (str/includes? (:out (run :clojure "eval" "(demo.core/boom 3)")) "└─✗ clojure.lang.ExceptionInfo: boom\n"))
      (run :clojure "untrace" "demo.core/boom")))
  (testing "babashka"
    (is (= {:status 1 :out "" :err "neorepl: Tracing takes cider-nrepl on the server\n"}
           (run :bb "trace" "demo.core/greet")))))

(deftest java-doc-test
  (testing "cider-nrepl, with the Javadoc"
    (is (str/includes? (:out (run :clojure "doc" "java.util.ArrayList"))
                       "Resizable-array implementation of the List interface."))
    (is (str/starts-with? (:out (run :clojure "doc" ".toUpperCase"))
                          "java.lang.String/toUpperCase\n([this locale] [this])\n")))
  (testing "babashka, without it"
    (is (= {:status 0 :out "java.util.ArrayList\n" :err ""} (run :bb "doc" "java.util.ArrayList")))))

(deftest refs-test
  (testing "cider-nrepl"
    (let [dir (project-dir :clojure)
          core (io/file dir "src" "demo" "core.clj")
          core-test (io/file dir "test" "demo" "core_test.clj")
          ;; Each line's name and location.
          rows (fn [& args]
                 (->> (apply run :clojure args) :out str/split-lines (map #(str/split % #"\s+")) set))]
      (is (= #{["demo.core/welcome" (str core ":11:1")]
               ["demo.core-test/greet-test" (str core-test ":5:1")]
               ["demo.core-test/failing-test" (str core-test ":8:1")]}
             (rows "refs" "demo.core/greet")))
      (testing "and the functions a function uses"
        (is (contains? (rows "refs" "--deps" "demo.core/welcome") ["demo.core/greet" (str core ":3:1")])))
      (testing "but not for a var that isn't there, which cider-nrepl takes for one nothing uses"
        (is (= {:status 1 :out "" :err "neorepl: Nothing called demo.core/nope in user\n"}
               (run :clojure "refs" "demo.core/nope"))))))
  (testing "babashka"
    (is (= {:status 1 :out "" :err "neorepl: Cross-references take cider-nrepl on the server\n"}
           (run :bb "refs" "demo.core/greet")))))

(deftest reload-test
  (let [file (io/file (project-dir :clojure) "src" "demo" "core.clj")]
    (run :clojure "reload")
    (try
      (spit file "\n(defn added [] :added)\n" :append true)
      (is (= {:status 0 :out "Reloaded demo.core, demo.core-test\n" :err ""} (run :clojure "reload")))
      (is (= ":added\n" (:out (run :clojure "eval" "(demo.core/added)"))))
      (finally
        (spit file servers/core)
        (run :clojure "reload")))))

(deftest eval-reload-test
  (testing "cider-nrepl"
    ;; Gone after, so the other tests have the project as it was.
    (let [file (io/file (project-dir :lein) "src" "demo" "reloaded.clj")
          write! #(spit file (str "(ns demo.reloaded)\n\n(defonce state (atom 0))\n\n(defn f [] " % ")\n"))
          run-reloading #(run :lein "eval" "-s" "reloading" "--reload" %)]
      (try
        (write! ":before")
        ;; Reloading first, where clj-reload starts noting what changes.
        (run-reloading "(require 'demo.reloaded) (swap! demo.reloaded/state inc)")
        (write! ":after")
        (is (= {:status 0 :out "[:after 1]\n" :err ""} (run-reloading "[(demo.reloaded/f) @demo.reloaded/state]")))
        (testing "and not running the code when a file doesn't compile"
          (write! "nope")
          (let [{:keys [status out err]} (run-reloading "(swap! demo.reloaded/state inc)")]
            (is (= 1 status))
            (is (= "" out))
            (is (str/includes? err "Unable to resolve symbol: nope"))
            (is (str/ends-with? err "Reloading what changed failed\n")))
          (write! ":fixed")
          (is (= {:status 0 :out "[:fixed 1]\n" :err ""} (run-reloading "[(demo.reloaded/f) @demo.reloaded/state]"))))
        (testing "with the session back in the namespace it's in, which a reload makes anew"
          (run-reloading "(in-ns 'demo.reloaded)")
          (spit file "\n(defn added [] :added)\n" :append true)
          (is (= {:status 0 :out ":added\n" :err ""} (run-reloading "(added)"))))
        (finally
          (io/delete-file file true)))))
  (testing "babashka"
    (is (= {:status 1 :out "" :err "neorepl: Reloading what changed takes cider-nrepl on the server\n"}
           (run :bb "eval" "--reload" "1")))))

(deftest namespaces-test
  ;; What the namespace browser shows, from cider-nrepl's ops, and from
  ;; evaluating code on babashka.
  (on-each-server
   (fn [server]
     (with-client [client {:port (discovery/read-port (io/file (project-dir server) ".nrepl-port"))}]
       (let [names (features/loaded-namespaces client {} {})]
         (is (every? (set names) ["clojure.core" "demo.core"]))
         (is (not-any? #(re-find #"^(cider\.nrepl|nrepl)\." %) names)))
       (is (= [{:name "boom" :arglists "([x])"}
               {:name "greet" :arglists "([who])" :doc "Says hi."}
               {:name "welcome" :arglists "([who])"}]
              (features/ns-vars client "demo.core" {} {})))))))

(deftest specs-test
  ;; What the spec browser shows: the list from cider-nrepl's spec-list,
  ;; or from the registry on babashka, and the forms evaluated.
  (on-each-server
   (fn [server]
     (run server "eval" "(require '[clojure.spec.alpha :as s]) (s/def :demo/age pos-int?) (s/def :demo/person (s/keys :req [:demo/age]))")
     (with-client [client {:port (discovery/read-port (io/file (project-dir server) ".nrepl-port"))}]
       (is (every? (set (features/specs client {})) [":demo/age" ":demo/person"]))
       (is (= "(s/keys :req [:demo/age])\n" (features/spec-form client ":demo/person" 80 {})))
       (testing "and an example, from cider-nrepl's spec-example, which takes test.check, as projects rarely have"
         (if (= :bb server)
           (is (re-matches #"#:demo\{:age \d+\}\n" (features/spec-example client ":demo/person" 80 {})))
           (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^Generating examples takes test.check on the server$"
                                 (features/spec-example client ":demo/person" 80 {})))))))))

(deftest edit-test
  ;; The editor's a shell script.
  (when-not (test-server/windows?)
    (testing "cider-nrepl finding the file of the namespace the REPL's in"
      (let [edited (io/file *dir* "edited")
            editor (doto (spit-file *dir* "vi" (str "#!/bin/sh\necho \"$@\" > '" edited "'\n"))
                     (.setExecutable true))]
        (is (= 0 (:status (test-server/run-in {:dir (str (project-dir :clojure))
                                               :sessions-file (servers/sessions-file)
                                               :editor-command (str editor)}
                                              ",ns demo.core\n,edit\n" "repl"))))
        (is (= (str "+1 " (io/file (project-dir :clojure) "src" "demo" "core.clj") "\n") (slurp edited)))))))

(deftest repl-test
  (on-each-server
   (fn [server]
     (let [{:keys [status out]} (run-with server ",ns demo.core\n(greet \"r\")\n,test demo.core-test/failing-test\n" "repl")]
       (is (= 0 status))
       (is (str/includes? out "demo.core=> \"hi r\"\n"))
       ;; bb's comes as output nobody asked for, which the REPL prints from
       ;; another thread, where *out* isn't this one.
       (when-not (= :bb server)
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

(deftest other-cider-nrepl-test
  (testing "a project with a cider-nrepl of its own gets a word about it"
    (doseq [[tool project & args]
            [["clojure" "{:aliases {:old {:extra-deps {cider/cider-nrepl {:mvn/version \"0.63.1\"}}}}}" "--aliases" ":old"]
             ["lein" "(defproject old \"0.1.0\" :dependencies [[org.clojure/clojure \"1.12.6\"]] :plugins [[cider/cider-nrepl \"0.63.1\"]])"]]]
      (testing tool
        (let [dir (io/file *dir* (str "old-" tool))]
          (spit-file dir (:file (jack-in/tool tool)) project)
          (let [{:keys [status out err]} (apply test-server/run-in {:dir (str dir)
                                                                   :sessions-file (servers/sessions-file)}
                                                "(+ 1 2)\n" "repl" args)]
            (is (= 0 status))
            (is (= "user=> 3\nuser=> \n" out))
            (is (str/includes? err (str "neorepl: The server runs cider-nrepl 0.63.1 from the project's or the user's "
                                        "settings, rather than the " (:cider-nrepl jack-in/versions) " jack-in adds\n")))))))))
