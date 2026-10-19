(ns neorepl.browser-test
  "Full-screen views, without a terminal."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [neorepl.browser :as browser]))

(def ^:private lines
  [[{:text "Class: "} {:text "clojure.lang.PersistentVector" :index 0}]
   []
   [{:text "--- Contents:" :heading? true}]
   [{:text "  0. "} {:text "{:a 1," :index 1}]
   [{:text " :b 2}" :index 1 :continued true}]
   [{:text "  1. "} {:text "2" :index 2}]
   [{:text "  2. "} {:text "3" :index 3}]])

(defn screen
  "What the screen shows of view, height lines of it, as text, with the
  selection in brackets, scrolled the way browse scrolls it."
  [view height]
  (for [line (browser/draw (browser/scrolled view (dec height)) "help" height)]
    (str/join (for [{:keys [text selected?]} line]
                (if selected? (str "[" text "]") text)))))

(defn- press [view & keys]
  (reduce browser/moved view keys))

(deftest view-test
  (testing "the first value selected"
    (is (= ["Class: [clojure.lang.PersistentVector]"
            ""
            "--- Contents:"
            "  0. {:a 1,"
            " :b 2}"
            "  1. 2"
            "  2. 3"
            ""
            "help"]
           (screen (browser/view lines) 9))))
  (testing "nothing, without values"
    (is (nil? (:selected (browser/view [[{:text "Value: nil"}]])))))
  (testing "a message instead of the help"
    (is (= "No luck" (last (screen (assoc (browser/view lines) :message "No luck") 3)))))
  (testing "values with a style of their own"
    (is (= [{:text "at "} {:text "f" :style :notice}]
           (first (browser/draw (assoc (browser/view [[{:text "at "} {:text "f" :index 0 :style :notice}]
                                                      [{:text "g" :index 1}]])
                                       :selected 1)
                                "help" 3))))))

(deftest moved-test
  (let [view (assoc (browser/view lines) :height 3 :selected 1)]
    (testing "moving between values"
      (is (= 2 (:selected (press view :down))))
      (is (= 3 (:selected (press view :down :down :down :down))))
      (is (= 0 (:selected (press view :up :up :up))))
      (is (= 3 (:selected (press view :end))))
      (is (= 0 (:selected (press view :end :home)))))
    (testing "scrolling to keep the selection on the screen"
      (is (= [" :b 2}" "  1. 2" "  2. [3]"] (butlast (screen (press view :end) 4)))))
    (testing "a screen at a time"
      (let [paged (press view :page-down)]
        (is (= 3 (:top paged)))
        (is (= 1 (:selected paged)))
        (is (= 0 (:top (press paged :page-up)))))
      (testing "past lines without values, until the selection moves"
        (let [view (browser/view (into [[{:text "Value: "} {:text "1" :index 0}]]
                                       (for [i (range 6)] [{:text (str "line " i)}])))
              paged (press (browser/scrolled view 3) :page-down)]
          (is (= ["line 2" "line 3" "line 4"] (butlast (screen paged 4))))
          (is (= "Value: [1]" (first (screen (press paged :down) 4)))))))
    (testing "other keys aren't for moving"
      (is (nil? (browser/moved view "x"))))))

(deftest prompted-test
  (let [view (assoc (browser/view lines) :prompt {:label "Name: " :text ""})
        typed #(reduce browser/prompted view %&)]
    (testing "typing after the label at the bottom, with a block for the cursor"
      (is (= "Name: ab[ ]" (last (screen (typed "a" "x" :backspace "b") 3)))))
    (testing "the keys that move around or quit are for typing too"
      (is (= "jq" (:text (:prompt (typed "j" :down :page-down "q"))))))
    (testing "Backspace with nothing typed"
      (is (= "" (:text (:prompt (typed :backspace))))))
    (testing "Escape and Ctrl-C drop the prompt"
      (doseq [key [:escape :ctrl-c]]
        (is (= "help" (last (screen (typed "a" key) 3))))))))
