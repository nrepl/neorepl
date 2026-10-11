(ns neorepl.render-test
  (:require [clojure.test :refer [are deftest is testing]]
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
