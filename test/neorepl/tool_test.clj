(ns neorepl.tool-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [neorepl.main :as main]
            [neorepl.tool :as tool]))

(def ^:private argv #'tool/argv)

(deftest argv-test
  (testing "the long options, by name, and the arguments after --"
    (is (= ["eval" "--session" "agent" "--" "(+ 1 2)"]
           (argv "eval" {:session "agent" :args ["(+ 1 2)"]}))))
  (testing "options that take no value, with true, and none for false or nil"
    (is (= ["eval" "--reload"] (argv "eval" {:reload true :ns nil :session false}))))
  (testing "values and arguments that aren't strings, as they print"
    (is (= ["doc" "--port" "7888" "--" "map" "(inc 1)"]
           (argv "doc" {:port 7888 :args ['map '(inc 1)]}))))
  (testing "a keyword's name for a value, as -T has them"
    (is (= ["eval" "--format" "json" "--session" "agent"]
           (argv "eval" {:format :json :session :agent}))))
  (testing "one argument, without a vector, even a list"
    (is (= ["eval" "--" "-1"] (argv "eval" {:args "-1"})))
    (is (= ["eval" "--" "(+ 1 2)"] (argv "eval" {:args '(+ 1 2)}))))
  (testing "true for an option that takes a value, as the value"
    (is (= ["eval" "--session" "true" "--" "1"] (argv "eval" {:session true :args ["1"]}))))
  (testing "nothing but the command, for the REPL"
    (is (= ["repl"] (argv "repl" {}))))
  (testing "the options in the same order whatever the map's"
    (is (= ["eval" "--format" "json" "--host" "h" "--port" "1"]
           (argv "eval" {:port 1 :host "h" :format 'json})))))

(deftest terminal-properties-test
  (testing "JLine's exec terminal, without native access"
    (is (= {"org.jline.terminal.provider" "exec"
            "org.jline.terminal.exec.redirectPipeCreationMode" "reflection"}
           (#'tool/terminal-properties false "Mac OS X"))))
  (testing "but JLine's choice with it, or on Windows, which has no stty"
    (is (= {} (#'tool/terminal-properties true "Linux")))
    (is (= {} (#'tool/terminal-properties false "Windows 11")))))

(deftest commands-test
  (testing "a function for each command, with its summary"
    (doseq [{:keys [name summary]} @#'main/commands
            :let [v (ns-resolve 'neorepl.tool (symbol name))]]
      (is (fn? (some-> v deref)) name)
      (is (str/starts-with? (:doc (meta v)) (str/replace summary #" \(the default\)$" "")) name)))
  (testing "but not the REPL's being the default, which it isn't under -T"
    (is (= "Start a REPL. :help true shows its options." (:doc (meta (ns-resolve 'neorepl.tool 'repl)))))))
