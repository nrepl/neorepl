(ns neorepl.settings
  "The REPL's settings: what there is, what each starts as, and what the
  config file changes. ,set changes them while the REPL runs, and so do the
  keys that toggle them."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.io File)))

(def all
  "The settings, in the order ,set lists them, each with its :name, what it
  starts as, its :default, and its :help. Each is on or off. Each has a
  widget that toggles it, neorepl-toggle- and its name, bound to C-x and
  the Ctrl of its :key when it has one, unless it says :toggle? false, as
  :autoreload does: turning it on reloads, which takes an evaluation."
  [{:name :autoreload :default false :toggle? false
    :help "Reload what changed before each evaluation"}
   {:name :color :default true
    :help "Colors, unless NO_COLOR is set"}
   {:name :eldoc :default true :key \H
    :help "The called function's arglists, under the input"}
   {:name :quiet :default false :key \Q
    :help "Leave out server output, taps and trace events"}
   {:name :server-output :default true
    :help "What the server's System/out and System/err get"}
   {:name :taps :default true
    :help "The values sent to tap>"}])

(def defaults
  (into {} (map (juxt :name :default)) all))

(defn parse-value
  "What text, as typed after ,set and the name, sets a setting to: true for
  on, false for off, nil for anything else."
  [text]
  (case (str/lower-case text)
    ("on" "true") true
    ("off" "false") false
    nil))

(defn read-file
  "What the config file sets, as {:settings settings :problems problems}:
  the settings it sets to what they take, and a sentence for each thing it
  has that isn't, or for the whole file, when it's no map. No file sets
  nothing."
  [^File file]
  (try
    (let [config (when (some-> file .isFile)
                   (edn/read-string (slurp file)))]
      (cond
        (nil? config) {:settings {} :problems []}
        (not (map? config)) {:settings {} :problems [(str file " should have a map of settings, like {:eldoc false}")]}
        :else (reduce-kv (fn [read k v]
                           (cond
                             (not (contains? defaults k)) (update read :problems conj (str file " sets " (pr-str k) ", which is no setting"))
                             (not (boolean? v)) (update read :problems conj (str file " sets " k " to " (pr-str v)
                                                                                 ", which takes true or false"))
                             :else (assoc-in read [:settings k] v)))
                         {:settings {} :problems []}
                         config)))
    (catch Exception e
      {:settings {} :problems [(str "Can't read " file ": " (ex-message e))]})))
