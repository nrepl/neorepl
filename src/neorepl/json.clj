(ns neorepl.json
  "Just enough JSON for --format json and MCP, so we don't need a library."
  (:require [clojure.string :as str]))

(defn- escape [s]
  (str/replace s #"[\"\\\x00-\x1f]"
               #(case %
                  "\"" "\\\""
                  "\\" "\\\\"
                  "\n" "\\n"
                  "\r" "\\r"
                  "\t" "\\t"
                  (format "\\u%04x" (int (first %))))))

(defn- write-key [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn write-str
  "x as a JSON string. Maps become objects, with keywords as keys losing their
  colon, other collections become arrays, and keywords and symbols strings."
  [x]
  (cond
    (nil? x) "null"
    (or (boolean? x) (integer? x)) (str x)
    (and (double? x) (Double/isFinite x)) (str x)
    (string? x) (str \" (escape x) \")
    (or (keyword? x) (symbol? x)) (write-str (write-key x))
    (map? x) (str "{"
                  (str/join "," (for [[k v] x] (str (write-str (write-key k)) ":" (write-str v))))
                  "}")
    (coll? x) (str "[" (str/join "," (map write-str x)) "]")
    :else (throw (ex-info (str "Can't write as JSON: " (pr-str x)) {:value x}))))

(defn- invalid [^String s i what]
  (throw (ex-info (str "Invalid JSON at " i ": " what) {:type ::invalid :json s :index i})))

(defn- skip-space ^long [^String s ^long i]
  (if (and (< i (.length s)) (Character/isWhitespace (.charAt s i)))
    (recur s (inc i))
    i))

(defn- expect [^String s ^long i ^String word value]
  (if (.startsWith s word i)
    [value (+ i (.length word))]
    (invalid s i (str "expected " word))))

(defn- read-string-at
  "The string starting with the quote at i, and where it ends."
  [^String s ^long i]
  (let [sb (StringBuilder.)]
    (loop [i (inc i)]
      (when (>= i (.length s))
        (invalid s i "unterminated string"))
      (let [c (.charAt s i)]
        (case c
          \" [(str sb) (inc i)]
          \\ (let [e (when (< (inc i) (.length s)) (.charAt s (inc i)))]
               (case e
                 \u (let [hex (when (<= (+ i 6) (.length s)) (subs s (+ i 2) (+ i 6)))]
                      (when-not (and hex (re-matches #"[0-9a-fA-F]{4}" hex))
                        (invalid s i "bad \\u escape"))
                      ;; Surrogate pairs come as two escapes, which add up.
                      (.append sb (char (Integer/parseInt hex 16)))
                      (recur (+ i 6)))
                 (let [replacement (case e
                                     \" \" \\ \\ \/ \/ \b \backspace \f \formfeed
                                     \n \newline \r \return \t \tab
                                     (invalid s i "bad escape"))]
                   (.append sb replacement)
                   (recur (+ i 2)))))
          (if (< (int c) 0x20)
            (invalid s i "control character in a string")
            (do (.append sb c)
                (recur (inc i)))))))))

(defn- read-number-at [^String s ^long i]
  (let [m (re-matcher #"-?(?:0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?" s)]
    (if (and (.find m i) (= i (.start m)))
      (let [text (.group m)]
        [(if (or (.group m 1) (.group m 2))
           (Double/parseDouble text)
           (Long/parseLong text))
         (.end m)])
      (invalid s i "bad number"))))

(declare read-value-at)

(defn- read-seq-at
  "The array or object starting at i, read with (read-item s i) into to, and
  where it ends."
  [^String s i close read-item to]
  (let [i (skip-space s (inc i))]
    (if (and (< i (.length s)) (= close (.charAt s i)))
      [to (inc i)]
      (loop [acc to i i]
        (let [[acc i] (read-item s i acc)
              i (skip-space s i)
              c (when (< i (.length s)) (.charAt s i))]
          (cond
            (= \, c) (recur acc (skip-space s (inc i)))
            (= close c) [acc (inc i)]
            :else (invalid s i (str "expected , or " close))))))))

(defn- read-value-at [^String s ^long i]
  (let [i (skip-space s i)]
    (if (>= i (.length s))
      (invalid s i "unexpected end")
      (case (.charAt s i)
        \{ (read-seq-at s i \}
                        (fn [s i acc]
                          (when-not (and (< i (.length s)) (= \" (.charAt s i)))
                            (invalid s i "expected a key"))
                          (let [[k i] (read-string-at s i)
                                i (skip-space s i)
                                _ (when-not (and (< i (.length s)) (= \: (.charAt s i)))
                                    (invalid s i "expected :"))
                                [v i] (read-value-at s (inc i))]
                            [(assoc acc (keyword k) v) i]))
                        {})
        \[ (read-seq-at s i \]
                        (fn [s i acc]
                          (let [[v i] (read-value-at s i)]
                            [(conj acc v) i]))
                        [])
        \" (read-string-at s i)
        \t (expect s i "true" true)
        \f (expect s i "false" false)
        \n (expect s i "null" nil)
        (read-number-at s i)))))

(defn read-str
  "The value of s, a JSON text: objects as maps with keyword keys, arrays as
  vectors, integers as longs and other numbers as doubles. Throws ::invalid
  when s isn't JSON."
  [^String s]
  (let [[value i] (read-value-at s 0)
        i (skip-space s i)]
    (when (< i (.length s))
      (invalid s i "trailing characters"))
    value))
