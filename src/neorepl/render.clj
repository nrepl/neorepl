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

(defn- frame-text [{:keys [name file line]}]
  (str "    at " name " ("
       (cond
         (and file line) (str file ":" line)
         file file
         :else "Unknown Source")
       ")"))

(defn error-text
  "An error from last-error as text, the way Java prints stack traces, minus
  the frames of the tools around the code and the duplicates."
  [causes]
  (str/join "\n"
            (for [[i {:keys [class message data stacktrace]}] (map-indexed vector causes)
                  line (concat [(str (when (pos? i) "Caused by: ") class (some->> message (str ": ")))]
                               (some->> data str/trim str/split-lines (map #(str "  " %)))
                               (for [{:keys [flags] :as frame} stacktrace
                                     :when (not-any? #{"tooling" "dup"} flags)]
                                 (frame-text frame)))]
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
