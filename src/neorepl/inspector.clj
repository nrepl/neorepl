(ns neorepl.inspector
  "The REPL's inspector: a full-screen view of a value on the server, the
  way cider-nrepl's inspector renders it, to move around in and dig into.
  The inspector itself lives in the session, on the server."
  (:require [neorepl.editor :as editor]
            [neorepl.features :as features]
            [neorepl.nrepl :as nrepl]))

(def ^:private help
  "↑↓ move  Enter inspect  ← back  [ ] page  v view  P pretty  g refresh  q quit")

(defn- heading? [line]
  (:heading? (first line)))

(defn view
  "The view of lines, as features/inspector has them: the :lines, the
  :values to inspect further, as [line index] pairs, the one :selected,
  as its position there, the first one under a heading if any, like the
  first item of a collection, and the line at the :top of the screen, with
  :reveal? to scroll the selection onto it."
  [lines]
  (let [values (vec (for [[n line] (map-indexed vector lines)
                          {:keys [index continued]} line
                          :when (and index (not continued))]
                      [n index]))
        first-heading (first (keep-indexed #(when (heading? %2) %1) lines))]
    {:lines lines
     :values values
     :selected (when (seq values)
                 (or (first (keep-indexed (fn [i [n]] (when (and first-heading (> n first-heading)) i)) values))
                     0))
     :top 0
     :reveal? true}))

(defn- selected-value
  "The selected value in view, as its [line index] pair."
  [{:keys [values selected]}]
  (some->> selected (get values)))

(defn scrolled
  "view on a screen height lines high, which it remembers as its :height,
  for paging, scrolled so that the selected value's line is on it if the
  selection's to be revealed. Paging leaves it where it is, to get to lines
  without values on them."
  [{:keys [top reveal?] :as view} height]
  (let [view (-> view (assoc :height height) (dissoc :reveal?))
        [n] (selected-value view)]
    (cond-> view
      (and reveal? n) (assoc :top (cond
                                    (< n top) n
                                    (>= n (+ top height)) (inc (- n height))
                                    :else top)))))

(defn- select [{:keys [values] :as view} i]
  (cond-> view
    (seq values) (assoc :selected (max 0 (min i (dec (count values))))
                        :reveal? true)))

(defn- page
  "view scrolled a screen down, or up when direction is -1, with the
  selection moved onto the screen if it's off it now."
  [{:keys [lines values top height] :as view} direction]
  (let [top (max 0 (min (+ top (* direction height)) (- (count lines) height)))
        on-screen? #(<= top % (+ top height -1))
        on-screen (keep-indexed (fn [i [n]] (when (on-screen? n) i)) values)
        [n] (selected-value view)]
    (cond-> (assoc view :top top)
      (and (seq on-screen) n (not (on-screen? n)))
      (assoc :selected (if (pos? direction) (first on-screen) (last on-screen))))))

(defn draw
  "What the screen shows of view, width columns and height lines of it: as
  much of the view as fits and a line of help, or a message, at the bottom."
  [{:keys [lines top message] :as view} _width height]
  (let [[_ selected-index] (selected-value view)
        body (for [line (take (dec height) (drop top lines))]
               (for [{:keys [text index heading?]} line]
                 (cond
                   (and index (= index selected-index)) {:text text :selected? true}
                   index {:text text :code? true}
                   heading? {:text text :style :heading}
                   :else {:text text})))]
    (concat body
            (repeat (- (dec height) (count body)) [])
            [[{:text (or message help) :style :notice}]])))

(defn- ask
  "The view after msg, an inspector op, goes to the server, or current with
  a :message saying why it couldn't. Losing the connection ends the
  inspection."
  [client session current msg]
  (try
    (view (:lines (features/inspector client session msg)))
    (catch clojure.lang.ExceptionInfo e
      (when (= ::nrepl/disconnected (:type (ex-data e)))
        (throw e))
      (assoc current :message (ex-message e)))))

(defn handle
  "view after key, as editor/full-screen has keys, or :done. Inspecting
  something else asks the server, with (ask msg)."
  [{:keys [values] :as view} key ask]
  (let [view (dissoc view :message)
        op #(ask view {:op %})]
    (case key
      (:down :tab "j" "n") (select view (inc (or (:selected view) -1)))
      (:up :backtab "k" "p") (select view (dec (or (:selected view) 1)))
      (:page-down " ") (page view 1)
      :page-up (page view -1)
      :home (select view 0)
      :end (select view (dec (count values)))
      (:enter :right) (if-let [[_ index] (selected-value view)]
                        (ask view {:op "inspect-push" :idx index})
                        view)
      (:backspace :left) (op "inspect-pop")
      "]" (op "inspect-next-page")
      "[" (op "inspect-prev-page")
      "v" (op "inspect-toggle-view-mode")
      "P" (op "inspect-toggle-pretty-print")
      "g" (op "inspect-refresh")
      ("q" :escape :ctrl-c) :done
      view)))

(defn browse
  "Shows lines, the inspector's view in session, full-screen on editor,
  until the user's done with it."
  [editor client session lines]
  (let [state (atom (view lines))
        ask (partial ask client session)]
    (editor/full-screen editor
                        (fn [width height]
                          (draw (swap! state scrolled (dec height)) width height))
                        (fn [key]
                          (let [view (handle @state key ask)]
                            (if (= :done view)
                              :done
                              (reset! state view)))))))
