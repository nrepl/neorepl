(ns neorepl.mcp
  "neorepl as an MCP server on stdio: JSON-RPC 2.0, a message a line, with
  tools over the same features as the CLI's commands, a curated few of them,
  since every tool's description takes up the model's context. It keeps its
  connections to nREPL servers open, and uses the CLI's named sessions, so
  an agent can mix neorepl eval and the eval tool."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.discovery :as discovery]
            [neorepl.evaluation :as evaluation]
            [neorepl.features :as features]
            [neorepl.json :as json]
            [neorepl.lexer :as lexer]
            [neorepl.lookups :as lookups]
            [neorepl.nrepl :as nrepl]
            [neorepl.render :as render]
            [neorepl.repair :as repair]
            [neorepl.sessions :as sessions])
  (:import (java.io BufferedReader Writer)
           (java.util.concurrent ExecutorService Executors TimeUnit)))

(def ^:private protocol-versions
  "The MCP versions neorepl speaks, the latest first."
  ["2025-11-25" "2025-06-18" "2025-03-26" "2024-11-05"])

;;; Connections

(defn- live?
  "Whether d, a connection on its way, is one to use: still opening, or open
  to a server that hasn't gone away."
  [d]
  (or (not (realized? d))
      (try (nrepl/connected? @d) (catch Exception _ false))))

