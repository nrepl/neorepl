(ns neorepl.main
  "The neorepl command."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.cli :as cli]
            [neorepl.discovery :as discovery]
            [neorepl.evaluation :as evaluation]
            [neorepl.features :as features]
            [neorepl.jack-in :as jack-in]
            [neorepl.json :as json]
            [neorepl.lexer :as lexer]
            [neorepl.lookups :as lookups]
            [neorepl.mcp :as mcp]
            [neorepl.nrepl :as nrepl]
            [neorepl.render :as render]
            [neorepl.repair :as repair]
            [neorepl.sessions :as sessions]
            [neorepl.shutdown :as shutdown]
            [neorepl.xdg :as xdg])
  (:import (java.io BufferedReader File)))

(defn- usage-error [msg]
  (ex-info msg {:type ::usage}))

(def version
  "neorepl's version, which every release bumps."
  "0.4.0-snapshot")

(defn- warn [msg]
  (binding [*out* *err*]
    (println (str "neorepl: " msg))))

(defn- parse-port [s]
  (let [port (Long/parseLong s)]
    (when (< 0 port 65536) port)))

(defn- parse-index [s]
  (when (re-matches #"\d{1,9}" s)
    (Long/parseLong s)))

(defn- parse-count [s]
  (when-let [n (parse-index s)]
    (when (pos? n) n)))


(defn- parse-seconds [s]
  ;; Nine digits are plenty, and the milliseconds can't overflow.
  (when (re-matches #"\d{1,9}(\.\d+)?" s)
    (let [n (if (str/includes? s ".") (Double/parseDouble s) (Long/parseLong s))]
      (when (pos? n) n))))

(def ^:private connection-options
  [{:short "-p" :long "--port" :arg "PORT" :parse parse-port
    :doc "Connect to the server on PORT"}
   {:long "--host" :arg "HOST" :parse not-empty
    :doc "Host for --port (default: 127.0.0.1)"}
   {:long "--url" :arg "URL" :parse nrepl/parse-url
    :doc "Connect to nrepl://HOST:PORT or nrepl+unix:PATH"}])

(def ^:private session-option
  {:short "-s" :long "--session" :arg "NAME" :parse not-empty :default "default"
   :doc "Use the session called NAME (default: default)"})

(def ^:private timeout-option
  {:long "--timeout" :arg "SECS" :parse parse-seconds
   :doc "Interrupt the evaluation after SECS seconds"})

(def ^:private evaluation-options
  [session-option
   timeout-option
   {:long "--max-output" :arg "CHARS" :parse parse-count
    :doc "Cut each value, stdout and stderr at CHARS characters"}])

(def ^:private help-option
  {:short "-h" :long "--help"
   :doc "Show this help"})

(def ^:private common-options
  [{:long "--format" :arg "FORMAT" :parse #{"text" "edn" "json"} :default "text"
    :doc "text (default), edn or json"}
   help-option])

(def ^:private port-file-names
  (str "(" (str/join ", " discovery/port-files) ")"))

(def ^:private discovery-note
  (str "Without --port or --url it connects to the nearest server with a port file
" port-file-names " in this directory or a parent."))

(def ^:private sessions-note
  "Evaluations share a session, per server and --session name, so *ns*, *1, *e
and the like carry over from one call to the next.")

(def ^:private evaluation-exit-statuses
  "Exit status: 0 on success, 1 when the evaluation fails, 2 on usage errors,
3 when there's no server to talk to or the connection to it drops and 124
when it times out.")

(defn- help-text
  "A --help text: the usage and description paragraphs, then the options and
  any paragraphs after them."
  [usage description options & more]
  (str/join "\n\n" (concat [usage description (str "Options:\n" (cli/summary options))] more)))

(defn- reject-args [args]
  (when (seq args)
    (throw (usage-error (str "Unexpected argument: " (first args))))))

(defn- existing-file ^File [path]
  (let [file (io/file path)]
    (if (.isFile file)
      file
      (throw (usage-error (str "No such file: " path))))))

(defn- connect [{:keys [url host port]} dir]
  (cond
    (and url (or host port)) (throw (usage-error "--url doesn't go with --host or --port"))
    url (nrepl/connect url)
    port (nrepl/connect (cond-> {:port port} host (assoc :host host)))
    host (throw (usage-error "--host needs a --port"))
    :else (discovery/connect dir)))

(defn- call-with-client
  "Calls f with client, and closes it after."
  [client f]
  (try
    (f client)
    (finally
      (nrepl/close client))))

(defn- with-client
  "Calls f with a client connected the way options say, and closes it after."
  [options dir f]
  (call-with-client (connect options dir) f))

(defn- print-err
  "Prints text to stderr in one go, so lines from several threads don't
  get mixed up."
  [text]
  (binding [*out* *err*]
    (print text)
    (flush)))

(defn- print-err-line [line]
  (print-err (str line "\n")))

(defn- parse-aliases [s]
  (not-empty (remove str/blank? (str/split s #"[:,]"))))

(def ^:private jack-in-options
  [{:long "--tool" :arg "TOOL" :parse (set (map :tool jack-in/tools))
    :doc "Start the server with TOOL: clojure, lein, bb or shadow-cljs (default: the project's)"}
   {:long "--aliases" :arg "ALIASES" :parse parse-aliases
    :doc "Start the server with deps.edn aliases or Leiningen profiles, like :dev:test"}])

(defn- shell-words
  "command the way it'd be typed in a shell."
  [command]
  (str/join " " (for [word command]
                  (if (re-matches #"[\w@%+=:,./-]+" word)
                    word
                    (str "'" (str/replace word "'" "'\\''") "'")))))

(defn- with-server
  "Starts a server for project, a :dir and the :tool to use, the way options
  say, saying what it runs, and calls f with it, as jack-in/with-server
  does. What the server prints goes to (on-line line)."
  [{:keys [dir tool]} {:keys [aliases port]} on-line f]
  (let [{:keys [file aliases? port?]} (jack-in/tool tool)
        command (jack-in/command tool {:aliases aliases :port port})]
    (when (and aliases (not aliases?))
      (throw (usage-error (str "--aliases doesn't go with " tool))))
    (when (and port (not port?))
      (throw (usage-error (str "--port doesn't go with " tool ", which takes the port from " file))))
    (warn (str "Starting an nREPL server in " dir ": " (shell-words command)))
    ;; Printing from the thread that follows the server's output, with this
    ;; one's *out* and *err*.
    (jack-in/with-server dir command (bound-fn* on-line) f)))

(defn- with-started-server
  "Calls f with a client for a server started for project, the way
  with-server does. What the server prints goes where output nobody asked
  for goes, like an evaluation's that comes late, and to stderr before
  something takes that over and after f."
  [project options f]
  (let [output (atom print-err-line)]
    (with-server project options #(@output %)
      (fn [{:keys [host port]}]
        (call-with-client
         (-> (nrepl/connect {:host host :port port
                             :on-unclaimed (fn [{:keys [out err]}]
                                             (some-> (or out err) print-err))})
             (assoc :project-dir (:dir project)))
         (fn [client]
           (reset! output #(nrepl/unclaimed client {:err (str % "\n")}))
           (try
             (f client)
             (finally
               (reset! output print-err-line)))))))))

(defn- with-in-process-server
  "Calls f with a client for a server started in this process, with
  cider-nrepl when it's on the classpath, and stops the server after."
  [dir f]
  (let [start-server (requiring-resolve 'nrepl.server/start-server)
        stop-server (requiring-resolve 'nrepl.server/stop-server)
        ;; Only when it's there, so failing to load it isn't kept quiet.
        handler (when (io/resource "cider/nrepl.clj")
                  @(requiring-resolve 'cider.nrepl/cider-nrepl-handler))
        server (apply start-server :bind "127.0.0.1" :port 0 (when handler [:handler handler]))]
    (try
      (with-client {:port (:port server)} dir f)
      (finally
        (stop-server server)))))

(defn- with-repl-client
  "Calls f with a client for the REPL: connected the way options say, or to
  the nearest server, or else to one started for the project in dir, which
  stops when f returns. With :in-process, to a server in this process."
  [{:keys [url host port tool aliases in-process] :as options} dir f]
  (let [connect? (or url host port)]
    (when (and in-process connect?)
      (throw (usage-error "--in-process doesn't go with --port, --host or --url")))
    (when (and (or tool aliases) (or in-process connect?))
      (throw (usage-error "--tool and --aliases don't go with --port, --host, --url or --in-process")))
    (cond
      in-process (with-in-process-server dir f)
      connect? (with-client options dir f)
      :else
      (let [project (delay (jack-in/find-project dir tool))
            client (try
                     (discovery/connect dir)
                     (catch clojure.lang.ExceptionInfo e
                       (cond
                         (not= ::nrepl/no-server (:type (ex-data e))) (throw e)
                         @project nil
                         :else (throw (ex-info (str (ex-message e) ", nor a " (jack-in/project-files tool)
                                                    " to start one for")
                                               (ex-data e))))))]
        (if client
          (do (when (or tool aliases)
                (warn "There's a server running already, so --tool and --aliases don't apply"))
              (call-with-client client f))
          (with-started-server @project options f))))))

(defn- emit
  "Prints data in format, edn or json."
  [format data]
  (case format
    "edn" (prn data)
    "json" (println (json/write-str data))))

(defn- print-rows
  "Prints items in format: as edn or json, or as text, with each item's
  (row item), a vector of strings, in columns."
  [format row items]
  (if (= "text" format)
    (run! println (cli/columns (map row items)))
    (emit format items)))

(defn- report
  "Prints result, a map with a :status and maybe a :message, in format. Text
  is only the message, on stderr."
  [format {:keys [message] :as result}]
  (if (= "text" format)
    (some-> message warn)
    (emit format result)))

(defn- no-session [name]
  {:status "no-session" :message (str "No session called " name)})

(defn- with-named-session
  "Calls (f client id) with a client connected the way options say and the
  id of the session their :session names, and returns what it does, the exit
  status. When there's no such session it says so and returns 1."
  [{:keys [session format] :as options} {:keys [dir sessions-file]} f]
  (with-client options dir
    (fn [client]
      (if-let [id (get (sessions/named sessions-file client) session)]
        (f client id)
        (do (report format (no-session session))
            1)))))

(defn- report-run
  "Runs (run client session opts) on the server options point at, in the
  session they name, where opts are evaluation/evaluate's, prints the result
  in the format they ask for, with (text result) after the output for text,
  if it says anything, and returns the exit status."
  [run {:keys [session timeout max-output format] :as options} {:keys [dir sessions-file]} text]
  (with-client options dir
    (fn [client]
      (let [printer (if (= "text" format) (evaluation/text-printer) (fn [_]))
            {:keys [status message truncated out] :as result}
            (run client
                 (sessions/ensure! sessions-file client session)
                 {:timeout timeout
                  :max-output max-output
                  :on-response printer})]
        (if (= "text" format)
          ;; So that a warning after it, or the shell's prompt, gets a
          ;; line of its own.
          (do (printer :end)
              (when truncated (warn (str "Cut the output at " max-output " characters")))
              (when-let [text (text result)]
                (when-not (or (empty? out) (str/ends-with? out "\n"))
                  (newline))
                (println text))
              (some-> message warn))
          (emit format result))
        (case status "ok" 0 "timeout" 124 "disconnected" 3 1)))))

(defn- report-evaluation
  "Evaluates msg the way report-run runs things, with evaluate-options on top
  of the ones options give."
  [msg options ctx evaluate-options]
  (report-run (fn [client session opts]
                (evaluation/evaluate client (assoc msg :session session) (merge opts evaluate-options)))
              options ctx (constantly nil)))

(def ^:private eval-options
  (concat connection-options
          [{:short "-f" :long "--file" :arg "FILE" :parse not-empty
            :doc "Evaluate the code in FILE"}]
          evaluation-options
          common-options))

(def ^:private eval-help
  (help-text
   "Usage: neorepl eval [options] CODE
       neorepl eval [options] -f FILE
       neorepl eval [options] -"
   "Evaluates CODE, the contents of FILE or, with -, standard input. Output is
printed as it arrives and each value on a line of its own."
   eval-options
   discovery-note
   sessions-note
   evaluation-exit-statuses
   "Examples:
  neorepl eval '(+ 1 2)'
  neorepl eval --format json -f script.clj
  neorepl eval --max-output 2000 '(slurp \"big.txt\")'
  echo '(range 3)' | neorepl eval -"))

(defn- read-code [file args]
  (cond
    (and file (seq args)) (throw (usage-error "Give either CODE or --file, not both"))
    file (slurp (existing-file file))
    (= ["-"] args) (slurp *in*)
    (= 1 (count args)) (first args)
    (empty? args) (throw (usage-error "Nothing to evaluate"))
    :else (throw (usage-error "Too many arguments: quote the code to pass it as one"))))

(defn- eval-command [{:keys [file] :as options} args ctx]
  (report-evaluation (cond-> {:op "eval" :code (read-code file args)}
                       ;; For the file and line numbers in errors.
                       file (assoc :file (.getAbsolutePath (io/file file))))
                     options
                     ctx
                     ;; Reading the code used stdin up, so reads from *in*
                     ;; get end of input.
                     (when (= ["-"] args)
                       {:read-line (constantly nil)})))

(def ^:private load-options
  (concat connection-options evaluation-options common-options))

(def ^:private load-help
  (help-text
   "Usage: neorepl load [options] FILE"
   "Loads FILE the way load-file does: loading stops at the first error, only
the value of the last form comes back and the session stays in its
namespace. eval -f, on the other hand, evaluates the forms one by one, and
an ns form switches the session to that namespace."
   load-options
   discovery-note
   sessions-note
   evaluation-exit-statuses
   "Examples:
  neorepl load src/my/app.clj
  neorepl load --timeout 60 --format json src/my/app.clj"))

(defn- load-command [options args ctx]
  (let [file (case (count args)
               0 (throw (usage-error "Nothing to load"))
               1 (existing-file (first args))
               (throw (usage-error "Load one file at a time")))]
    (report-evaluation {:op "load-file"
                        :file (slurp file)
                        :file-path (.getAbsolutePath file)
                        :file-name (.getName file)}
                       options
                       ctx
                       nil)))

(def ^:private interrupt-options
  (concat connection-options [session-option] common-options))

(def ^:private interrupt-help
  (help-text
   "Usage: neorepl interrupt [options]"
   "Interrupts the evaluation running in a session, freeing it up for the next
one. It's meant for evaluations nobody is waiting for anymore, like one a
neorepl eval left behind when it got killed. A neorepl eval that's still
waiting won't find out that its evaluation ended, so stop that one with
Ctrl-C instead."
   interrupt-options
   discovery-note
   "Exit status: 0 when it interrupted an evaluation, 1 when there was none to
interrupt, 2 on usage errors and 3 when there's no server to talk to."
   "Examples:
  neorepl interrupt
  neorepl interrupt -s agent"))

(defn- interrupt-command [{:keys [session format] :as options} args {:keys [dir sessions-file]}]
  (reject-args args)
  (with-client options dir
    (fn [client]
      (let [{:keys [status] :as result} (sessions/interrupt-result sessions-file client session)]
        (report format result)
        (if (= "interrupted" status) 0 1)))))

(def ^:private sessions-options
  (concat connection-options common-options))

(def ^:private sessions-help
  (help-text
   "Usage: neorepl sessions [options]
       neorepl sessions [options] close NAME"
   "Lists the named sessions on the server, or closes one. Closing a session
throws its state away: the next evaluation under that name starts afresh in
the user namespace."
   sessions-options
   discovery-note
   "Exit status: 0 on success, 1 when there's no session to close, 2 on usage
errors and 3 when there's no server to talk to."
   "Examples:
  neorepl sessions
  neorepl sessions close agent"))

(defn- sessions-command [{:keys [format] :as options} args {:keys [dir sessions-file]}]
  (let [[action name & more] args]
    (cond
      (not= "close" action) (reject-args args)
      (nil? name) (throw (usage-error "Give the NAME of the session to close"))
      :else (reject-args more))
    (with-client options dir
      (fn [client]
        (if name
          (let [{:keys [status] :as result}
                (if (sessions/close! sessions-file client name)
                  {:status "closed"}
                  (no-session name))]
            (report format result)
            (if (= "closed" status) 0 1))
          (do (print-rows format (juxt :name :id) (for [[name id] (sessions/named sessions-file client)]
                                                     {:name name :id id}))
              0))))))

(def ^:private ports-options common-options)

(def ^:private ports-help
  (help-text
   "Usage: neorepl ports [options]"
   (str "Lists the port files " port-file-names " in this
directory and its parents, nearest first, and the servers answering on their
ports. The other commands connect to the first server that answers.")
   ports-options
   "Exit status: 0 when a server answers, 2 on usage errors and 3 when none
does."
   "Examples:
  neorepl ports
  neorepl ports --format json"))

(defn- ports-command [{:keys [format]} args {:keys [dir]}]
  (reject-args args)
  (let [ports (discovery/probe dir)]
    (print-rows format
                (fn [{:keys [file port live versions]}]
                  [file
                   (str port)
                   (if live
                     (str/join ", " (for [[k v] versions] (str (name k) " " v)))
                     "not answering")])
                ports)
    (if (some :live ports) 0 3)))

(def ^:private check-options
  (cons {:long "--fix" :doc "Repair the delimiters from the indentation, writing the files back"}
        common-options))

(def ^:private check-help
  (help-text
   "Usage: neorepl check [options] FILE...
       neorepl check [options] -"
   "Checks that the parens, brackets and braces in Clojure source balance and
that its strings end, and reports the first problem in each file. Standard
input stands in for a file as -. No server needed.

--fix repairs what doesn't balance the way parinfer's indent mode would, from
the indentation: a line closes the forms opened at its column or right of it.
It only touches the top-level forms that don't balance, and leaves a file
alone, saying why, when it can't tell how to repair it, as with a string that
never ends. With -, it prints the repaired code."
   check-options
   "Exit status: 0 when everything balances, after --fix's repairs, 1 when
something doesn't or a file can't be read and 2 on usage errors."
   "Examples:
  neorepl check src/my/app.clj
  neorepl check --fix src/my/app.clj
  neorepl check --format json $(git ls-files '*.clj')"))

(defn- check-file
  "The first delimiter problem in the file at path, or standard input for -,
  as lexer/file-problem has it, repaired, as repair/fix-file does, if fix?.
  Standard input, repaired, goes to out."
  [path fix? ^java.io.Writer out]
  (cond
    (not= "-" path) (if fix? (repair/fix-file path) (lexer/file-problem path))
    fix? (let [[text problem] (repair/fix-text (slurp *in*))]
           (.write out ^String text)
           (.flush out)
           (some->> problem (into {:file "<stdin>"})))
    :else (some->> (lexer/delimiter-problem (slurp *in*)) (into {:file "<stdin>"}))))

(defn- check-command [{:keys [format fix]} args _]
  (when (empty? args)
    (throw (usage-error "Nothing to check")))
  (let [stdout *out*]
    ;; Repaired code from standard input goes to standard output.
    (binding [*out* (if (and fix (some #{"-"} args)) *err* *out*)]
      (let [problems (reduce (fn [problems problem]
                               (when (= "text" format)
                                 (println (render/problem-text problem)))
                               (conj problems problem))
                             []
                             ;; Lexed in parallel, reported in order.
                             (remove nil? (pmap #(check-file % fix stdout) args)))]
        (when-not (= "text" format)
          (emit format problems))
        (if (every? :fixed problems) 0 1)))))

(def ^:private ns-option
  {:long "--ns" :arg "NS" :parse not-empty
   :doc "Look symbols up in the namespace NS (default: user, or the session's)"})

(def ^:private lookup-session-option
  (-> session-option
      (dissoc :default)
      (assoc :doc "Look symbols up as the session called NAME sees them, ClojureScript too")))

(def ^:private lookup-options
  (concat connection-options [ns-option lookup-session-option] common-options))

(defn- with-lookup-context
  "Calls (f client ctx) with a client connected the way options say and the
  context they say to look symbols up in, as lookups/context makes it, and
  returns what it does, the exit status, as with-named-session does for
  their :session, when they name one."
  [{:keys [ns session] :as options} {:keys [dir] :as ctx} f]
  (if session
    (with-named-session options ctx #(f %1 (lookups/context %1 %2 session ns)))
    (with-client options dir #(f % (lookups/context % nil nil ns)))))

(defn- the-arg
  "The one argument in args, named name in the usage."
  [args name]
  (when (empty? args)
    (throw (usage-error (str "Give a " name))))
  (reject-args (rest args))
  (first args))

(def ^:private doc-help
  (help-text
   "Usage: neorepl doc [options] SYM"
   "Prints the documentation of SYM, a var or special form, the way
clojure.repl/doc does. --format edn and json have its arglists, docstring
and where it's defined."
   lookup-options
   discovery-note
   "Exit status: 0 when SYM is found, 1 when it isn't, 2 on usage errors and 3
when there's no server to talk to."
   "Examples:
  neorepl doc map
  neorepl doc --ns my.app str/join"))

(defn- report-lookup
  "Does the lookup called name, see neorepl.lookups, and prints what it found
  in format. Returns the exit status: 0, or 1 when it found nothing or what
  it found says it failed."
  [format name client ctx arg]
  (let [{:keys [found text none failed?]} (lookups/run name client ctx arg)]
    (if found
      (do (if (= "text" format)
            (println text)
            (emit format found))
          (if failed? 1 0))
      (do (report format {:status "not-found" :message none})
          1))))

(defn- lookup-command
  "The command doing the lookup called name on its one argument, or on all of
  them when it takes several."
  [name]
  (let [{arg-name :arg :keys [args?]} (lookups/lookup name)]
    (fn [{:keys [format] :as options} args ctx]
      (let [arg (if args? args (the-arg args arg-name))]
        (with-lookup-context options ctx
          #(report-lookup format name %1 %2 arg))))))

(def ^:private source-help
  (help-text
   "Usage: neorepl source [options] SYM"
   "Prints the source of the var SYM, the way clojure.repl/source does."
   lookup-options
   discovery-note
   "Exit status: 0 when the source is found, 1 when it isn't, 2 on usage errors
and 3 when there's no server to talk to."
   "Examples:
  neorepl source when
  neorepl source --ns my.app my.app/start"))

(def ^:private complete-help
  (help-text
   "Usage: neorepl complete [options] PREFIX"
   "Lists the completions for PREFIX, with their types when the server knows
them."
   lookup-options
   discovery-note
   "Examples:
  neorepl complete str/
  neorepl complete --ns my.app --format json ma"))

(defn- complete-command [{:keys [format] :as options} args ctx]
  (let [prefix (the-arg args "PREFIX")]
    (with-lookup-context options ctx
      (fn [client ctx]
        (print-rows format (juxt :candidate (comp str :type)) (features/complete client prefix ctx))
        0))))

(def ^:private apropos-options
  (concat connection-options common-options))

(def ^:private apropos-help
  (help-text
   "Usage: neorepl apropos [options] REGEX"
   "Lists the vars, in every loaded namespace, whose names match REGEX."
   apropos-options
   discovery-note
   "Exit status: 0 when something matches, 1 when nothing does, 2 on usage
errors and 3 when there's no server to talk to."
   "Examples:
  neorepl apropos split
  neorepl apropos --format json '^map'"))

(def ^:private macroexpand-options
  (concat connection-options
          [(assoc ns-option :doc "Expand in the namespace NS (default: user, or the session's)")
           lookup-session-option
           {:long "--all"
            :doc "Expand every macro in FORM, not just FORM itself"}]
          common-options))

(def ^:private macroexpand-help
  (help-text
   "Usage: neorepl macroexpand [options] FORM"
   "Expands the macro that FORM calls, once, the way macroexpand-1 does. With
--all, it expands every macro in FORM instead, all the way down."
   macroexpand-options
   discovery-note
   "Examples:
  neorepl macroexpand '(when x (println x))'
  neorepl macroexpand --all '(-> x f (g y))'"))

(defn- macroexpand-command [{:keys [all format] :as options} args ctx]
  (let [form (the-arg args "FORM")]
    (with-lookup-context options ctx
      #(report-lookup format (if all "macroexpand-all" "macroexpand") %1 %2 form))))

(def ^:private where-help
  (help-text
   "Usage: neorepl where [options] SYM"
   "Prints where SYM is defined, as file:line:column, the way compilers and
editors write locations. A definition in a jar comes as a jar: URL."
   lookup-options
   discovery-note
   "Exit status: 0 when the server knows where SYM is, 1 when it doesn't, 2 on
usage errors and 3 when there's no server to talk to."
   "Examples:
  neorepl where map
  neorepl where --ns my.app --format json start"))

(def ^:private reload-options
  (concat connection-options common-options))

(def ^:private reload-help
  (help-text
   "Usage: neorepl reload [options] [NS...]"
   "Reloads the namespaces NS. Without any, it reloads the ones that changed
since the last reload, which takes cider-nrepl on the server. Either way it
waits for as long as reloading takes, and shows what loading the code
printed, like reflection warnings."
   reload-options
   discovery-note
   "Exit status: 0 when the code reloaded, 1 when that failed, 2 on usage errors
and 3 when there's no server to talk to."
   "Examples:
  neorepl reload
  neorepl reload my.app my.app.db"))

(defn- reload-command [{:keys [format] :as options} args {:keys [dir]}]
  (with-client options dir
    #(report-lookup format "reload" % {} args)))

(def ^:private trace-help
  (help-text
   "Usage: neorepl trace [options] [SYM...]"
   "Traces the functions SYM, or every function in a namespace SYM, so each
call prints its arguments and what it returns, nested in the calls it's in.
Without SYM it lists what's traced. Calls print in the output of the
evaluation that makes them, as neorepl eval shows it, and calls on other
threads where those threads print, unless a neorepl REPL on the server is
showing them. It takes cider-nrepl on the server, and listing takes 0.60 or
later."
   lookup-options
   discovery-note
   "Exit status: 0 when everything SYM names is traced, 1 when something
isn't a function or a namespace, 2 on usage errors and 3 when there's no
server to talk to."
   "Examples:
  neorepl trace my.app/start
  neorepl trace --ns my.app start parse-date
  neorepl trace my.app.db"))

(def ^:private untrace-help
  (help-text
   "Usage: neorepl untrace [options] [SYM...]"
   "Stops tracing the functions SYM, or the functions in a namespace SYM.
Without SYM it stops tracing everything, which takes cider-nrepl 0.60 or
later on the server."
   lookup-options
   discovery-note
   "Exit status: 0 when nothing SYM names is traced anymore, 1 when something
isn't a function or a namespace, 2 on usage errors and 3 when there's no
server to talk to."
   "Examples:
  neorepl untrace my.app/start
  neorepl untrace"))

(def ^:private pst-options
  (concat connection-options [session-option] common-options))

(def ^:private pst-help
  (help-text
   "Usage: neorepl pst [options]"
   "Prints the stack trace of the last error in a session, the way
clojure.repl/pst does: the session neorepl eval calls share, or the one
--session names. --format edn and json have each cause's class, message,
ex-data and stack frames."
   pst-options
   discovery-note
   "Exit status: 0 when there's an error to print, 1 when there's none, 2 on
usage errors and 3 when there's no server to talk to."
   "Examples:
  neorepl pst
  neorepl pst --session agent --format json"))

(defn- pst-command [{:keys [session format] :as options} args ctx]
  (reject-args args)
  (with-named-session options ctx
    #(report-lookup format "pst" %1 {:session %2 :session-name session} nil)))

(def ^:private test-options
  (concat connection-options evaluation-options common-options))

(def ^:private test-exit-statuses
  "Exit status: 0 when every test passes, 1 when one doesn't, the tests can't
run or a namespace named has none, 2 on usage errors, 3 when there's no
server to talk to or the connection to it drops and 124 when they time out.")

(defn- report-tests
  "Runs the tests names names, as features/run-tests takes them, the way
  report-run runs things."
  [names options ctx]
  (report-run (fn [client session opts]
                (let [{:keys [report] :as result} (features/run-tests client session names opts)]
                  ;; The report's at the top in edn and json.
                  (merge (dissoc result :report) report)))
              options ctx #(when (:summary %) (render/test-report-text %))))

(def ^:private test-help
  (help-text
   "Usage: neorepl test [options] [NS...]
       neorepl test [options] NS/TEST..."
   "Runs the tests in the namespaces NS, or the tests NS/TEST, and prints what
didn't pass and how many did. It loads namespaces that aren't loaded yet,
but not the changes to ones that are, which neorepl load and reload are for.
Without arguments it runs every test in the project, which takes cider-nrepl
or babashka on the server. --format edn and json have the counts and what
each failure expected, got and where."
   test-options
   discovery-note
   sessions-note
   test-exit-statuses
   "Examples:
  neorepl test
  neorepl test my.app-test my.app.db-test
  neorepl test --format json my.app-test/start-test"))

(defn- test-command [options args ctx]
  (report-tests (vec args) options ctx))

(def ^:private retest-help
  (help-text
   "Usage: neorepl retest [options]"
   "Reruns the tests that didn't pass the last time tests ran on the server, by
neorepl test, a REPL or an editor. It takes cider-nrepl or babashka on the
server."
   test-options
   discovery-note
   sessions-note
   test-exit-statuses
   "Examples:
  neorepl retest
  neorepl retest --format json"))

(defn- retest-command [options args ctx]
  (reject-args args)
  (report-tests :failed options ctx))

(def ^:private inspector-actions
  "The options that do something with the inspector, the ops they send, and
  the key in those that takes the option's value, if it has one."
  [[{:long "--push" :arg "N" :parse parse-index
     :doc "Inspect the value marked [N] in what's being inspected"}
    "inspect-push" :idx]
   [{:long "--pop" :doc "Go back to what was being inspected before"} "inspect-pop"]
   [{:long "--next-page" :doc "Show the next page of a big collection"} "inspect-next-page"]
   [{:long "--prev-page" :doc "Show the previous page"} "inspect-prev-page"]
   [{:long "--view" :doc "Switch to the next view mode, like the object's fields"}
    "inspect-toggle-view-mode"]
   [{:long "--def" :arg "NAME" :parse #(when (features/var-name? %) %)
     :doc "Define what's being inspected as NAME in the session's namespace"}
    "inspect-def-current-value" :var-name]])

(def ^:private inspect-options
  (concat connection-options
          [session-option timeout-option]
          (map first inspector-actions)
          common-options))

(def ^:private inspect-help
  (help-text
   "Usage: neorepl inspect [options] CODE
       neorepl inspect [options] -
       neorepl inspect [options] [--push N | --pop | --next-page | --prev-page | --view]
       neorepl inspect [options] --def NAME"
   "Evaluates CODE, or standard input with -, and prints its value the way
cider-nrepl's inspector shows it: the class, the contents, page by page for
big collections, and so on. Each value in there that can be inspected further
is marked with a number, as [3], which --push takes. The inspector stays in
the session, so the next neorepl inspect can go on from there. Without CODE
or an option it prints what's being inspected. It takes cider-nrepl or
babashka on the server."
   inspect-options
   discovery-note
   sessions-note
   evaluation-exit-statuses
   "Examples:
  neorepl inspect '(System/getProperties)'
  neorepl inspect --push 3
  neorepl inspect --pop
  neorepl inspect --def props
  neorepl inspect --format json '{:a [1 2 3]}'"))

(defn- inspector-result
  "result, as features/inspect and inspector have it, with the view as :text."
  [{:keys [lines] :as result}]
  (cond-> (dissoc result :lines)
    lines (assoc :text (render/inspector-text lines))))

(defn- inspect-command [{:keys [format timeout] :as options} args ctx]
  (let [actions (keep (fn [[spec op k]]
                        (when-some [value (get options (cli/option-key spec))]
                          (cond-> {:op op} k (assoc k value))))
                      inspector-actions)]
    (cond
      (< 1 (count actions)) (throw (usage-error (str "Give one of "
                                                     (str/join ", " (map (comp :long first) inspector-actions)))))
      (and (seq actions) (seq args)) (throw (usage-error "Give either CODE or an option for the inspector, not both"))

      (seq args)
      (let [code (read-code nil args)]
        (report-run (fn [client session opts]
                      (inspector-result (features/inspect client session code
                                                          (cond-> opts
                                                            (= ["-"] args) (assoc :read-line (constantly nil))))))
                    options ctx :text))

      :else
      (with-named-session options ctx
        (fn [client id]
          (try
            (let [{:keys [var-name] :as action} (or (first actions) {:op "inspect-refresh"})
                  ns (when var-name (nrepl/evaluation-ns client id))
                  opts (cond-> {} timeout (assoc :timeout-ms (long (* 1000 timeout))))
                  result (inspector-result (features/inspector client id (cond-> action ns (assoc :ns ns)) opts))
                  {:keys [text] :as result} (cond-> result
                                              ;; Rather than the view again, which --def leaves as it was.
                                              var-name (assoc :text (str "#'" ns "/" var-name)))]
              (if (= "text" format)
                (println text)
                (emit format result))
              0)
            (catch clojure.lang.ExceptionInfo e
              (if (= ::nrepl/timeout (:type (ex-data e)))
                (do (warn (ex-message e)) 124)
                (throw e)))))))))

(def ^:private repl-options
  (concat connection-options
          [(-> session-option
               (dissoc :default)
               (assoc :doc "Use the session called NAME instead of a fresh one"))]
          jack-in-options
          [{:long "--in-process"
            :doc "Start the server in neorepl's own process"}
           help-option]))

(def ^:private repl-help
  (help-text
   "Usage: neorepl [repl] [options]"
   "Starts a REPL on a running server, in a fresh session of its own unless
--session names one, such as the one neorepl eval calls share. Enter
evaluates the input once its forms are complete, Ctrl-C interrupts an
evaluation and Ctrl-D exits. Commands start with a comma, like ,doc map,
and ,help lists them.

Without a terminal, as in a pipe or Emacs' M-x shell, it reads forms from
standard input and prints prompts and results the way clojure.main's REPL
does. Ctrl-C interrupts an evaluation there too, and ends the REPL when
nothing's running."
   repl-options
   discovery-note
   "Without a server to connect to, it starts one for the project, the way
neorepl server does, and stops it when the REPL ends. --tool and --aliases
say how, and with --in-process the server runs in neorepl's own process."
   "Exit status: 0 when the REPL ends, 1 when a server fails to start, 2 on
usage errors and 3 when there's no server to talk to or the connection to it
drops."
   "Examples:
  neorepl
  neorepl -p 7888
  neorepl --session default
  neorepl --aliases :dev:test"))

(defn- repl-command [{:keys [session] :as options} args {:keys [dir sessions-file] :as ctx}]
  (reject-args args)
  (with-repl-client options dir
    (fn [client]
      (let [id (if session
                 (sessions/ensure! sessions-file client session)
                 (nrepl/clone-session client))
            ;; Loaded only when needed, along with JLine.
            start (requiring-resolve 'neorepl.repl/start)]
        ;; A fresh session goes with the REPL, even when it gets killed.
        (shutdown/call-with-cleanup
         #(when-not session
            ;; There's nothing to close when the connection is gone.
            (try (nrepl/close-session client id) (catch Exception _ nil)))
         #(do (start client id (-> (select-keys ctx [:dir :history-dir :terminal :editor-command :sessions-file])
                                   (assoc :session-name session)))
              0))))))

(def ^:private server-options
  (concat jack-in-options
          [{:long "--port" :arg "PORT" :parse parse-port
            :doc "Listen on PORT (default: any free one)"}]
          common-options))

(def ^:private server-help
  (help-text
   "Usage: neorepl server [options]"
   "Starts an nREPL server for the project in this directory, or the nearest
parent with a project.clj, deps.edn, bb.edn or shadow-cljs.edn. The server
writes a port file, for neorepl and editors to find it, and runs until
neorepl gets Ctrl-C or SIGTERM. What it prints goes to stderr, and its
nrepl:// URL to stdout once it's up."
   server-options
   "The project's build tool runs the server: Leiningen for project.clj, the
Clojure CLI for deps.edn, babashka for bb.edn and shadow-cljs for
shadow-cljs.edn, in that order when there are several. Except that
shadow-cljs goes first when it builds with the project's deps.edn or
project.clj, as :deps or :lein in shadow-cljs.edn say. The server gets
cider-nrepl, for CIDER's tooling, which babashka's has built in. Leiningen
and Clojure CLI servers also get piggieback, for ClojureScript REPLs."
   "Exit status: 0 when the server ends fine, 1 when it fails to start or ends
with an error, and 2 on usage errors."
   "Examples:
  neorepl server
  neorepl server --aliases :dev:test --port 7888
  neorepl server --tool bb --format json"))

(defn- running-server
  "The live server with its port file in dir, if any, the way
  discovery/probe has it."
  [dir]
  (some #(when (and (:live %) (= dir (:dir %))) %)
        (try
          (discovery/probe dir)
          (catch clojure.lang.ExceptionInfo _ nil))))

(defn- server-command [{:keys [tool format] :as options} args {:keys [dir]}]
  (reject-args args)
  (let [{project-dir :dir :as project} (or (jack-in/find-project dir tool)
                                           (throw (jack-in/no-project dir tool)))]
    ;; Another one would take its port file.
    (when-let [{:keys [port]} (running-server project-dir)]
      (throw (ex-info (str "There's a server for " project-dir " running already, on port " port)
                      {:type ::server-running})))
    (with-server project options print-err-line
      (fn [{:keys [^Process process host port]}]
        (let [url (str "nrepl://" host ":" port)]
          (if (= "text" format)
            (println url)
            (emit format {:url url :host host :port port :pid (.pid process)
                          :dir project-dir :tool (:tool project)}))
          (flush)
          (if (zero? (.waitFor process)) 0 1))))))

(def ^:private mcp-options
  (concat connection-options
          [{:long "--timeout" :arg "SECS" :parse parse-seconds :default 60
            :doc "Interrupt evaluations after SECS seconds, unless a call says otherwise (default: 60)"}
           {:long "--max-output" :arg "CHARS" :parse parse-count :default 10000
            :doc "Cut what an evaluation prints and returns at CHARS characters (default: 10000)"}
           help-option]))

(def ^:private mcp-help
  (help-text
   "Usage: neorepl mcp [options]"
   "Serves MCP on standard input and output, for coding agents: tools to
evaluate code, load files, look up docs and source, run tests, show stack
traces and check files for unbalanced delimiters, through the server it
finds the way the other commands do. The tools that talk to a server take a
port too, for projects with several. They share the CLI's named sessions,
the one called agent unless a call names another, so neorepl eval --session
agent and the eval tool see the same *ns*, *1 and *e."
   mcp-options
   discovery-note
   "Examples:
  neorepl mcp
  claude mcp add neorepl -- neorepl mcp"))

(defn- mcp-command [options args {:keys [dir sessions-file]}]
  (reject-args args)
  (mcp/serve (BufferedReader. *in*) *out*
             (assoc (select-keys options [:timeout :max-output])
                    :version version
                    :dir dir
                    :sessions-file sessions-file
                    :connect #(connect (if % {:port %} options) dir)))
  0)

(def ^:private commands
  [{:name "repl" :summary "Start a REPL (the default)" :options repl-options :help repl-help
    :main repl-command}
   {:name "server" :summary "Start a server for the project" :options server-options :help server-help
    :main server-command}
   {:name "eval" :summary "Evaluate code" :options eval-options :help eval-help :main eval-command}
   {:name "load" :summary "Load a file" :options load-options :help load-help :main load-command}
   {:name "doc" :summary "Show a var's documentation" :options lookup-options :help doc-help
    :main (lookup-command "doc")}
   {:name "source" :summary "Show a var's source" :options lookup-options :help source-help
    :main (lookup-command "source")}
   {:name "complete" :summary "List completions" :options lookup-options :help complete-help
    :main complete-command}
   {:name "apropos" :summary "Find vars by name" :options apropos-options :help apropos-help
    :main (lookup-command "apropos")}
   {:name "macroexpand" :summary "Expand macros" :options macroexpand-options :help macroexpand-help
    :main macroexpand-command}
   {:name "where" :summary "Show where a var is defined" :options lookup-options :help where-help
    :main (lookup-command "where")}
   {:name "trace" :summary "Trace functions" :options lookup-options :help trace-help
    :main (lookup-command "trace")}
   {:name "untrace" :summary "Stop tracing functions" :options lookup-options :help untrace-help
    :main (lookup-command "untrace")}
   {:name "reload" :summary "Reload code" :options reload-options :help reload-help
    :main reload-command}
   {:name "test" :summary "Run tests" :options test-options :help test-help :main test-command}
   {:name "retest" :summary "Rerun the tests that didn't pass" :options test-options :help retest-help
    :main retest-command}
   {:name "inspect" :summary "Inspect a value" :options inspect-options :help inspect-help
    :main inspect-command}
   {:name "pst" :summary "Print the last error's stack trace" :options pst-options :help pst-help
    :main pst-command}
   {:name "interrupt" :summary "Interrupt an evaluation" :options interrupt-options :help interrupt-help
    :main interrupt-command}
   {:name "sessions" :summary "List or close sessions" :options sessions-options :help sessions-help
    :main sessions-command}
   {:name "ports" :summary "List the servers for this directory" :options ports-options :help ports-help
    :main ports-command}
   {:name "check" :summary "Check files for unbalanced delimiters" :options check-options :help check-help
    :main check-command}
   {:name "mcp" :summary "Serve MCP, for coding agents" :options mcp-options :help mcp-help
    :main mcp-command}])

(def ^:private help
  (str "Usage: neorepl [COMMAND] [options]

A terminal client for nREPL servers. Without a COMMAND it starts a REPL.

Commands:
" (cli/indented-columns (map (juxt :name :summary) commands)) "

Run neorepl COMMAND --help for its options. neorepl --version prints the
version."))

(defn- run-command [{:keys [options help main]} args ctx]
  (let [{:keys [error] :as parsed} (cli/parse options args)]
    (cond
      error (throw (usage-error error))
      (:help (:options parsed)) (do (println help) 0)
      :else (main (:options parsed) (:args parsed) ctx))))

(defn run
  "Runs the neorepl command line args and returns the exit status. ctx has
  the :dir to look for port files in, the :sessions-file, the REPL's
  :history-dir, the :terminal options for its line editor, without which the
  REPL is the plain kind, and the :editor-command ,edit uses."
  [args ctx]
  (let [[command & more] args
        ;; Without a command, it's the REPL, options and all.
        [command args] (if (or (nil? command)
                               (and (str/starts-with? command "-") (not (#{"-h" "--help" "--version"} command))))
                         ["repl" args]
                         [command more])
        spec (some #(when (= command (:name %)) %) commands)]
    (try
      (cond
        (contains? #{"help" "-h" "--help"} command) (do (println help) 0)
        (= "--version" command) (do (println "neorepl" version) 0)
        spec (run-command spec args ctx)
        :else (throw (usage-error (str "Unknown command: " command))))
      (catch clojure.lang.ExceptionInfo e
        (case (:type (ex-data e))
          ::usage (do (warn (str (ex-message e) "\nRun neorepl " (when spec (str command " ")) "--help for usage."))
                      2)
          (::nrepl/no-server ::nrepl/disconnected ::nrepl/timeout) (do (warn (ex-message e)) 3)
          (::features/failed ::jack-in/failed ::jack-in/no-project ::server-running) (do (warn (ex-message e)) 1)
          (throw e))))))

(defn -main [& args]
  (let [status (run args {:dir (System/getProperty "user.dir")
                          :sessions-file (io/file (xdg/cache-home) "neorepl" "sessions.edn")
                          :history-dir (io/file (xdg/state-home) "neorepl" "history")
                          ;; See https://no-color.org.
                          :terminal {:color? (str/blank? (System/getenv "NO_COLOR"))}
                          :editor-command (some #(not-empty (System/getenv %)) ["VISUAL" "EDITOR"])})]
    (flush)
    (System/exit status)))
