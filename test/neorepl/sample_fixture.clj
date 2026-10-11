(ns neorepl.sample-fixture
  "Tests for neorepl test to run whose :once fixture fails, which the test
  runner leaves alone."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [_] (throw (ex-info "no database" {}))))

(deftest never-runs
  (is true))
