(ns neorepl.spec-browser-test
  "The spec browser, without a terminal or a server."
  (:require [clojure.test :refer [deftest is testing]]
            [neorepl.browser-test :refer [screen]]
            [neorepl.spec-browser :as spec-browser]))

(def ^:private names
  [":demo/age" ":demo/gone" ":demo/name" ":demo/person" "demo.core/greet"])

(def ^:private forms
  {":demo/person" "(s/keys :req [:demo/name] :opt [:demo/age :other/id])\n"
   ":demo/age" "pos-int?\n"
   ":demo/name" "string?\n"})

(def ^:private ask
  "What the browser asks a server, answered here."
  {:form #(if (= "demo.core/greet" %) (throw (ex-info "Couldn't" {})) (forms %))
   :example #(if (= ":demo/age" %) "42\n" "#:demo{:name \"\"}\n")})

(defn- press
  "view after keys, or what the user picked once handle has it reduced."
  [view & keys]
  (reduce #(spec-browser/handle %1 %2 ask) view keys))

(defn- start
  "The view browse starts at, given spec."
  [spec]
  (spec-browser/browse (fn [view _ _] view) names spec ask))

(deftest specs-test
  (testing "every spec"
    (is (= ["Specs" "[:demo/age]" ":demo/gone" ":demo/name" ":demo/person" "demo.core/greet" ""
            "↑↓ move  → form  / filter  q quit"]
           (screen (start nil) 8))))
  (testing "or the ones with a name in theirs"
    (is (= ["Specs" "[:demo/name]" "" "↑↓ move  → form  / filter  q quit"]
           (screen (start "name") 4))))
  (testing "or saying there are none"
    (is (= "No specs in the registry" (last (screen (spec-browser/specs-view [] nil nil) 3)))))
  (testing "q ending it"
    (is (nil? (press (start nil) "q")))))

(deftest spec-test
  (let [person (start ":demo/person")]
    (testing "a spec's form, with the specs it names to move to"
      (is (= [":demo/person"
              "(s/keys :req [[:demo/name]] :opt [:demo/age :other/id])"
              ""
              "↑↓ move  → spec  x example  ← back  q quit"]
             (screen person 4)))
      (is (= "(s/keys :req [:demo/name] :opt [[:demo/age] :other/id])" (second (screen (press person :down) 4)))))
    (testing "→ going to the one selected"
      (is (= [":demo/age" "pos-int?"] (take 2 (screen (press person :down :right) 4))))
      (testing "and ← back where it was"
        (is (= "(s/keys :req [:demo/name] :opt [[:demo/age] :other/id])"
               (second (screen (press person :down :right :left) 4))))))
    (testing "← going back to the list, with the spec selected"
      (is (= ["Specs" ":demo/age" ":demo/gone" ":demo/name" "[:demo/person]"] (take 5 (screen (press person :left) 7)))))
    (testing "x generating an example, keeping the selection"
      (is (= [":demo/person"
              "(s/keys :req [:demo/name] :opt [[:demo/age] :other/id])"
              ""
              "Example"
              "#:demo{:name \"\"}"]
             (take 5 (screen (press person :down "x") 7)))))))

(deftest errors-test
  (testing "a message saying why it couldn't ask"
    (is (= "Couldn't" (last (screen (press (start nil) :end :right) 4)))))
  (testing "or that the spec's gone"
    (is (= "No spec called :demo/gone" (last (screen (press (start "gone") :right) 4))))))
