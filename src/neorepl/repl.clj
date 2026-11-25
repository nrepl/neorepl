(ns neorepl.repl
  "The REPL, with a line editor on a terminal and without one elsewhere."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.browser :as browser]
            [neorepl.cli :as cli]
            [neorepl.debugger :as debugger]
            [neorepl.editor :as editor]
            [neorepl.evaluation :as evaluation]
            [neorepl.features :as features]
            [neorepl.hints :as hints]
            [neorepl.inspector :as inspector]
            [neorepl.lookups :as lookups]
            [neorepl.lexer :as lexer]
            [neorepl.nrepl :as nrepl]
            [neorepl.ns-browser :as ns-browser]
            [neorepl.render :as render]
            [neorepl.sessions :as sessions]
            [neorepl.settings :as settings]
            [neorepl.shutdown :as shutdown]
            [neorepl.spec-browser :as spec-browser]
            [neorepl.stacktrace :as stacktrace])
  (:import (java.io File)
           (java.lang ProcessBuilder$Redirect)
           (java.security MessageDigest)
           (java.util.regex Pattern)
           (sun.misc Signal SignalHandler)))

(defn- eval-msg
  "The eval message for code, with values printed the way settings say:
  pretty printed width columns wide, or not, and to their print-length and
  print-level. Servers without nREPL's printer say so and print the plain
  way."
  [session code width {:keys [pretty print-length print-level]}]
  (let [[printer options] (if pretty
                            ;; clojure.pprint/write's options.
                            ["nrepl.util.print/pprint" {:right-margin width :length print-length :level print-level}]
                            ;; The vars pr binds.
                            ["nrepl.util.print/pr" {:print-length print-length :print-level print-level}])]
    {:op "eval"
     :code code
     :session session
     :nrepl.middleware.print/print printer
     ;; Without the limits that are off, the session's vars count.
     :nrepl.middleware.print/options (into {} (filter val) options)}))

(defn- on-sigint
  "Calls f on SIGINT, returning the handler it replaces."
  ^SignalHandler [f]
  (Signal/handle (Signal. "INT") (reify SignalHandler
                                   (handle [_ _] (f)))))

(defn- interruptions
  "What Ctrl-C needs in a REPL: the evaluation :running, as its :id and the
  function that interrupts it, the id of the one :interrupted last, the
  :command-thread while a command runs, and an :interrupt function that
  interrupts whichever's going on and returns something truthy, or nil when
  nothing is."
  []
  (let [running (atom nil)
        interrupted (atom nil)
        command-thread (atom nil)]
    {:running running
     :interrupted interrupted
     :command-thread command-thread
     :interrupt #(if-let [{:keys [id interrupt]} @running]
                   (do (reset! interrupted id)
                       (interrupt)
                       id)
                   ;; A command waiting on the server stops waiting.
                   (when-let [^Thread thread @command-thread]
                     (.interrupt thread)
                     thread))}))

(declare edit enter-ns switch-session inspect inspect-tapped run-tests browse-error autoreload change-setting
         browse-specs fix break breaks unbreak)

