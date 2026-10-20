(ns neorepl.cli
  "Command line parsing, small enough to do without tools.cli.

  Options are specs: maps with the :long name (\"--port\"), optionally a
  :short one (\"-p\"), the :arg name for options that take a value, a :parse
  function for that value, its :default and a :doc line. The option's key is
  its long name as a keyword."
  (:require [clojure.string :as str]))

(defn option-key
  "The key of the option spec in parse's :options."
  [{:keys [long]}]
  (keyword (subs long 2)))

(defn- option?
  "Whether arg looks like an option: --name, a short -x, or one of the
  short options in by-name with more after it, so that symbols like -> and
  -main, the arguments of lookups, don't."
  [by-name arg]
  (boolean (or (re-matches #"--[^-=].*|-[a-zA-Z](=.*)?" arg)
               (and (< 2 (count arg)) (contains? by-name (subs arg 0 2))))))

(defn parse
  "Parses args against specs into {:options {...} :args [...]}, or {:error
  msg}. Options and arguments can come in any order, values either as the
  next argument or after =. -- ends the options; - alone is an argument, as
  is anything else that doesn't look like an option."
  [specs args]
  (let [by-name (into {} (for [spec specs
                               name ((juxt :short :long) spec)
                               :when name]
                           [name spec]))]
    (loop [[a & more :as args] args
           options (into {} (for [spec specs
                                  :when (contains? spec :default)]
                              [(option-key spec) (:default spec)]))
           positional []]
      (cond
        (empty? args) {:options options :args positional}
        (= "--" a) {:options options :args (into positional more)}
        (not (option? by-name a)) (recur more options (conj positional a))
        :else
        (let [[name inline] (str/split a #"=" 2)
              {:keys [arg parse] :or {parse identity} :as spec} (by-name name)]
          (cond
            (nil? spec) {:error (str "Unknown option: " name)}
            (not arg) (if inline
                        {:error (str name " takes no value")}
                        (recur more (assoc options (option-key spec) true) positional))
            (and (nil? inline) (empty? more)) {:error (str name " needs a value")}
            :else (let [value (or inline (first more))]
                    ;; parse returning nil or throwing both mean it's invalid.
                    (if-some [v (try (parse value) (catch Exception _ nil))]
                      (recur (if inline more (rest more))
                             (assoc options (option-key spec) v)
                             positional)
                      {:error (str "Invalid value for " name ": " value)}))))))))

(defn columns
  "rows, vectors of strings, as lines with the strings lined up in columns."
  [rows]
  (when (seq rows)
    (let [widths (apply map (fn [& cells] (apply max (map count cells))) rows)
          pad #(str %2 (str/join (repeat (- %1 (count %2)) " ")))]
      (for [row rows]
        (str/trimr (str/join "  " (map pad widths row)))))))

(defn indented-columns
  "columns, indented and joined into one string, as in --help texts."
  [rows]
  (str/join "\n" (map #(str "  " %) (columns rows))))

(defn summary
  "The options part of a --help text."
  [specs]
  (indented-columns (for [{:keys [short long arg doc]} specs]
                      [(str (if short (str short ", ") "    ") long (when arg (str " " arg)))
                       doc])))
