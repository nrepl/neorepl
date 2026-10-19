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

(deftest define-test
  (let [view (assoc (inspector/view lines) :ns "user")
        asked (atom nil)
        new-lines (fn [msg]
                    (reset! asked msg)
                    lines)]
    (testing "d asks for a name at the bottom"
      (is (= "Define #'user/[ ]" (last (screen (press view new-lines "d") 4)))))
    (testing "Enter defines what's being inspected as that var"
      (let [defined (press view new-lines "d" "p" "i" "x" :backspace "c" "k" :enter)]
        (is (= {:op "inspect-def-current-value" :ns "user" :var-name "pick"} @asked))
        (is (= "Defined #'user/pick" (last (screen defined 4))))
        (testing "until the next key"
          (is (= "help" (last (screen (press defined new-lines :down) 4)))))))
    (testing "but not without a name, or after Escape"
      (reset! asked nil)
      (doseq [keys [["d" " " :enter] ["d" "x" :escape]]]
        (let [view (apply press view new-lines keys)]
          (is (nil? (:prompt view)))
          (is (= "help" (last (screen view 4))))))
      (is (nil? @asked)))
    (testing "nor with a name a var can't have"
      (is (= "Not a name for a var: a/b" (last (screen (press view new-lines "d" "a" "/" "b" :enter) 4))))
      (is (nil? @asked)))
    (testing "what the server can't do shows at the bottom"
      (let [failed (reduce #(inspector/handle %1 %2 (fn [view _] (assoc view :message "No luck")))
                           view
                           ["d" "x" :enter])]
        (is (= "No luck" (last (screen failed 4))))))))

(defn- view-lines
  "n lines of a view, the last saying which page it is, if it has pages."
  [n page]
  (concat (repeat (dec n) [{:text "  1. "} {:text "1" :index 1}])
          [[{:text (if page (str "  Page size: 10, showing page: " page) "Count: 3")}]]))

(deftest fitted-page-size-test
  (testing "on the first page, as many items as fill the screen, with a line to spare"
    (is (= 4 (inspector/fitted-page-size (view-lines 28 "1 of 9") 23)))
    (is (= 14 (inspector/fitted-page-size (view-lines 18 "1 of 9") 23))))
  (testing "but never none"
    (is (= 1 (inspector/fitted-page-size (view-lines 40 "1 of 9") 23))))
  (testing "none for a first page that fills the screen"
    (is (nil? (inspector/fitted-page-size (view-lines 22 "1 of 9") 23))))
  (testing "nor for the pages after it, which a new page size would leave"
    (is (nil? (inspector/fitted-page-size (view-lines 24 "2 of 9") 23)))
    (is (nil? (inspector/fitted-page-size (view-lines 5 "9 of 9") 23))))
  (testing "nor for a value without pages, which fewer items wouldn't shorten"
    (is (nil? (inspector/fitted-page-size (view-lines 30 nil) 23)))
    (is (nil? (inspector/fitted-page-size (view-lines 5 nil) 23)))))
