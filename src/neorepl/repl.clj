(ns neorepl.repl
  "The REPL, with a line editor on a terminal and without one elsewhere."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.browser :as browser]
            [neorepl.cli :as cli]
            [neorepl.editor :as editor]
            [neorepl.evaluation :as evaluation]
            [neorepl.features :as features]
            [neorepl.inspector :as inspector]
            [neorepl.lookups :as lookups]
            [neorepl.lexer :as lexer]
            [neorepl.nrepl :as nrepl]
            [neorepl.render :as render]
            [neorepl.sessions :as sessions]
            [neorepl.shutdown :as shutdown]
            [neorepl.stacktrace :as stacktrace])
  (:import (java.security MessageDigest)
           (sun.misc Signal SignalHandler)))

(defn- eval-msg
  "The eval message for code, with values pretty printed width columns wide.
  Servers without nREPL's printer say so and print the plain way."
  [session code width]
  {:op "eval"
   :code code
   :session session
   :nrepl.middleware.print/print "nrepl.util.print/pprint"
   :nrepl.middleware.print/options {:right-margin width}})

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

(declare edit enter-ns switch-session inspect run-tests browse-error)

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
           {:name "stacktrace" :help "Browse the last error's stack trace, or the last test run's, and open its frames"
            :run (fn [state _] (browse-error state))}
           {:name "edit" :arg "SYM" :help "Open where SYM is defined in your EDITOR"
            :run #(edit %1 %2)}
           {:name "ns" :arg "NS" :optional? true
            :help "Switch to NS, loading it if need be, or list the project's"
            :run #(enter-ns %1 %2)}
           {:name "sessions" :arg "NAME" :optional? true
            :help "Switch to the session called NAME, or - for the REPL's own, or list them"
            :run #(switch-session %1 %2)}
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

(defn- open
  "Opens file at line with the :editor-command, with the terminal :suspended
  for it. Returns what to print, as command-output does."
  [{:keys [editor-command suspended]} file line]
  (cond
    (str/blank? editor-command) (out "Set EDITOR, or VISUAL, to the editor to open")
    (not (.isFile (io/file file))) (out (str "Can't open " file))
    :else (let [command (editor-command-line editor-command file line)
                status (suspended #(let [editor (-> (ProcessBuilder. ^java.util.List command) .inheritIO .start)]
                                     (try
                                       (.waitFor editor)
                                       ;; Ctrl-C stops waiting, for an editor with a
                                       ;; window of its own that doesn't take it.
                                       (catch InterruptedException e
                                         (.destroy editor)
                                         (throw e)))))]
            (if (zero? status)
              {}
              (out (str "The editor exited with status " status))))))

(defn- edit
  "Opens the definition of sym in the editor, as open does."
  [{:keys [client] :as state} sym]
  (let [{:keys [found none]} (lookups/run "where" client state sym)]
    (if none
      (out none)
      (open state (:file found) (:line found)))))

(defn- open-frame
  "Opens the source of frame, from features/last-error, at its line, as open
  does: its :path, or the file its :var is in."
  [{:keys [client] :as state} {:keys [path var line] :as frame}]
  (if-let [found (if path
                   {:file path}
                   (when var (:found (lookups/run "where" client state var))))]
    (open state (:file found) (or line (:line found)))
    (out (str "No source to open for " (render/frame-text frame)))))

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
  "Shows the last error, letting the user :browse it, open the frame they
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
                              frame (open-frame state frame)
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

(defn- inspect
  "Inspects the value of code, or *1 without it, evaluating it the way the
  REPL :evaluates code, and lets the user :browse it, or prints it when
  there's no browsing. Returns what to print, as command-output does."
  [{:keys [client session ns evaluate browse height]} code]
  (let [height (when browse (height))
        page-size (some-> height inspector/start-page-size)]
    (if-let [lines (:lines (evaluate #(features/inspect client session (or code "*1")
                                                        (assoc % :page-size page-size))))]
      (if browse
        (do (inspector/browse browse client session ns lines height)
            {})
        (out (render/inspector-text lines)))
      {})))

(defn- enter-ns
  "Switches to the namespace called ns, loading it the way the REPL
  :evaluates code if need be, or lists the project's loaded namespaces
  without it. Returns what to print, as command-output does, with the
  namespace the REPL's in now as its :ns."
  [{:keys [client session evaluate] :as state} ns]
  (if ns
    (let [{:keys [status] :as result} (evaluate #(features/enter-ns client session ns %))]
      (if (= "ok" status)
        {:ns (or (:ns result) ns)}
        {}))
    (out (or (some->> (features/namespaces client state) seq (str/join "\n"))
             "None of the project's namespaces are loaded"))))

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
      (out (str "No session called " name ", neorepl eval -s " name " CODE makes one")))
    (out (str/join "\n" (cli/columns (for [[name id] (cons [(or (:session-name own) "-") (:session own)]
                                                           (dissoc (sessions/named sessions-file client) (:session-name own)))]
                                       [(str (if (= id session) "* " "  ") name)
                                        (nrepl/session-ns client id)
                                        (when (= id (:session own)) "this REPL's")]))))))