(defn- client
  "The connection to the server on port, or the one the server was started
  for without a port, opening it if there's none yet, or a new one if the
  one there was failed to open or went down with its server, to the server
  that's there by then. Calls that come at once share the one they open."
  [{:keys [clients connect]} port]
  (let [[old new] (swap-vals! clients update port #(if (some-> % live?) % (delay (connect port))))
        d (get new port)
        replaced (get old port)]
    (when (and replaced (not (identical? replaced d)) (realized? replaced))
      (try (nrepl/close @replaced) (catch Exception _ nil)))
    @d))

(defn- with-client
  "Calls (f client) with the connection to the server on port."
  [server port f]
  (f (client server port)))

;;; Tools

(defn- result
  "A tool's result: text for the model, data like --format json's, and
  whether it's an error."
  ([text data] (result text data false))
  ([text data error?]
   {:text text :data data :error? error?}))

(defn- error
  "A tool's result saying it failed, and why."
  [message]
  (result message {:status "error" :message message} true))

(defn- output
  "What an evaluation, r, printed and came to, as text cut at max-output
  characters, as each of its values and streams is, and r, :truncated if
  that cut it."
  [{:keys [out err values] :as r} max-output]
  (let [text (str/join "\n" (remove str/blank? [(some-> out str/trimr)
                                                (some-> err str/trimr)
                                                (str/join "\n" values)]))
        cut? (< max-output (count text))]
    [(cond-> text cut? (evaluation/cut max-output))
     (cond-> r cut? (assoc :truncated true))]))

(defn- evaluate
  "Runs (run client session opts) in the session args name, opts being
  evaluation/evaluate's, the way neorepl eval runs things, and returns the
  tool's result, with (text result) after the output, if it says
  anything."
  ([server args on-send run] (evaluate server args on-send run (constantly nil)))
  ([{:keys [sessions-file timeout max-output] :as server} {:keys [session port] :as args} on-send run text]
   (with-client server port
     (fn [client]
       (let [[output {:keys [status message truncated] :as r}]
             (output (run client
                          (sessions/ensure! sessions-file client session)
                          {:timeout (or (:timeout args) timeout)
                           :max-output max-output
                           :on-send on-send
                           ;; Standard input is the protocol's, so there's
                           ;; none for the code.
                           :read-line (constantly nil)})
                     max-output)]
         (result (str/join "\n" (remove str/blank? [output
                                                    (when truncated (str "(Cut at " max-output " characters)"))
                                                    message
                                                    (text r)]))
                 r
                 ;; Tests that don't pass are an answer, not the tool failing.
                 (not (#{"ok" "failed"} status))))))))

(defn- run-tests
  "Runs the tests names names, as features/run-tests takes them, the way
  evaluate runs things."
  [server args on-send names]
  (evaluate server args on-send
            #(let [{:keys [report] :as r} (features/run-tests %1 %2 names %3)]
               ;; The report's at the top, as in neorepl test's edn and json.
               (merge (dissoc r :report) report))
            #(when (:summary %) (render/test-report-text %))))

(defn- lookup
  "Does the lookup called name, see neorepl.lookups, on arg, in the context
  (ctx client) makes, by default the one lookups/context makes of the
  :session and :ns args name."
  ([{:keys [sessions-file] :as server} {:keys [session ns] :as args} name arg]
   ;; Without that session yet, in user.
   (lookup server args name arg #(lookups/context % (get (sessions/named sessions-file %) session) session ns)))
  ([server {:keys [port]} name arg ctx]
   (with-client server port
     (fn [client]
       (let [{:keys [found text none]} (lookups/run name client (ctx client) arg)]
         (if found
           (result text found)
           (result none {:status "not-found" :message none})))))))

(defn- wrap
  "r, a tool's result, with its data under k if it's a list, since the
  structured content of a result is an object."
  [r k]
  (cond-> r
    (sequential? (:data r)) (update :data #(hash-map k %))))

(defn- reload
  "Reloads namespaces, or what changed without them, as neorepl reload does,
  giving up once the call's timeout, or the server's, is up."
  [{:keys [timeout] :as server} args namespaces]
  (let [seconds (or (:timeout args) timeout)]
    (try
      (lookup server args "reload" (seq namespaces) (constantly {:timeout-ms (long (* 1000 seconds))}))
      (catch clojure.lang.ExceptionInfo e
        (if (= ::nrepl/timeout (:type (ex-data e)))
          (error (str "Gave up waiting after " seconds "s, while the server may still be reloading"))
          (throw e))))))

(defn- check
  "The first unbalanced delimiter in each of files, as neorepl check finds
  them, repaired, as neorepl check --fix does, if fix?."
  [files fix?]
  (let [problems (into [] (keep (if fix? repair/fix-file lexer/file-problem)) files)]
    (result (if (seq problems)
              (str/join "\n" (map render/problem-text problems))
              "Every delimiter balances")
            {:problems problems})))

(def ^:private port-arg
  {:port {:type "integer" :minimum 1 :maximum 65535
          :description "The nREPL server's port, when the project runs several"}})

(def ^:private session-arg
  {:session {:type "string" :minLength 1
             :description "The named session, shared with neorepl's CLI (default: agent)"}})

(def ^:private timeout-arg
  {:timeout {:type "number" :exclusiveMinimum 0 :maximum 86400
             :description "Seconds before interrupting it"}})

(def ^:private ns-arg
  {:ns {:type "string" :description "The namespace to resolve in (default: the session's, or user)"}})

(defn- schema
  "An object schema with properties, required being the names of the ones
  that are."
  [properties & required]
  (cond-> {:type "object" :properties properties}
    (seq required) (assoc :required (vec required))))

(defn- symbol-tool [name description]
  {:name name
   :description description
   :inputSchema (schema (merge {:symbol {:type "string" :description "The symbol, qualified or as the ns sees it"}}
                               ns-arg session-arg port-arg)
                        "symbol")
   :run (fn [server {:keys [symbol] :as args} _] (lookup server args name symbol))})

(def tools
  "The tools, as tools/list has them, and how each one :runs, given the
  server, the arguments, with a :session and an :ns by default, and a
  function to call with the message id and the interrupt function of an
  evaluation, for cancelling it."
  [{:name "eval"
    :description "Evaluate Clojure code in a session that keeps *ns*, *1 and *e from call to call. Returns what it printed and its values; an exception's message and its class come in the error output, and pst shows the stack trace."
    :inputSchema (schema (merge {:code {:type "string" :description "The code, one form or several"}}
                                timeout-arg session-arg port-arg)
                         "code")
    :run (fn [server {:keys [code] :as args} on-send]
           (evaluate server args on-send
                     #(evaluation/evaluate %1 {:op "eval" :code code :session %2} %3)))}
   {:name "load"
    :description "Load a Clojure file into the session, so its errors carry the file's name and lines. Check it first."
    :inputSchema (schema (merge {:file {:type "string" :description "The file's path"}} timeout-arg session-arg port-arg)
                         "file")
    :run (fn [server {:keys [file] :as args} on-send]
           (let [f (io/file file)]
             (if (.isFile f)
               (evaluate server args on-send
                         #(evaluation/evaluate %1 {:op "load-file" :session %2 :file (slurp f)
                                                   :file-path (.getAbsolutePath f) :file-name (.getName f)}
                                               %3))
               (error (str "No such file: " file)))))}
   (symbol-tool "doc" "Show a var's arglists and documentation.")
   (symbol-tool "source" "Show a var's source.")
   (symbol-tool "where" "Show the file and line a var is defined at.")
   {:name "apropos"
    :description "Find vars whose names match a regex, with the first line of their docs."
    :inputSchema (schema (merge {:regex {:type "string" :description "A Java regex, matched against the names"}} port-arg)
                         "regex")
    :run (fn [server {:keys [regex] :as args} _] (wrap (lookup server args "apropos" regex (constantly {})) :vars))}
   {:name "macroexpand"
    :description "Expand the macro a form calls, or every macro in it with all."
    :inputSchema (schema (merge {:form {:type "string" :description "The form, as code"}
                                 :all {:type "boolean" :description "Expand every macro, not just the outermost"}}
                                ns-arg session-arg port-arg)
                         "form")
    :run (fn [server {:keys [form all] :as args} _]
           (lookup server args (if all "macroexpand-all" "macroexpand") form))}
   {:name "reload"
    :description "Reload namespaces after changing their files: the ones given, or with cider-nrepl, every one that changed."
    :inputSchema (schema (merge {:namespaces {:type "array" :items {:type "string"}
                                              :description "The namespaces to reload, by name"}}
                                timeout-arg port-arg))
    :run (fn [server {:keys [namespaces] :as args} _] (reload server args namespaces))}
   {:name "test"
    :description "Run tests: the namespaces given, or tests as ns/name, or with cider-nrepl or babashka, the whole project's. Says what didn't pass, where and why."
    :inputSchema (schema (merge {:names {:type "array" :items {:type "string"}
                                         :description "Namespaces, and tests as ns/name"}}
                                timeout-arg session-arg port-arg))
    :run (fn [server {:keys [names] :as args} on-send] (run-tests server args on-send (vec names)))}
   {:name "retest"
    :description "Rerun the tests that didn't pass last time. Takes cider-nrepl or babashka."
    :inputSchema (schema (merge timeout-arg session-arg port-arg))
    :run (fn [server args on-send] (run-tests server args on-send :failed))}
   {:name "pst"
    :description "Show the stack trace of the session's last error, minus the frames of the tooling."
    :inputSchema (schema (merge session-arg port-arg))
    :run (fn [{:keys [sessions-file] :as server} {:keys [session] :as args} _]
           (wrap (lookup server args "pst" nil
                         (fn [client] {:session (sessions/ensure! sessions-file client session) :session-name session}))
                 :causes))}
   {:name "interrupt"
    :description "Interrupt what's running in a session, like an evaluation that's been left behind."
    :inputSchema (schema (merge session-arg port-arg))
    :run (fn [{:keys [sessions-file] :as server} {:keys [port session]} _]
           (with-client server port
             (fn [client]
               (let [{:keys [status message] :as r} (sessions/interrupt-result sessions-file client session)]
                 (result (or message "Interrupted") r (= "error" status))))))}
   {:name "check"
    :description "Check Clojure files for unbalanced parens, brackets, braces and strings, without a server. Run it after editing a file and before loading it. With fix, it repairs them from the indentation, as parinfer would, and writes the files back, leaving alone the ones it can't tell how to repair."
    :inputSchema (schema {:files {:type "array" :items {:type "string"} :description "The files' paths"}
                          :fix {:type "boolean" :description "Repair what doesn't balance, writing the files back"}}
                         "files")
    :run (fn [_ {:keys [files fix]} _] (check files fix))}
   {:name "ports"
    :description "List the nREPL servers this project's port files point at, and whether they answer."
    :inputSchema (schema {})
    :run (fn [{:keys [dir]} _ _]
           (let [ports (try
                         (discovery/probe dir)
                         (catch clojure.lang.ExceptionInfo _ []))]
             (result (if (seq ports)
                       (str/join "\n" (for [{:keys [file port live]} ports]
                                        (str file " " port (when-not live " (not answering)"))))
                       "No port files")
                     {:ports ports})))}])

(def ^:private tools-by-name
  (into {} (map (juxt :name identity)) tools))

(defn- fits?
  "Whether value fits schema, as far as the tools' schemas go."
  [{:keys [type items minimum maximum exclusiveMinimum minLength]} value]
  (and (case type
         "string" (string? value)
         "integer" (integer? value)
         "number" (number? value)
         "boolean" (boolean? value)
         "array" (and (sequential? value) (every? #(fits? items %) value))
         true)
       (or (nil? minimum) (<= minimum value))
       (or (nil? maximum) (<= value maximum))
       (or (nil? exclusiveMinimum) (< exclusiveMinimum value))
       (or (nil? minLength) (<= minLength (count value)))))

(defn- problem
  "What's wrong with arguments for tool, as its inputSchema has it, or nil."
  [{{:keys [properties required]} :inputSchema} arguments]
  (if-not (or (nil? arguments) (map? arguments))
    "The arguments have to be an object"
    (if-let [lacking (seq (remove #(some? (get arguments (keyword %))) required))]
      (str "Missing " (str/join ", " lacking))
      (first (for [[k v] arguments
                   :let [schema (get properties k)]
                   :when (and schema (some? v) (not (fits? schema v)))]
               (str "The " (name k) " " (pr-str v) " doesn't fit its schema, " (json/write-str (dissoc schema :description))))))))

(defn- call-tool
  "Runs tool with arguments, as a tools/call result."
  [{:keys [run] :as tool} arguments server on-send]
  (let [{:keys [text data error?]}
        (if-let [message (problem tool arguments)]
          (error message)
          (try
            ;; The skill has the CLI use the session called agent too.
            (run server (merge {:session "agent"} arguments) on-send)
            (catch clojure.lang.ExceptionInfo e
              (error (ex-message e)))))]
    {:content [{:type "text" :text (if (str/blank? text) "(nothing)" text)}]
     :structuredContent data
     :isError (boolean error?)}))

;;; The protocol

(defn- send! [{:keys [^Writer out lock]} message]
  (locking lock
    (.write out (str (json/write-str (assoc message :jsonrpc "2.0")) "\n"))
    (.flush out)))

(defn- respond [server id result]
  (send! server {:id id :result result}))

(defn- fail [server id code message]
  (send! server {:id id :error {:code code :message message}}))

(defn- call
  "Runs tool, for the tools/call with id, on a thread of its own, so the
  requests after it get answered, a cancellation among them. It's :running
  until it's done, with the interrupt function of its evaluation once
  there's one."
  [{:keys [running cancelled ^ExecutorService calls] :as server} id tool arguments]
  (swap! running assoc id nil)
  (.execute calls
            (fn []
              (try
                (let [response (call-tool tool arguments server
                                          (fn [_ interrupt]
                                            (swap! running assoc id interrupt)
                                            ;; Cancelled before it got this far.
                                            (when (contains? @cancelled id)
                                              (interrupt))))]
                  (when-not (contains? @cancelled id)
                    (respond server id response)))
                (catch Throwable t
                  (fail server id -32603 (str "neorepl failed: " t)))
                (finally
                  (swap! running dissoc id)
                  (swap! cancelled disj id))))))

(defn- cancel
  "Interrupts the evaluation of the request with id, if it's still running,
  and leaves the request unanswered, as MCP wants."
  [{:keys [running cancelled]} id]
  (when (contains? @running id)
    (swap! cancelled conj id)
    (some-> (get @running id) (apply []))))

(defn- handle
  "Does what message, a JSON-RPC request or notification, asks."
  [server {:keys [id method params]}]
  (case method
    "initialize" (respond server id {:protocolVersion (or (some #{(:protocolVersion params)} protocol-versions)
                                                          (first protocol-versions))
                                     :capabilities {:tools {}}
                                     :serverInfo {:name "neorepl" :version (:version server)}
                                     :instructions "Evaluate Clojure, look things up, run tests and check files through the project's nREPL server. Sessions keep *ns*, *1 and *e between calls and are shared with the neorepl CLI."})
    "ping" (respond server id {})
    "tools/list" (respond server id {:tools (mapv #(dissoc % :run) tools)})
    "tools/call" (if-let [tool (get tools-by-name (:name params))]
                   (call server id tool (:arguments params))
                   (fail server id -32602 (str "No tool called " (:name params))))
    "notifications/cancelled" (cancel server (:requestId params))
    ;; Other notifications, and responses to requests neorepl never sends.
    (when (and id method)
      (fail server id -32601 (str "No method called " method)))))

(defn serve
  "Serves MCP, reading messages from in and writing to out, until in ends.
  opts has the :version to report, the :sessions-file, the :dir to look for
  port files in, the default :timeout and :max-output of evaluations, and
  (connect port) to open a connection to the server on port, or the
  default one for nil."
  [^BufferedReader in ^Writer out opts]
  (let [server (assoc opts :out out :lock (Object.)
                      :clients (atom {}) :running (atom {}) :cancelled (atom #{})
                      :calls (Executors/newCachedThreadPool))]
    (try
      (doseq [line (line-seq in)
              :when (not (str/blank? line))]
        (let [message (try
                        (json/read-str line)
                        ;; Whatever it chokes on, a stack overflow from
                        ;; nesting too.
                        (catch Throwable _
                          (fail server nil -32700 "That's not JSON")
                          nil))]
          (when message
            (try
              (handle server message)
              (catch Exception e
                (when (:id message)
                  (fail server (:id message) -32603 (str "neorepl failed: " (ex-message e)))))))))
      ;; The calls still under way get answered before it ends.
      (doto ^ExecutorService (:calls server)
        (.shutdown)
        (.awaitTermination Long/MAX_VALUE TimeUnit/MILLISECONDS))
      (finally
        (doseq [d (vals @(:clients server))
                :when (realized? d)]
          (try (nrepl/close @d) (catch Exception _ nil)))))))
