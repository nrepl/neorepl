(ns neorepl.repair-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [neorepl.repair :as repair]))

(defn- lines [& ls]
  (str (str/join "\n" ls) "\n"))

(deftest repair-test
  (testing "closers the indentation says are missing, extra or the wrong kind"
    (are [broken fixed] (= {:text fixed} (repair/repair broken))
      (lines "(defn f [x]"
             "  (inc x)")
      (lines "(defn f [x]"
             "  (inc x))")

      (lines "(defn f [x]"
             "  (inc x)))")
      (lines "(defn f [x]"
             "  (inc x))")

      (lines "(defn f [x]"
             "  (let [y (inc x)"
             "    (* y 2))")
      (lines "(defn f [x]"
             "  (let [y (inc x)]"
             "    (* y 2)))")

      (lines "(let [m {:a 1"
             "         :b 2}"
             "  m]")
      (lines "(let [m {:a 1"
             "         :b 2}]"
             "  m)")))
  (testing "past what isn't a delimiter: strings, chars, regexes and comments"
    (is (= {:text (lines "(defn f [x] ; (a comment"
                         "  (str \"(\" \\) #\"[\" x)) ; more")}
           (repair/repair (lines "(defn f [x] ; (a comment"
                                 "  (str \"(\" \\) #\"[\" x) ; more")))))
  (testing "with strings over several lines, whose lines aren't indentation"
    (is (= {:text (lines "(defn f []"
                         "  \"one"
                         "two\""
                         "  (g))")}
           (repair/repair (lines "(defn f []"
                                 "  \"one"
                                 "two\""
                                 "  (g)")))))
  (testing "keeping the end of a multi-line string on a line with only closers after it"
    (is (= {:text (lines "(defn f []"
                         "  (str \"one"
                         "two\"))")}
           (repair/repair (lines "(defn f []"
                                 "  (str \"one"
                                 "two\")))")))))
  (testing "set and function literals"
    (is (= {:text (lines "(def s #{1"
                         "        2})"
                         "(def f #(inc %))")}
           (repair/repair (lines "(def s #{1"
                                 "        2}"
                                 "(def f #(inc %))")))))
  (testing "closers on lines of their own move up, and the lines go"
    (is (= {:text (lines "(defn f [x]"
                         "  (when x"
                         "    (prn x)))"
                         "  ; done")}
           (repair/repair (lines "(defn f [x]"
                                 "  (when x"
                                 "    (prn x)"
                                 "  ; done"
                                 "  )")))))
  (testing "only the forms that don't balance, however the others are indented"
    (let [fine (lines "(ns a)"
                      ""
                      "(defn ok"
                      "[x]"
                      "x)"
                      "")]
      (is (= {:text (str fine (lines "(defn broken []"
                                     "  (inc 1))")
                         fine)}
             (repair/repair (str fine (lines "(defn broken []"
                                             "  (inc 1)")
                                 fine))))))
  (testing "nothing, when everything balances"
    (let [s (lines "(defn f [x]" "x)")]
      (is (= {:text s} (repair/repair s)))))
  (testing "nothing, when it can't tell how"
    (are [broken error] (= {:error error} (repair/repair broken))
      (lines "(defn f []"
             "  (str \"open")
      "Can't tell where an unterminated string or regex should end"

      (lines "(defn f []"
             "\t(inc 1)")
      "Can't tell the indentation apart from tabs, in the form at line 1")))
