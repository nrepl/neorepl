(ns neorepl.browser
  "Full-screen views of lines to move around in, from one value marked in
  them to the next, like the inspector's and the stack trace's."
  (:require [neorepl.editor :as editor]))

(defn view
  "The view of lines, vectors of segments, maps with the :text and, for the
  values to move between, their :index, or :heading? for the heading of a
  section, and a :style of their own, if any: the :lines, the :values, as
  [line index] pairs, the one :selected, as its position there, the first,
  and the line at the :top of the screen, with :reveal? to scroll the
  selection onto it."
  [lines]
  (let [values (vec (for [[n line] (map-indexed vector lines)
                          {:keys [index continued]} line
                          :when (and index (not continued))]
                      [n index]))]
    {:lines lines
     :values values
     :selected (when (seq values) 0)
     :top 0
     :reveal? true}))

(defn selected-value
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

(defn moved
  "view after key, as editor/full-screen has keys, if it's one that moves
  around in it, or nil: the arrows, j and k or n and p, Tab, Home and End
  for the values, and Space, Page Up and Page Down for screens."
  [{:keys [values] :as view} key]
  (case key
    (:down :tab "j" "n") (select view (inc (or (:selected view) -1)))
    (:up :backtab "k" "p") (select view (dec (or (:selected view) 1)))
    (:page-down " ") (page view 1)
    :page-up (page view -1)
    :home (select view 0)
    :end (select view (dec (count values)))
    nil))

(defn prompted
  "view, which has a :prompt at the bottom, a map with its :label and the
  :text typed after it, after key, as editor/full-screen has keys, but for
  Enter, which is the caller's to take: printable characters go in the
  :text, Backspace takes the last one out, and Escape or Ctrl-C drops the
  prompt."
  [view key]
  (case key
    :backspace (update-in view [:prompt :text] #(subs % 0 (max 0 (dec (count %)))))
    (:escape :ctrl-c) (dissoc view :prompt)
    (cond-> view
      (string? key) (update-in [:prompt :text] str key))))

(defn draw
  "What the screen shows of view, height lines of it: as
  much of the view as fits and help, a line saying what the keys do, or the
  view's :message, or its :prompt, at the bottom. Values are highlighted as
  code, unless they have a :style."
  [{:keys [lines top message prompt] :as view} help height]
  (let [[_ selected-index] (selected-value view)
        body (for [line (take (dec height) (drop top lines))]
               (for [{:keys [text index heading? style]} line]
                 (cond
                   (and index (= index selected-index)) {:text text :selected? true}
                   style {:text text :style style}
                   index {:text text :code? true}
                   heading? {:text text :style :heading}
                   :else {:text text})))]
    (concat body
            (repeat (- (dec height) (count body)) [])
            [(if-let [{:keys [label text]} prompt]
               ;; A full-screen view hides the cursor, so this stands in.
               [{:text label :style :notice} {:text text} {:text " " :selected? true}]
               [{:text (or message help) :style :notice}])])))

(defn browse
  "Shows view full-screen on editor, with help at the bottom, until (handle
  view key) returns (reduced x), rather than the view to show next, and
  returns x. The REPL hands this out as its :browse, partly applied to its
  editor."
  [editor view help handle]
  (let [state (atom view)
        result (atom nil)]
    (editor/full-screen editor
                        (fn [_width height]
                          (draw (swap! state scrolled (dec height)) help height))
                        (fn [key]
                          (let [view (handle @state key)]
                            (if (reduced? view)
                              (do (reset! result @view)
                                  :done)
                              (reset! state view)))))
    @result))
