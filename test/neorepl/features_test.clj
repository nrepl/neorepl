(ns neorepl.features-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [neorepl.features :as features]
            [neorepl.nrepl :as nrepl]
            [neorepl.render :as render]
            [neorepl.test-server :as test-server :refer [*port* with-client]]
            [nrepl.middleware :as middleware]
            [nrepl.server :as server]
            [nrepl.transport :as transport]))

(use-fixtures :once (test-server/fixture))

(def ^:private refresh-fails? (atom false))

(def ^:private test-requests
  "The test ops wrap-fake-cider got, and their var-queries."
  (atom []))

(defn- wrap-fake-cider
  "Answers info, complete and apropos the way cider-nrepl does, with canned
  results, to tell its rung of the ladder from the others."
  [handler]
  (fn [{:keys [op sym] :as msg}]
    (case op
      "info" (case sym
               "map" (transport/respond-to msg :status "done" :ns "clojure.core" :name "map"
                                           :arglists-str "[f]\n[f coll]" :doc "Cider's map."
                                           :file "file:/tmp/a%20b/core.clj" :line 1)
               "mapv" (transport/respond-to msg :status "done" :ns "clojure.core" :name "mapv"
                                            :file "jar:file:/x.jar!/clojure/core.clj" :line 2)
               "made-at-the-repl" (transport/respond-to msg :status "done" :ns "user" :name "made-at-the-repl"
                                                        :file "NO_SOURCE_PATH" :line 1)
               "if" (transport/respond-to msg :status "done" :name "if" :special-form "true"
                                          :forms-str "(if test then else?)")
               "broken" (transport/respond-to msg :status ["done" "info-error"])
               ;; Says which session it was asked in.
               "session" (transport/respond-to msg :status "done" :ns "user" :name "session"
                                               :doc (:session msg))
               (transport/respond-to msg :status ["done" "no-info"]))
      "complete" (transport/respond-to msg :status "done"
                                       :completions [{:candidate "map" :type "function" :ns "clojure.core"}])
      "apropos" (transport/respond-to msg :status "done"
                                      :apropos-matches [{:name "clojure.core/map" :type "function"
                                                         :doc "Cider's map.\nMore."}])
      "macroexpand" (transport/respond-to msg :status "done" :expansion (str "Cider's " (:expander msg)))
      ("test-var-query" "retest")
      (do (swap! test-requests conj (select-keys msg [:op :var-query :stream]))
          (when (= "true" (:stream msg))
            (transport/respond-to msg :test-event {:type "begin-ns" :ns "neorepl.sample-suite"}))
          (transport/respond-to msg :out "testing\n")
          (when (= "true" (:stream msg))
            (transport/respond-to msg :test-event {:type "end-ns" :ns "neorepl.sample-suite"
                                                   :elapsed-time {:ms 5 :humanized "Completed in 5 ms"}}))
          (transport/respond-to msg
                                :summary {:ns 1 :var 2 :test 3 :pass 1 :fail 1 :error 1}
                                :elapsed-time {:ms 5 :humanized "Completed in 5 ms"}
                                :results {"neorepl.sample-suite"
                                          {"passing" [{:type "pass" :index 0 :ns "neorepl.sample-suite"
                                                       :var "passing" :context [] :message ""}]
                                           "failing" [{:type "pass" :index 0 :ns "neorepl.sample-suite"
                                                       :var "failing" :context [] :message ""}
                                                      {:type "fail" :index 1 :ns "neorepl.sample-suite"
                                                       :var "failing" :context "with a context"
                                                       :message "adds up" :expected "3\n" :actual "2\n"
                                                       :file "sample_suite.clj" :line 12}]
                                           "erring" [{:type "error" :index 0 :ns "neorepl.sample-suite"
                                                      :var "erring" :context [] :message ""
                                                      :expected "(= 1 (throw (ex-info \"boom\" {})))\n"
                                                      :error "clojure.lang.ExceptionInfo: boom {}"
                                                      :file "sample_suite.clj" :line 15}]}})
          (transport/respond-to msg :status "done"))
      "refresh" (if @refresh-fails?
                  ;; cider-nrepl prints the error, as the REPL would.
                  (do (transport/respond-to msg :reloading ["broken.ns"])
                      (transport/respond-to msg :err "Syntax error compiling at (broken/ns.clj:1:1).\n")
                      (transport/respond-to msg :status "error" :error-ns "broken.ns")
                      (transport/respond-to msg :status "done"))
                  (do (transport/respond-to msg :reloading ["changed.ns"])
                      (transport/respond-to msg :err "Reflection warning, changed/ns.clj:3:1\n")
                      (transport/respond-to msg :status ["ok" "done"])))
      "test-stacktrace" (do (swap! test-requests conj (select-keys msg [:op :ns :var :index]))
                            (transport/respond-to msg :class "clojure.lang.ExceptionInfo" :message "boom" :data "{}"
                                                  :stacktrace [{:name "neorepl.sample_suite$fn__1/invokeStatic"
                                                                :var "neorepl.sample-suite/fn" :file "sample_suite.clj"
                                                                :line 15 :flags ["project" "clj"]}
                                                               {:name "orchard.test$test_var/invokeStatic"
                                                                :var "orchard.test/test-var" :file "test.clj"
                                                                :line 362 :flags ["dependency" "clj"]}])
                            (transport/respond-to msg :status "done"))
      "analyze-last-stacktrace" (do (transport/respond-to msg :class "clojure.lang.ExceptionInfo" :message "boom"
                                                          :data "{:k 1}" :phase "execution"
                                                          :stacktrace [{:name "user$f/invoke" :var "user/f" :file "x.clj" :line 1
                                                                        :file-url []}
                                                                       {:name "my.app$g/invoke" :var "my.app/g" :file "app.clj" :line 5
                                                                        :file-url "file:/src/my/app.clj"}
                                                                       ;; As babashka's server has them.
                                                                       {:name "user/g" :ns "user" :fn "g/fn"
                                                                        :file "y.clj" :line 2}])
                                    (transport/respond-to msg :class "java.lang.Exception" :message []
                                                          :stacktrace [])
                                    (transport/respond-to msg :status "done"))
      (handler msg))))