(def ^:private commands
  "The REPL's commands, the CIDER way: a comma, which Clojure reads as
  whitespace, then a name. Most are the lookups in neorepl.lookups, the
  others :run with what command-output gets and their argument."
  (concat lookups/all
          [{:name "test" :arg "NS..." :optional? true
            :help "Run the tests in NS, or NS/TEST, or the project's"
            :run #(run-tests %1 (some-> %2 (str/split #"\s+")))}
           {:name "retest" :help "Rerun the tests that didn't pass"
            :run (fn [state _] (run-tests state :failed))}
           {:name "inspect" :arg "CODE" :optional? true :code-arg? true
            :help "Inspect the value of CODE, or the last value"
            :run #(inspect %1 %2)}
           {:name "tap" :help "Inspect the value tapped last"
            :run (fn [state _] (inspect-tapped state))}
           {:name "stacktrace" :help "Browse the last error's stack trace, or the last test run's, and open its frames"
            :run (fn [state _] (browse-error state))}
           {:name "fix" :arg "N" :help "Try fix N of the ones the last error listed"
            :run #(fix %1 %2)}
           {:name "break" :arg "SYM" :help "Stop at each step of SYM, evaluating its source again with #dbg"
            :run #(break %1 %2)}
           {:name "breaks" :help "List the functions that stop at each step"
            :run (fn [state _] (breaks state))}
           {:name "unbreak" :arg "SYM" :optional? true
            :help "Stop stopping in SYM, or in every function that does, evaluating its source again"
            :run #(unbreak %1 %2)}
           {:name "edit" :arg "SYM" :optional? true
            :help "Open where SYM, or this namespace, is defined in your EDITOR"
            :run #(edit %1 %2)}
           {:name "ns" :arg "NS" :optional? true
            :help "Switch to NS, loading it if need be, or browse the namespaces"
            :run #(enter-ns %1 %2)}
           {:name "spec" :arg "NAME" :optional? true
            :help "Browse the specs, or NAME's, or the ones with NAME in them"
            :run #(browse-specs %1 %2)}
           {:name "sessions" :arg "NAME" :optional? true
            :help "Switch to the session called NAME, or - for the REPL's own, or list them"
            :run #(switch-session %1 %2)}
           {:name "autoreload" :arg "on|off" :optional? true
            :help "Reload what changed before each evaluation, or stop"
            :run #(autoreload %1 %2)}
           {:name "set" :arg "NAME [on|off|N]" :optional? true
            :help "List the settings, or set NAME, or toggle it"
            :run #(change-setting %1 %2)}
           {:name "help" :help "List the commands"}
           {:name "quit" :help "Leave the REPL"}]))

(defn- command [name]
  (some #(when (= name (:name %)) %) commands))

(defn- command-call
  "The command input calls, a comma and a name, as [name arg more]: its
  name, its argument, nil when there's none, and the code after it, nil when
  there's none. A command whose argument is code takes the rest of the input
  as its argument, the others take the rest of their line. Nil when input
  isn't a command."
  [input]
  (when-let [[_ name rest] (re-matches #"(?s)\s*,([a-z][a-z-]*)(.*)" input)]
    (let [[arg more] (if (:code-arg? (command name))
                       [rest]
                       (str/split rest #"\n" 2))]
      [name (not-empty (str/trim arg)) (when-not (str/blank? more) more)])))

(defn- incomplete?
  "Whether input needs more lines: code whose forms aren't done yet, or a
  command whose argument, being code, isn't. Other commands end with their
  line."
  [input]
  (if-let [[name arg] (command-call input)]
    (and (:code-arg? (command name)) arg (lexer/incomplete? arg))
    (lexer/incomplete? input)))

(defn- out [text]
  {:out (str text "\n")})

(def ^:private windows?
  (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn- editor-command-line
  "The command line that opens file at line with editor-command, a command
  line like EDITOR's. It runs through the shell, the way git runs EDITOR, so
  quotes in it work. The line goes in the way the editor takes it: +line
  for vi, Emacs and most others."
  [editor-command file line]
  (let [[_ & words] (re-find #"^\s*(?:\"([^\"]*)\"|'([^']*)'|(\S+))" editor-command)
        editor (some-> (some identity words) io/file .getName (str/replace #"(?i)\.(exe|cmd|bat)$" ""))
        args (cond
               (nil? line) [file]
               (#{"code" "code-insiders" "codium" "cursor"} editor) ["-g" (str file ":" line)]
               (#{"subl" "zed" "hx"} editor) [(str file ":" line)]
               :else [(str "+" line) file])]
    (if windows?
      (concat (str/split (str/trim editor-command) #"\s+") args)
      (concat ["sh" "-c" (str editor-command " \"$@\"") "sh"] args))))

(defn- extracted
  "The file url, a jar: URL, points to, copied out of its jar into dir, for
  an editor to open, or nil when it can't be read. The copies of a jar's
  files go under its name and a hash of its path, since jars in different
  places can share a name."
  [dir url]
  (when-let [[_ jar entry] (and dir (re-matches #"jar:(file:.+)!/(.+)" url))]
    (try
      (let [jar (io/file (java.net.URI. jar))
            file (io/file dir (str (.getName jar) "-" (Integer/toHexString (.hashCode (.getAbsolutePath jar)))) entry)]
        ;; A jar can change and keep its path, as a snapshot's does.
        (when (< (.lastModified file) (.lastModified jar))
          (io/make-parents file)
          (.setWritable file true)
          (try
            (with-open [in (io/input-stream url)]
              (io/copy in file))
            (catch Exception e
              ;; Rather than part of it, which would pass for all of it after.
              (.delete file)
              (throw e)))
          ;; Changing it changes nothing, so the editor might as well say so.
          (.setReadOnly file))
        (str file))
      (catch Exception _ nil))))

(defn- open
  "Opens file at line with the :editor-command, with the terminal :suspended
  for it. A file in a jar, as a jar: URL, opens as a copy in the
  :sources-dir. Returns nil, or a message saying why it couldn't."
  [{:keys [editor-command suspended sources-dir]} file line]
  (let [path (if (str/starts-with? file "jar:") (extracted sources-dir file) file)]
    (cond
      (str/blank? editor-command) "Set EDITOR, or VISUAL, to the editor to open"
      (not (some-> path io/file .isFile)) (str "Can't open " file)
      :else (let [command (editor-command-line editor-command path line)
                  status (suspended #(let [editor (-> (ProcessBuilder. ^java.util.List command) .inheritIO .start)]
                                       (try
                                         (.waitFor editor)
                                         ;; Ctrl-C stops waiting, for an editor with a
                                         ;; window of its own that doesn't take it.
                                         (catch InterruptedException e
                                           (.destroy editor)
                                           (throw e)))))]
              (when-not (zero? status)
                (str "The editor exited with status " status))))))

(defn- open-definition
  "Opens where sym, a var's or a namespace's name, is defined, as open does.
  Returns nil, or a message saying why it couldn't."
  [{:keys [client] :as state} sym]
  (let [{:keys [found none]} (lookups/run "where" client state sym)]
    (or none (open state (:file found) (:line found)))))

(defn- edit
  "Opens the definition of sym in the editor, as open does, or without sym,
  the file of the namespace the REPL's in. Returns what to print, as
  command-output does."
  [{:keys [ns] :as state} sym]
  (if-let [message (open-definition state (or sym ns))]
    (out message)
    {}))

(defn- edit-text
  "Has the user edit text with the editor-command, as open opens files, in a
  file of its own, as C-x C-e does, with the terminal handed over already:
  {:text text}, what they leave in it, or {:message message} saying why
  there's none, as when the editor fails, the way vi's :cq does to leave
  the input be."
  [editor-command text]
  (let [file (File/createTempFile "neorepl-" ".clj")]
    (try
      ;; Ending with a newline, as editors end files.
      (spit file (str text "\n"))
      (if-let [message (open {:editor-command editor-command :suspended #(%)} (str file) nil)]
        {:message message}
        {:text (str/trimr (slurp file))})
      (finally
        (.delete file)))))

(defn- open-frame
  "Opens the source of frame, from features/last-error, at its line, as open
  does: its :path, or the file its :var is in. Returns nil, or a message
  saying why it couldn't."
  [{:keys [client] :as state} {:keys [path var line] :as frame}]
  (if-let [found (if path
                   {:file path}
                   (when var (:found (lookups/run "where" client state var))))]
    (open state (:file found) (or line (:line found)))
    (str "No source to open for " (render/frame-text frame))))

(defn- inspect-cause
  "Inspects the cause at index of the error last-error found, or its ex-data
  if ex-data?, letting the user :browse it, and returns nil, or a message
  saying why it couldn't."
  [{:keys [client session ns browse height]} index ex-data?]
  (try
    (let [height (height)]
      (inspector/browse browse client session ns
                        (:lines (features/inspect-error client session index ex-data?
                                                        (inspector/start-page-size height)))
                        height)
      nil)
    (catch clojure.lang.ExceptionInfo e
      (when (= ::nrepl/disconnected (:type (ex-data e)))
        (throw e))
      (ex-message e))))

(defn- browse-error
  "Shows the last error, letting the user :browse it, open the frames they
  pick, and inspect its causes, or prints it when there's no browsing: the
  errors of the last test run, its :test-errors, if it had any and no
  evaluation failed since, or the error ,pst finds, whose causes the server
  may be able to inspect. Returns what to print, as command-output does."
  [{:keys [client browse test-errors] :as state}]
  (let [browse-errors (fn [errors inspect?]
                        (loop [view nil]
                          (let [{:keys [frame inspect ex-data? view]}
                                (stacktrace/browse browse errors {:inspect? inspect? :view view})]
                            (cond
                              frame (recur (assoc view :message (open-frame state frame)))
                              inspect (recur (assoc view :message (inspect-cause state inspect ex-data?)))
                              :else {}))))]
    (if-let [failures @test-errors]
      (if browse
        (browse-errors (mapv (fn [failure] {:heading (render/failure-heading failure) :causes (:causes failure)})
                             failures)
                       false)
        (out (str/join "\n\n" (map render/failure-text failures))))
      (let [{:keys [found text none]} (lookups/run "pst" client state nil)]
        (cond
          none (out none)
          browse (browse-errors [{:causes found}] (features/inspects-errors? client))
          :else (out text))))))

(defn- run-tests
  "Runs the tests names names, as features/run-tests takes them, the way the
  REPL :evaluates code, keeping the errors with a stack trace as the
  :test-errors, for ,stacktrace. Returns what to print, as command-output
  does."
  [{:keys [client session evaluate test-errors]} names]
  (if-let [report (:report (evaluate #(features/run-tests client session names %)))]
    (do (reset! test-errors (not-empty (filterv :causes (:failures report))))
        (out (render/test-report-text report)))
    {}))

(defn- fix
  "Has the REPL evaluate the :input of fix n, as typed, of the last error's
  :fixes, an atom with them, as if it was typed. Returns what to print, as
  command-output does, with that :input."
  [{:keys [fixes]} n]
  (if-let [fix (some->> (cli/parse-count n) dec (get @fixes))]
    (select-keys fix [:input])
    (out (if-let [found (seq @fixes)]
           (str "Usage: ,fix N, N being 1" (when (next found) (str " to " (count found))))
           "No fixes to try: the last evaluation's error listed none"))))

(def ^:private no-debugger
  "The debugger takes cider-nrepl, or babashka 1.13.230 or later, on the server")

(def ^:private nothing-breaks
  "Nothing stops at each step")

(defn- instrument-text
  "Has the debugger stop at each step of the function sym names, or stop
  that, unless on?, as features/instrument does, calling done once it has,
  and says so, or why not."
  [{:keys [client] :as state} sym on? done]
  (try
    (let [name (features/instrument client sym state on? {})]
      (done)
      (str name (if on? " stops at each step now" " runs without stopping again")))
    (catch clojure.lang.ExceptionInfo e
      (when (= ::nrepl/disconnected (:type (ex-data e)))
        (throw e))
      (ex-message e))))

(defn- break
  "Has the debugger stop at each step of the function sym names, starting
  it, with the REPL's :debugger function, once that's done. Returns what
  to print, as command-output does."
  [{:keys [client debugger] :as state} sym]
  (if (features/debugger? client)
    (out (instrument-text state sym true debugger))
    (out no-debugger)))

(defn- breaks
  "Lists the functions the debugger stops in, as break has them do. Returns
  what to print, as command-output does."
  [{:keys [client]}]
  (if (features/debugger? client)
    (out (or (some->> (features/instrumented client {}) seq (str/join "\n"))
             nothing-breaks))
    (out no-debugger)))

(defn- unbreak
  "Has the function sym names run without stopping again, or without sym,
  every one the debugger stops in. Returns what to print, as
  command-output does."
  [{:keys [client] :as state} sym]
  (cond
    (not (features/debugger? client)) (out no-debugger)
    sym (out (instrument-text state sym false (fn [])))
    :else (out (or (some->> (features/instrumented client {}) seq (map #(instrument-text state % false (fn []))) (str/join "\n"))
                   nothing-breaks))))

(defn- show-inspector
  "Shows the inspector's view, the lines (view page-size) returns, with a
  page that fits the screen, letting the user :browse it, or prints it when
  there's no browsing. Returns what to print, as command-output does."
  [{:keys [client session ns browse height]} view]
  (let [height (when browse (height))]
    (if-let [lines (view (some-> height inspector/start-page-size))]
      (if browse
        (do (inspector/browse browse client session ns lines height)
            {})
        ;; Without browsing, there's nothing to push the marks' indexes to.
        (out (render/inspector-text lines false)))
      {})))

(defn- inspect
  "Inspects the value of code, or *1 without it, evaluating it the way the
  REPL :evaluates code, as show-inspector shows it."
  [{:keys [client session evaluate] :as state} code]
  (show-inspector state (fn [page-size]
                          (:lines (evaluate #(features/inspect client session (or code "*1")
                                                               (assoc % :page-size page-size)))))))

(defn- inspect-tapped
  "Inspects the value tapped last, the :tapped summary says, as
  show-inspector shows it, unless :taps, a promise of the subscription,
  comes to nothing."
  [{:keys [client session taps tapped] :as state}]
  (cond
    (nil? @taps) (out "Taps take cider-nrepl 0.64 or later on the server")
    (nil? @tapped) (out "Nothing's been tapped yet")
    :else (show-inspector state #(:lines (features/inspect-tap client session (:idx @tapped) %)))))

(defn- autoreload
  "Turns reloading what changed before each evaluation on or off, as arg
  says, or the other way from how it is without it. The REPL's :settings
  say which, as their :autoreload. Turning it on reloads right away, the
  way the REPL :evaluates code, which is when clj-reload starts noting
  changes on a server that hasn't reloaded yet, and leaves it off when that
  fails. Returns what to print, as command-output does."
  [{:keys [client session evaluate settings]} arg]
  (case (if arg (settings/parse-value :autoreload arg) (not (:autoreload @settings)))
    false (do (swap! settings assoc :autoreload false)
              (out "Not reloading before evaluations"))
    true (cond
           (:autoreload @settings) (out "Already reloading what changed before each evaluation")
           ;; Having said why.
           (evaluate #(features/reload-changed client session %)) (out "Not reloading before evaluations")
           :else (do (swap! settings assoc :autoreload true)
                     (out "Reloading what changed before each evaluation")))
    (out "Usage: ,autoreload [on|off]")))

(defn- set-setting
  "Sets the setting called k in settings, an atom, to v, or the other way
  from how it is when v is nil, and says so."
  [settings k v]
  (render/setting-text k (k (swap! settings update k #(if (nil? v) (not %) v)))))

(defn- change-setting
  "Lists the REPL's :settings without arg, or sets the one arg names as the
  rest of arg says, on or off, or to a number for a limit, or the other way
  from how it is without it, as autoreload does for :autoreload. Returns
  what to print, as command-output does."
  [{:keys [settings] :as state} arg]
  (if-let [[name value] (some-> arg (str/split #"\s+" 2))]
    (let [k (keyword name)
          limit? (contains? settings/limits k)
          v (some->> value (settings/parse-value k))]
      (cond
        (not (contains? settings/defaults k)) (out (str "No setting called " name ", ,set lists them"))
        (and (or value limit?) (nil? v)) (out (str "Usage: ,set " name (if limit? " N|off" " [on|off]")))
        (= :autoreload k) (autoreload state value)
        :else (out (set-setting settings k v))))
    (out (render/settings-text @settings))))

(defn- browse-namespaces
  "Lets the user :browse the namespaces called names, the project's, or all
  of them, their vars and their docs, and switches to the namespace they
  pick, as enter-ns does, or opens the namespace or var they pick, as open
  does. Returns what to print, as command-output does."
  [{:keys [client ns browse] :as state} names]
  (let [ask {:namespaces #(if %
                            (features/loaded-namespaces client state {:tooling? (some-> (:settings state) deref :tooling)})
                            (features/namespaces client state))
             :vars #(features/ns-vars client % state {})
             :doc #(some-> (features/info client % state) render/doc-text)}]
    (loop [view nil]
      (let [{picked :ns :keys [edit view]} (ns-browser/browse browse names ns ask {:view view})]
        (cond
          picked (enter-ns state picked)
          edit (recur (assoc view :message (open-definition state edit)))
          :else {})))))

(defn- browse-specs
  "Lets the user :browse the specs, starting at the form of the one called
  spec, or at those with spec in their names, or prints that when there's
  no browsing. Returns what to print, as command-output does."
  [{:keys [client browse width]} spec]
  (let [names (features/specs client {})]
    (cond
      browse (do (spec-browser/browse browse names spec
                                      {:form #(features/spec-form client % (width) {})
                                       :example #(features/spec-example client % (width) {})})
                 {})
      (some #{spec} names) (out (or (some-> (features/spec-form client spec (width) {}) str/trimr)
                                    (str "No spec called " spec)))
      ;; Whatever the case, as the browser filters.
      :else (out (or (some->> names
                              (filter #(str/includes? (str/lower-case %) (str/lower-case (or spec ""))))
                              seq
                              (str/join "\n"))
                     "No specs")))))

(defn- enter-ns
  "Switches to the namespace called ns, loading it the way the REPL
  :evaluates code if need be, or without it, lets the user :browse the
  project's loaded namespaces, or lists them when there's no browsing.
  Returns what to print, as command-output does, with the namespace the
  REPL's in now as its :ns."
  [{:keys [client session evaluate browse] :as state} ns]
  (if ns
    (let [{:keys [status] :as result} (evaluate #(features/enter-ns client session ns %))]
      (if (= "ok" status)
        {:ns (or (:ns result) ns)}
        {}))
    (let [names (features/namespaces client state)]
      (cond
        browse (browse-namespaces state names)
        (seq names) (out (str/join "\n" names))
        :else (out "None of the project's namespaces are loaded")))))

(defn- switch-session
  "Switches to the session called name in the :sessions-file, or to the
  REPL's :own session for -, or lists them without a name, marking the one
  the REPL's in. Returns what to print, as command-output does, with where
  the REPL is now, as run-loop's read-code gets it."
  [{:keys [client sessions-file session own test-errors print-message]} name]
  (if name
    (if-let [id (if (= "-" name) (:session own) (get (sessions/named sessions-file client) name))]
      (let [own? (= id (:session own))]
        ;; So ,stacktrace shows that session's last error.
        (when-not (= id session)
          (reset! test-errors nil))
        (when-not own?
          (print-message (str "Reading *in* won't work in " name
                              ": the server asks the connection that made the session for the input")))
        {:session id
         :session-name (if own? (:session-name own) name)
         :ns (nrepl/evaluation-ns client id)})
      (out (str (sessions/missing-message sessions-file client name) ", neorepl eval -s " name " CODE makes one")))
    (out (str/join "\n" (cli/columns (for [[name id] (cons [(or (:session-name own) "-") (:session own)]
                                                           (dissoc (sessions/named sessions-file client) (:session-name own)))]
                                       [(str (if (= id session) "* " "  ") name)
                                        (nrepl/session-ns client id)
                                        (when (= id (:session own)) "this REPL's")]))))))

(def ^:private follow-ups
  "What the REPL does after some lookups, by name, given command-output's
  state and what the lookup found."
  {"trace" (fn [{:keys [show-traces]} {:keys [results]}]
             (when (some #(#{"traced" "already-traced"} (:status %)) results)
               (show-traces)))
   "untrace" (fn [{:keys [hide-traces]} {:keys [untraced]}]
               ;; Everything, that is.
               (when untraced
                 (hide-traces)))})

(defn- command-output
  "Runs the command called name with arg, and returns what to print, as a
  response for the REPL's printer, or :quit. state has the :client and
  where the REPL is, as run-loop's read-code gets it, for the lookups, the
  :editor-command, a function to run it with the terminal :suspended and
  the :sources-dir, for ,edit, a function that :evaluates the way the REPL
  does, for ,test and ,inspect, and one to :browse what ,inspect and
  ,stacktrace find, as browser/browse does, partly applied to the editor,
  if the REPL can, an atom with the last test run's :test-errors, one with
  the last error's :fixes, for ,fix, functions
  that have the REPL :show-traces and :hide-traces, a promise of the
  subscription to :taps and an atom with the summary of the value :tapped
  last, for ,tap, the :sessions-file and
  the REPL's :own session, where it started, for ,sessions, an atom with
  its :settings, for ,set and ,autoreload, and a function to run lookups
  :quietly with, as editor/quietly takes f. What to
  print can have the :ns the REPL goes on in, or the :session and its
  :session-name too, as switch-session says, and the :input it evaluates
  next, as if typed."
  [{:keys [client quietly] :as state} name arg]
  (let [{arg-name :arg :keys [code? args? optional?] :as command} (command name)]
    (cond
      (= "quit" name) :quit
      (= "help" name) (out (str/join "\n" (cli/columns (for [{:keys [name arg help]} commands]
                                                         [(str "," name (some->> arg (str " "))) help]))))
      (nil? command) (out (str "No command called ," name ", ,help lists them"))
      (and arg-name (nil? arg) (not optional?)) (out (str "Usage: ," name " " arg-name))
      :else (try
              (if-let [run (:run command)]
                (run state arg)
                (let [ctx (assoc state :tooling? (some-> (:settings state) deref :tooling))
                      {:keys [found text none]} (quietly #(lookups/run name client ctx (if args? (some-> arg (str/split #"\s+")) arg)))]
                  (some-> (follow-ups name) (apply [state found]))
                  (cond
                    none (out none)
                    code? {:value text}
                    :else (out text))))
              (catch InterruptedException _
                (out "Interrupted"))
              (catch Exception e
                ;; Without a connection, the REPL's done.
                (when (= ::nrepl/disconnected (:type (ex-data e)))
                  (throw e))
                (out (or (ex-message e) (str e))))))))

(defn- prompt
  "The prompt for where the REPL is, as run-loop's read-code gets it: the
  namespace, after the session's name and a colon when it has a name.
  Without a space, which comint modes' prompt patterns don't take."
  [{:keys [ns session-name]}]
  (str (some-> session-name (str ":")) ns "=> "))

(defn- as-command
  "Calls f the way a command runs, so that Ctrl-C stops it: with the
  current thread as the :command-thread, the atom interruptions has, while
  it runs."
  [command-thread f]
  (reset! command-thread (Thread/currentThread))
  (try
    (f)
    (finally
      (reset! command-thread nil)
      ;; In case Ctrl-C came after it was done.
      (Thread/interrupted))))

(defn- debugging?
  "Whether code has a tag that has cider-nrepl's debugger instrument it."
  [code]
  (boolean (and (str/includes? code "#")
                (some #(and (= :reader-macro (:type %))
                            (#{"#dbg" "#dbg!" "#break" "#break!"} (lexer/text code %)))
                      (lexer/tokenize code)))))

(def ^:private error-hints
  "How many errors get the hint saying where to see more of them."
  3)

(def ^:private error-hint
  "Where to see more of an error, for those who don't know yet."
  "  ,pst shows its stack trace, ,stacktrace browses it")

(defn- hint
  "Prints what could fix the error evaluating code at place got, having
  printed err, numbered for ,fix, and returns the fixes, or prints where
  to see more of the error, for the first few errors, as :hinted, an atom,
  counts them. Looking for fixes runs as a command does, through quietly,
  so that Ctrl-C stops it."
  [client place code err {:keys [hinted command-thread quietly width print-message]}]
  (let [{:keys [token fixes]} (try
                                (as-command command-thread #(quietly (fn [] (hints/suggest client place code err {}))))
                                ;; They're only hints, which a server that
                                ;; can't give them shouldn't get in the way of.
                                (catch Exception _ nil))
        more? (and (empty? fixes) (< @hinted error-hints))
        lines (concat (when token (render/marked-lines code token (width)))
                      (when (seq fixes)
                        [(cli/indented-columns (map-indexed (fn [i fix] [(str ",fix " (inc i)) (render/fix-text fix)])
                                                            fixes))])
                      (when more? [error-hint]))]
    (when more?
      (swap! hinted inc))
    (when (seq lines)
      (print-message (str/join "\n" lines)))
    fixes))

(defn- run-loop
  "Reads code with read-code and evaluates it until the input ends or says
  :repl/quit. read-code gets where the REPL is: the :ns, the :session,
  session to begin with, and its :session-name, if it has one,
  session-name to begin with, and how the last evaluation went, with its
  :status and the :ms it took, or nil before one and once a command's run
  since. Runs commands too, which get io for what else they need, and can
  switch sessions. Responses go to print-response, late ones and what
  commands print too, and result messages to print-message. Evaluations,
  and commands' lookups, run through quietly, if there is one, as
  editor/quietly takes f. The :settings, an atom, say what gets printed
  above the prompt, and whether what changed gets reloaded before each
  evaluation, which :autoreload? turns on at the start, as ,autoreload
  does.

  What an interrupted evaluation says from then on is left out, since it
  comes from it dying: an InterruptedException, or code carrying on after a
  read from *in* got cut short or catching the interrupt, as clojure.test
  does, even once the next evaluation's started. So is what evaluations say
  once neorepl's exiting, which interrupts them."
  [client session {:keys [running interrupted command-thread]}
   {:keys [read-code read-line width print-response print-message session-name quietly settings autoreload?]
    :or {quietly #(%)}
    :as io}]
  (let [print-response #(let [stopped @interrupted
                              id (:id %)]
                          ;; What a server neorepl started prints has no id,
                          ;; and isn't an evaluation dying.
                          (when-not (or (and stopped (= stopped id))
                                        (and id (shutdown/exiting?)))
                            (print-response %)))
        late (agent nil :error-mode :continue)
        own {:session session :session-name session-name}
        ;; The errors of the last test run, until an evaluation fails.
        test-errors (atom nil)
        ;; The fixes the last evaluation's error listed, and how many
        ;; errors got the hint saying where to see more of them.
        fixes (atom nil)
        hinted (atom 0)
        ;; What has the thread waiting for the evaluation that's running do
        ;; something, as evaluation/evaluate's :on-start gives it, the
        ;; session neorepl's debugger answers in, once it's started, and
        ;; what the debugger last did.
        here (atom nil)
        debug-session-atom (atom nil)
        debug-state (atom {})
        ;; The subscription to trace events, once something's traced.
        traces (atom nil)
        ;; Without one, the server prints trace events, so failing to get
        ;; one isn't worth more than that.
        show-traces #(when-not @traces
                       (reset! traces (try
                                        (features/subscribe-traces client)
                                        (catch clojure.lang.ExceptionInfo _ nil))))
        ;; So the server prints them for other clients again.
        hide-traces #(when-let [subscription (first (reset-vals! traces nil))]
                       (try
                         (features/unsubscribe-traces client subscription)
                         (catch Exception _)))
        ;; The session that has the server forward what gets printed on
        ;; threads no session started, once it's forced, which is once
        ;; that's shown.
        forwarding (delay (try
                            (features/forward-system-output client)
                            (catch clojure.lang.ExceptionInfo _ nil)))
        shows-server-output? #(and (:server-output %) (not (:quiet %)))
        ;; The subscription to what gets tapped, once there's an answer, and
        ;; the summary of the value tapped last.
        taps (promise)
        tapped (atom nil)
        ;; Evaluates with (run opts) in session, opts being
        ;; evaluation/evaluate's, after reloading what changed when it's
        ;; reloading, and returns the result after printing its message.
        evaluate (fn [session run]
                   ;; What the debugger did is the evaluation's.
                   (reset! debug-state {})
                   (let [run (if (:autoreload @settings)
                               ;; Nothing to reload for ClojureScript.
                               #(or (let [failed (features/reload-changed client session %)]
                                      (when-not (:clojurescript? failed) failed))
                                    (run %))
                               run)
                         {:keys [status message] :as result}
                         (try
                           (quietly
                            (fn []
                              (run {:on-send #(reset! running {:id %1 :interrupt %2})
                                    :on-start #(reset! here %)
                                    ;; It's not dying after all, so what it says
                                    ;; from then on is worth seeing.
                                    :on-interrupt-failed
                                    #(do (reset! interrupted nil)
                                         (print-message "The server can't interrupt the evaluation, Ctrl-C again stops waiting for it"))
                                    ;; Trace events go out before the
                                    ;; responses after them, but by way of
                                    ;; late, which has them in hand by then.
                                    :on-response #(do (when @traces (await-for 1000 late))
                                                      (print-response %))
                                    ;; A Ctrl-C that came before the server
                                    ;; asked for a line is for the read too.
                                    :read-line #(if (= @interrupted (:id @running)) :interrupted (read-line))
                                    :collect? false})))
                           (finally
                             (reset! running nil)
                             (reset! here nil)))]
                     ;; The Ctrl-C that stopped the wait marked it interrupted again.
                     (when (= "stopped" status)
                       (reset! interrupted nil))
                     (when (= "eval-error" status)
                       (reset! test-errors nil))
                     ;; There's no point in reading more input then.
                     (when (= "disconnected" status)
                       (throw (ex-info message {:type ::nrepl/disconnected})))
                     (some-> message print-message)
                     result))
        ;; What the debugger says, answered on the thread waiting for the
        ;; evaluation, which reads the input. A breakpoint on a thread no
        ;; evaluation of the REPL's is waiting on goes on, since the REPL's
        ;; reading code then.
        on-debugger (fn [debug-session debug-io response]
                      (let [note #(send-off late (fn [_] (print-response (out %))))
                            run @here]
                        (cond
                          ;; Another client started the server's debugger.
                          (nrepl/done? response)
                          (when (compare-and-set! debug-session-atom debug-session nil)
                            (note "The server's breakpoints stop in another client now"))

                          (not (and run (run #(debugger/handle client debug-session response debug-io debug-state))))
                          (when (debugger/let-go client debug-session response)
                            (note "A breakpoint stopped a thread the REPL isn't evaluating on, which goes on")))))
        ;; The session the debugger answers in, starting it the first time,
        ;; or nil when the server has none.
        debugger (fn []
                   (when (features/debugger? client)
                     (locking debug-session-atom
                       (or @debug-session-atom
                           (let [debug-session (nrepl/clone-session client session)
                                 debug-io (assoc io :inspect-lines
                                                 (fn [lines ns]
                                                   (print-response (show-inspector (assoc io :client client :session debug-session :ns ns)
                                                                                   (constantly lines)))))]
                             (features/start-debugger client debug-session (partial on-debugger debug-session debug-io))
                             (print-message "The server's breakpoints stop in this REPL now, as its debugger answers one client, the last to start it")
                             (reset! debug-session-atom debug-session))))))]
    ;; Late output comes on the client's reader thread, which mustn't wait
    ;; for the terminal: it has the responses lookups are waiting for.
    ;; So do trace events, and taps, and what the server prints itself,
    ;; unless the settings leave them out.
    (nrepl/on-unclaimed! client (fn [{event :cider/trace-event value :cider/tap-value :keys [source] :as response}]
                                  (some->> value (reset! tapped))
                                  (let [{:keys [quiet taps] :as current} @settings]
                                    (when-let [shown (cond
                                                       event (when-not quiet (out (render/trace-event-text event)))
                                                       value (when (and taps (not quiet)) (out (render/tap-text value)))
                                                       (= "system" source) (when (shows-server-output? current) response)
                                                       :else response)]
                                      (send-off late (fn [_] (print-response shown)))))))
    ;; nREPL keeps forwarding till the connection's gone, so leaving it out
    ;; again only leaves it out.
    (when (shows-server-output? @settings)
      (force forwarding))
    (add-watch settings ::server-output (fn [_ _ old new]
                                          (when (and (shows-server-output? new) (not (shows-server-output? old)))
                                            (future (force forwarding)))))
    ;; Without waiting, since cider-nrepl loads what it takes the first time.
    (future
      (deliver taps (try
                      (features/subscribe-taps client)
                      (catch clojure.lang.ExceptionInfo _ nil))))
    (shutdown/call-with-cleanup
     ;; Or the server goes on sending the events nowhere, rather than
     ;; printing them.
     #(do (hide-traces)
          (when-let [subscription (deref taps 1000 nil)]
            (try
              (features/unsubscribe-taps client subscription)
              (catch Exception _)))
          ;; Leaving the debugger first, so it's not taken for another
          ;; client starting it.
          (doseq [session [(when (realized? forwarding) @forwarding) (first (reset-vals! debug-session-atom nil))]
                  :when session]
            (try
              (nrepl/close-session client session)
              (catch Exception _))))
     (fn []
       ;; Turned on in the config file, it starts off the way ,autoreload
       ;; turns it on, saying so only when that fails.
       (when autoreload?
         (let [output (command-output (merge io {:client client :session session :evaluate (partial evaluate session)})
                                      "autoreload" "on")]
           (when-not (:autoreload @settings)
             (print-response output))))
       (loop [{:keys [ns session] :as place} (assoc own :ns (nrepl/evaluation-ns client session))
              pending nil
              last-eval nil]
         (let [code (or pending (read-code place last-eval))
               [name arg more] (some-> code command-call)
               output (when name
                        (as-command command-thread
                                    #(command-output (merge io place {:client client :evaluate (partial evaluate session)
                                                                      :test-errors test-errors :fixes fixes :debugger debugger
                                                                      :show-traces show-traces :hide-traces hide-traces
                                                                      :taps taps :tapped tapped :own own :quietly quietly})
                                                     name arg)))]
           (cond
             (or (nil? code) (= ":repl/quit" (str/trim code)) (= :quit output)) nil
             (str/blank? code) (recur place nil last-eval)
             output (do (print-response output)
                        (recur (merge place (select-keys output [:session :session-name :ns]))
                               (some->> [(:input output) more] (remove nil?) seq (str/join "\n"))
                               nil))
             :else
             (let [_ (when (and (nil? @debug-session-atom) (debugging? code))
                       (debugger))
                   started (System/nanoTime)
                   result (evaluate session #(evaluation/evaluate client (eval-msg session code (width) @settings) %))
                   ms (quot (- (System/nanoTime) started) 1000000)
                   place (assoc place :ns (or (:ns result) ns))]
               (reset! fixes (when (and (= "eval-error" (:status result)) (:hints @settings) (not (:quit @debug-state)))
                               (hint client place code (:err result)
                                     (assoc io :hinted hinted :command-thread command-thread :quietly quietly))))
               (recur place nil {:status (:status result) :ms ms})))))))))

(defn- read-input
  "Reads lines from *in* until they hold complete forms, or nil at the end of
  the input. Incomplete forms at the end go to the server as they are, so
  it can report them."
  []
  (loop [lines []
         input lexer/empty-input]
    (if-let [line (read-line)]
      (let [lines (conj lines line)
            input (lexer/add-line input line)]
        (if (if-let [[name] (command-call (first lines))]
              ;; The comma is whitespace and the name a symbol to the lexer,
              ;; so a command is as incomplete as its argument, when that's
              ;; code. The others end with their line.
              (and (:code-arg? (command name)) (lexer/input-incomplete? input))
              (lexer/input-incomplete? input))
          (recur lines input)
          (str/join "\n" lines)))
      (when (seq lines)
        (str/join "\n" lines)))))

(defn- prompted-line
  "A line read from *in* after printing prompt."
  [prompt]
  (print prompt)
  (flush)
  (read-line))

(defn- echoes-ctrl-c?
  "Whether standard input is a terminal that echoes the ^C that interrupts,
  as terminals do, but not Emacs' M-x shell, which turns echo off."
  []
  (try
    (let [stty (-> (ProcessBuilder. ["stty" "-a"])
                   (.redirectInput ProcessBuilder$Redirect/INHERIT)
                   (.redirectError ProcessBuilder$Redirect/DISCARD)
                   .start)
          flags (set (str/split (slurp (.getInputStream stty)) #"[\s;]+"))]
      (and (zero? (.waitFor stty))
           (every? flags ["echo" "echoctl"])))
    (catch Exception _ false)))

(defn- plain
  "A REPL without a line editor, for when there's no terminal to edit on:
  pipes, Emacs' M-x shell and the like. Reads from *in* and prints prompts and
  results to *out*, the way clojure.main's REPL does."
  [client session opts]
  (let [{:keys [interrupt] :as interruptions} (interruptions)
        print-response (evaluation/text-printer)
        echoes? (delay (echoes-ctrl-c?))
        previous (on-sigint #(do
                               ;; Ends the line the terminal echoed ^C on, before
                               ;; the interrupt, which waits for the server.
                               (when @echoes?
                                 (binding [*out* *err*]
                                   (newline)
                                   (flush)))
                               ;; With nothing to interrupt, Ctrl-C is for the REPL.
                               (when-not (interrupt)
                                 (System/exit 130))))]
    (try
      (run-loop client session interruptions
                (assoc opts
                       :read-code (fn [place _]
                                    ;; Rather than run into a line stderr left open.
                                    (print-response :end)
                                    (print (prompt place))
                                    (flush)
                                    (let [code (read-input)]
                                      (print-response)
                                      code))
                       :read-line read-line
                       ;; For the debugger: a key, Enter, or an expression.
                       :read-key #(when-let [line (some-> (prompted-line %) str/trim)]
                                    (case (count line)
                                      0 :enter
                                      1 line
                                      [:expression line]))
                       :read-form (fn [prompt _] (not-empty (prompted-line prompt)))
                       :print-line println
                       :width (constantly 80)
                       :print-response print-response
                       :print-message #(binding [*out* *err*]
                                         (println %))
                       :suspended #(%)))
      ;; Ends the line the last prompt is on.
      (newline)
      (finally
        (Signal/handle (Signal. "INT") previous)))))

(defn- line-printer
  "A function printing a line of text on editor, in the style k names, on a
  line of its own."
  [editor k]
  #(do (editor/fresh-line editor)
       (editor/print-text editor k (str % "\n"))))

(defn- printer
  "A response handler printing output and values on editor."
  [editor]
  (let [stream (atom nil)
        ;; stdout and stderr share the terminal, so each gets lines of its own.
        switch-to #(when-not (= % (first (reset-vals! stream %)))
                     (editor/fresh-line editor))]
    (fn [{:keys [out err value]}]
      (when out
        (switch-to :out)
        (editor/print-text editor nil out))
      (when err
        (switch-to :err)
        (editor/print-text editor :err err))
      (when value
        (editor/fresh-line editor)
        (editor/print-code editor (str value "\n"))))))

(defn- banner [{:keys [address versions]}]
  (let [{:keys [babashka clojure]} versions]
    (str "neorepl connected to " address
         (cond
           babashka (str " (babashka " babashka ")")
           clojure (str " (Clojure " clojure ")"))
         ". Ctrl-D or :repl/quit exits.\n")))

(defn- lookups
  "What the line editor's completion, eldoc and C-x keys need, looking names
  up where the REPL is, plus :in-ns, to say where that is, as run-loop's
  read-code gets it. Lookups for eldoc and docs get cached until then, since
  evaluating code can change what names mean. Eldoc's go on in the
  background, so typing never waits on the server. settings, the REPL's,
  say whether C-x C-a keeps the REPL tooling's vars."
  [client settings]
  (let [ctx (atom nil)
        ;; What lookups found where the REPL is. Failures don't get cached,
        ;; so a slow first lookup gets another go.
        found (atom {})
        looking (atom #{})
        ;; Short timeouts, since these happen as the user types.
        info (fn [sym]
               (let [place @ctx
                     cache @found]
                 (if (contains? cache sym)
                   (get cache sym)
                   (try
                     (let [info (features/info client sym place {:timeout-ms 500})]
                       ;; Unless the REPL's somewhere else by now.
                       (swap! found #(cond-> % (identical? place @ctx) (assoc sym info)))
                       info)
                     (catch clojure.lang.ExceptionInfo _ nil)))))
        ;; What's cached, or nothing, with a lookup started that calls
        ;; (refresh) once it finds something.
        info-later (fn [sym refresh]
                     (let [cache @found]
                       (if (contains? cache sym)
                         (get cache sym)
                         (when-not (contains? (first (swap-vals! looking conj sym)) sym)
                           (future
                             (when (try
                                     (info sym)
                                     (finally
                                       (swap! looking disj sym)))
                               (refresh)))
                           nil))))
        token-at (fn [f text cursor]
                   (when-let [token (f (lexer/lex text) cursor)]
                     (assoc token :text (lexer/text text token))))
        symbol-at #(let [token (token-at lexer/symbol-at %1 %2)]
                     (when (= :symbol (:type token))
                       (:text token)))
        ;; Keywords and constants have no docs, but the call they're in might.
        subject #(or (symbol-at %1 %2) (:text (token-at lexer/enclosing-op %1 %2)))
        ;; Asked for with a key, so worth waiting longer for.
        asked {:timeout-ms 5000}]
    {:complete #(features/complete client % @ctx {:timeout-ms 2000})
     :eldoc (fn [text cursor refresh]
              (when-let [{:keys [op] :as call} (lexer/call-at (lexer/lex text) cursor)]
                (some-> (info-later op refresh) (render/eldoc-line call))))
     :doc (fn [text cursor _]
            (some-> (subject text cursor) info render/doc-text))
     :source (fn [text cursor _]
               (when-let [sym (subject text cursor)]
                 (features/source client sym @ctx asked)))
     :apropos (fn [text cursor width]
                (when-let [sym (symbol-at text cursor)]
                  (some-> (features/apropos client (Pattern/quote sym) (assoc asked :tooling? (:tooling @settings)))
                          not-empty
                          (render/apropos-text width))))
     :in-ns (fn [place]
              (reset! ctx place)
              (reset! found {}))}))

(defn- interactive
  "The REPL with a line editor. in-ns gets called with where the REPL is, as
  run-loop's read-code gets it, at each prompt, which shows how the last
  evaluation went at its right, when the settings say so."
  [client session editor in-ns {:keys [settings] :as opts}]
  (let [{:keys [interrupt] :as interruptions} (interruptions)]
    (editor/on-interrupt editor interrupt)
    (editor/print-text editor :notice (banner client))
    (run-loop client session interruptions
              (assoc opts
                     :read-code (fn [place last-eval]
                                  (in-ns place)
                                  (editor/read-code editor (prompt place)
                                                    (when (:outcome @settings)
                                                      (some-> last-eval render/outcome))))
                     :read-line #(let [line (editor/read-line editor)]
                                   (if (= ::editor/interrupted line)
                                     (do (interrupt) :interrupted)
                                     line))
                     ;; For the debugger.
                     :read-key #(do (editor/fresh-line editor)
                                    (editor/print-text editor :notice %)
                                    (editor/read-key editor))
                     :read-form (partial editor/read-form editor)
                     :echo #(editor/print-text editor :notice (str % "\n"))
                     :print-line (line-printer editor nil)
                     :width #(editor/width editor)
                     ;; What lists get fitted to, which the plain REPL keeps whole.
                     :columns #(editor/width editor)
                     :height #(editor/height editor)
                     :print-response (printer editor)
                     :print-message (line-printer editor :notice)
                     :suspended #(editor/suspended editor %)
                     :quietly #(editor/quietly editor %)
                     :browse (partial browser/browse editor)))))

(defn- history-file
  "The history for the project in dir: a file in history-dir named after a
  hash of dir's path, which could be too long for a file name itself."
  [history-dir dir]
  (let [path (.getCanonicalPath (io/file dir))
        digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes path "UTF-8"))]
    (io/file history-dir (format "%064x" (BigInteger. 1 digest)))))

(defn start
  "Runs a REPL in session until the user ends it: the interactive kind when
  the line editor can open the terminal, the plain kind otherwise. opts:

    :terminal     options for editor/open, nil for the plain REPL regardless
    :history-dir  where to keep the history, per project
    :dir          the project's directory when the client doesn't say
    :editor-command
                  the command line ,edit opens files with, like EDITOR's
    :sources-dir  where ,edit copies the files in jars it opens to
    :sessions-file
                  the named sessions' file, for ,sessions
    :session-name session's name in it, if it has one
    :settings     what the REPL's settings start as, where they're not
                  settings/defaults"
  [client session {:keys [terminal history-dir dir editor-command] :as opts}]
  (let [{:keys [autoreload] :as start} (merge settings/defaults (:settings opts))
        ;; Autoreload's off till run-loop turns it on, which can fail.
        settings (atom (assoc start :autoreload false))
        {:keys [in-ns] :as lookups} (lookups client settings)
        opts (assoc opts :settings settings :autoreload? autoreload)]
    (if-let [editor (some-> terminal
                            (merge (select-keys lookups [:complete :eldoc :doc :source :apropos])
                                   {:incomplete? incomplete?
                                    :settings settings
                                    :toggles settings/toggles
                                    :toggle #(set-setting settings % nil)
                                    :commands (set (map :name commands))
                                    :edit (partial edit-text editor-command)
                                    :history-file (history-file history-dir (or (:project-dir client) dir))})
                            editor/open)]
      (try
        (interactive client session editor in-ns opts)
        (finally
          (editor/close editor)))
      (plain client session opts))))
