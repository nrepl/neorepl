(ns neorepl.debugger-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [neorepl.debugger :as debugger]))

(deftest stop-text-test
  (let [stop {:code "(defn f [x]\n  (* x 2))" :coor [3 1] :debug-value "3" :file [] :line 4 :original-ns "my.app"}]
    (testing "what it's in, the form marked on its line, and the value"
      (is (= "● my.app/f\n    (* x 2))\n       ^\n  => 3" (debugger/stop-text stop 80))))
    (testing "where it is in its file, by the form's line"
      (is (str/starts-with? (debugger/stop-text (assoc stop :file "/no/such/dir/app.clj") 80)
                            "● my.app/f  /no/such/dir/app.clj:5\n")))
    (testing "a form that spans lines, marked on its first"
      (is (= "● my.app/f\n    (let [y x]\n    ^^^^^^^^^^\n  => 3"
             (debugger/stop-text (assoc stop :code "(defn f [x]\n  (let [y x]\n    y))" :coor [3]) 80))))
    (testing "what the form threw, for #dbg! and #break!"
      (is (str/ends-with? (debugger/stop-text (assoc stop :caught-msg "Divide by zero"
                                                     :debug-value "#error[java.lang.ArithmeticException \"Divide by zero\" \"x\"]")
                                              80)
                          "\n  ✗ java.lang.ArithmeticException: Divide by zero")))
    (testing "the namespace, for code that defines nothing"
      (is (= "● my.app\n  (inc x)\n       ^\n  => 3" (debugger/stop-text (assoc stop :code "(inc x)" :coor [1]) 80))))
    (testing "without the #dbg tags ,break-at puts in front of forms"
      (is (= "● my.app/f\n    (let [y #dbg! (* x 2)]\n                     ^\n  => 3"
             (debugger/stop-text (assoc stop :code "(defn f [x]\n  (let [#dbg y #dbg! (* x 2)]\n    #dbg y))" :coor [3 1 1 1]) 80)))
      (is (= "● my.app/f\n      y))\n      ^\n  => 3"
             (debugger/stop-text (assoc stop :code "(defn f [x]\n  (let [#dbg y #dbg (* x 2)]\n    #dbg y))" :coor [3 2]) 80))))))

(deftest help-text-test
  (let [help (debugger/help-text #{"next" "quit"})]
    (is (str/includes? help "  n  Go to the next step\n"))
    (is (str/includes? help "  q  Stop the evaluation\n"))
    (testing "only the keys the breakpoint takes"
      (is (not (str/includes? help "Inspect"))))))

(deftest locals-text-test
  (is (= "  x    3\n  acc  [1 2]" (debugger/locals-text [["x" "3"] ["acc" "[1 2]"]])))
  (is (= "  No locals here" (debugger/locals-text []))))
