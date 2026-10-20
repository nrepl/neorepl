(ns neorepl.render
  "What the features find, as text."
  (:require [clojure.string :as str]
            [neorepl.cli :as cli]))

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

(defn eldoc-text
  "What to show while the function info describes is being called: its
  arglists, or a special form's shapes. Nil when there are none."
  [{:keys [arglists forms]}]
  (some->> (or forms arglists) seq (str/join " ")))

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

(defn location-text
  "Where something is, its :file, :line and :column, the way compilers and
  editors write it: file:line:column."
  [{:keys [file line column]}]
  (str/join ":" (remove nil? [file line column])))

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

(defn- relative
  "path relative to dir, when it's in there, or else as it is."
  [path dir]
  (let [p (.toPath (java.io.File. ^String path))
        d (.toPath (java.io.File. ^String dir))]
    (if (.startsWith p d)
      (str (.relativize d p))
      path)))

(defn failure-heading
  "The first line of a test that didn't pass, from a run-tests report, as
  clojure.test prints it: what it is and where, relative to the directory
  neorepl runs in when it's in there, as the project's files are."
  ([failure] (failure-heading failure (System/getProperty "user.dir")))
  ([{:keys [type ns var file line]} dir]
   (str (str/upper-case type)
        ;; Not in a test, when a fixture fails.
        (some->> (not-empty (str/join "/" (remove nil? [ns var]))) (str " in "))
        (when file (str " (" (location-text {:file (relative file dir) :line line}) ")")))))

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
  value to inspect further marked with the index to push it by, as [1]."
  [lines]
  (str/join "\n" (for [line lines]
                   (str/trimr (str/join (for [{:keys [text index continued]} line]
                                          (str (when-not continued (some->> index (format "[%d]"))) text)))))))

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
                               "not-found" (str "No var or namespace called " name))))
    (= 0 untraced) "Nothing was traced"
    untraced (str "Stopped tracing " (counted untraced "function"))
    (or (seq nses) (seq vars)) (str/join "\n" (concat vars (map #(str "every function in " %) nses)))
    :else "Nothing's traced"))

(defn trace-event-text
  "A traced call, or its return, the way orchard prints them, a line under
  the calls it's in: the call, with the arguments, or └─→ and the value."
  [{:keys [phase name args value depth]}]
  (str (str/join (repeat depth "│ "))
       (if (= "call" phase)
         (str "(" (str/join " " (cons name args)) ")")
         (str "└─→ " value))))
