(ns neorepl.render-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [are deftest is testing]]
            [neorepl.render :as render]))

(deftest doc-text-test
  (testing "like clojure.repl/doc"
    (is (= "clojure.core/map\n([f] [f coll])\n  Maps."
           (render/doc-text {:ns "clojure.core" :name "map" :arglists ["[f]" "[f coll]"] :doc "Maps."})))
    (is (= "when\n([test & body])\nMacro"
           (render/doc-text {:name "when" :arglists ["[test & body]"] :macro true})))
    (is (= "if\n  (if test then else?)\nSpecial Form"
           (render/doc-text {:name "if" :forms ["(if test then else?)"] :special-form true})))
    (is (= "let\n  (let [bindings*] exprs*)\nSpecial Form"
           (render/doc-text {:name "let" :forms ["(let [bindings*] exprs*)"] :special-form true :macro true})))))

(deftest failure-heading-test
  (let [dir (str (io/file "/work/app"))]
    (testing "where, relative to the directory neorepl runs in when it's in there"
      (is (= (str "FAIL in my.app-test/adds (" (io/file "test/my/app_test.clj") ":12)")
             (render/failure-heading {:type "fail" :ns "my.app-test" :var "adds" :line 12
                                      :file (str (io/file dir "test/my/app_test.clj"))}
                                     dir)))
      (is (= (str "ERROR in my.app-test/adds (" (io/file "/elsewhere/helper.clj") ":3)")
             (render/failure-heading {:type "error" :ns "my.app-test" :var "adds" :line 3
                                      :file (str (io/file "/elsewhere/helper.clj"))}
                                     dir))))
    (testing "and nowhere, for a fixture outside the tests"
      (is (= "ERROR in my.app-test" (render/failure-heading {:type "error" :ns "my.app-test"} dir))))))

(deftest eldoc-text-test
  (are [info text] (= text (render/eldoc-text info))
    {:arglists ["[f]" "[f coll]"]} "[f] [f coll]"
    {:forms ["(if test then else?)"]} "(if test then else?)"
    {:name "cfg"} nil))

(deftest apropos-text-test
  (is (= "clojure.core/map     Returns a lazy sequence\nclojure.core/mapcat"
         (render/apropos-text [{:name "clojure.core/map" :doc "Returns a lazy sequence\nmore"}
                               {:name "clojure.core/mapcat"}]))))

(deftest location-text-test
  (are [location text] (= text (render/location-text location))
    {:file "/x.clj" :line 3 :column 1} "/x.clj:3:1"
    {:file "/x.clj" :line 3} "/x.clj:3"
    {:file "/x.clj"} "/x.clj"))

(deftest test-report-text-test
  (testing "like clojure.test"
    (is (= (str "FAIL in my.app-test/adds (/x/app_test.clj:3)\n"
                "with a context\n"
                "adds up\n"
                "expected: 3\n"
                "  actual: {:a 1,\n"
                "           :b 2}\n"
                "\n"
                "ERROR in my.app-test/throws\n"
                "   error: clojure.lang.ExceptionInfo: boom {}\n"
                "\n"
                "Ran 3 tests containing 4 assertions.\n"
                "1 failure, 1 error.")
           (render/test-report-text {:summary {:ns 1 :var 3 :test 4 :pass 2 :fail 1 :error 1}
                                     :failures [{:type "fail" :ns "my.app-test" :var "adds"
                                                 :context "with a context" :message "adds up"
                                                 :expected "3" :actual "{:a 1,\n :b 2}"
                                                 :file "/x/app_test.clj" :line 3}
                                                {:type "error" :ns "my.app-test" :var "throws"
                                                 :error "clojure.lang.ExceptionInfo: boom {}"}]}))))
  (testing "an error's stack trace"
    (is (= (str "ERROR in my.app-test/throws\n"
                "   error: clojure.lang.ExceptionInfo: boom {}\n"
                "            at my.app/f (app.clj:2)\n"
                "            at my.app-test/fn (app_test.clj:5)\n"
                "          Caused by: java.lang.ArithmeticException: Divide by zero\n"
                "            at clojure.lang.Numbers/divide (Numbers.java:190)\n"
                "\n"
                "Ran 1 test containing 1 assertion.\n0 failures, 1 error.")
           (render/test-report-text
            {:summary {:ns 1 :var 1 :test 1 :pass 0 :fail 0 :error 1}
             :failures [{:type "error" :ns "my.app-test" :var "throws"
                         :error "clojure.lang.ExceptionInfo: boom {}"
                         :causes [{:class "clojure.lang.ExceptionInfo" :message "boom" :data "{}"
                                   :stacktrace [{:name "my.app/f" :file "app.clj" :line 2}
                                                {:name "my.app/f" :file "app.clj" :line 1 :flags ["dup"]}
                                                {:name "my.app-test/fn" :file "app_test.clj" :line 5}]}
                                  {:class "java.lang.ArithmeticException" :message "Divide by zero"
                                   :stacktrace [{:name "clojure.lang.Numbers/divide" :file "Numbers.java" :line 190}]}]}]}))))
  (testing "a fixture failing outside the tests"
    (is (= "ERROR in my.app-test\n   error: java.lang.Exception: no db\n\nRan 1 test containing 0 assertions.\n0 failures, 1 error."
           (render/test-report-text {:summary {:ns 1 :var 1 :test 0 :pass 0 :fail 0 :error 1}
                                     :failures [{:type "error" :ns "my.app-test" :error "java.lang.Exception: no db"}]}))))
  (testing "all passing"
    (is (= "Ran 1 test containing 1 assertion.\n0 failures, 0 errors."
           (render/test-report-text {:summary {:ns 1 :var 1 :test 1 :pass 1 :fail 0 :error 0} :failures []}))))
  (testing "nothing to run"
    (is (= "No tests ran."
           (render/test-report-text {:summary {:ns 0 :var 0 :test 0 :pass 0 :fail 0 :error 0} :failures []})))))

