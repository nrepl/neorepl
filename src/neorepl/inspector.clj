(ns neorepl.inspector
  "The REPL's inspector: a full-screen view of a value on the server, the
  way cider-nrepl's inspector renders it, to move around in and dig into.
  The inspector itself lives in the session, on the server."
  (:require [clojure.string :as str]
            [neorepl.browser :as browser]
            [neorepl.features :as features]
            [neorepl.nrepl :as nrepl]))

(def ^:private help
  "↑↓ move  → inspect  ← back  [ ] page  v view  P pretty  d def  g refresh  q quit")

(defn view
  "The browser/view of lines, as features/inspector has them, with the first
  value under a heading selected, like the first item of a collection, if
  there's one."
  [lines]
  (let [{:keys [values] :as view} (browser/view lines)
        first-heading (first (keep-indexed #(when (:heading? (first %2)) %1) lines))]
    (cond-> view
      first-heading (assoc :selected (or (first (keep-indexed (fn [i [n]] (when (> n first-heading) i)) values))
                                         (:selected view))))))

(def ^:private surroundings
  "About how many lines the inspector's view of a collection has besides its
  items: the class, the count, the headings, the page and the view mode."
  12)

(defn start-page-size
  "The page size to start inspecting with on a screen height lines high."
  [height]
  (max 1 (- height 1 surroundings)))

(defn- page-info
  "The page size and the page of lines, as features/inspector has them, if
  the value has more than one."
  [lines]
  (some (fn [line]
          (when-let [[_ & numbers] (re-find #"Page size: (\d+), showing page: (\d+)"
                                            (str/join (map :text line)))]
            (map #(Long/parseLong %) numbers)))
        lines))

(defn fitted-page-size
  "The page size that fills height lines with lines, the inspector's view,
  or nil when they fill them already or can't
  be made to. Only the first page of a value with pages gets one, since the
  inspector goes back to that one when the page size changes, with a line
  to spare for the ... that the pages after it start with. A value without
  pages, like an object with its fields, keeps its page size, which taking
  items off wouldn't shorten."
  [lines height]
  (let [[shown page] (page-info lines)]
    (when (= 1 page)
      (let [size (max 1 (+ shown (- height 1 (count lines))))]
        (when (not= size shown)
          size)))))

(defn- shown
  "current, the inspector's view, after msg, an inspector op, goes to the
  server: the view of what the server shows then, or current, with the
  selection where it was, when that's what it showed already, as after
  defining it as a var."
  [client session current msg]
  (let [{:keys [lines]} (features/inspector client session msg)]
    (if (= lines (:lines current))
      current
      (merge current (view lines)))))

(defn- fit
  "view, the inspector's, refreshed with the page size that fills its
  :height, if it doesn't. Twice at most, for items that take more than a
  line."
  ([client session view] (fit client session view 2))
  ([client session {:keys [lines height] :as current} tries]
   (if-let [size (and (pos? tries) (fitted-page-size lines height))]
     (recur client session (shown client session current {:op "inspect-refresh" :page-size size}) (dec tries))
     current)))

(defn- ask
  "current, the inspector's view, after msg, an inspector op, goes to the
  server, as shown has it, fitted to the screen it was on, or with a
  :message saying why it couldn't. Losing the connection ends the
  inspection."
  [client session current msg]
  (try
    (fit client session (shown client session current msg))
    (catch clojure.lang.ExceptionInfo e
      (when (= ::nrepl/disconnected (:type (ex-data e)))
        (throw e))
      (assoc current :message (ex-message e)))))

(defn- define
  "view, the inspector's, with what's being inspected defined as a var in
  its :ns, named what its :prompt has, with (ask view msg), and a :message
  saying so, or why it couldn't. A blank name only drops the prompt."
  [{:keys [ns prompt] :as view} ask]
  (let [view (dissoc view :prompt)
        name (str/trim (:text prompt))]
    (cond
      (str/blank? name) view
      (not (features/var-name? name)) (assoc view :message (str "Not a name for a var: " name))
      :else (let [asked (ask view {:op "inspect-def-current-value" :ns ns :var-name name})]
              (cond-> asked
                (not (:message asked)) (assoc :message (str "Defined #'" ns "/" name)))))))

(defn handle
  "view, the inspector's, after key, as
  editor/full-screen has keys, or (reduced nil) when the user's done.
  Inspecting something else asks the server, with (ask view msg), and so
  does defining what's being inspected as a var in the view's :ns, under the
  name d prompts for."
  [view key ask]
  (let [view (dissoc view :message)
        op #(ask view {:op %})]
    (if (:prompt view)
      (if (= :enter key)
        (define view ask)
        (browser/prompted view key))
      (or (browser/moved view key)
          (case key
            (:enter :right) (if-let [[_ index] (browser/selected-value view)]
                              (ask view {:op "inspect-push" :idx index})
                              view)
            (:backspace :left) (op "inspect-pop")
            "]" (op "inspect-next-page")
            "[" (op "inspect-prev-page")
            "v" (op "inspect-toggle-view-mode")
            "P" (op "inspect-toggle-pretty-print")
            "d" (assoc view :prompt {:label (str "Define #'" (:ns view) "/") :text ""})
            "g" (op "inspect-refresh")
            ("q" :escape :ctrl-c) (reduced nil)
            view)))))

(defn browse
  "Shows lines, the inspector's view in session, full-screen with browse, a
  browser/browse partly applied to the editor, until the user's done with
  it, defining vars in ns. A collection's first page gets as many items as
  fill the screen, height lines high."
  [browse client session ns lines height]
  (let [ask (partial ask client session)]
    ;; The browser keeps the :height up to date as it draws; this one's
    ;; for the first page, before it does.
    (browse (fit client session (assoc (view lines) :height (dec height) :ns ns))
            help
            #(handle %1 %2 ask))))
