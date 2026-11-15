(ns demo.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [demo.core :as core]))

(deftest price-test
  (is (= 760 (core/price :latte 2))))

(deftest total-test
  (is (= 670 (core/total (core/find-order 2)))))

(deftest format-cents-test
  (testing "dollars and cents"
    (is (= "$3.80" (core/format-cents 380)))
    (is (= "$12.05" (core/format-cents 1205)))))
