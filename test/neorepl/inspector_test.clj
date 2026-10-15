(ns neorepl.inspector-test
  "The inspector's keys, without a terminal."
  (:require [clojure.test :refer [deftest is testing]]
            [neorepl.browser-test :refer [screen]]
            [neorepl.inspector :as inspector]))

(def ^:private lines
  [[{:text "Class: "} {:text "clojure.lang.PersistentVector" :index 0}]
   [{:text "--- Contents:" :heading? true}]
   [{:text "  0. "} {:text "1" :index 1}]])

(defn- press
  "view after keys, with the server answering inspect-push and the like with
  (new-lines msg)."
  [view new-lines & keys]
  (reduce (fn [view key]
            (inspector/handle view key (fn [_ msg] (inspector/view (new-lines msg)))))
          view
          keys))

(deftest view-test
  (testing "the first value under a heading is the one selected"
    (is (= ["Class: clojure.lang.PersistentVector"
            "--- Contents:"
            "  0. [1]"
            "help"]
           (screen (inspector/view lines) 4))))
  (testing "or the first value, without headings"
    (is (= 0 (:selected (inspector/view (take 1 lines))))))
  (testing "nothing, without values"
    (is (nil? (:selected (inspector/view [[{:text "Value: nil"}]]))))))

(deftest handle-test
  (let [view (inspector/view lines)
        pushed (atom nil)
        new-lines (fn [msg]
                    (reset! pushed msg)
                    [[{:text "Value: "} {:text "2" :index 1}]])]
    (testing "moving between values"
      (is (= 0 (:selected (press view new-lines :up)))))
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
        (is (reduced? (inspector/handle view key (fn [_ _]))))))
    (testing "other keys do nothing"
      (is (= view (inspector/handle view "x" (fn [_ _])))))))
