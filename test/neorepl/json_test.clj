(ns neorepl.json-test
  (:require [clojure.test :refer [are deftest is]]
            [neorepl.json :as json]))

(deftest write-str-test
  (are [x s] (= s (json/write-str x))
    nil "null"
    true "true"
    false "false"
    42 "42"
    -1 "-1"
    "plain" "\"plain\""
    "say \"hi\"\\" "\"say \\\"hi\\\"\\\\\""
    "a\nb\r\tc" "\"a\\nb\\r\\tc\""
    "\u0000\u001b" "\"\\u0000\\u001b\""
    "日本語 é" "\"日本語 é\""
    :status "\"status\""
    'sym "\"sym\""
    [] "[]"
    ["1" 2 nil] "[\"1\",2,null]"
    '("a") "[\"a\"]"
    {} "{}"
    {:root-ex "x" "k" [1]} "{\"root-ex\":\"x\",\"k\":[1]}"
    {:a/b {:c true}} "{\"a/b\":{\"c\":true}}")
  (is (= "1.5" (json/write-str 1.5)))
  (is (thrown? clojure.lang.ExceptionInfo (json/write-str Double/NaN)))
  (is (thrown? clojure.lang.ExceptionInfo (json/write-str (Object.)))))

(deftest read-str-test
  (are [s x] (= x (json/read-str s))
    "null" nil
    "true" true
    " false " false
    "42" 42
    "-7" -7
    "1.5" 1.5
    "1e3" 1000.0
    "\"plain\"" "plain"
    "\"say \\\"hi\\\" \\\\ \\/ \\b\\f\\n\\r\\t\"" "say \"hi\" \\ / \b\f\n\r\t"
    "\"\\u00e9\\u0000\"" "é\u0000"
    ;; A surrogate pair, as two escapes.
    "\"\\ud83d\\ude00\"" "😀"
    "\"日本語\"" "日本語"
    "[]" []
    "[1, \"a\", null, [true]]" [1 "a" nil [true]]
    "{}" {}
    "{\"jsonrpc\": \"2.0\", \"id\": 1, \"params\": {\"name\": \"eval\", \"arguments\": {}}}"
    {:jsonrpc "2.0" :id 1 :params {:name "eval" :arguments {}}}
    "\n{ \"a\" :\t[ 1 , 2 ] }\r\n" {:a [1 2]})
  (doseq [bad ["" "nul" "[1,]" "[1 2]" "{\"a\" 1}" "{a: 1}" "{\"a\": 1,}" "\"open" "\"bad \\x\""
               "\"\\u12\"" "01x" "-" "[1] x" "\"tab\there\""]]
    (is (= ::json/invalid (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (json/read-str bad))))))
        bad)))

(deftest round-trip-test
  (let [x {:jsonrpc "2.0" :id 7 :result {:content [{:type "text" :text "a \"b\"\n\u001b😀"}]
                                          :isError false :n 2.5}}]
    (is (= x (json/read-str (json/write-str x))))))