(defn- reenter
  "Puts session back in the namespace called ns, as tools.namespace's refresh
  does: reloading code can make a namespace anew, and the session would go
  on in the old one."
  [client session ns]
  ;; Qualified, since the namespace it's in might not refer clojure.core.
  (nrepl/request client {:op "eval" :session session
                         :code (format "(clojure.core/in-ns (clojure.core/symbol %s))" (pr-str ns))}))

(def ^:private follow-ups
  "What the REPL does after some lookups, by name, given command-output's
  state and what the lookup found."
  {"reload" (fn [{:keys [client session ns]} _]
              (reenter client session ns))
   "trace" (fn [{:keys [show-traces]} {:keys [results]}]
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
  :editor-command and a function to run it with the terminal :suspended,
  for ,edit, a function that :evaluates the way the REPL does, for ,test
  and ,inspect, and one to :browse what ,inspect and ,stacktrace find, as
  browser/browse does, partly applied to the editor, if the REPL can, an
  atom with the last test run's :test-errors, functions that have the REPL
  :show-traces and :hide-traces, and the :sessions-file and the REPL's :own
  session, where it started, for ,sessions. What to print can have the :ns
  the REPL goes on in, or the :session and its :session-name too, as
  switch-session says."
  [{:keys [client] :as state} name arg]
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
                (let [{:keys [found text none]} (lookups/run name client state (if args? (some-> arg (str/split #"\s+")) arg))]
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

(defn- run-loop
  "Reads code with read-code and evaluates it until the input ends or says
  :repl/quit. read-code gets where the REPL is: the :ns, the :session,
  session to begin with, and its :session-name, if it has one,
  session-name to begin with. Runs commands too, which get io for what
  else they need, and can switch sessions. Responses go to print-response,
  late ones and what commands print too, and result messages to
  print-message.

  What an interrupted evaluation says from then on is left out, since it
  comes from it dying: an InterruptedException, or code carrying on after a
  read from *in* got cut short or catching the interrupt, as clojure.test
  does, even once the next evaluation's started. So is what evaluations say
  once neorepl's exiting, which interrupts them."
  [client session {:keys [running interrupted command-thread]}
   {:keys [read-code read-line width print-response print-message session-name] :as io}]
  (let [print-response #(let [stopped @interrupted
                              id (:id %)]
                          ;; What the server prints has no id, and isn't an
                          ;; evaluation dying.
                          (when-not (or (and stopped (= stopped id))
                                        (and id (shutdown/exiting?)))
                            (print-response %)))
        late (agent nil :error-mode :continue)
        own {:session session :session-name session-name}
        ;; The errors of the last test run, until an evaluation fails.
        test-errors (atom nil)
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
        ;; Evaluates with (run opts), opts being evaluation/evaluate's, and
        ;; returns the result after printing its message.
        evaluate (fn [run]
                   (let [{:keys [status message] :as result}
                         (try
                           (run {:on-send #(reset! running {:id %1 :interrupt %2})
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
                                 :collect? false})
                           (finally
                             (reset! running nil)))]
                     ;; The Ctrl-C that stopped the wait marked it interrupted again.
                     (when (= "stopped" status)
                       (reset! interrupted nil))
                     (when (= "eval-error" status)
                       (reset! test-errors nil))
                     ;; There's no point in reading more input then.
                     (when (= "disconnected" status)
                       (throw (ex-info message {:type ::nrepl/disconnected})))
                     (some-> message print-message)
                     result))]
    ;; Late output comes on the client's reader thread, which mustn't wait
    ;; for the terminal: it has the responses lookups are waiting for.
    ;; So do trace events.
    (nrepl/on-unclaimed! client #(send-off late (fn [_]
                                                 (print-response (if-let [event (:cider/trace-event %)]
                                                                   (out (render/trace-event-text event))
                                                                   %)))))
    (shutdown/call-with-cleanup
     ;; Or the server goes on sending the events nowhere, rather than
     ;; printing them.
     hide-traces
     (fn []
       (loop [{:keys [ns session] :as place} (assoc own :ns (nrepl/evaluation-ns client session))
              pending nil]
         (let [code (or pending (read-code place))
               [name arg more] (some-> code command-call)
               output (when name
                        (reset! command-thread (Thread/currentThread))
                        (try
                          (command-output (merge io place {:client client :evaluate evaluate :test-errors test-errors
                                                           :show-traces show-traces :hide-traces hide-traces
                                                           :own own})
                                          name arg)
                          (finally
                            (reset! command-thread nil)
                            ;; In case Ctrl-C came after the command was done.
                            (Thread/interrupted))))]
           (cond
             (or (nil? code) (= ":repl/quit" (str/trim code)) (= :quit output)) nil
             (str/blank? code) (recur place nil)
             output (do (print-response output)
                        (recur (merge place (select-keys output [:session :session-name :ns])) more))
             :else
             (let [result (evaluate #(evaluation/evaluate client (eval-msg session code (width)) %))]
               (recur (assoc place :ns (or (:ns result) ns)) nil)))))))))

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

(defn- plain
  "A REPL without a line editor, for when there's no terminal to edit on:
  pipes, Emacs' M-x shell and the like. Reads from *in* and prints prompts and
  results to *out*, the way clojure.main's REPL does."
  [client session opts]
  (let [{:keys [interrupt] :as interruptions} (interruptions)
        print-response (evaluation/text-printer)
        ;; With nothing to interrupt, Ctrl-C is for the REPL.
        previous (on-sigint #(when-not (interrupt) (System/exit 130)))]
    (try
      (run-loop client session interruptions
                (assoc opts
                       :read-code (fn [place]
                                    (print (prompt place))
                                    (flush)
                                    (let [code (read-input)]
                                      (print-response)
                                      code))
                       :read-line read-line
                       :width (constantly 80)
                       :print-response print-response
                       :print-message #(binding [*out* *err*]
                                         (println %))
                       :suspended #(%)))
      ;; Ends the line the last prompt is on.
      (newline)
      (finally
        (Signal/handle (Signal. "INT") previous)))))

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
  "What the line editor's completion, eldoc and C-x C-d need, looking names
  up where the REPL is, plus :in-ns, to say where that is, as run-loop's
  read-code gets it. Lookups get cached until then, since evaluating code
  can change what names mean."
  [client]
  (let [ctx (atom nil)
        ;; Short timeouts, since these happen as the user types. Failures
        ;; don't get cached, so a slow first lookup gets another go.
        lookup (fn [sym] (features/info client sym @ctx {:timeout-ms 500}))
        cached (atom (memoize lookup))
        info #(try
                (@cached %)
                (catch clojure.lang.ExceptionInfo _ nil))
        token-at (fn [f text cursor]
                   (when-let [token (f (lexer/lex text) cursor)]
                     (assoc token :text (lexer/text text token))))]
    {:complete #(features/complete client % @ctx {:timeout-ms 2000})
     :eldoc (fn [text cursor]
              (when-let [{:keys [end text]} (token-at lexer/enclosing-op text cursor)]
                ;; Not while the function's name is still being typed.
                (when (< end cursor)
                  (some-> (info text) render/eldoc-text))))
     :doc (fn [text cursor]
            (let [at (token-at lexer/symbol-at text cursor)
                  ;; Keywords and constants have no docs, but the call they're in might.
                  token (if (= :symbol (:type at)) at (token-at lexer/enclosing-op text cursor))]
              (some-> token :text info render/doc-text)))
     :in-ns (fn [place]
              (reset! ctx place)
              (reset! cached (memoize lookup)))}))

(defn- interactive
  "The REPL with a line editor. in-ns gets called with where the REPL is, as
  run-loop's read-code gets it, at each prompt."
  [client session editor in-ns opts]
  (let [{:keys [interrupt] :as interruptions} (interruptions)]
    (editor/on-interrupt editor interrupt)
    (editor/print-text editor :notice (banner client))
    (run-loop client session interruptions
              (assoc opts
                     :read-code (fn [place]
                                  (in-ns place)
                                  (editor/fresh-line editor)
                                  (editor/read-code editor (prompt place)))
                     :read-line #(let [line (editor/read-line editor)]
                                   (if (= ::editor/interrupted line)
                                     (do (interrupt) :interrupted)
                                     line))
                     :width #(editor/width editor)
                     :height #(editor/height editor)
                     :print-response (printer editor)
                     :print-message #(do (editor/fresh-line editor)
                                         (editor/print-text editor :notice (str % "\n")))
                     :suspended #(editor/suspended editor %)
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
    :sessions-file
                  the named sessions' file, for ,sessions
    :session-name session's name in it, if it has one"
  [client session {:keys [terminal history-dir dir] :as opts}]
  (let [{:keys [in-ns] :as lookups} (lookups client)]
    (if-let [editor (some-> terminal
                            (merge (select-keys lookups [:complete :eldoc :doc])
                                   {:incomplete? incomplete?
                                    :history-file (history-file history-dir (or (:project-dir client) dir))})
                            editor/open)]
      (try
        (interactive client session editor in-ns opts)
        (finally
          (editor/close editor)))
      (plain client session opts))))
