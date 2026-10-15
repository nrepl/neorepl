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

(defn error-text
  "An error from last-error as text, the way Java prints stack traces, minus
  the frames of the tools around the code and the duplicates."
  [causes]
  (str/join "\n"
            (for [[i {:keys [data stacktrace] :as cause}] (map-indexed vector causes)
                  line (concat [(cause-text i cause)]
                               (data-lines data)
                               (for [frame stacktrace
                                     :when (not (hidden-frame? frame))]
                                 (str "    at " (frame-text frame))))]
              line)))

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

(defn- counted [n word]
  (str n " " word (when-not (= 1 n) "s")))

(defn- failure-text
  "A test that didn't pass, from a run-tests report, as clojure.test prints it."
  [{:keys [type ns var context message expected actual error file line]}]
  (str/join "\n" (remove nil? [(str (str/upper-case type)
                                     ;; Not in a test, when a fixture fails.
                                     (some->> (not-empty (str/join "/" (remove nil? [ns var]))) (str " in "))
                                     (when file (str " (" (location-text {:file file :line line}) ")")))
                                context
                                message
                                (some->> expected (labeled "expected: "))
                                (if (= "error" type)
                                  (some->> error (labeled "   error: "))
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
