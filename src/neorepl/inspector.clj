(ns neorepl.inspector
  "The REPL's inspector: a full-screen view of a value on the server, the
  way cider-nrepl's inspector renders it, to move around in and dig into.
  The inspector itself lives in the session, on the server."
  (:require [neorepl.browser :as browser]
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
  it."
  [browse client session lines]
  (let [ask (partial ask client session)]
    (browse (view lines) help #(handle %1 %2 ask))))