(deftest error-text-test
  (is (= (str "clojure.lang.ExceptionInfo: boom\n"
              "  {:k 1,\n"
              "   :l 2}\n"
              "    at user/f (x.clj:1)\n"
              "Caused by: java.lang.ArithmeticException\n"
              "    at user/g (x.clj)\n"
              "    at java.lang.Object/wait (Unknown Source)")
         (render/error-text [{:class "clojure.lang.ExceptionInfo" :message "boom" :data "{:k 1,\n :l 2}\n"
                              :stacktrace [{:name "user/f" :file "x.clj" :line 1}
                                           {:name "nrepl.x/y" :file "y.clj" :line 3 :flags ["tooling"]}]}
                             {:class "java.lang.ArithmeticException"
                              :stacktrace [{:name "user/g" :file "x.clj"}
                                           {:name "user/g" :file "x.clj" :line 2 :flags ["dup"]}
                                           {:name "java.lang.Object/wait"}]}]))))

(deftest inspector-text-test
  (is (= "Class: [0]clojure.lang.PersistentVector\n\n--- Contents:\n  0. [1]{:a 1,\n :b 2}"
         (render/inspector-text [[{:text "Class: "} {:text "clojure.lang.PersistentVector" :index 0}]
                                 []
                                 [{:text "--- Contents:"}]
                                 [{:text "  0. "} {:text "{:a 1," :index 1}]
                                 [{:text " :b 2}" :index 1 :continued true} {:text "  "}]])))
  (testing "without the marks"
    (is (= "Class: clojure.lang.PersistentVector"
           (render/inspector-text [[{:text "Class: "} {:text "clojure.lang.PersistentVector" :index 0}]] false)))))

(deftest tracing-text-test
  (testing "what happened to each var or namespace"
    (is (= (str "Tracing my.app/f\nTracing every function in my.db\nAlready tracing my.app/g\n"
                "Stopped tracing my.app/h\nWasn't tracing my.app/i\n"
                "Can't trace clojure.core/when, which is a macro or not a function\n"
                "No var or namespace called nope\n"
                "Won't trace clojure.core/map, which the server's own tools call: tracing it would take the server down")
           (render/tracing-text {:results [{:var "my.app/f" :status "traced"}
                                           {:ns "my.db" :status "traced"}
                                           {:var "my.app/g" :status "already-traced"}
                                           {:var "my.app/h" :status "untraced"}
                                           {:var "my.app/i" :status "not-traced"}
                                           {:var "clojure.core/when" :status "not-traceable"}
                                           {:name "nope" :status "not-found"}
                                           {:var "clojure.core/map" :status "refused"}]}))))
  (testing "what's traced"
    (is (= "my.app/f\nevery function in my.db" (render/tracing-text {:nses ["my.db"] :vars ["my.app/f"]})))
    (is (= "Nothing's traced" (render/tracing-text {:nses [] :vars []}))))
  (testing "untracing everything"
    (are [n text] (= text (render/tracing-text {:untraced n}))
      0 "Nothing was traced"
      1 "Stopped tracing 1 function")))

(deftest trace-event-text-test
  (are [event text] (= text (render/trace-event-text event))
    {:phase "call" :name "my.app/f" :depth 0 :args ["1" "\"a\""]} "(my.app/f 1 \"a\")"
    {:phase "call" :name "my.app/g" :depth 2 :args []} "│ │ (my.app/g)"
    {:phase "return" :name "my.app/g" :depth 1 :value "{:a 1}"} "│ └─→ {:a 1}"))
