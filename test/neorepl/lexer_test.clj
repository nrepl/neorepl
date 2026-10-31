(ns neorepl.lexer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [neorepl.lexer :as lexer]))

(defn- pairs
  "s as [type text] pairs."
  [s]
  (mapv (juxt :type #(lexer/text s %)) (lexer/tokenize s)))

(defn- text-at
  "The text of the token (f lexed-s cursor) finds, if any."
  [f s cursor]
  (some->> (f (lexer/lex s) cursor) (lexer/text s)))

(deftest tokenize-test
  (testing "lists, symbols, numbers and the like"
    (is (= [[:open "("] [:symbol "+"] [:number "1"] [:number "-2.5"] [:number "1/2"]
            [:constant "nil"] [:keyword "::a/b"] [:close ")"]]
           (pairs "(+ 1, -2.5 1/2 nil ::a/b)"))))

  (testing "strings, including escaped quotes and newlines"
    (is (= [[:string "\"a \\\" b\""] [:string "\"x\ny\""]]
           (pairs "\"a \\\" b\" \"x\ny\""))))

  (testing "unterminated strings run to the end of the input"
    (is (= [[:open "("] [:symbol "str"] [:open-string "\"abc )"]]
           (pairs "(str \"abc )"))))

  (testing "char literals swallow the character after the backslash"
    (is (= [[:char "\\("] [:char "\\)"] [:char "\\\""] [:char "\\;"] [:char "\\newline"]
            [:char "\\u00e9"] [:char "\\\\"]]
           (pairs "\\( \\) \\\" \\; \\newline \\u00e9 \\\\")))
    (is (= [[:char "\\a"] [:char "\\b"] [:close ")"]]
           (pairs "\\a\\b)"))))

  (testing "comments run to the end of the line"
    (is (= [[:open "("] [:comment "; a (comment"] [:close ")"]]
           (pairs "(; a (comment\n)")))
    (is (= [[:comment "#!/usr/bin/env bb"] [:number "1"]]
           (pairs "#!/usr/bin/env bb\n1"))))

  (testing "dispatch forms"
    (is (= [[:open "#{"] [:open "#("] [:symbol "%"] [:close ")"] [:close "}"]
            [:regex "#\"\\d+\""] [:discard "#_"] [:symbol "x"]]
           (pairs "#{#(%)} #\"\\d+\" #_x")))
    (is (= [[:reader-macro "#?"] [:open "("] [:keyword ":clj"] [:number "1"] [:close ")"]
            [:reader-macro "#?@"] [:open "("] [:close ")"]
            [:reader-macro "#:ns"] [:open "{"] [:close "}"]
            [:reader-macro "#inst"] [:string "\"2026\""] [:number "##Inf"] [:macro-char "#'"] [:symbol "foo"]]
           (pairs "#?(:clj 1) #?@() #:ns{} #inst \"2026\" ##Inf #'foo"))))

  (testing "quoting and other macro characters"
    (is (= [[:macro-char "'"] [:symbol "a"] [:macro-char "`"] [:open "("] [:macro-char "~"]
            [:macro-char "@"] [:symbol "xs"] [:close ")"] [:macro-char "^"] [:keyword ":private"]]
           (pairs "'a `(~@xs) ^:private"))))

  (testing "terminating macro characters end symbols; ' and # don't"
    (is (= [[:symbol "foo"] [:macro-char "@"] [:symbol "bar"] [:symbol "x'"] [:symbol "y#"]]
           (pairs "foo@bar x' y#"))))

  (testing "offsets point into the input"
    (is (= [{:type :open :start 0 :end 1} {:type :symbol :start 1 :end 4}]
           (lexer/tokenize "(abc")))))

(def ^:private incomplete-inputs
  ["("
   "(+ 1"
   "(let [x 1]"
   "{:a [1 2"
   "#{"
   "#(inc %"
   "\"unterminated"
   "(str \"abc )"
   "(+ 1 ; and a comment"
   "#\"open regex"
   "'"
   "(foo) '"
   "`"
   "@"
   "^:private"
   "^"
   "^{:doc \"x\"}"
   "''"
   "#_"
   "#_#_ a"
   "(+ 1 2) #_"
   "#'"
   "#?"
   "#inst"
   "#:ns"])

(def ^:private complete-inputs
  [""
   "   "
   "42"
   "(+ 1 2)"
   "(str \")\")"
   "[\\( \\[ \\{]"
   "(+ 1 2))"
   ";; just a comment"
   "#?(:clj 1 :cljs 2)"
   "(do\n  1\n  2)"
   "(let [x 1)"
   "[(]"
   "{:a 1]"
   "#'foo"
   "##Inf"
   "'sym"
   "''sym"
   "^:private x"
   "^{:doc \"x\"} x"
   "#_x 1"
   "#?(:clj 1)"
   "#inst \"2026\""])

(defn- lines-incomplete?
  "input-incomplete? for s, added line by line."
  [s]
  (lexer/input-incomplete? (reduce lexer/add-line lexer/empty-input (str/split s #"\n" -1))))

(deftest incomplete?-test
  (doseq [s incomplete-inputs]
    (is (lexer/incomplete? s) s))
  (doseq [s complete-inputs]
    (is (not (lexer/incomplete? s)) s)))

(deftest add-line-test
  (testing "line by line, input is as incomplete as all at once"
    (doseq [s (concat incomplete-inputs complete-inputs)]
      (is (= (lexer/incomplete? s) (lines-incomplete? s)) s)))
  (testing "forms and strings across lines"
    (are [s incomplete] (= incomplete (lines-incomplete? s))
      "(defn f [x]\n  (inc x)" true
      "(defn f [x]\n  (inc x))" false
      "(str \"a\nb" true
      "(str \"a\nb\")" false
      "(str \"a\n(\nb\")" false
      "(str \"a\n\n\")" false
      "'\nfoo" false
      "#_\n" true
      "(foo ; (\n)" false
      "(str \"a\\\nb\")" false
      "[\"a\nb\nc\" \"d\ne\"]" false
      "[\"a\nb\nc\" \"d\ne" true))
  (testing "broken input stays complete"
    (is (not (lines-incomplete? "(]\n("))))
  (testing "unless a string in it is still going"
    (is (lines-incomplete? ") \"foo"))
    (is (not (lines-incomplete? ") \"foo\nbar\""))))
  (testing "takes time in proportion to the input, strings too"
    (let [lines (concat ["(str \""] (repeat 20000 "a line of a long string") ["\")"])
          start (System/nanoTime)]
      (is (not (lexer/input-incomplete? (reduce lexer/add-line lexer/empty-input lines))))
      (is (< (- (System/nanoTime) start) 2e9)))))

(deftest forms-test
  (are [s forms] (= forms (lexer/forms s))
    "" []
    "[f] [f coll]" ["[f]" "[f coll]"]
    "[{:keys [a b]} & more] [x]" ["[{:keys [a b]} & more]" "[x]"]
    "a 'b ; c\n\"d e\"" ["a" "'b" "\"d e\""]
    "^:foo [1 2] 3" ["^:foo [1 2]" "3"]
    "' #_ a b c" ["' #_ a b" "c"])
  (testing "without the ones #_ discards, #_#_ two of them"
    (are [s forms] (= forms (lexer/forms s))
      "#_ a" []
      "#_#_ 1 (x) 3" ["3"]
      "#_ ^:foo x y" ["y"]))
  (testing "and where they are"
    (is (= [[0 1] [2 4] [9 14]] (lexer/form-spans (lexer/lex "a 'b ; c\n\"d e\""))))))

(deftest symbol-at-test
  (let [s "(map inc :kw)"]
    (are [cursor expected] (= expected (text-at lexer/symbol-at s cursor))
      0 nil
      1 "map"
      3 "map"
      4 "map"
      5 "inc"
      9 ":kw"
      12 ":kw"
      13 nil))
  (testing "past var quotes, and on constants"
    (is (= "ma" (text-at lexer/symbol-at "#'ma" 4)))
    (is (= "nil" (text-at lexer/symbol-at "nil" 3)))))

(deftest enclosing-op-test
  (are [s cursor expected] (= expected (text-at lexer/enclosing-op s cursor))
    "(map inc " 9 "map"
    "(map inc xs)" 5 "map"
    "(map inc xs)" 12 nil
    "(map (fn [x] " 13 "fn"
    "(map (fn [x] x) " 16 "map"
    "(let [x " 8 "let"
    "(assoc {:a " 11 "assoc"
    "#(inc " 6 "inc"
    "((comp inc) " 12 nil
    "(:k m" 5 nil
    "inc" 3 nil
    "(ma" 3 "ma"
    "(let [x 1) (" 12 nil))

(deftest delimiter-match-test
  (let [match (fn [s cursor]
                (when-let [{:keys [at match mismatched?]} (lexer/delimiter-match (lexer/lex s) cursor)]
                  [(:start at) (:start match) (boolean mismatched?)]))]
    (testing "a closer right before the cursor, or an opener under it"
      (are [s cursor expected] (= expected (match s cursor))
        "(+ 1 2)" 7 [6 0 false]
        "(+ 1 2)" 0 [0 6 false]
        "(+ 1 2)" 3 nil
        "(a [b] c)" 6 [5 3 false]
        "(a [b] c)" 3 [3 5 false]
        "#{1 2}" 0 [0 5 false]
        "#{1 2}" 1 [0 5 false]
        "#(inc %)" 8 [7 0 false]))
    (testing "the closer before the cursor over the opener under it"
      (is (= [4 0 false] (match "(a b)(c)" 5))))
    (testing "past the delimiters in strings, chars and comments"
      (are [s cursor expected] (= expected (match s cursor))
        "(str \"(\" x)" 11 [10 0 false]
        "(str \\( x)" 10 [9 0 false]
        "(a ; (\n b)" 10 [9 0 false]))
    (testing "the wrong kind"
      (is (= [2 0 true] (match "(a]" 3))))
    (testing "nothing to pair with"
      (is (= [3 nil false] (match "(a))" 4)))
      (is (= [0 nil false] (match "(a (b)" 0))))))

(deftest delimiter-error-test
  (letfn [(error [s]
            ;; With the tokens' text in place of the tokens.
            (when-let [e (lexer/delimiter-error (lexer/lex s))]
              (into {} (for [[k v] e] [k (if (map? v) (lexer/text s v) v)]))))]
    (testing "balanced"
      (are [s] (nil? (error s))
        ""
        "(defn f [x] (inc x))"
        "(str \\( \")\" #\"[\") ; ]\n{:a #{1}}"
        "#_(a) #(+ % 1) #?(:clj [1])"))
    (testing "problems"
      (are [s expected] (= expected (error s))
        "(defn f [x]\n  (inc x)" {:error :unclosed :open "("}
        "(let [x 1) x)" {:error :mismatched :open "[" :close ")" :expected "]"}
        "(+ 1 2))" {:error :unmatched :close ")"}
        "(str \"abc)" {:error :open-string :open "\"abc)"}
        "#{1 2" {:error :unclosed :open "#{"}))
    (testing "the outermost delimiter left open"
      (is (= 0 (:start (:open (lexer/delimiter-error (lexer/lex "(a (b [c]"))))))))
  (testing "the first problem wins"
    (is (= :unmatched (:error (lexer/delimiter-error (lexer/lex ") (")))))))

(deftest position-test
  (are [s index pos] (= pos (lexer/position s index))
    "abc" 0 [1 1]
    "abc" 2 [1 3]
    "a\nbc" 3 [2 2]
    "a\n" 2 [2 1]
    "a\n\n(" 3 [3 1]
    "\uD83D\uDE00(" 2 [1 2]))
