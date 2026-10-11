(ns neorepl.repl
  "The REPL, with a line editor on a terminal and without one elsewhere."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.cli :as cli]
            [neorepl.editor :as editor]
            [neorepl.evaluation :as evaluation]
            [neorepl.features :as features]
            [neorepl.lookups :as lookups]
            [neorepl.lexer :as lexer]
            [neorepl.nrepl :as nrepl]
            [neorepl.render :as render]
            [neorepl.shutdown :as shutdown])
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

(declare edit run-tests)

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
           {:name "edit" :arg "SYM" :help "Open where SYM is defined in your EDITOR"
            :run #(edit %1 %2)}
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

(defn- edit
  "Opens the definition of sym with the :editor-command, with the terminal
  :suspended for it. Returns what to print, as command-output does."
  [{:keys [client editor-command suspended] :as state} sym]
  (let [{:keys [found none]} (lookups/run "where" client state sym)
        {:keys [file line]} found]
    (cond
      none (out none)
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
                (out (str "The editor exited with status " status)))))))

(defn- run-tests
  "Runs the tests names names, as features/run-tests takes them, the way the
  REPL :evaluates code. Returns what to print, as command-output does."
  [{:keys [client session evaluate]} names]
  (if-let [report (:report (evaluate #(features/run-tests client session names %)))]
    (out (render/test-report-text report))
    {}))

(defn- reenter
  "Puts session back in the namespace called ns, as tools.namespace's refresh
  does: reloading code can make a namespace anew, and the session would go
  on in the old one."
  [client session ns]
  ;; Qualified, since the namespace it's in might not refer clojure.core.
  (nrepl/request client {:op "eval" :session session
                         :code (format "(clojure.core/in-ns (clojure.core/symbol %s))" (pr-str ns))}))

(defn- command-output
  "Runs the command called name with arg, and returns what to print, as a
  response for the REPL's printer, or :quit. state has the :client, the
  :session and the :ns, for the lookups, the :editor-command and a function
  to run it with the terminal :suspended, for ,edit, and a function that
  :evaluates the way the REPL does, for ,test."
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
                (let [{:keys [text none]} (lookups/run name client state (if args? (some-> arg (str/split #"\s+")) arg))]
                  (when (= "reload" name)
                    (reenter client (:session state) (:ns state)))
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

(defn- run-loop
  "Reads code with read-code, a function of the current namespace's name,
  and evaluates it in session until the input ends or says :repl/quit. Runs
  commands too, which get io for what else they need. Responses go to
  print-response, late ones and what commands print too, and result
  messages to print-message.

  What an interrupted evaluation says from then on is left out, since it
  comes from it dying: an InterruptedException, or code carrying on after a
  read from *in* got cut short. So is what evaluations say once neorepl's
  exiting, which interrupts them."
  [client session {:keys [running interrupted command-thread]}
   {:keys [read-code read-line width print-response print-message] :as io}]
  (let [print-response #(let [stopped @interrupted
                              id (:id %)]
                          ;; What the server prints has no id, and isn't an
                          ;; evaluation dying.
                          (when-not (or (and stopped (= stopped id))
                                        (and id (shutdown/exiting?)))
                            (print-response %)))
        late (agent nil :error-mode :continue)
        ;; Evaluates with (run opts), opts being evaluation/evaluate's, and
        ;; returns the result after printing its message.
        evaluate (fn [run]
                   (reset! interrupted nil)
                   (let [{:keys [status message] :as result}
                         (try
                           (run {:on-send #(reset! running {:id %1 :interrupt %2})
                                 ;; It's not dying after all, so what it says
                                 ;; from then on is worth seeing.
                                 :on-interrupt-failed
                                 #(do (reset! interrupted nil)
                                      (print-message "The server can't interrupt the evaluation, Ctrl-C again stops waiting for it"))
                                 :on-response print-response
                                 ;; A Ctrl-C that came before the server
                                 ;; asked for a line is for the read too.
                                 :read-line #(if @interrupted :interrupted (read-line))
                                 :collect? false})
                           (finally
                             (reset! running nil)))]
                     ;; The Ctrl-C that stopped the wait marked it interrupted again.
                     (when (= "stopped" status)
                       (reset! interrupted nil))
                     ;; There's no point in reading more input then.
                     (when (= "disconnected" status)
                       (throw (ex-info message {:type ::nrepl/disconnected})))
                     (some-> message print-message)
                     result))]
    ;; Late output comes on the client's reader thread, which mustn't wait
    ;; for the terminal: it has the responses lookups are waiting for.
    (nrepl/on-unclaimed! client #(send-off late (fn [_] (print-response %))))
    ;; Not necessarily user: shadow-cljs starts sessions in shadow.user, and a
    ;; named session is wherever it was left.
    (loop [ns (or (nrepl/session-ns client session) "user")
           pending nil]
      (let [code (or pending (read-code ns))
            [name arg more] (some-> code command-call)
            output (when name
                     (reset! command-thread (Thread/currentThread))
                     (try
                       (command-output (assoc io :client client :session session :ns ns :evaluate evaluate)
                                       name arg)
                       (finally
                         (reset! command-thread nil)
                         ;; In case Ctrl-C came after the command was done.
                         (Thread/interrupted))))]
        (cond
          (or (nil? code) (= ":repl/quit" (str/trim code)) (= :quit output)) nil
          (str/blank? code) (recur ns nil)
          output (do (print-response output)
                     (recur ns more))
          :else
          (let [result (evaluate #(evaluation/evaluate client (eval-msg session code (width)) %))]
            (recur (or (:ns result) ns) nil)))))))

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
  [client session editor-command]
  (let [{:keys [interrupt] :as interruptions} (interruptions)
        print-response (evaluation/text-printer)
        ;; With nothing to interrupt, Ctrl-C is for the REPL.
        previous (on-sigint #(when-not (interrupt) (System/exit 130)))]
    (try
      (run-loop client session interruptions
                {:read-code (fn [ns]
                              (print (str ns "=> "))
                              (flush)
                              (let [code (read-input)]
                                (print-response)
                                code))
                 :read-line read-line
                 :width (constantly 80)
                 :print-response print-response
                 :print-message #(binding [*out* *err*]
                                   (println %))
                 :editor-command editor-command
                 :suspended #(%)})
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
  up in session, in the namespace the REPL's in, plus :in-ns, to say which
  that is. Lookups get cached until then, since evaluating code can change
  what names mean."
  [client session]
  (let [current-ns (atom "user")
        ctx #(hash-map :ns @current-ns :session session)
        ;; Short timeouts, since these happen as the user types. Failures
        ;; don't get cached, so a slow first lookup gets another go.
        lookup (fn [sym] (features/info client sym (ctx) {:timeout-ms 500}))
        cached (atom (memoize lookup))
        info #(try
                (@cached %)
                (catch clojure.lang.ExceptionInfo _ nil))
        token-at (fn [f text cursor]
                   (when-let [token (f (lexer/lex text) cursor)]
                     (assoc token :text (lexer/text text token))))]
    {:complete #(features/complete client % (ctx) {:timeout-ms 2000})
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
     :in-ns (fn [ns]
              (reset! current-ns ns)
              (reset! cached (memoize lookup)))}))

(defn- interactive
  "The REPL with a line editor. in-ns gets called with the namespace the
  REPL's in at each prompt."
  [client session editor in-ns editor-command]
  (let [{:keys [interrupt] :as interruptions} (interruptions)]
    (editor/on-interrupt editor interrupt)
    (editor/print-text editor :notice (banner client))
    (run-loop client session interruptions
              {:read-code (fn [ns]
                            (in-ns ns)
                            (editor/fresh-line editor)
                            (editor/read-code editor (str ns "=> ")))
               :read-line #(let [line (editor/read-line editor)]
                             (if (= ::editor/interrupted line)
                               (do (interrupt) :interrupted)
                               line))
               :width #(editor/width editor)
               :print-response (printer editor)
               :print-message #(do (editor/fresh-line editor)
                                   (editor/print-text editor :notice (str % "\n")))
               :editor-command editor-command
               :suspended #(editor/suspended editor %)})))

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
                  the command line ,edit opens files with, like EDITOR's"
  [client session {:keys [terminal history-dir dir editor-command]}]
  (let [{:keys [in-ns] :as lookups} (lookups client session)]
    (if-let [editor (some-> terminal
                            (merge (select-keys lookups [:complete :eldoc :doc])
                                   {:incomplete? incomplete?
                                    :history-file (history-file history-dir (or (:project-dir client) dir))})
                            editor/open)]
      (try
        (interactive client session editor in-ns editor-command)
        (finally
          (editor/close editor)))
      (plain client session editor-command))))
