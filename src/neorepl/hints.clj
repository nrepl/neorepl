(ns neorepl.hints
  "What to suggest after code fails: for naming something that isn't
  there, where the name is in the code, and the fixes to try, the requires
  and imports cider-nrepl's resolve-missing and resolve-alias find for it
  and the names completion has that it's a typo of, and for the rest, what
  the error has to say past its message."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.features :as features]
            [neorepl.lexer :as lexer]
            [neorepl.nrepl :as nrepl]))

(def ^:private unresolved-patterns
  "What Clojure's compiler and babashka say when code names something that
  isn't there, by what it is. ClojureScript only warns."
  [[:symbol #"Unable to resolve symbol: ([^\s\]]+)"]
   [:class #"Unable to resolve classname: ([^\s\]]+)"]
   [:namespace #"No such namespace: ([^\s\]]+)"]
   [:var #"No such var: ([^\s\]]+)"]])

(defn unresolved
  "What err, what an evaluation printed to stderr, says first the code names
  that isn't there, as a map with its :kind, a :symbol, a :class, a
  :namespace or a :var, its :name, as err has it, and where it is, :at a
  [line column], when err says: on the line before, as Clojure does, or at
  the end of the line, as babashka does. Nil when it says nothing of the
  sort."
  [err]
  (let [lines (str/split-lines (str err))
        location #":(\d+):(\d+)[)\]]"]
    (some (fn [[before line]]
            (some (fn [[kind re]]
                    (when-let [[_ name] (re-find re line)]
                      (let [[_ at-line column] (or (re-find location line) (re-find location (str before)))]
                        (cond-> {:kind kind :name name}
                          at-line (assoc :at [(Long/parseLong at-line) (Long/parseLong column)])))))
                  unresolved-patterns))
          (map vector (cons nil lines) lines))))

(defn- about?
  "Whether text, a symbol's, is the one unresolved is about. An error about
  a class names it without the dot of a constructor call, and one about a
  var names its namespace, where the code may have an alias."
  [{:keys [kind name]} text]
  (let [sym (symbol text)]
    (case kind
      :symbol (= name text)
      :class (contains? #{name (str name ".")} text)
      :namespace (= name (namespace sym))
      :var (and (namespace sym) (= (clojure.core/name (symbol name)) (clojure.core/name sym))))))

(defn- located
  "The token of the symbol in code, as lexed, that unresolved is about: the
  first one at or after where it is, which for Clojure is the form it's in,
  or else the first one. Nil when code has none."
  [{s :text :keys [tokens]} {:keys [at] :as unresolved}]
  (let [found (filter #(and (= :symbol (:type %)) (about? unresolved (lexer/text s %))) tokens)
        from (or (some->> at (apply lexer/index s)) 0)]
    (or (first (filter #(<= from (:start %)) found))
        (first found))))

(defn- requires
  "The fixes to {:run} for the symbol written as alias/name, or name, which
  doesn't resolve, from what cider-nrepl found: the namespaces
  resolve-alias has for the alias first, then what resolve-missing has."
  [alias name aliases missing]
  (->> (concat
        (for [{:keys [ns]} aliases]
          (str "(require '[" ns " :as " alias "])"))
        (keep (fn [{:keys [type ns] candidate :name}]
                (case type
                  ;; A namespace that isn't loaded yet, by its name, or one
                  ;; with a var of that name, for the alias.
                  "ns" (cond
                         (= alias candidate) (str "(require '" candidate ")")
                         alias (str "(require '[" candidate " :as " alias "])")
                         :else (str "(require '[" candidate " :refer [" name "]])"))
                  "class" (str "(import '" candidate ")")
                  "type" (str "(require '" ns ") (import '" candidate ")")
                  ;; ClojureScript's macros take :refer-macros, which is
                  ;; for the ns form, as the REPL's require doesn't do them.
                  nil))
              missing))
       distinct
       (map #(hash-map :run %))))

(defn distance
  "How many edits turn a into b: inserting, deleting or changing a
  character, or swapping two next to each other, the commonest typo."
  [^String a ^String b]
  (let [n (count b)
        row (fn [prev prev2 i]
              (let [x (.charAt a (dec i))]
                (reduce (fn [row j]
                          (let [y (.charAt b (dec j))
                                d (min (inc (peek row))
                                       (inc (nth prev j))
                                       (+ (nth prev (dec j)) (if (= x y) 0 1)))]
                            (conj row (if (and (> i 1) (> j 1)
                                               (= x (.charAt b (- j 2)))
                                               (= (.charAt a (- i 2)) y))
                                        (min d (inc (nth prev2 (- j 2))))
                                        d))))
                        [i]
                        (range 1 (inc n)))))]
    (loop [i 1
           prev2 nil
           prev (vec (range (inc n)))]
      (if (> i (count a))
        (peek prev)
        (recur (inc i) prev (row prev prev2 i))))))

(defn- close
  "A few of names, the ones an edit or two from s, the closest first, or
  only an edit for a short s."
  [s names]
  (let [most (if (< (count s) 5) 1 2)]
    (->> names
         (keep #(let [d (distance s %)] (when (<= 1 d most) [d %])))
         (into (sorted-set))
         (map second)
         (take 3))))

(defn- completions
  "The candidates completion has where ctx says for prefix and the first
  two characters of s, or none for an s that short, which makes a typo
  there go unnoticed."
  [client ctx prefix s opts]
  (when (< 2 (count s))
    (map :candidate (features/complete client (str prefix (subs s 0 2)) ctx opts))))

(defn- name-typos
  "The fixes to {:replace} sym, written as alias/name, or name, with the
  candidates whose names it's a typo of and that have the same alias or
  namespace, or none, with dot after, as a constructor call has."
  [sym alias name dot candidates]
  (let [prefix (some-> alias (str "/"))]
    (->> candidates
         (keep #(let [found (symbol %)] (when (= alias (namespace found)) (clojure.core/name found))))
         (close name)
         (map #(hash-map :replace (str prefix % dot) :in-place-of sym)))))

(defn- alias-typos
  "The fixes to {:replace} alias, which isn't one, everywhere, with the
  aliases among candidates it's a typo of, which completion has as
  namespaces with a slash after, and babashka's leaves out."
  [alias candidates]
  (->> candidates
       (keep #(second (re-matches #"(.+)/" %)))
       (close alias)
       (map #(hash-map :replace (str % "/") :in-place-of (str alias "/")))))

(defn- fixed
  "The code fix runs, for form, the top-level form with the name, as lexed:
  what it says to :run, then form again, or form with what it says to
  :replace in place of the name, or of the alias, everywhere, since the
  same typo tends to go everywhere."
  [{s :text :keys [tokens]} {:keys [run replace in-place-of]}]
  (if run
    (str run "\n" s)
    (reduce (fn [form {:keys [start end type] :as token}]
              (let [text (lexer/text s token)]
                (cond
                  (not= :symbol type) form
                  (= in-place-of text) (str (subs form 0 start) replace (subs form end))
                  (and (str/ends-with? in-place-of "/") (str/starts-with? text in-place-of))
                  (str (subs form 0 start) replace (subs text (count in-place-of)) (subs form end))
                  :else form)))
            s
            (rseq tokens))))

(defn suggest
  "What to suggest for code, after err, what evaluating it printed to
  stderr, said it names something that isn't there: {:token token :fixes
  fixes}, the name's token in code and what could fix it where ctx says,
  each a map with the :input to evaluate for it and either the code to :run
  before the top-level form the name is in, or what to :replace the name,
  or the alias, with, :in-place-of what. Nil when err says nothing of the
  sort or the name isn't in code. The lookups go out at once, each with the
  :timeout-ms in opts, 2s by default, and one that fails leaves the others'
  fixes."
  [client ctx code err {:keys [timeout-ms] :or {timeout-ms 2000}}]
  (when-let [{:keys [kind] :as unresolved} (unresolved err)]
    (let [lexed (lexer/lex code)]
      (when-let [{:keys [start] :as token} (located lexed unresolved)]
        (let [sym (lexer/text code token)
              alias (namespace (symbol sym))
              name (name (symbol sym))
              ;; A constructor call's.
              dot (when (str/ends-with? name ".") ".")
              bare (cond-> name dot (subs 0 (dec (count name))))
              ;; Only the ones the kind of error leaves a point to: a var
              ;; that isn't there has an alias that is, and an alias that
              ;; isn't there completes to nothing.
              lookup (fn [needed? f]
                       (when needed?
                         (future (try
                                   (f {:timeout-ms timeout-ms})
                                   (catch Exception _ nil)))))
              aliases (lookup (and alias (not= :var kind)) #(features/resolve-alias client alias ctx %))
              missing (lookup (not= :var kind) #(features/resolve-missing client sym ctx %))
              alias-names (lookup (= :namespace kind) #(completions client ctx nil alias %))
              names (lookup (not= :namespace kind) #(completions client ctx (some-> alias (str "/")) bare %))
              found #(some-> % deref)
              [from to] (or (some #(when (<= (first %) start (dec (second %))) %) (lexer/form-spans lexed))
                            [0 (count code)])
              form (lexer/lex (subs code from to))]
          {:token token
           ;; An alias an edit from one there is likelier a typo than a
           ;; namespace to require under it.
           :fixes (mapv #(assoc % :input (fixed form %))
                        (concat (alias-typos alias (found alias-names))
                                (requires alias name (found aliases) (found missing))
                                (name-typos sym alias bare dot (found names))))})))))

(defn with-suggestions
  "result, of evaluating code in session, with the :suggestions suggest
  finds for it, when it's an eval-error it finds fixes for, where the code
  ran: in ns, or the session's namespace. The lookups get two seconds,
  or till the :deadline in opts, if that's sooner."
  [client session ns code {:keys [status err] :as result} {:keys [deadline]}]
  (let [timeout-ms (cond-> 2000 deadline (min (- deadline (System/currentTimeMillis))))]
    (if-let [fixes (when (and (= "eval-error" status) (pos? timeout-ms) (unresolved err))
                     (try
                       (some-> (suggest client {:session session :ns (or ns (:ns result) (nrepl/evaluation-ns client session))}
                                        code err {:timeout-ms timeout-ms})
                               :fixes
                               not-empty)
                       ;; They're only suggestions.
                       (catch Exception _ nil)))]
      (assoc result :suggestions fixes)
      result)))

(def ^:private explanations
  "What the commonest errors tend to mean, by the class of the cause and
  what its message has."
  [["java.lang.ClassCastException" #"cannot be cast to class clojure\.lang\.IFn"
    "A value that isn't a function got called, as the first in a list is"]
   ["java.lang.ClassCastException" #"cannot be cast to class java\.lang\.Number"
    "Arithmetic got a value that isn't a number"]
   ["java.lang.IllegalArgumentException" #"Don't know how to create ISeq from"
    "A sequence function got a value that isn't a collection"]])

(defn- explanation
  "What cause, as features/last-error has it, tends to mean, if it's one of
  the commonest errors: arithmetic getting nil is a NullPointerException in
  clojure.lang.Numbers."
  [{:keys [class message stacktrace]}]
  (or (some (fn [[c re text]] (when (and (= c class) (re-find re (str message))) text)) explanations)
      (when (and (= "java.lang.NullPointerException" class)
                 (some-> (first stacktrace) :name (str/starts-with? "clojure.lang.Numbers/")))
        "Arithmetic got nil, as a key a map doesn't have gives")))

(defn- fields
  "The ex-data printed as data, a map's, as [key value] rows, each on a
  line, a namespaced map's keys qualified, but for the :clojure.error/ keys
  Clojure's compiler adds, which its message says already. Nil for
  anything else."
  [data]
  (when-let [[_ ns body] (re-matches #"(?s)(?:#:([^\s{]+))?\{(.*)\}" (str/trim (str data)))]
    (let [forms (lexer/forms body)
          qualified #(if (and ns (re-matches #":[^:/]+" %)) (str ":" ns "/" (subs % 1)) %)]
      (when (even? (count forms))
        (into [] (comp (map (fn [[k v]] [(qualified k) (str/replace v #"\s*\n\s*" " ")]))
                       (remove #(str/starts-with? (first %) ":clojure.error/")))
              (partition 2 forms))))))

(defn- source-lines
  "The line of frame, one of the project's, in its file, and the one
  before it, as [number text] pairs, or nil when this machine can't read
  the file."
  [{:keys [path line]}]
  (when (and path line)
    (try
      (with-open [r (io/reader path)]
        (let [from (max 1 (dec line))
              found (vec (take (- (inc line) from) (drop (dec from) (line-seq r))))]
          (when (= (- (inc line) from) (count found))
            (mapv vector (range from (inc line)) found))))
      (catch Exception _ nil))))

(defn details
  "What the error whose causes features/last-error found says past its
  message, as a vector of maps: an :explanation of what the commonest
  errors tend to mean, the :fields of a cause's ex-data, or its :data when
  it's no map, the :arglists of the function an arity error is about, by
  its :name, and the :lines of the project's code the error came from, its
  :frame's. The arglists are info's where ctx says."
  [client ctx opts causes]
  (let [root (last causes)
        [_ called] (re-find #"^Wrong number of args \(\d+\) passed to: (\S+)$" (str (:message root)))
        ;; A var's, rather than a function's of no var, or babashka's
        ;; interpreter's, which it names.
        arglists (when (and called (re-matches #"[^/$]+/[^/$]+" called) (not (re-find #"--|/eval\d" called)))
                   (try
                     (:arglists (features/info client called ctx opts))
                     (catch clojure.lang.ExceptionInfo _ nil)))
        ;; A file's, unlike the REPL's frames, which babashka flags project too.
        frame (some #(when (and (:path %) (some #{"project"} (:flags %))) %) (:stacktrace root))
        lines (some-> frame source-lines)]
    (vec (concat (when-let [text (explanation root)] [{:explanation text}])
                 (for [{:keys [data]} causes
                       :when data
                       :let [rows (fields data)]
                       :when (or (nil? rows) (seq rows))]
                   (if rows {:fields rows} {:data data}))
                 (when (seq arglists) [{:name called :arglists arglists}])
                 (when lines [{:frame frame :lines lines}])))))
