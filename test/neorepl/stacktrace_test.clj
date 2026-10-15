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
  (reduce stacktrace/handle view keys))

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
           (screen (stacktrace/error-view causes) 9))))
  (testing "all the frames, keeping the selection"
    (let [view (press (stacktrace/error-view causes) :down "a")]
      (is (= ["    at user/f (x.clj:1)"
              "    at user/f (x.clj:2)"
              "    at [user/g (x.clj:3)]"]
             (take 3 (drop 2 (screen view 10)))))
      (testing "and back, onto the frame before a hidden one"
        (is (= "    at [user/f (x.clj:1)]" (nth (screen (press view :up "a") 10) 2)))))))

(deftest handle-test
  (let [view (stacktrace/error-view causes)]
    (testing "picking a frame to open"
      (is (= {:name "user/g" :file "x.clj" :line 3 :var "user/g"}
             @(stacktrace/handle (press view :down) :enter))))
    (testing "done"
      (doseq [key ["q" :escape :ctrl-c]]
        (is (nil? @(stacktrace/handle view key)))))
    (testing "other keys do nothing"
      (is (= view (stacktrace/handle view "x"))))))
