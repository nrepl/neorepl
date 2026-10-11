(ns neorepl.sample-slow
  "A test for neorepl test to interrupt, which the test runner leaves alone."
  (:require [clojure.test :refer [deftest is]]))

(deftest sleeping
  (println :sleeping)
  (Thread/sleep 20000)
  (is true))
