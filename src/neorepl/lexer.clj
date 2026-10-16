(ns neorepl.lexer
  "A small Clojure lexer. It finds where forms end, classifies tokens for
  highlighting and locates the symbols around the cursor.

  It deliberately doesn't read code: reading on the client breaks on
  `::alias/kw`, reader conditionals and unknown tagged literals, none of
  which matter for telling whether the user has finished typing a form."
  (:require [clojure.string :as str]))

;; Characters that end a symbol, keyword or number: whitespace, delimiters and
;; the reader's terminating macro characters.
(def ^:private terminators #{\( \) \[ \] \{ \} \" \; \@ \^ \` \~ \\ \,})

(defn- atom-end? [c]
  (or (Character/isWhitespace (char c)) (contains? terminators c)))

(defn- scan-atom
  "Index of the first character at or after i that ends an atom."
  [^String s i]
  (let [n (.length s)]
    (loop [j i]
      (if (and (< j n) (not (atom-end? (.charAt s j))))
        (recur (inc j))
        j))))

(defn- string-token
  "[type end] for the string whose opening quote is at i, or an :open-string
  running to the end of s when nothing closes it."
  [type ^String s i]
  (loop [j (inc i)]
    (if (< j (.length s))
      (case (.charAt s j)
        \\ (recur (+ j 2))
        \" [type (inc j)]
        (recur (inc j)))
      [:open-string (.length s)])))

(defn- line-end [^String s i]
  (or (str/index-of s "\n" i) (.length s)))

(defn- atom-type [^String text]
  (cond
    (str/starts-with? text ":") :keyword
    (re-find #"^[+-]?\d" text) :number
    :else (case text
            ("nil" "true" "false") :constant
            :symbol)))

(defn- dispatch-token
  "[type end] for the # form starting at index i of s."
  [^String s i]
  (case (when (< (inc i) (.length s)) (.charAt s (inc i)))
    (\( \{) [:open (+ i 2)]
    \" (string-token :regex s (inc i))
    \_ [:discard (+ i 2)]
    \! [:comment (line-end s i)]
    (\' \=) [:macro-char (+ i 2)]
    \# [:number (scan-atom s i)]
    (if (.startsWith s "#?@" i)
      [:reader-macro (+ i 3)]
      ;; #?, #:ns, #inst and other tagged literals
      [:reader-macro (scan-atom s (inc i))])))

(defn- next-token
  "Returns [type end] for the token starting at index i of s. The type is nil
  for whitespace, commas included."
  [^String s i]
  (let [c (.charAt s i)]
    (if (Character/isWhitespace c)
      [nil (inc i)]
      (case c
        \, [nil (inc i)]
        \; [:comment (line-end s i)]
        \" (string-token :string s i)
        ;; The character right after the backslash always belongs to the
        ;; literal, so \( and \" are chars, not delimiters.
        \\ [:char (scan-atom s (min (.length s) (+ i 2)))]
        (\( \[ \{) [:open (inc i)]
        (\) \] \}) [:close (inc i)]
        (\' \` \~ \@ \^) [:macro-char (inc i)]
        \# (dispatch-token s i)
        (let [end (scan-atom s i)]
          [(atom-type (subs s i end)) end])))))

(defn tokenize
  "Splits s into tokens: maps with a :type and the :start and :end (exclusive)
  indexes of the token in s. Whitespace and commas aren't tokens.

  Types: :open, :close, :symbol, :keyword, :number (##Inf and friends too),
  :constant (nil, true, false), :string, :open-string (unterminated), :regex,
  :char, :comment, :discard (#_), :macro-char (' ` ~ @ ^ #' #=) and
  :reader-macro (#?, #?@, #:ns and tagged literal tags)."
  [^String s]
  (loop [i 0 tokens []]
    (if (< i (.length s))
      (let [[type end] (next-token s i)]
        (recur end (cond-> tokens type (conj {:type type :start i :end end}))))
      tokens)))

(defn lex
  "Tokenizes s once for the functions below, which all take the result."
  [s]
  {:text s :tokens (tokenize s)})

(defn text
  "The text of token in s."
  [s {:keys [start end]}]
  (subs s start end))

(defn closer
  "The closing delimiter matching the opening delimiter token."
  [s token]
  (case (last (text s token)) \( ")" \[ "]" \{ "}"))

(defn- form-done
  "Counts a finished form against the innermost pending prefix. A prefix that
  got all the forms it needs is a finished form itself, for the next one."
  [prefixes]
  (if-let [needed (peek prefixes)]
    (if (= 1 needed)
      (recur (pop prefixes))
      (conj (pop prefixes) (dec needed)))
    prefixes))

(defn- update-prefixes [frames f & args]
  (apply update-in frames [(dec (count frames)) :prefixes] f args))

(def ^:private top-level
  "The frames before any tokens."
  [{:prefixes []}])

(defn- scan
  "Walks tokens of s keeping a stack of frames, starting from frames: the top
  level first, then one per open delimiter, {:open token :closer \")\"
  :prefixes [...]}. :prefixes holds how many forms each pending prefix (' #_
  ^ #inst ...) still needs. When a closing delimiter doesn't match its
  opener or has none, it stops there and returns the error as a map, the way
  delimiter-error does.

  Frames don't refer back to s, so scanning can carry on with the tokens of
  another string."
  [s tokens frames]
  (reduce
   (fn [frames {:keys [type] :as token}]
     (case type
       :open (conj frames {:open token :closer (closer s token) :prefixes []})
       :close (let [{:keys [open closer]} (peek frames)]
                (cond
                  (nil? open) (reduced {:error :unmatched :close token})
                  (= (text s token) closer) (update-prefixes (pop frames) form-done)
                  :else (reduced {:error :mismatched :open open :close token :expected closer})))
       (:macro-char :discard :reader-macro)
       (update-prefixes frames conj (if (= "^" (text s token)) 2 1))
       :comment frames
       (update-prefixes frames form-done)))
   frames
   tokens))

(defn- waiting?
  "Whether frames from scan still wait for something: delimiters left open
  or a prefix without its form. Errors don't wait, they're for the server to
  report."
  [frames]
  (boolean (and (vector? frames)
                (or (next frames) (seq (:prefixes (first frames)))))))

(def empty-input
  "Input with no lines yet, for add-line."
  {:frames top-level})

(defn- string-end
  "The index right after the quote that ends a string continuing into line,
  or nil when the string goes on past it. A backslash at the end of the
  previous line escaped the newline, so line starts unescaped."
  [^String line]
  (loop [i 0]
    (when (< i (.length line))
      (case (.charAt line i)
        \\ (recur (+ i 2))
        \" (inc i)
        (recur (inc i))))))

(defn add-line
  "input with line added. Only the new line gets lexed, so checking input
  line by line takes time in proportion to its length."
  [{:keys [frames in-string?] :as input} line]
  (let [end (when in-string? (string-end line))]
    (if (and in-string? (not end))
      input
      (let [;; A string that ends here is a finished form.
            frames (cond-> frames (and end (vector? frames)) (update-prefixes form-done))
            line (cond-> line end (subs end))
            tokens (tokenize line)
            ;; Strings are the only tokens that span lines.
            string? (= :open-string (:type (peek tokens)))]
        (cond-> {:frames (cond->> frames
                           ;; Once input is broken, only an open string still
                           ;; keeps it from being complete.
                           (vector? frames) (scan line (cond-> tokens string? pop)))}
          string? (assoc :in-string? true))))))

(defn input-incomplete?
  "Whether the user isn't done typing the lines added to input: they end
  inside a string, with delimiters left open or with a prefix such as ' or
  ^:private still waiting for its form. Mismatched and extra closing
  delimiters make input complete, since those are errors for the server to
  report."
  [{:keys [frames in-string?]}]
  (or (boolean in-string?) (waiting? frames)))

(defn incomplete?
  "input-incomplete? for the code in text, typed all at once."
  [text]
  (input-incomplete? (add-line empty-input text)))

(defn forms
  "The text of each top-level form in s, without comments. A prefix such as
  ' goes with the form after it."
  [s]
  (loop [[{:keys [type start end] :as token} & more] (tokenize s)
         depth 0
         from nil
         forms []]
    (cond
      (nil? token) forms
      (and (zero? depth) (= :comment type)) (recur more depth from forms)
      :else (let [from (or from start)
                  depth (case type :open (inc depth) :close (dec depth) depth)]
              (if (and (<= depth 0)
                       (not (#{:open :macro-char :discard :reader-macro} type)))
                (recur more 0 nil (conj forms (subs s from end)))
                (recur more depth from forms))))))

(def ^:private symbolic-types #{:symbol :keyword :constant})

(defn symbol-at
  "The symbol, keyword or constant token the cursor is in or right after."
  [{:keys [tokens]} cursor]
  (some (fn [{:keys [type start end] :as token}]
          (when (and (<= start cursor end) (contains? symbolic-types type))
            token))
        tokens))

(defn enclosing-op
  "The symbol token in operator position of the innermost list around the
  cursor, e.g. `map` for \"(map inc |\" and `let` for \"(let [x |\". Nil
  outside of lists and when the head of the list isn't a symbol."
  [{:keys [text tokens]} cursor]
  (let [before (into [] (take-while #(< (:start %) cursor)) tokens)
        frames (scan text before top-level)]
    (when-let [open (when (vector? frames)
                      (->> frames (filter #(= ")" (:closer %))) last :open))]
      (let [head (some #(when (>= (:start %) (:end open)) %) before)]
        (when (= :symbol (:type head))
          head)))))

(defn delimiter-error
  "The first delimiter in the lexed text that doesn't balance, or nil when
  they all do. A map with the kind of :error and the tokens involved:

    :unclosed     the :open delimiter, the outermost one left open
    :mismatched   the :close delimiter, the :open one it doesn't match and
                  the closer that one :expected
    :unmatched    a :close delimiter with nothing to close
    :open-string  the :open string or regex that never ends"
  [{s :text :keys [tokens]}]
  (let [frames (scan s tokens top-level)]
    (cond
      (map? frames) frames
      ;; Always the last token, so anything else went wrong before it.
      (= :open-string (:type (peek tokens))) {:error :open-string :open (peek tokens)}
      (next frames) {:error :unclosed :open (:open (second frames))})))

(defn position
  "The line and column, both counting from 1, of index in s. Columns count
  characters the way editors do, with surrogate pairs as one."
  [^String s index]
  (let [before (subs s 0 index)
        line-start (inc (.lastIndexOf before "\n"))]
    [(inc (count (filter #{\newline} before)))
     (inc (.codePointCount before (int line-start) (int index)))]))

(defn delimiter-problem
  "The first delimiter that doesn't balance in s, as a map with its :line,
  :column and a :message, or nil."
  [s]
  (when-let [{:keys [error open close expected]} (delimiter-error (lex s))]
    (let [at #(position s (:start %))
          [line column] (at (or close open))
          delimiter #(text s %)]
      {:line line
       :column column
       :message (case error
                  :unclosed (str "Unclosed " (delimiter open))
                  :unmatched (str "Unmatched " (delimiter close))
                  :mismatched (format "Mismatched %s: expected %s to close the %s at %s"
                                      (delimiter close) expected (delimiter open) (str/join ":" (at open)))
                  :open-string (if (str/starts-with? (delimiter open) "#")
                                 "Unterminated regex"
                                 "Unterminated string"))})))

(defn file-problem
  "The first delimiter problem in the file at path, as delimiter-problem has
  it plus the :file. When the file can't be read, that's the problem, with
  only a :message."
  [path]
  (let [text (try (slurp path) (catch java.io.IOException _ nil))]
    (some->> (if text
               (delimiter-problem text)
               {:message (if (.exists (java.io.File. ^String path)) "Can't read the file" "No such file")})
             (into {:file path}))))
