(ns neorepl.stacktrace-test
  "The stack trace browser's view and keys, without a terminal."
  (:require [clojure.test :refer [deftest is testing]]
            [neorepl.browser-test :refer [screen]]
            [neorepl.stacktrace :as stacktrace]))

(def ^:private causes
  [{:class "clojure.lang.ExceptionInfo" :message "boom" :data "{:k 1}"
    :stacktrace [{:name "user/f" :file "x.clj" :line 1 :var "user/f"}
                 {:name "user/f" :file "x.clj" :line 2 :var "user/f" :flags ["dup"]}
                 {:name "user/g" :file "x.clj" :line 3 :var "user/g"}]}
   {:class "java.lang.ArithmeticException" :message "Divide by zero\nfor real"
    :stacktrace [{:name "clojure.lang.Numbers/divide" :file "Numbers.java" :line 190}]}])

(defn- press [view & keys]
  (reduce #(stacktrace/handle %1 %2 true) view keys))

(deftest error-view-test
  (testing "the causes, their data and their frames, without the duplicates"
    (is (= ["clojure.lang.ExceptionInfo: boom"
            "  {:k 1}"
            "    at [user/f (x.clj:1)]"
            "    at user/g (x.clj:3)"
            "Caused by: java.lang.ArithmeticException: Divide by zero"
            "for real"
            "    at clojure.lang.Numbers/divide (Numbers.java:190)"
            ""
            "help"]
           (screen (stacktrace/error-view [{:causes causes}]) 9))))
  (testing "all the frames, keeping the selection"
    (let [view (press (stacktrace/error-view [{:causes causes}]) :down "a")]
      (is (= ["    at user/f (x.clj:1)"
              "    at user/f (x.clj:2)"
              "    at [user/g (x.clj:3)]"]
             (take 3 (drop 2 (screen view 10)))))
      (testing "and back, onto the frame before a hidden one"
        (is (= "    at [user/f (x.clj:1)]" (nth (screen (press view :up "a") 10) 2)))))))

(deftest errors-test
  (let [view (stacktrace/error-view [{:heading "ERROR in my.app-test/a" :causes (subvec causes 0 1)}
                                     {:heading "ERROR in my.app-test/b" :causes (subvec causes 1)}])]
    (testing "several, each under its heading"
      (is (= ["ERROR in my.app-test/a"
              "clojure.lang.ExceptionInfo: boom"
              "  {:k 1}"
              "    at [user/f (x.clj:1)]"
              "    at user/g (x.clj:3)"
              ""
              "ERROR in my.app-test/b"
              "java.lang.ArithmeticException: Divide by zero"
              "for real"
              "    at clojure.lang.Numbers/divide (Numbers.java:190)"
              ""
              "help"]
             (screen view 12))))
    (testing "picking a frame of the second"
      (is (= {:name "clojure.lang.Numbers/divide" :file "Numbers.java" :line 190}
             (:frame @(stacktrace/handle (press view :down :down) :enter false)))))))

(deftest handle-test
  (let [view (stacktrace/error-view [{:causes causes}])]
    (testing "picking a frame to open"
      (let [view (press view :down)]
        (is (= {:frame {:name "user/g" :file "x.clj" :line 3 :var "user/g"} :view view}
               @(stacktrace/handle view :enter false)))))
    (testing "the causes, when they can be inspected"
      (testing "by the index of the one the frame's in"
        (is (= {:inspect 1 :ex-data? false}
               (dissoc @(stacktrace/handle (press view :down :down) "i" true) :view))))
      (testing "or their data"
        (is (= {:inspect 0 :ex-data? true}
               (dissoc @(stacktrace/handle view "d" true) :view)))
        (is (= "No data on this one" (:message (press view :down :down "d")))))
      (testing "coming back to the same frame"
        (let [{back :view} @(stacktrace/handle (press view :down "a") "i" true)]
          (is (= "    at [user/g (x.clj:3)]" (nth (screen back 10) 4))))))
    (testing "but not otherwise"
      (is (= view (stacktrace/handle view "i" false))))
    (testing "done"
      (doseq [key ["q" :escape :ctrl-c]]
        (is (nil? @(stacktrace/handle view key false)))))
    (testing "other keys do nothing"
      (is (= view (stacktrace/handle view "x" false))))))
