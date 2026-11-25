(ns neorepl.hints-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.features :as features]
            [neorepl.hints :as hints]
            [neorepl.lexer :as lexer]
            [neorepl.nrepl :as nrepl]
            [neorepl.test-server :as test-server :refer [*port* with-client]]))

(use-fixtures :once (test-server/fixture))

(defn- err-of
  "What evaluating code prints to stderr, which says what's wrong with it
  the way the server's runtime says it."
  [client code]
  (str/join (keep :err (nrepl/request client {:op "eval" :code code} {}))))

(deftest unresolved-test
  (testing "what Clojure's compiler says, at the line and column the line before has"
    (is (= {:kind :symbol :name "joinn" :at [2 3]}
           (hints/unresolved "Syntax error compiling at (REPL:2:3).\nUnable to resolve symbol: joinn in this context\n")))
    (is (= {:kind :namespace :name "str" :at [1 1]}
           (hints/unresolved "Syntax error compiling at (REPL:1:1).\nNo such namespace: str\n")))
    (is (= {:kind :var :name "clojure.string/joinn" :at [1 1]}
           (hints/unresolved "Syntax error compiling at (REPL:1:1).\nNo such var: clojure.string/joinn\n")))
    (is (= {:kind :class :name "Date" :at [1 1]}
           (hints/unresolved "Syntax error (IllegalArgumentException) compiling new at (REPL:1:1).\nUnable to resolve classname: Date\n"))))
  (testing "and babashka, at the end of the line"
    (is (= {:kind :symbol :name "str/joinn" :at [2 3]}
           (hints/unresolved "Unable to resolve symbol: str/joinn [at NO_SOURCE_PATH:2:3]\n"))))
  (testing "the first such error, with where it says it is"
    (is (= {:kind :var :name "clojure.string/joinn" :at [1 1]}
           (hints/unresolved (str "Syntax error compiling at (REPL:1:1).\nNo such var: clojure.string/joinn\n"
                                  "Syntax error compiling at (REPL:2:1).\nUnable to resolve symbol: mapp in this context\n")))))
  (testing "and nothing else"
    (is (nil? (hints/unresolved "Execution error (ArithmeticException) at user/eval1 (REPL:1).\nDivide by zero\n")))))

(deftest distance-test
  (is (= 0 (hints/distance "map" "map")))
  (is (= 1 (hints/distance "mapp" "map")))
  (is (= 1 (hints/distance "rduce" "reduce")))
  (is (= 1 (hints/distance "mpa" "map")))
  (testing "a swap of two characters next to each other as one"
    (is (= 1 (hints/distance "fitler" "filter"))))
  (is (= 3 (hints/distance "abc" ""))))

(deftest suggest-test
  (with-client [client {:port *port*}]
    (let [suggest (fn [code & [err]]
                    (hints/suggest client {:ns "user"} code (or err (err-of client code)) {}))
          run (fn [code fixes] (some #(when (= code (:run %)) %) fixes))]
      (testing "the requires that give a symbol"
        (let [code "(join \", \" [\"a\"])"
              {:keys [token fixes]} (suggest code)]
          (is (= "join" (lexer/text code token)))
          (testing "with what the fix runs: that, then the code again"
            (is (= "(require '[clojure.string :refer [join]])\n(join \", \" [\"a\"])"
                   (:input (run "(require '[clojure.string :refer [join]])" fixes)))))))
      (testing "the namespace an alias could stand for, ahead of the ones with the var"
        (is (= "(require '[clojure.string :as string])"
               (:run (first (:fixes (suggest "(string/join \", \" [\"a\"])")))))))
      (testing "the class a constructor needs"
        (is (run "(import 'java.util.Date)" (:fixes (suggest "(Date.)")))))
      (testing "the names it's a typo of"
        (is (= {:replace "map" :in-place-of "mapp" :input "(do (map inc [1]) (map dec [1]))"}
               (first (:fixes (suggest "(do (mapp inc [1]) (mapp dec [1]))")))))
        (testing "in its namespace"
          (is (= "clojure.string/join" (:replace (first (:fixes (suggest "(clojure.string/joinn \",\" [1])")))))))
        (testing "keeping a constructor call's dot"
          (with-redefs [features/complete (fn [_ prefix _ _] (when (= "St" prefix) [{:candidate "String"}]))]
            (is (= {:replace "String." :in-place-of "Strng." :input "(String. \"a\")"}
                   (first (:fixes (suggest "(Strng. \"a\")" "Unable to resolve classname: Strng\n"))))))))
      (testing "an alias it's a typo of, everywhere, ahead of what to require under it"
        (with-redefs [features/complete (fn [_ prefix _ _] (when (= "st" prefix) [{:candidate "str/" :type "namespace"}]))]
          (is (= {:replace "str/" :in-place-of "strr/" :input "(str/join (str/split \"a\" #\",\"))"}
                 (first (:fixes (suggest "(strr/join (strr/split \"a\" #\",\"))"
                                         "Syntax error compiling at (REPL:1:1).\nNo such namespace: strr\n")))))))
      (testing "the fixes the other lookups find when one fails"
        (with-redefs [features/complete (fn [& _] (throw (ex-info "Too slow" {})))]
          (is (run "(require '[clojure.string :refer [join]])" (:fixes (suggest "(join \", \" [\"a\"])"))))))
      (testing "the symbol where the error says, rather than one before it"
        (let [code "(let [mapp inc] (mapp 1))\n(mapp inc [1])"
              {:keys [token fixes]} (suggest code "Syntax error compiling at (REPL:2:1).\nUnable to resolve symbol: mapp in this context\n")]
          (is (= 27 (:start token)))
          (testing "with only its form run again"
            (is (= "(map inc [1])" (:input (first fixes)))))))
      (testing "nothing"
        (testing "for other errors"
          (is (nil? (suggest "(/ 1 0)"))))
        (testing "for a name the code doesn't have, as when loading a file fails"
          (is (nil? (suggest "(load-file \"x.clj\")" "Syntax error compiling at (x.clj:1:1).\nUnable to resolve symbol: nope in this context\n"))))))))
