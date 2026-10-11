(ns neorepl.sample-suite
  "Tests for neorepl test to run, which the test runner leaves alone: one
  passes, one fails and one errs."
  (:require [clojure.test :refer [deftest is testing]]))

(deftest passing
  (println "passing prints")
  (is (= 2 (+ 1 1))))

(deftest failing
  (testing "with a context"
    (is (= 3 (+ 1 1)) "adds up")))

(deftest erring
  (is (= 1 (throw (ex-info "boom" {})))))
