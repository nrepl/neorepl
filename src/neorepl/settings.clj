(ns neorepl.settings
  "The REPL's settings: what there is, what each starts as, and what the
  config file changes. ,set changes them while the REPL runs, and so do the
  keys that toggle them."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [neorepl.cli :as cli])
  (:import (java.io File)))

(def all
  "The settings, in the order ,set lists them, each with its :name, what it
  starts as, its :default, and its :help. Each is on or off, but for the
  :limit? ones, a number, or off as false."
  [{:name :autoreload :default false :toggle? false
    :help "Reload what changed before each evaluation"}
   {:name :color :default true
    :help "Colors, unless NO_COLOR is set"}
   {:name :eldoc :default true :key \H
    :help "The called function's arglists, under the input"}
   {:name :pretty :default true :key \P
    :help "Values pretty printed, as wide as the terminal"}
   {:name :print-length :default false :limit? true
    :help "The items of a collection a value shows, or all"}
   {:name :print-level :default false :limit? true
    :help "How deep into collections a value shows, or all the way"}
   {:name :quiet :default false :key \Q
    :help "Leave out server output, taps and trace events"}
   {:name :server-output :default true
    :help "What the server's System/out and System/err get"}
   {:name :taps :default true
    :help "The values sent to tap>"}])

(def defaults
  (into {} (map (juxt :name :default)) all))

(def limits
  "The names of the settings that are a number, or off."
  (into #{} (comp (filter :limit?) (map :name)) all))

(def toggles
  "The settings that have a widget that toggles them, neorepl-toggle- and
  their name, bound to C-x and the Ctrl of their :key when they have one:
  those that are on or off, but for the ones that say :toggle? false, as
  :autoreload does, since turning it on reloads, which takes an evaluation."
  (remove #(or (:limit? %) (false? (:toggle? %))) all))

(defn parse-value
  "What text, as typed after ,set and the name, sets the setting called k
  to: true for on, false for off, or a number for a limit, nil for anything
  else."
  [k text]
  (let [text (str/lower-case text)]
    (cond
      (#{"off" "false"} text) false
      (limits k) (cli/parse-count text)
      (#{"on" "true"} text) true)))

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
                             (if (limits k) (or (false? v) (pos-int? v)) (boolean? v)) (assoc-in read [:settings k] v)
                             :else (update read :problems conj (str file " sets " k " to " (pr-str v) ", which takes "
                                                                    (if (limits k) "a number, or false" "true or false")))))
                         {:settings {} :problems []}
                         config)))
    (catch Exception e
      {:settings {} :problems [(str "Can't read " file ": " (ex-message e))]})))
