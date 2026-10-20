(ns neorepl.stacktrace
  "The REPL's stack trace browser: errors full-screen, their causes and
  their frames, to move between and pick one to open in an editor, or a
  cause to inspect."
  (:require [clojure.string :as str]
            [neorepl.browser :as browser]
            [neorepl.render :as render]))

(def ^:private help
  "↑↓ move  Enter edit  a all frames  q quit")

(def ^:private inspect-help
  "↑↓ move  Enter edit  i inspect  d data  a all frames  q quit")

(defn- cause-lines
  "causes, as features/last-error has them, as lines for browser/view: each
  cause's class and message, its data and its frames, as values whose
  :index is [e cause frame], e being the error's. The frames
  render/error-text leaves out are left out unless all?, and faint then."
  [e causes all?]
  (for [[i {:keys [data stacktrace] :as cause}] (map-indexed vector causes)
        line (concat (let [[heading & more] (str/split-lines (render/cause-text i cause))]
                       (cons [{:text heading :heading? true}]
                             (for [text more] [{:text text}])))
                     (for [text (render/data-lines data)]
                       [{:text text}])
                     (for [[j frame] (map-indexed vector stacktrace)
                           :let [hidden? (render/hidden-frame? frame)]
                           :when (or all? (not hidden?))]
                       [{:text "    at "}
                        (cond-> {:text (render/frame-text frame) :index [e i j]}
                          hidden? (assoc :style :notice))]))]
    line))

(defn- lines
  "errors, maps with their :causes, as features/last-error has them, and a
  :heading, if any, as lines for browser/view, a blank one between two."
  [errors all?]
  (vec (apply concat
              (interpose [[]]
                         (for [[e {:keys [heading causes]}] (map-indexed vector errors)]
                           (concat (when heading [[{:text heading :heading? true}]])
                                   (cause-lines e causes all?)))))))

(defn error-view
  "The browser/view of errors, maps with their :causes, as
  features/last-error has them, and a :heading, if any, like the test a test
  error's from, with all their frames if all?, and the same frame selected
  as in previous, if there's one and it's there, or the one before it."
  ([errors] (error-view errors false nil))
  ([errors all? previous]
   (let [{:keys [values] :as view} (assoc (browser/view (lines errors all?)) :errors errors :all? all?)
         [_ selected] (some-> previous browser/selected-value)
         i (when selected
             (last (keep-indexed (fn [i [_ index]] (when (<= (compare index selected) 0) i)) values)))]
     (cond-> view i (assoc :selected i)))))

(defn handle
  "view after key, as editor/full-screen has keys, or (reduced x), x being
  what the user picked, with the :view to come back to: the :frame to open,
  or the cause, by its index, to :inspect, or its ex-data if :ex-data?, if
  inspect?, or nil when they're done."
  [{:keys [errors all?] :as view} key inspect?]
  (let [view (dissoc view :message)
        [_ [e i j]] (browser/selected-value view)]
    (or (browser/moved view key)
        (case key
          :enter (if j
                   (reduced {:frame (get-in errors [e :causes i :stacktrace j]) :view view})
                   view)
          ("i" "d") (cond
                      (not (and inspect? i)) view
                      (and (= "d" key) (nil? (get-in errors [e :causes i :data])))
                      (assoc view :message "No data on this one")
                      :else (reduced {:inspect i :ex-data? (= "d" key) :view view}))
          "a" (error-view errors (not all?) view)
          ("q" :escape :ctrl-c) (reduced nil)
          view))))

(defn browse
  "Shows errors, as error-view takes them, full-screen with browse, a
  browser/browse partly applied to the editor, and returns what the user
  picked, as handle has it. It starts at view, one handle gave back, if
  there's one, and their causes can be inspected if inspect?."
  [browse errors {:keys [inspect? view]}]
  (browse (or view (error-view errors))
          (if inspect? inspect-help help)
          #(handle %1 %2 inspect?)))
