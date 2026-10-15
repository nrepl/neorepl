(ns neorepl.stacktrace
  "The REPL's stack trace browser: an error full-screen, its causes and
  their frames, to move between and pick one to open in an editor."
  (:require [clojure.string :as str]
            [neorepl.browser :as browser]
            [neorepl.render :as render]))

(def ^:private help
  "↑↓ move  Enter edit  a all frames  q quit")

(defn- lines
  "causes, as features/last-error has them, as lines for browser/view: each
  cause's class and message, its data and its frames, as values whose
  :index is [cause frame]. The frames render/error-text leaves out are left
  out unless all?, and faint then."
  [causes all?]
  (vec (for [[i {:keys [data stacktrace] :as cause}] (map-indexed vector causes)
             line (concat (let [[heading & more] (str/split-lines (render/cause-text i cause))]
                            (cons [{:text heading :heading? true}]
                                  (for [text more] [{:text text}])))
                          (for [text (render/data-lines data)]
                            [{:text text}])
                          (for [[j frame] (map-indexed vector stacktrace)
                                :let [hidden? (render/hidden-frame? frame)]
                                :when (or all? (not hidden?))]
                            [{:text "    at "}
                             (cond-> {:text (render/frame-text frame) :index [i j]}
                               hidden? (assoc :style :notice))]))]
         line)))

(defn error-view
  "The browser/view of causes, as features/last-error has them, with all
  their frames if all?, and the same frame selected as in previous, if
  there's one and it's there, or the one before it."
  ([causes] (error-view causes false nil))
  ([causes all? previous]
   (let [{:keys [values] :as view} (assoc (browser/view (lines causes all?)) :causes causes :all? all?)
         [_ selected] (some-> previous browser/selected-value)
         i (when selected
             (last (keep-indexed (fn [i [_ index]] (when (<= (compare index selected) 0) i)) values)))]
     (cond-> view i (assoc :selected i)))))

(defn handle
  "view after key, as editor/full-screen has keys, or (reduced frame), the
  frame to open, or (reduced nil) when the user's done."
  [{:keys [causes all?] :as view} key]
  (or (browser/moved view key)
      (case key
        :enter (if-let [[_ [i j]] (browser/selected-value view)]
                 (reduced (get-in causes [i :stacktrace j]))
                 view)
        "a" (error-view causes (not all?) view)
        ("q" :escape :ctrl-c) (reduced nil)
        view)))

(defn browse
  "Shows causes, an error as features/last-error has it, full-screen with
  browse, a browser/browse partly applied to the editor, and returns the
  frame the user picked to open, or nil."
  [browse causes]
  (browse (error-view causes) help handle))
