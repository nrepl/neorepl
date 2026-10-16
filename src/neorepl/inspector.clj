(ns neorepl.inspector
  "The REPL's inspector: a full-screen view of a value on the server, the
  way cider-nrepl's inspector renders it, to move around in and dig into.
  The inspector itself lives in the session, on the server."
  (:require [clojure.string :as str]
            [neorepl.browser :as browser]
            [neorepl.features :as features]
            [neorepl.nrepl :as nrepl]))

(def ^:private help
  "↑↓ move  Enter inspect  ← back  [ ] page  v view  P pretty  g refresh  q quit")

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

(defn- fit
  "view, the inspector's, refreshed with the page size that fills its
  :height, if it doesn't. Twice at most, for items that take more than a
  line."
  ([client session view] (fit client session view 2))
  ([client session {:keys [lines height] :as current} tries]
   (if-let [size (and (pos? tries) (fitted-page-size lines height))]
     (recur client session
            (assoc (view (:lines (features/inspector client session {:op "inspect-refresh" :page-size size})))
                   :height height)
            (dec tries))
     current)))

(defn- ask
  "The view after msg, an inspector op, goes to the server, fitted to the
  screen current was on, or current with a :message saying why it
  couldn't. Losing the connection ends the inspection."
  [client session {:keys [height] :as current} msg]
  (try
    (fit client session (assoc (view (:lines (features/inspector client session msg)))
                               :height height))
    (catch clojure.lang.ExceptionInfo e
      (when (= ::nrepl/disconnected (:type (ex-data e)))
        (throw e))
      (assoc current :message (ex-message e)))))

(defn handle
  "view, the inspector's, after key, as
  editor/full-screen has keys, or (reduced nil) when the user's done.
  Inspecting something else asks the server, with (ask msg)."
  [view key ask]
  (let [view (dissoc view :message)
        op #(ask view {:op %})]
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
          "g" (op "inspect-refresh")
          ("q" :escape :ctrl-c) (reduced nil)
          view))))

(defn browse
  "Shows lines, the inspector's view in session, full-screen with browse, a
  browser/browse partly applied to the editor, until the user's done with
  it. A collection's first page gets as many items as fill the screen,
  height lines high."
  [browse client session lines height]
  (let [ask (partial ask client session)]
    ;; The browser keeps the :height up to date as it draws; this one's
    ;; for the first page, before it does.
    (browse (fit client session (assoc (view lines) :height (dec height)))
            help
            #(handle %1 %2 ask))))
