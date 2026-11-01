(ns neorepl.dialects-test
  "neorepl against the servers of other Clojure dialects, which jack-in
  doesn't start: nbb's, with nbb from npm, and Basilisp's, in a venv. They
  take Node and npm, and Python 3."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.servers :as servers :refer [project-dir run run-with]]))

(def ^:private servers
  "The servers the tests run against, by name, with what each expands
  (when 1 2) to."
  (let [files (servers/sources "cljc")]
    {:nbb {:command ["node_modules/.bin/nbb" "nrepl-server" ":port" "0"]
           :files (assoc files
                         "nbb.edn" "{:paths [\"src\" \"test\"]}"
                         "package.json" "{\"private\": true, \"dependencies\": {\"nbb\": \"1.6.214\"}}")
           :expansion "(if 1 (do 2))\n"}
     ;; Basilisp reads .cljc as :lpy, with clojure.test as basilisp.test.
     :basilisp {:command [".venv/bin/basilisp" "nrepl-server" "-p" "src" "-p" "test"]
                :files (assoc files "requirements.txt" "basilisp==0.5.1\n")
                :expansion "(if 1 (do 2) nil)\n"}}))

(def ^:private on-each-server
  "Calls (f server) for each server, testing it under its name."
  (partial servers/on-each-server servers))

(use-fixtures :once
  (fn [f]
    ;; Rather than loading the files, which on nbb gives their vars the
    ;; file's contents as their :file.
    (servers/call-with-servers servers #(run % "eval" "(require 'demo.core-test)") f)))

(deftest eval-test
  (on-each-server
   (fn [server]
     (is (= {:status 0 :out "3\nout\nnil\n42\n" :err ""} (run server "eval" "(+ 1 2) (println \"out\") 42")))
     ;; Which nbb's server says only with an :ex.
     (testing "with an error"
       (let [{:keys [status err]} (run server "eval" "(demo.core/boom 1)")]
         (is (= 1 status))
         (is (str/includes? err "boom")))))))

(deftest load-test
  ;; nbb's load-file gives a value for each form.
  (is (= {:status 0 :out "#'demo.core/welcome\n" :err ""}
         (run :basilisp "load" (str (io/file (project-dir :basilisp) "src" "demo" "core.cljc"))))))

(deftest lookups-test
  (on-each-server
   (fn [server]
     (is (= {:status 0 :out "demo.core/greet\n([who])\n  Says hi.\n" :err ""} (run server "doc" "demo.core/greet")))
     ;; Which nbb answers with its nils as empty lists.
     (testing "of nothing"
       (is (= 1 (:status (run server "doc" "nothing-here")))))
     ;; With no column on nbb, and one counting from 0 on Basilisp,
     ;; basilisp#1357.
     (is (str/starts-with? (:out (run server "where" "demo.core/greet"))
                           (str (io/file (project-dir server) "src" "demo" "core.cljc") ":3")))
     (is (= {:status 0 :out "(defn greet\n  \"Says hi.\"\n  [who]\n  (str \"hi \" who))\n" :err ""}
            (run server "source" "demo.core/greet")))
     ;; In the namespace, as Basilisp completes demo.core/gr from
     ;; demo.core-test.
     (is (some #{"greet"} (map #(first (str/split % #"\s+"))
                               (str/split-lines (:out (run server "complete" "gr" "--ns" "demo.core"))))))
     (is (str/includes? (:out (run server "apropos" "greet")) "demo.core/greet\n"))
     (is (= {:status 0 :out (get-in servers [server :expansion]) :err ""} (run server "macroexpand" "(when 1 2)"))))))

(deftest repl-test
  (on-each-server
   (fn [server]
     (let [{:keys [status out]} (run-with server ",ns demo.core\n(greet \"r\")\n,ns demo.core-test\n(core/greet \"q\")\n" "repl")]
       (is (= 0 status))
       (is (str/includes? out "demo.core=> \"hi r\"\n"))
       (is (str/includes? out "demo.core-test=> \"hi q\"\n"))))))
