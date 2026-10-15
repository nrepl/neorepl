(ns neorepl.inspector-test
  "The inspector's view, without a terminal."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [neorepl.inspector :as inspector]))

(def ^:private lines
  [[{:text "Class: "} {:text "clojure.lang.PersistentVector" :index 0}]
   []
   [{:text "--- Contents:" :heading? true}]
   [{:text "  0. "} {:text "{:a 1," :index 1}]
   [{:text " :b 2}" :index 1 :continued true}]
   [{:text "  1. "} {:text "2" :index 2}]
   [{:text "  2. "} {:text "3" :index 3}]])

(defn- screen
  "What the screen shows of view, height lines of it, as text, with the
  selection in brackets, scrolled the way browse scrolls it."
  [view height]
  (for [line (inspector/draw (inspector/scrolled view (dec height)) 40 height)]
    (str/join (for [{:keys [text selected?]} line]
                (if selected? (str "[" text "]") text)))))

(defn- press
  "view after keys, with the server answering inspect-push and the like with
  (new-lines msg)."
  [view new-lines & keys]
  (reduce (fn [view key]
            (inspector/handle view key (fn [current msg]
                                         (assoc (inspector/view (new-lines msg)) :height (:height current)))))
          view
          keys))

(deftest view-test
  (testing "the first value under a heading is the one selected"
    (is (= ["Class: clojure.lang.PersistentVector"
            ""
            "--- Contents:"
            "  0. [{:a 1,]"
            "[ :b 2}]"
            "  1. 2"
            "  2. 3"
            ""
            "↑↓ move  Enter inspect  ← back  [ ] page  v view  P pretty  g refresh  q quit"]
           (screen (inspector/view lines) 9))))
  (testing "or the first value, without headings"
    (is (= 0 (:selected (inspector/view (take 1 lines))))))
  (testing "nothing, without values"
    (is (nil? (:selected (inspector/view [[{:text "Value: nil"}]]))))))

(deftest handle-test
  (let [view (assoc (inspector/view lines) :height 3)
        pushed (atom nil)
        new-lines (fn [msg]
                    (reset! pushed msg)
                    [[{:text "Value: "} {:text "2" :index 1}]])]
    (testing "moving between values"
      (is (= 2 (:selected (press view new-lines :down))))
      (is (= 3 (:selected (press view new-lines :down :down :down :down))))
      (is (= 0 (:selected (press view new-lines :up :up :up))))
      (is (= 3 (:selected (press view new-lines :end))))
      (is (= 0 (:selected (press view new-lines :end :home)))))
    (testing "scrolling to keep the selection on the screen"
      (is (= [" :b 2}" "  1. 2" "  2. [3]"] (butlast (screen (press view new-lines :end) 4)))))
    (testing "a screen at a time"
      (let [paged (press view new-lines :page-down)]
        (is (= 3 (:top paged)))
        (is (= 1 (:selected paged)))
        (is (= 0 (:top (press paged new-lines :page-up)))))
      (testing "past lines without values, until the selection moves"
        (let [view (inspector/view (into [[{:text "Value: "} {:text "1" :index 0}]]
                                         (for [i (range 6)] [{:text (str "line " i)}])))
              paged (press (inspector/scrolled view 3) new-lines :page-down)]
          (is (= ["line 2" "line 3" "line 4"] (butlast (screen paged 4))))
          (is (= "Value: [1]" (first (screen (press paged new-lines :down) 4)))))))
    (testing "inspecting the selected value"
      (is (= "Value: [2]" (first (screen (press view new-lines :enter) 2))))
      (is (= {:op "inspect-push" :idx 1} @pushed)))
    (testing "the other keys ask the server"
      (doseq [[key op] {:backspace "inspect-pop" :left "inspect-pop" "]" "inspect-next-page"
                        "[" "inspect-prev-page" "v" "inspect-toggle-view-mode"
                        "P" "inspect-toggle-pretty-print" "g" "inspect-refresh"}]
        (press view new-lines key)
        (is (= {:op op} @pushed))))
    (testing "what the server can't do shows at the bottom, until the next key"
      (let [failed (inspector/handle view "g" (fn [view _] (assoc view :message "No luck")))]
        (is (= "No luck" (last (screen failed 3))))
        (is (nil? (:message (press failed new-lines :down))))))
    (testing "done"
      (doseq [key ["q" :escape :ctrl-c]]
        (is (= :done (inspector/handle view key (fn [_ _]))))))
    (testing "other keys do nothing"
      (is (= view (inspector/handle view "x" (fn [_ _])))))))
