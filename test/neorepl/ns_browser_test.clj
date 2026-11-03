(ns neorepl.ns-browser-test
  "The namespace browser, without a terminal or a server."
  (:require [clojure.test :refer [deftest is testing]]
            [neorepl.browser-test :refer [screen]]
            [neorepl.ns-browser :as ns-browser]))

(def ^:private vars
  {"demo.core" [{:name "greet" :arglists "([who])" :doc "Says hi.\n  To who."}
                {:name "state"}]})

(def ^:private ask
  "What the browser asks a server, answered here."
  {:namespaces #(if % ["clojure.core" "demo.core" "demo.core-test"] ["demo.core" "demo.core-test"])
   :vars #(or (vars %) (throw (ex-info (str "No namespace: " %) {})))
   :doc #(when (= "demo.core/greet" %) "demo.core/greet\n([who])\n  Says hi.\n  To who.")})

(defn- press
  "view after keys, or what the user picked once handle has it reduced."
  [view & keys]
  (reduce #(ns-browser/handle %1 %2 ask) view keys))

(defn- start []
  (ns-browser/start-view ["demo.core" "demo.core-test"] "demo.core-test"))

(defn- shown
  "What the screen shows of view, 4 lines high, but for its last line."
  [view]
  (butlast (screen view 4)))

(deftest namespaces-test
  (testing "the project's, with the one the REPL's in selected"
    (is (= ["The project's namespaces"
            "demo.core"
            "[demo.core-test]"
            "↑↓ move  Enter switch  → vars  e edit  a all  / filter  q quit"]
           (screen (start) 4))))
  (testing "or saying there are none"
    (is (= "None of the project's namespaces are loaded, a shows the rest"
           (last (screen (ns-browser/start-view [] "user") 4)))))
  (testing "Enter switching to the one selected"
    (is (= "demo.core" (:ns (press (start) :up :enter)))))
  (testing "a toggling all of them, keeping the selection"
    (let [all (press (start) "a")]
      (is (= ["Loaded namespaces" "clojure.core" "demo.core" "[demo.core-test]"] (butlast (screen all 5))))
      (is (= ["The project's namespaces" "demo.core" "[demo.core-test]"] (shown (press all "a"))))))
  (testing "/ filtering them as the name's typed, whatever the case"
    (let [filtering (press (start) "/" "T" "e")]
      (is (= ["The project's namespaces" "[demo.core-test]" "" "/Te[ ]"] (screen filtering 4)))
      (testing "and Enter keeping the filter"
        (is (= ["The project's namespaces" "[demo.core-test]" ""] (shown (press filtering :enter))))
        (testing "through a"
          (is (= ["Loaded namespaces" "[demo.core-test]" ""] (shown (press filtering :enter "a"))))))
      (testing "and Escape dropping it"
        (is (= ["The project's namespaces" "demo.core" "[demo.core-test]"] (shown (press filtering :escape)))))))
  (testing "e opening the namespace"
    (is (= "demo.core-test" (:edit (press (start) "e")))))
  (testing "q ending it"
    (is (nil? (press (start) "q")))))

(deftest vars-test
  (let [vars (press (start) :up :right)]
    (testing "→ showing the namespace's vars, with the first line of their docs"
      (is (= ["demo.core"
              "[greet]  Says hi."
              "state"
              "↑↓ move  → doc  e edit  ← back  / filter  q quit"]
             (screen vars 4))))
    (testing "e opening the var"
      (is (= "demo.core/greet" (:edit (press vars "e")))))
    (testing "← going back where it was"
      (is (= ["The project's namespaces" "[demo.core]" "demo.core-test"] (shown (press vars :left)))))
    (testing "and → or Enter showing the doc"
      (let [doc (press vars :enter)]
        (is (= ["demo.core/greet" "([who])" "  Says hi." "  To who." "Space page  e edit  ← back  q quit"]
               (screen doc 5)))
        (is (= "demo.core/greet" (:edit (press doc "e"))))
        (is (= "demo.core" (first (screen (press doc :left) 4))))))
    (testing "or saying the var's gone"
      (is (= "Nothing called demo.core/state" (last (screen (press vars :down :right) 4)))))))

(deftest errors-test
  (testing "a message saying why it couldn't ask"
    (is (= "No namespace: demo.core-test" (last (screen (press (start) :right) 4))))))
