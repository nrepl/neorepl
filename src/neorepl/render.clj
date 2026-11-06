(ns neorepl.render
  "What the features find, as text."
  (:require [clojure.string :as str]
            [neorepl.cli :as cli]
            [neorepl.lexer :as lexer]
            [neorepl.settings :as settings]))

(defn doc-text
  "info as text, the way clojure.repl/doc prints it, minus the dashes."
  [{:keys [ns name arglists forms macro special-form doc]}]
  (str/join "\n" (concat [(str (some-> ns (str "/")) name)]
                         (if forms
                           (map #(str "  " %) forms)
                           (some->> arglists (str/join " ") (format "(%s)") vector))
                         (cond
                           special-form ["Special Form"]
                           macro ["Macro"])
                         (some->> doc (str "  ") vector))))

(defn- parameters
  "The parameters of an arglist, or of a shape, like (if test then else?),
  past the special form's name, as maps with their :start and :end in s,
  their :type and the :kind of form they :want, if they're particular about
  it. Only macros and special forms are shaped?: their parameters can be
  optional, ending in ?, like a doc-string? or an attr-map?, which want a
  string or a map then, or take all the arguments left, like exprs* or a
  body at the end, and a + or * after one repeats it. Their vectors and
  lists want those. A function's are what they are, & and all."
  [^String s shaped? shape?]
  (when (re-find #"^[\[(]" s)
    (let [inner (subs s 1 (max 1 (dec (count s))))
          spans (cond-> (lexer/form-spans (lexer/lex inner)) shape? rest)
          params (vec (for [[start end] spans
                            :let [p (subs inner start end)
                                  kind (when shaped? ({\[ :vector \( :list} (first p)))]]
                        {:start (inc start)
                         :end (inc end)
                         :want (or kind
                                   (when (and shaped? (str/ends-with? p "?"))
                                     (cond
                                       (str/includes? p "string") :string
                                       (str/includes? p "map") :map)))
                         :type (cond
                                 (= "&" p) :rest
                                 (not shaped?) :one
                                 kind :one
                                 (#{"+" "*"} p) :repeat
                                 (str/ends-with? p "?") :optional
                                 (re-find #"[*+]$" p) :many
                                 :else :one)}))]
      (cond-> params
        (and shaped? (seq params) (= "body" (let [{:keys [start end]} (peek params)] (subs s start end))))
        (assoc-in [(dec (count params)) :type] :many)))))

(defn- bound
  "The index of the parameter in params that the last of args goes into,
  as parameters has them, or nil when they can't take that many arguments,
  or arguments of those kinds. An optional parameter is skipped for an
  argument that doesn't fit it, or that fits the one after it, as a vector
  after fn's name? goes to its [params*]."
  [params args]
  (let [n (count params)]
    (loop [p 0
           a 0
           last-bound nil]
      (cond
        (= a (count args)) last-bound
        (<= n p) nil
        :else (let [{:keys [type want]} (params p)
                    kind (:kind (args a))
                    fits? #(or (nil? %) (nil? kind) (= % kind))
                    repeats? (= :repeat (:type (get params (inc p))))]
                (case type
                  ;; The rest takes all that's left.
                  :rest (when (< (inc p) n) (inc p))
                  :many p
                  :repeat (recur (inc p) a last-bound)
                  :optional (if (and (fits? want)
                                     (not (and (nil? want) kind (= kind (:want (get params (inc p)))))))
                              (recur (inc p) (inc a) p)
                              (recur (inc p) a last-bound))
                  :one (cond
                         (not (fits? want)) (when (and repeats? (= last-bound p))
                                              (recur (+ p 2) a last-bound))
                         repeats? (recur p (inc a) p)
                         :else (recur (inc p) (inc a) p))))))))

(defn- arglist-pieces
  "s, an arglist or a shape, as eldoc-line's pieces for the call's args."
  [^String s shaped? shape? args]
  (let [params (parameters s shaped? shape?)
        at (when params (bound params args))]
    (if-let [{:keys [start end]} (some-> at params)]
      ;; The rest goes with its &.
      (let [start (if (and (pos? at) (= :rest (:type (params (dec at)))))
                    (:start (params (dec at)))
                    start)]
        [[(subs s 0 start)] [(subs s start end) :eldoc-arg] [(subs s end)]])
      [[s :eldoc-faint]])))

(defn eldoc-line
  "What to show of call, from lexer/call-at, while info describes its op:
  the op's name and arglists, or a special form's shapes, as pieces, [text]
  or [text style], the style :eldoc-arg for the parameter the argument at
  the cursor goes into, :eldoc-faint for the arglists that can't take that
  many arguments, or of those kinds, and :eldoc-ns for the name's
  namespace, which can go when there's no room. Macros and special forms
  get matched by their shape, as parameters says. Nil without arglists or
  shapes."
  [{:keys [ns name arglists forms macro special-form]} {:keys [args]}]
  (when-let [lists (seq (or forms arglists))]
    (let [shaped? (boolean (or forms macro special-form))
          pieces (apply concat (interpose [[" "]] (map #(arglist-pieces % shaped? (boolean forms) args) lists)))]
      (if forms
        (vec pieces)
        (vec (concat (when ns [[(str ns "/") :eldoc-ns]])
                     [[(str name ": (")]]
                     pieces
                     [[")"]]))))))

(defn apropos-text
  "apropos matches, a line each: the name and the first line of the doc."
  [matches]
  (str/join "\n" (cli/columns (for [{:keys [name doc]} matches]
                                [name (str (some-> doc str/split-lines first))]))))

(defn frame-text
  "A frame of a stack trace, from last-error, as Java prints it, after at."
  [{:keys [name file line]}]
  (str name " ("
       (cond
         (and file line) (str file ":" line)
         file file
         :else "Unknown Source")
       ")"))

(defn cause-text
  "The class and the message of cause, from last-error, the nth one of an
  error's, as Java prints them."
  [n {:keys [class message]}]
  (str (when (pos? n) "Caused by: ") class (some->> message (str ": "))))

(defn data-lines
  "The ex-data of a cause, from last-error, as the lines Java would print
  after its message, indented."
  [data]
  (some->> data str/trim str/split-lines (map #(str "  " %))))

(defn hidden-frame?
  "Whether a frame of a stack trace, from last-error, is one of the tools
  around the code, or one more of the function before it, which error-text
  leaves out."
  [{:keys [flags]}]
  (some #{"tooling" "dup"} flags))

(defn- frame-lines
  "The frames of stacktrace, from last-error, minus those of the tools
  around the code and the duplicates, with at, its indentation included,
  before each."
  [stacktrace at]
  (for [frame stacktrace
        :when (not (hidden-frame? frame))]
    (str at (frame-text frame))))

(defn- causes-lines
  "causes, from last-error, as the lines Java prints stack traces in, the
  first being the nth cause, minus the frames frame-lines leaves out."
  [causes at n]
  (for [[i {:keys [data stacktrace] :as cause}] (map-indexed vector causes)
        line (concat [(cause-text (+ n i) cause)]
                     (data-lines data)
                     (frame-lines stacktrace at))]
    line))

(defn error-text
  "An error from last-error as text, the way Java prints stack traces, minus
  the frames of the tools around the code and the duplicates."
  [causes]
  (str/join "\n" (causes-lines causes "    at " 0)))

(defn- relative
  "path relative to dir, when it's in there and isn't dir itself, or else
  as it is, as with a root for dir, which everything is in."
  [path dir]
  (try
    (let [p (.normalize (.toPath (java.io.File. ^String path)))
          d (.normalize (.toPath (java.io.File. ^String dir)))]
      (if (and (.getParent d) (.startsWith p d) (not= p d))
        (str (.relativize d p))
        path))
    ;; Windows' InvalidPathException, which babashka doesn't have by name,
    ;; for what isn't a path there, like a jar:file: URL or <stdin>.
    (catch IllegalArgumentException _ path)))

(defn location-text
  "Where something is, its :file, :line and :column, the way compilers and
  editors write it: file:line:column, with a file in dir, by default the
  directory neorepl runs in, relative to it."
  ([location] (location-text location (System/getProperty "user.dir")))
  ([{:keys [file line column]} dir]
   (str/join ":" (remove nil? [(some-> file str (relative dir)) line column]))))

(defn references-text
  "Cross-references, from features/references, a line each: the function's
  name and where it's defined."
  [references]
  (str/join "\n" (cli/columns (map (juxt :name location-text) references))))

(defn- labeled
  "text after label, with the lines after the first lined up with it."
  [label text]
  (let [indent (apply str (repeat (count label) " "))]
    (str label (str/join (str "\n" indent) (str/split-lines text)))))

(defn problem-text
  "A delimiter problem, from lexer/delimiter-problem, as a line saying where
  and what, and whether repair/fix-file repaired it."
  [{:keys [message fixed fix-error] :as problem}]
  (str (location-text problem) ": " message
       (cond
         fixed ", repaired from the indentation"
         fix-error (str ", left alone: " fix-error))))

(defn- counted [n word]
  (str n " " word (when-not (= 1 n) "s")))

(defn- trace-text
  "causes, a test error's, from a run-tests report, as the lines of its
  stack trace that come after the error itself, which says what the first
  cause is."
  [[{:keys [stacktrace]} & more]]
  (concat (frame-lines stacktrace "  at ")
          (causes-lines more "  at " 1)))

(defn failure-heading
  "The first line of a test that didn't pass, from a run-tests report, as
  clojure.test prints it: what it is and where, relative to the directory
  neorepl runs in when it's in there, as the project's files are."
  ([failure] (failure-heading failure (System/getProperty "user.dir")))
  ([{:keys [type ns var file line]} dir]
   (str (str/upper-case type)
        ;; Not in a test, when a fixture fails.
        (some->> (not-empty (str/join "/" (remove nil? [ns var]))) (str " in "))
        (when file (str " (" (location-text {:file file :line line} dir) ")")))))

(defn failure-text
  "A test that didn't pass, from a run-tests report, as clojure.test prints it."
  [{:keys [type context message expected actual error causes] :as failure}]
  (str/join "\n" (remove nil? [(failure-heading failure)
                                context
                                message
                                (some->> expected (labeled "expected: "))
                                (if (= "error" type)
                                  (when error
                                    (labeled "   error: " (str/join "\n" (cons error (trace-text causes)))))
                                  (some->> actual (labeled "  actual: ")))])))

(defn test-report-text
  "A report from run-tests as text, the way clojure.test prints one: what
  didn't pass, then how it went."
  [{{:keys [var test fail error]} :summary :keys [failures]}]
  (str/join "\n\n" (concat (map failure-text failures)
                            [(if (zero? var)
                               "No tests ran."
                               (str "Ran " (counted var "test") " containing " (counted test "assertion") ".\n"
                                    (counted fail "failure") ", " (counted error "error") "."))])))

(defn inspector-text
  "The inspector's view, as features/inspector has it, as text, with each
  value to inspect further marked with the index to push it by, as [1],
  unless marks? is false."
  ([lines] (inspector-text lines true))
  ([lines marks?]
   (str/join "\n" (for [line lines]
                    (str/trimr (str/join (for [{:keys [text index continued]} line]
                                           (str (when (and marks? (not continued)) (some->> index (format "[%d]")))
                                                text))))))))

(defn tracing-text
  "What trace, untrace and the like did: the :results of tracing or
  untracing, a line each, what's traced, as the :nses and :vars, or how many
  vars got :untraced."
  [{:keys [results nses vars untraced]}]
  (cond
    results (str/join "\n" (for [{:keys [status var ns name]} results
                                 :let [what (or var (str "every function in " ns))]]
                             (case status
                               "traced" (str "Tracing " what)
                               "untraced" (str "Stopped tracing " what)
                               "already-traced" (str "Already tracing " what)
                               "not-traced" (str "Wasn't tracing " what)
                               "not-traceable" (str "Can't trace " var ", which is a macro or not a function")
                               "refused" (str "Won't trace " what ", which the server's own tools call: tracing it"
                                              " would take the server down")
                               "not-found" (str "No var or namespace called " name))))
    (= 0 untraced) "Nothing was traced"
    untraced (str "Stopped tracing " (counted untraced "function"))
    (or (seq nses) (seq vars)) (str/join "\n" (concat vars (map #(str "every function in " %) nses)))
    :else "Nothing's traced"))

(defn tap-text
  "A value tapped, as features/subscribe-taps summarizes it."
  [{:keys [summary]}]
  (str "tap> " summary))

(defn- setting-value-text
  "A setting's value v: on, off, or a limit's number."
  [v]
  (cond
    (true? v) "on"
    v (str v)
    :else "off"))

(defn setting-text
  "What the setting called k is set to, v."
  [k v]
  (str (name k) " " (setting-value-text v)))

(defn- duration-text
  "ms, a duration in milliseconds, in milliseconds under a second, then in
  seconds, to a tenth, and minutes and seconds past a minute."
  [ms]
  (cond
    (< ms 1000) (str ms "ms")
    (< ms 60000) (str (quot ms 1000) "." (quot (rem ms 1000) 100) "s")
    :else (str (quot ms 60000) "m" (quot (rem ms 60000) 1000) "s")))

(defn outcome
  "How an evaluation went, by its :status, as evaluation/evaluate has it,
  and how long it took, :ms, as pieces like eldoc-line's: a check mark in
  the :success style or a cross as an :err, and the time as a :notice."
  [{:keys [status ms]}]
  [(if (= "ok" status) ["✓" :success] ["✗" :err])
   [(str " " (duration-text ms)) :notice]])

(defn settings-text
  "settings, the REPL's, as ,set lists them: a line each, with what it's
  set to, what it is, and the key that toggles it, if any."
  [settings]
  (str/join "\n" (cli/columns (for [{:keys [name help key]} settings/all]
                                [(clojure.core/name name) (setting-value-text (get settings name))
                                 (cond-> help key (str " (C-x C-" (str/lower-case (str key)) ")"))]))))

(defn profile-summary-text
  "The table in the inspector's view of what profiling measured, as
  features/profile-summary has it, its Contents section, in columns,
  without the row numbers and the samples, which are for the inspector,
  and with n for :n and names without #', or nil when it has no rows."
  [lines]
  (let [heading? #(:heading? (first %))
        contents (->> lines (drop-while (complement heading?)) rest (take-while (complement heading?)))
        ;; Each cell is a value to inspect, the bars around them aren't.
        [header & rows :as table] (keep #(not-empty (keep (fn [{:keys [text index]}] (when index text)) %)) contents)]
    (when rows
      (str/join "\n" (cli/columns (for [row table]
                                    (for [[column cell] (map vector header row)
                                          :when (not (#{"#" ":samples"} column))]
                                      (str/replace cell #"^(:|#')" ""))))))))

(defn profiling-text
  "What profile and unprofile did: the :results of profiling or stopping, a
  line each, what was measured, as the :summary, or that it got :cleared."
  [{:keys [results summary cleared]}]
  (cond
    results (str/join "\n" (for [{:keys [status var ns name]} results
                                 :let [what (or var (str "every function in " ns))]]
                             (case status
                               "profiled" (str "Profiling " what)
                               "unprofiled" (str "Stopped profiling " what)
                               "already-profiled" (str "Already profiling " what)
                               "not-profiled" (str "Wasn't profiling " what)
                               "not-profilable" (str "Can't profile " var ", which is a macro or not a function")
                               "refused" (str "Won't profile " what ", which the server's own tools call: profiling it"
                                              " would take the server down")
                               "not-found" (str "No var or namespace called " name))))
    summary summary
    cleared "Forgot what profiling measured"))

(defn trace-event-text
  "A traced call, its return or what it threw, the way orchard prints them,
  a line under the calls it's in: the call, with the arguments, └─→ and the
  value, or └─✗ and the exception's class and message."
  [{:keys [phase name args value error depth]}]
  (str (str/join (repeat depth "│ "))
       (case phase
         "call" (str "(" (str/join " " (cons name args)) ")")
         "throw" (str "└─✗ " error)
         (str "└─→ " value))))