(middleware/set-descriptor! #'wrap-fake-cider {:handles {"info" {} "complete" {} "apropos" {}
                                                         "macroexpand" {} "analyze-last-stacktrace" {} "test-stacktrace" {}
                                                         "refresh" {} "test-var-query" {} "retest" {}}})

(def ^:private user
  {:ns "user"})

(defn- without
  "client, pretending its server doesn't have ops."
  [client & ops]
  (update client :ops #(apply disj % ops)))

(deftest info-test
  (with-client [client {:port *port*}]
    (doseq [[rung client] {"lookup" client "eval" (without client "lookup")}]
      (testing rung
        (let [info (features/info client "map" user)]
          (is (= {:ns "clojure.core" :name "map"}
                 (select-keys info [:ns :name])))
          (is (= ["[f]" "[f coll]"] (take 2 (:arglists info))))
          (is (str/starts-with? (:doc info) "Returns a lazy sequence"))
          (is (pos? (:line info))))
        (testing "macros"
          (is (:macro (features/info client "when" user))))
        (testing "nothing there"
          (is (nil? (features/info client "no-such-thing" user))))
        (testing "nowhere for what was made at a REPL"
          (nrepl/request client {:op "eval" :ns "user" :code "(defn made-at-the-repl [])"})
          (is (= {:ns "user" :name "made-at-the-repl"}
                 (select-keys (features/info client "made-at-the-repl" user) [:ns :name :file :line]))))
        (testing "symbols are only ever symbols"
          (is (nil? (features/info client "(System/exit 1)" user))))
        (testing "in a session, leaving its *1 alone"
          (let [session (nrepl/clone-session client)]
            (nrepl/request client {:op "eval" :session session :code "42"})
            (is (= "map" (:name (features/info client "map" (assoc user :session session)))))
            (is (= ["42"] (keep :value (nrepl/request client {:op "eval" :session session :code "*1"}))))))))
    (testing "special forms"
      (is (= {:special-form true :forms ["(if test then else?)"]}
             (select-keys (features/info client "if" user) [:special-form :forms])))
      ;; On babashka they're macros, without forms.
      (when-not (System/getProperty "babashka.version")
        (testing "with nested shapes and bare symbols"
          (is (= "(Classname/staticMethod args*) Classname/staticField"
                 (->> (features/info client "." user) :forms (take-last 2) (str/join " ")))))))))

(deftest complete-test
  (with-client [client {:port *port*}]
    ;; Only the JVM's nREPL says what type a candidate is.
    (is (some #(= {:candidate "mapcat" :ns "clojure.core"} (dissoc % :type))
              (features/complete client "mapc" user)))
    (testing "no completion without the ops for it"
      (is (nil? (features/complete (without client "completions") "mapc" user))))))

(deftest source-test
  (with-client [client {:port *port*}]
    (is (str/starts-with? (features/source client "when" user) "(defmacro when"))
    (is (nil? (features/source client "no-such-thing" user)))))

(deftest apropos-test
  (with-client [client {:port *port*}]
    (is (some #{{:name "clojure.core/mapcat"}} (features/apropos client "^mapc")))))

(deftest expand-test
  (with-client [client {:port *port*}]
    (is (= "(if x (do y))" (features/expand client "(when x y)" user false)))
    (is (str/includes? (features/expand client "(when-let [a 1] a)" user true) "let*"))
    (testing "one form, only ever read, not evaluated"
      (are [text] (= ::features/failed
                     (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (features/expand client text user false))))))
        "#=(System/exit 1)"
        "(when x y) (+ 1 2)"
        "x) (spit \"/tmp/p\" 1"
        ""))))

(deftest last-error-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)]
      (testing "nothing yet"
        (is (nil? (features/last-error client session))))
      (nrepl/request client {:op "eval" :session session
                             :code "(defn boom [] (throw (ex-info \"boom\" {:k 1} (ArithmeticException. \"inner\")))) (boom)"})
      (let [[outer inner :as causes] (features/last-error client session)]
        (is (= 2 (count causes)))
        (is (= {:class "clojure.lang.ExceptionInfo" :message "boom" :data "{:k 1}"}
               (select-keys outer [:class :message :data])))
        (is (= {:class "java.lang.ArithmeticException" :message "inner"}
               (select-keys inner [:class :message])))
        (is (seq (:stacktrace inner)))
        ;; On babashka, the frames are its interpreter's.
        (when-not (System/getProperty "babashka.version")
          (testing "with the vars of Clojure's frames"
            (is (= "user/boom" (:var (first (:stacktrace outer)))))
          (is (= "user/boom" (:var (first (:stacktrace inner)))))
          (is (nil? (:var (first (filter #(str/starts-with? (:name %) "clojure.lang.Compiler/") (:stacktrace inner))))))))
        (testing "with frames that follow on in the same function flagged dup"
          (let [shown (remove #(some #{"dup" "tooling"} (:flags %)) (:stacktrace inner))
                fn-of #(first (str/split (:name %) #"/"))]
            (is (every? (fn [[a b]] (not= (fn-of a) (fn-of b))) (partition 2 1 shown))))))
      (testing "leaves the session's *1 alone"
        (is (= ["#'user/boom"] (keep :value (nrepl/request client {:op "eval" :session session :code "*1"}))))))))

(deftest frame-var-test
  (are [frame expected] (= expected (#'features/frame-var frame))
    {:var "user/f" :name "user$f/invoke"} "user/f"
    {:ns "user" :name "user/f"} "user/f"
    {:ns "user"} nil
    {:name "my_app.core$f$fn__123/invoke"} "my-app.core/f"
    {:name "user$eval123/invokeStatic"} "user/eval123"
    {:name "java.util.concurrent.ThreadPoolExecutor$Worker/run"} nil
    {:name "clojure.lang.Compiler/eval"} nil
    {} nil))

(deftest above-test-runner-test
  (testing "a test's frames, down to the runner's"
    (is (= [{:name "my.app-test/fn"}]
           (#'features/above-test-runner [{:name "my.app-test/fn"} {:name "clojure.test$test_var/invokeStatic"}
                                          {:name "my.app-test/fixture"}]))))
  (testing "or all of them, for an error in the runner"
    (is (= [{:name "orchard.test$test_var/invoke"} {:name "nrepl.SessionThread/run"}]
           (#'features/above-test-runner [{:name "orchard.test$test_var/invoke"} {:name "nrepl.SessionThread/run"}])))))

(deftest test-results-test
  (testing "ClojureScript's errors, which come with the stack"
    (is (= [{:type "error" :ns "app.core-test" :var "errs" :expected "(= 1 (throw (ex-info \"boom\" {})))"
             :actual "#error {:message \"boom\", :data {}}" :error "boom" :line 6 :index 0}]
           (#'features/test-results {:app.core-test {:errs [{:type "error" :index 0 :ns "app.core-test" :var "errs"
                                                             :expected "(= 1 (throw (ex-info \"boom\" {})))"
                                                             :actual "#error {:message \"boom\", :data {}}"
                                                             :error {:message "boom" :stack "Error: boom\n    at x.js:1:2"}
                                                             :line 6}]}})))))

(deftest reload-test
  (with-client [client {:port *port*}]
    (testing "the namespaces given"
      (is (= {:reloaded ["clojure.set"]} (features/reload client ["clojure.set"]))))
    (testing "what changed, which takes cider-nrepl"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes cider-nrepl" (features/reload client []))))))

(deftest run-tests-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)
          run #(features/run-tests client session % {})
          sample-file? #(str/ends-with? (str/replace (str %) "\\" "/") "test/neorepl/sample_suite.clj")]
      (testing "namespaces, through clojure.test without the test ops"
        (let [{:keys [status out report]} (run ["neorepl.sample-suite"])
              {:keys [summary failures]} report]
          (is (= "failed" status))
          (is (= "passing prints\n" (test-server/lf out)))
          (is (= {:ns 1 :var 3 :test 3 :pass 1 :fail 1 :error 1} summary))
          (is (= [{:type "error" :ns "neorepl.sample-suite" :var "erring"
                   :expected "(= 1 (throw (ex-info \"boom\" {})))" :error "clojure.lang.ExceptionInfo: boom {}"}
                  {:type "fail" :ns "neorepl.sample-suite" :var "failing" :context "with a context"
                   :message "adds up" :expected "(= 3 (+ 1 1))" :actual "(not (= 3 2))" :line 12}]
                 (map #(dissoc % :file :causes (when (= "error" (:type %)) :line)) failures)))
          (testing "with the files the tests are in"
            (is (every? (comp sample-file? :file) failures)))
          (testing "and the error's stack trace"
            (let [[{:keys [class message stacktrace]}] (:causes (first failures))]
              (is (= ["clojure.lang.ExceptionInfo" "boom"] [class message]))
              (is (seq stacktrace))))))
      (testing "a fixture failing outside the tests"
        (is (= [{:type "error" :ns "neorepl.sample-fixture" :error "clojure.lang.ExceptionInfo: no database {}"}]
               (map #(select-keys % [:type :ns :var :error]) (:failures (:report (run ["neorepl.sample-fixture"])))))))
      (testing "tests"
        (is (= {:ns 1 :var 1 :test 1 :pass 1 :fail 0 :error 0}
               (:summary (:report (run ["neorepl.sample-suite/passing"]))))))
      (testing "the session's *1 stays put"
        (nrepl/request client {:op "eval" :session session :code ":before"})
        (run ["neorepl.sample-suite/passing"])
        (is (= [":before"] (keep :value (nrepl/request client {:op "eval" :session session :code "*1"})))))
      (are [names message] (thrown-with-msg? clojure.lang.ExceptionInfo message (run names))
        ["neorepl.sample-suite/nope"] #"No test called neorepl.sample-suite/nope"
        ["neorepl.sample-suite" "neorepl.sample-suite/passing"] #"not both"
        ["no.such-suite"] #"no/such_suite"
        ;; They go into the code as they are.
        ["neorepl.sample-suite) (System/exit 1"] #"Not the name of a namespace or a test"
        ["neorepl.sample-suite/passing 1"] #"Not the name of a namespace or a test"
        [] #"Running every test takes cider-nrepl"
        :failed #"Rerunning the tests that failed takes cider-nrepl"))))

(deftest error-test
  (with-client [client {:port *port*}]
    (testing "errors aren't the same as finding nothing"
      (are [f] (= ::features/failed (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (f client))))))
        #(features/source % "map" {:ns "no.such.ns"})
        #(features/apropos % "(")
        #(features/reload % ["no.such.ns"])))))

(deftest cider-test
  (let [srv (server/start-server :port 0 :handler (server/default-handler #'wrap-fake-cider))]
    (try
      (with-client [client {:port (:port srv)}]
        (testing "cider-nrepl's ops come first"
          (is (= {:ns "clojure.core" :name "map" :arglists ["[f]" "[f coll]"] :doc "Cider's map."
                  :file (str (io/file "/tmp/a b/core.clj")) :line 1}
                 (features/info client "map" user)))
          (testing "with files in jars as they are, and none for what was made at a REPL"
            (is (= "jar:file:/x.jar!/clojure/core.clj" (:file (features/info client "mapv" user))))
            (is (= {:ns "user" :name "made-at-the-repl"} (features/info client "made-at-the-repl" user))))
          (is (= {:name "if" :special-form true :forms ["(if test then else?)"]}
                 (features/info client "if" user)))
          (is (nil? (features/info client "no-such-thing" user)))
          (testing "in the session asked for, where it's in ClojureScript, say"
            (let [session (nrepl/clone-session client)]
              (is (= session (:doc (features/info client "session" (assoc user :session session)))))))
          (is (= ::features/failed
                 (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (features/info client "broken" user)))))))
          (is (= [{:candidate "map" :type "function" :ns "clojure.core"}]
                 (features/complete client "ma" user)))
          (is (= [{:name "clojure.core/map" :type "function" :doc "Cider's map.\nMore."}]
                 (features/apropos client "map")))
          (is (= "Cider's macroexpand-all" (features/expand client "(when x y)" user true)))
          (is (= {:reloaded ["changed.ns"] :output "Reflection warning, changed/ns.clj:3:1\n"}
                 (features/reload client [])))
          (reset! refresh-fails? true)
          (try
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refresh: Syntax error compiling at \(broken/ns\.clj"
                                  (features/reload client [])))
            (finally
              (reset! refresh-fails? false)))
          (testing "tests"
            (reset! test-requests [])
            (let [session (nrepl/clone-session client)
                  out (atom [])
                  {:keys [status report]} (features/run-tests client session ["neorepl.sample-suite"]
                                                              {:on-response #(some->> (:out %) (swap! out conj))})]
              (is (= "failed" status))
              (testing "with each namespace's Testing line, from the events it streams"
                (is (= ["Testing neorepl.sample-suite\n" "testing\n"] @out)))
              (is (= {:summary {:ns 1 :var 2 :test 3 :pass 1 :fail 1 :error 1}
                      :failures [{:type "error" :ns "neorepl.sample-suite" :var "erring"
                                  :expected "(= 1 (throw (ex-info \"boom\" {})))"
                                  :error "clojure.lang.ExceptionInfo: boom {}"
                                  :causes [{:class "clojure.lang.ExceptionInfo" :message "boom" :data "{}"
                                            :stacktrace [{:name "neorepl.sample_suite$fn__1/invokeStatic"
                                                          :var "neorepl.sample-suite/fn" :file "sample_suite.clj"
                                                          :line 15 :flags ["project" "clj"]}]}]
                                  :file "sample_suite.clj" :line 15}
                                 {:type "fail" :ns "neorepl.sample-suite" :var "failing" :context "with a context"
                                  :message "adds up" :expected "3" :actual "2"
                                  :file "sample_suite.clj" :line 12}]
                      :elapsed-ms 5}
                     report))
              (features/run-tests client session ["neorepl.sample-suite/passing" "neorepl.sample-suite/failing"] {})
              (features/run-tests client session [] {})
              (features/run-tests client session :failed {})
              (is (= [{:op "test-var-query" :var-query {:ns-query {:exactly ["neorepl.sample-suite"]}} :stream "true"}
                      {:op "test-var-query" :var-query {:exactly ["neorepl.sample-suite/passing"
                                                                  "neorepl.sample-suite/failing"]}
                       :stream "true"}
                      {:op "test-var-query" :var-query {:ns-query {:project? "true" :load-project-ns? "true"}}
                       :stream "true"}
                      {:op "retest" :stream "true"}]
                     (remove (comp #{"test-stacktrace"} :op) @test-requests)))
              (testing "with the stack trace of each error, by its index"
                (is (= {:op "test-stacktrace" :ns "neorepl.sample-suite" :var "erring" :index 0}
                       (some #(when (= "test-stacktrace" (:op %)) %) @test-requests))))))
          (is (= [{:class "clojure.lang.ExceptionInfo" :message "boom" :data "{:k 1}"
                   :stacktrace [{:name "user$f/invoke" :file "x.clj" :line 1 :var "user/f"}
                                {:name "my.app$g/invoke" :file "app.clj" :line 5 :var "my.app/g"
                                 :path (str (java.io.File. (java.net.URI. "file:/src/my/app.clj")))}
                                {:name "user/g" :file "y.clj" :line 2 :var "user/g"}]}
                  ;; cider-nrepl sends nil as an empty list.
                  {:class "java.lang.Exception" :message nil :data nil :stacktrace []}]
                 (features/last-error client (nrepl/clone-session client))))))
      (finally
        (server/stop-server srv)))))

(deftest inspect-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)
          text #(render/inspector-text (:lines %))]
      (testing "a value, with what the code printed"
        (let [{:keys [status out] :as result} (features/inspect client session "(println :hi) {:a [1 2]}" {})]
          (is (= "ok" status))
          (is (= ":hi\n" (test-server/lf out)))
          (is (= "Class: [0]clojure.lang.PersistentArrayMap\nCount: 1\n\n--- Contents:\n  [1]:a = [2][1 2]"
                 (text result)))))
      (testing "moving around in it"
        (is (str/starts-with? (text (features/inspector client session {:op "inspect-push" :idx 2}))
                              "Class: [0]clojure.lang.PersistentVector\nCount: 2\n"))
        (is (str/starts-with? (text (features/inspector client session {:op "inspect-pop"}))
                              "Class: [0]clojure.lang.PersistentArrayMap\n")))
      (testing "code that fails, the way cider-nrepl says it does"
        (let [{:keys [status lines]} (features/inspect client session "(/ 1 0)" {})]
          (is (= "eval-error" status))
          (is (nil? lines))))
      (testing "without an inspector on the server"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes cider-nrepl or babashka"
                              (features/inspect (without client "inspect-push") session "1" {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes cider-nrepl or babashka"
                              (features/inspector (without client "inspect-push") session {:op "inspect-pop"})))))))

(deftest inspector-lines-test
  (testing "values that span lines are on each of them"
    (is (= [[{:text "Value: "} {:text "{:a 1," :index 1}]
            [{:text " :b 2}" :index 1 :continued true} {:text " and so on"}]
            [{:text "--- Path:" :heading? true}]]
           (#'features/inspector-lines
            "(\"Value: \" (:value \"{:a 1,\\n :b 2}\" 1) \" and so on\" (:newline) \"--- Path:\" (:newline))"))))
  (testing "what else might be in there"
    (is (= [[{:text "42"} {:text "x"} {:text "ab"}]]
           (#'features/inspector-lines "(42 x (\"a\" \"b\"))"))))
  (testing "what isn't a view"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"The server's inspector said 42"
                          (#'features/inspector-lines "42")))))
