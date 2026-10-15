(ns neorepl.features
  "What neorepl asks a server for besides evaluations. Each feature goes for
  the best way the server supports: cider-nrepl's ops first, then the ones
  nREPL has built in, then evaluating code. Anything evaluated runs outside
  the user's sessions, so *1 and *e stay put.

  The lookups take a ctx saying where to look: the :ns, and the :session
  the lookup's for, if any. cider-nrepl answers in that session's language,
  so a session in ClojureScript mode gets ClojureScript's answers. The
  functions take request options last, as nrepl/request does, for a
  :timeout-ms. They throw ::failed when the server reports an error, which
  isn't the same as finding nothing."
  (:require [clojure.edn :as edn]
            [clojure.main :as main]
            [clojure.string :as str]
            [neorepl.evaluation :as evaluation]
            [neorepl.lexer :as lexer]
            [neorepl.nrepl :as nrepl]))

(defn- supports? [client op]
  (contains? (:ops client) op))

(defn- ask
  "Sends msg and returns its responses, throwing ::failed when the server
  reports an error: a status ending in error, as eval-error and
  cider-nrepl's info-error do, other than its no-error."
  [client msg opts]
  (let [responses (nrepl/request client msg opts)
        statuses (into #{} (mapcat :status) responses)]
    (when (some #(and (str/ends-with? % "error") (not= "no-error" %)) statuses)
      (throw (ex-info (str "The server couldn't do " (:op msg)
                           (when-let [err (some-> (keep :err responses) seq str/join str/trim)]
                             (str ": " err)))
                      {:type ::failed :statuses statuses})))
    responses))

(defn- eval-data
  "Evaluates the code that (format template args...) makes and returns its
  value, which has to be plain data, read back. msg says where: its :ns, or
  its :session. The args go in as string literals, so they can't be code:
  the template turns them into what it needs, as with (symbol %s)."
  [client msg template args opts]
  (some->> (ask client
                (assoc msg :op "eval" :code (apply format template (map pr-str args)))
                opts)
           (some :value)
           edn/read-string))

(defn- lookup-msg
  "msg, looking something up where ctx says."
  [msg {:keys [ns session]}]
  ;; Only ops that don't evaluate, so the session's *1 and *e stay put.
  (cond-> (assoc msg :ns ns)
    session (assoc :session session)))

(defn- truthy [x]
  (contains? #{true "true"} x))

(defn- arglists
  "The arglists in lookup's \"([x] [x y])\" or cider-nrepl's arglist per line,
  as a vector of strings."
  [s]
  (cond
    (str/blank? s) nil
    (str/starts-with? s "(") (lexer/forms (subs s 1 (dec (count s))))
    :else (str/split-lines s)))

(defn- form-text
  "A special form's shape the way lookup has it, a vector of strings and
  vectors, as text."
  [form]
  (if (string? form)
    form
    (str "[" (str/join " " (map form-text form)) "]")))

(defn- source-file
  "Where a server says a definition is, as a path when that's a file on this
  machine, nil when it came from a REPL rather than a file, and as it is
  otherwise, like a jar: URL for a file in a jar."
  [file]
  (cond
    (or (str/blank? file) (str/ends-with? file "NO_SOURCE_PATH")) nil
    (str/starts-with? file "file:") (str (java.io.File. (java.net.URI. file)))
    :else file))

(defn- normalize-info
  "info and lookup responses in one shape: :ns, :name, :doc, where it is as
  the :file, :line and :column, :arglists and a special form's :forms as
  vectors of strings, and :macro and :special-form."
  [{:keys [file arglists-str forms forms-str macro special-form] :as info}]
  (let [file (source-file file)]
    (cond-> (select-keys info [:ns :name :doc])
      file (merge {:file file} (select-keys info [:line :column]))
      (seq (arglists arglists-str)) (assoc :arglists (arglists arglists-str))
      (truthy macro) (assoc :macro true)
      (truthy special-form) (assoc :special-form true)
      (seq forms) (assoc :forms (mapv #(if (string? %) % (str "(" (str/join " " (map form-text %)) ")")) forms))
      forms-str (assoc :forms (str/split-lines forms-str)))))

(defn info
  "What the server knows about sym, a symbol's text, as seen from where ctx
  says: a map with its :ns and :name, :arglists, :doc and where it
  is, :file, :line and :column, when it's in a file. Nil when sym means
  nothing there."
  ([client sym ctx] (info client sym ctx {}))
  ([client sym ctx opts]
   (cond
     (supports? client "info")
     (let [info (apply merge (ask client (lookup-msg {:op "info" :sym sym} ctx) opts))]
       (when (:name info)
         (normalize-info info)))

     (supports? client "lookup")
     (let [info (some :info (ask client (lookup-msg {:op "lookup" :sym sym} ctx) opts))]
       ;; Nothing found comes back as an empty list.
       (when (map? info)
         (normalize-info info)))

     :else
     (some-> (eval-data client (select-keys ctx [:ns]) "(when-let [m (meta (resolve (symbol %s)))]
                                    {:ns (str (:ns m))
                                     :name (str (:name m))
                                     :arglists-str (some->> (:arglists m) (map pr-str) (interpose \"\\n\") (apply str))
                                     :doc (:doc m)
                                     :file (:file m)
                                     :line (:line m)
                                     :column (:column m)
                                     :macro (:macro m)})"
                        [sym] opts)
             normalize-info))))

(defn complete
  "The completions for prefix where ctx says, as maps with the :candidate
  and, when the server says, its :type and :ns."
  ([client prefix ctx] (complete client prefix ctx {}))
  ([client prefix ctx opts]
   (when-let [op (some (:ops client) ["complete" "completions"])]
     (->> (ask client (lookup-msg {:op op :prefix prefix} ctx) opts)
          (some :completions)
          (mapv #(select-keys % [:candidate :type :ns]))))))

(defn source
  "The source of the var sym names in the namespace ctx has, or nil when the
  server can't find it."
  ([client sym ctx] (source client sym ctx {}))
  ([client sym ctx opts]
   (eval-data client (select-keys ctx [:ns]) "(do (require 'clojure.repl) (clojure.repl/source-fn (symbol %s)))" [sym] opts)))

(defn apropos
  "The vars whose names match the regex, as maps with the :name and, when
  the server says, the :type and :doc."
  ([client regex] (apropos client regex {}))
  ([client regex opts]
   (if (supports? client "apropos")
     (->> (ask client {:op "apropos" :query regex} opts)
          (some :apropos-matches)
          (mapv #(select-keys % [:name :type :doc])))
     (->> (eval-data client {:ns "user"} "(do (require 'clojure.repl) (map str (clojure.repl/apropos (re-pattern %s))))"
                     [regex] opts)
          (mapv #(hash-map :name %))))))

(defn- one-form
  "Throws unless text is a single form the reader can take without
  evaluating anything, the way #= would. cider-nrepl evaluates what it
  expands with *read-eval* on."
  [text]
  (when-not (and (= 1 (count (lexer/forms text)))
                 (not (str/includes? text "#=")))
    (throw (ex-info "Give one form to expand, without #=" {:type ::failed}))))

(defn expand
  "The form in text with macros expanded where ctx says, pretty printed: just
  the form itself, the way macroexpand-1 does, or every macro in it with
  all?"
  ([client text ctx all?] (expand client text ctx all? {}))
  ([client text ctx all? opts]
   (one-form text)
   (if (supports? client "macroexpand")
     (->> (ask client (lookup-msg {:op "macroexpand"
                                   :code text
                                   :expander (if all? "macroexpand-all" "macroexpand-1")
                                   ;; Without the namespaces of what the namespace refers.
                                   :display-namespaces "tidy"}
                                  ctx)
               opts)
          (some :expansion))
     (some-> (eval-data client (select-keys ctx [:ns])
                        "(do (require 'clojure.pprint 'clojure.walk)
                             (with-out-str
                               (clojure.pprint/pprint
                                 ((if %s clojure.walk/macroexpand-all macroexpand-1)
                                  (binding [*read-eval* false] (read-string %s))))))"
                        [all? text] opts)
             str/trimr))))

(defn- blank->nil
  "cider-nrepl sends nil as an empty list, babashka as an empty string."
  [x]
  (when-not (or (and (string? x) (str/blank? x)) (and (coll? x) (empty? x)))
    x))

(defn- dups
  "Flags the frames that call on from one before them in the same function,
  the way cider-nrepl does, as dup."
  [frames]
  (let [fn-of #(first (str/split (str (:name %)) #"/"))]
    (into (vec (take 1 frames))
          (map (fn [previous frame]
                 (cond-> frame (= (fn-of previous) (fn-of frame)) (update :flags (fnil conj []) "dup")))
               frames (rest frames)))))

(defn- frame-var
  "The var of frame's function, from analyze-last-stacktrace or *e, if it's
  Clojure's: cider-nrepl says, babashka's server names the frame after it,
  and the others name it after the class, user$f$fn__123 for user/f, which
  is a namespace's, lower case, rather than a Java class's, like
  ThreadPoolExecutor$Worker."
  [{:keys [var ns name]}]
  (let [ns-and-name #(some->> % (re-find #"^[^/]+/[^/]+"))]
    (cond
      var var
      ns (ns-and-name name)
      (re-find #"^(?:[^$/]*\.)?[a-z_][^.$/]*\$" (str name)) (ns-and-name (main/demunge (first (str/split name #"/")))))))

(def ^:private causes-code
  "A function, as code for the server, of a Throwable to its causes, the way
  analyze-last-stacktrace has them, roughly, with the whole stack trace on the
  last one and where it was thrown on the others. For servers without it.
  It goes through format, in templates, so it can't have a % of its own."
  "(fn [e]
     (let [frame (fn [[class method file line]]
                   {:name (str class \"/\" method) :file file :line line})
           cause (fn [{:keys [type message data at]}]
                   {:class (str type)
                    :message message
                    :data (binding [*print-length* 20 *print-level* 5]
                            (some-> data pr-str))
                    :stacktrace (if at [(frame at)] [])})
           {:keys [via trace]} (Throwable->map e)]
       (conj (mapv cause (pop via))
             (assoc (cause (peek via)) :stacktrace (mapv frame trace)))))")

(defn- tooling?
  "Whether frame, from causes-code, is one of what runs the code rather than
  of the code itself, which servers with analyze-last-stacktrace flag."
  [{:keys [name]}]
  (re-find #"^(clojure\.lang\.Compiler|clojure\.main|nrepl\..+|java\.lang\.Thread)/" (str name)))

(defn- stacktrace-causes
  "The causes in the responses to msg, an analyze-last-stacktrace or
  test-stacktrace, which answer with one each."
  [client msg opts]
  (filter :class (ask client msg opts)))

(defn- normalize-causes
  "causes, as analyze-last-stacktrace and test-stacktrace have them, or
  causes-code, in the shape last-error returns them."
  [causes]
  (mapv (fn [{:keys [class message data stacktrace]}]
          {:class class
           :message (blank->nil message)
           :data (blank->nil data)
           :stacktrace (dups (mapv (fn [{:keys [file-url flags] :as frame}]
                                     (let [var (frame-var frame)
                                           ;; cider-nrepl's, for the exact file the line's in.
                                           path (when (and (string? file-url) (str/starts-with? file-url "file:"))
                                                  (source-file file-url))]
                                       (cond-> (select-keys frame [:name :file :line :flags])
                                         (and (nil? flags) (tooling? frame)) (assoc :flags ["tooling"])
                                         var (assoc :var var)
                                         path (assoc :path path))))
                                   stacktrace))})
        causes))

(defn last-error
  "The last error in session, as its causes, outermost first: maps with the
  :class, the :message, ex-data as :data text and the :stacktrace, frames with
  a :name, :file and :line, the :var of the function, for Clojure's frames,
  the full :path of the file, when the server says, and :flags saying what
  kind of frame it is, like tooling for the frames running the code rather
  than of it. Nil when nothing's gone wrong yet."
  ([client session] (last-error client session {}))
  ([client session opts]
   (some->>
    (if (supports? client "analyze-last-stacktrace")
      (stacktrace-causes client {:op "analyze-last-stacktrace" :session session} opts)
      ;; *e is in the session, so the code looking at it has to run there,
      ;; in a copy, so the session's *1 stays put.
      (let [copy (try
                   (nrepl/clone-session client session)
                   (catch clojure.lang.ExceptionInfo e
                     (throw (ex-info "The server couldn't copy the session" {:type ::failed} e))))]
        (try
          (some->> (eval-data client {:session copy}
                              ;; Printed here, with print settings of its own:
                              ;; the copy has the session's, which could cut it short.
                              (format "(when-let [e *e]
                                         (binding [*print-length* nil *print-level* nil *print-meta* false]
                                           (pr-str (%s e))))"
                                      causes-code)
                              [] opts)
                   edn/read-string)
          (finally
            (nrepl/close-session client copy)))))
    normalize-causes
    not-empty)))

(defn reload
  "Reloads code and returns {:reloaded [the namespaces]}, and the :output
  loading them printed, like reflection warnings, if any. With nses, a seq
  of namespace names, those namespaces get reloaded. Without, the ones that
  changed since the last reload, which takes cider-nrepl's refresh. A big
  project takes a while, so it waits as long as that takes unless opts have
  a :timeout-ms."
  ([client nses] (reload client nses {}))
  ([client nses opts]
   (let [opts (merge {:timeout-ms nil} opts)
         [reloaded responses]
         (cond
           (seq nses)
           [(vec nses)
            (ask client {:op "eval"
                         :ns "user"
                         :code (format "(apply require (concat (map symbol %s) [:reload]))" (pr-str (vec nses)))}
                 opts)]

           (supports? client "refresh")
           (let [responses (ask client {:op "refresh"} opts)]
             [(vec (some :reloading responses)) responses])

           :else
           (throw (ex-info "Reloading what changed takes cider-nrepl on the server; name the namespaces to reload"
                           {:type ::failed})))
         output (str/join (keep #(or (:out %) (:err %)) responses))]
     (cond-> {:reloaded reloaded}
       (seq output) (assoc :output output)))))

(defn- qualified?
  "Whether name, a test's or a namespace's, is a test's: ns/name."
  [name]
  (str/includes? name "/"))

(defn- unknown-tests
  "Loads the namespaces that names, names of namespaces and of tests as
  ns/name, are or are in, in session, and returns the names of tests that
  aren't any. Both test ops go quiet when asked for a test in a namespace
  that isn't loaded, and cider-nrepl's runs every test it can find for one
  that isn't there. The code works in Clojure and ClojureScript alike, which
  only requires namespaces named by quoted symbols, so names have to be
  symbols."
  [client session names opts]
  (when-let [bad (seq (remove #(let [s (try (edn/read-string %) (catch Exception _ nil))]
                                 (and (symbol? s) (= % (str s))))
                              names))]
    (throw (ex-info (str "Not the name of a namespace or a test: " (str/join ", " bad)) {:type ::failed})))
  (let [symbols (map symbol names)
        nss (distinct (map #(symbol (or (namespace %) (name %))) symbols))
        tests (filter namespace symbols)]
    ;; Evaluations of their own: ClojureScript's require has to be a form
    ;; of its own, and piggieback evaluates only the first form of the code.
    (ask client {:op "eval" :session session
                 :code (str "(require " (str/join " " (map #(str "'" %) nss)) ")")}
         opts)
    (when (seq tests)
      (some->> (ask client {:op "eval" :session session
                            :code (str "(vec (remove nil? ["
                                       (str/join " " (for [t tests]
                                                       (format "(when-not (:test (meta (resolve '%s))) %s)" t (pr-str (str t)))))
                                       "]))")}
                    opts)
               (some :value)
               edn/read-string))))

(def ^:private run-tests-code
  "Runs the tests in the namespaces that %s names, or the tests it names, the
  way clojure.test does, and returns the report, the way test-var-query's
  reads after test-results, printed, with the :causes of errors, as the
  function the second %s is, causes-code, has them. For servers without a
  test op."
  "(do
     (require 'clojure.test)
     (let [names (map symbol %s)
           causes %s
           counts (atom {:ns 0 :var 0 :test 0 :pass 0 :fail 0 :error 0})
           failures (atom [])
           ;; For what goes wrong outside a test, like a :once fixture.
           current-ns (atom nil)
           start (System/currentTimeMillis)
           record (fn [{:keys [type message expected actual file line]}]
                    (let [m (meta (first clojure.test/*testing-vars*))]
                      (swap! counts (fn [c] (-> c (update type inc) (update :test inc))))
                      (when-not (= :pass type)
                        (swap! failures conj
                               (cond-> {:type (name type)
                                        :ns (some-> (or (:ns m) @current-ns) ns-name str)
                                        :var (some-> (:name m) str)
                                        :context (not-empty (clojure.test/testing-contexts-str))
                                        :message (some-> message str)
                                        :file file
                                        :line line}
                                 (some? expected) (assoc :expected (pr-str expected))
                                 (= :fail type) (assoc :actual (pr-str actual))
                                 (= :error type) (assoc :error (str actual))
                                 (and (= :error type) (instance? Throwable actual))
                                 (assoc :causes (binding [*print-meta* false] (causes actual))))))))
           ;; clojure.test lets what a :once fixture throws through.
           guarded (fn [ns f]
                     (reset! current-ns ns)
                     (try (f) (catch Throwable e (record {:type :error :actual e}))))]
       (binding [clojure.test/report (fn [{:keys [type] :as m}]
                                       (case type
                                         (:pass :fail :error) (record m)
                                         :begin-test-var (swap! counts update :var inc)
                                         :begin-test-ns (do (reset! current-ns (:ns m))
                                                            (swap! counts update :ns inc))
                                         nil))]
         (if (some namespace names)
           (doseq [[ns vars] (group-by (fn [v] (:ns (meta v))) (map find-var names))]
             (swap! counts update :ns inc)
             (guarded ns (fn [] (clojure.test/test-vars vars))))
           (doseq [n names]
             (guarded (the-ns n) (fn [] (clojure.test/test-ns n))))))
       (binding [*print-length* nil *print-level* nil *print-meta* false]
         (pr-str {:summary @counts
                  :failures (mapv (fn [f] (into {} (remove (comp nil? val)) f)) @failures)
                  :elapsed-ms (- (System/currentTimeMillis) start)}))))")

(defn- test-results
  "What didn't pass in the :results of test-var-query or retest, which go
  namespace, test and assertion, in that order."
  [results]
  (vec (for [[_ tests] (sort-by key results)
             [_ assertions] (sort-by key tests)
             {:keys [type line] :as assertion} (sort-by :index assertions)
             :when (#{"fail" "error"} type)
             :let [text #(some-> (get assertion %) blank->nil str str/trimr)]]
         (into {} (remove (comp nil? val))
               {:type type
                :ns (text :ns)
                :var (text :var)
                :context (text :context)
                :message (text :message)
                :expected (text :expected)
                :actual (text :actual)
                ;; ClojureScript's is a map, with the stack too.
                :error (if (map? (:error assertion)) (some-> assertion :error :message blank->nil) (text :error))
                :file (text :file)
                :line line
                ;; For test-stacktrace.
                :index (when (= "error" type) (:index assertion))}))))

(defn- locate
  "failures with the :file each one's test is in, for those whose :file is
  that file's name, as cider-nrepl and clojure.test give it, or missing, as
  babashka has it. Others, like an assertion in a helper elsewhere, keep
  theirs, which goes with their :line."
  [client session failures]
  (let [tests (distinct (keep (fn [{:keys [ns var]}] (when (and ns var) [ns var])) failures))
        ;; Independent lookups, so all at once.
        files (zipmap tests (pmap (fn [[ns var]]
                                    (try
                                      (:file (info client (str ns "/" var) {:ns ns :session session}))
                                      (catch clojure.lang.ExceptionInfo _ nil)))
                                  tests))]
    (mapv (fn [{:keys [ns var] :as failure}]
            (let [file (get files [ns var])
                  given (source-file (:file failure))]
              (if (and file (or (nil? given) (= given (.getName (java.io.File. ^String file)))))
                (assoc failure :file file)
                failure)))
          failures)))

(defn- test-runner-frame?
  "Whether frame, from normalize-causes, is one of the test runner's, which
  the frames of a test's error go on into."
  [{:keys [name]}]
  (re-find #"^(clojure\.test|orchard\.test|cider\.nrepl\.middleware\.test|babashka\.impl\.clojure\.test)[$/]"
           (str name)))

(defn- above-test-runner
  "The frames of stacktrace above the test runner's, or all of them when
  they start there, as for an error in the runner itself, like a broken
  fixture."
  [stacktrace]
  (let [above (into [] (take-while (complement test-runner-frame?)) stacktrace)]
    (if (seq above) above stacktrace)))

(defn- with-causes
  "failures with the :causes of the errors, the way last-error has them,
  without the test runner's frames: as run-tests-code has them, or from
  test-stacktrace, by their :index, with opts as nrepl/request takes them."
  [client session opts failures]
  (let [stacktrace? (supports? client "test-stacktrace")]
    ;; Independent requests, so all at once.
    (vec (pmap (fn [{:keys [ns var index causes] :as failure}]
                 (let [causes (or causes
                                  ;; Only errors have an index.
                                  (when (and stacktrace? ns var index)
                                    (try
                                      (stacktrace-causes client {:op "test-stacktrace" :session session
                                                                 :ns ns :var var :index index}
                                                         opts)
                                      (catch clojure.lang.ExceptionInfo _ nil))))]
                   (cond-> (dissoc failure :index :causes)
                     (seq causes) (assoc :causes (mapv #(update % :stacktrace above-test-runner)
                                                       (normalize-causes causes))))))
               failures))))

(defn- op-report
  "The report in a test-var-query or retest response."
  [{:keys [summary results elapsed-time]}]
  {:summary (merge {:ns 0 :var 0 :test 0 :pass 0 :fail 0 :error 0}
                   (select-keys summary [:ns :var :test :pass :fail :error]))
   :failures (test-results results)
   :elapsed-ms (:ms elapsed-time)})

(defn- eval-report
  "The report in the value of run-tests-code's evaluation."
  [value]
  (-> (edn/read-string (edn/read-string value))
      ;; In the order test-var-query has them.
      (update :failures #(vec (sort-by (juxt :ns :var) %)))))

(defn- test-msg
  "The message that runs the tests run-tests takes names for, in session, or
  in copy, a copy of it, through clojure.test, when there's one."
  [session copy names]
  (cond
    ;; cider-nrepl streams test-events with it, for progress.
    (= :failed names) {:op "retest" :session session :stream "true"}
    copy {:op "eval" :session copy :code (format run-tests-code (pr-str (vec names)) causes-code)}
    :else {:op "test-var-query"
           :session session
           :stream "true"
           :var-query (cond
                        (some qualified? names) {:exactly (vec names)}
                        (seq names) {:ns-query {:exactly (vec names)}}
                        ;; bencode has no booleans.
                        :else {:ns-query {:project? "true" :load-project-ns? "true"}})}))

(defn- progress
  "response, with a test-event cider-nrepl streams, as the progress
  clojure.test prints: output saying which namespace's tests start."
  [{:keys [test-event] :as response}]
  (cond-> (dissoc response :test-event)
    (= "begin-ns" (:type test-event)) (assoc :out (str "Testing " (:ns test-event) "\n"))))

(defn- evaluate-for
  "Evaluates msg the way evaluation/evaluate does, whose opts it takes, for
  what (capture response) makes of its responses rather than to show its
  values: they go on to :on-response without their :value, and the result
  has no :values."
  [client msg opts capture]
  (let [on-response (:on-response opts (fn [_]))]
    (-> (evaluation/evaluate client msg (assoc opts :on-response (fn [response]
                                                                   (capture response)
                                                                   (on-response (dissoc response :value)))))
        (dissoc :values))))

(defn run-tests
  "Runs tests in session: those in the namespaces names names, or the tests
  it names, as ns/name, every test in the project when it's empty and the
  ones that didn't pass last time when it's :failed. Returns what
  evaluation/evaluate does, whose opts it takes, and, when the tests ran to
  the end, the :report: the :summary, counting the :ns, the tests as :var,
  the assertions as :test and how many :pass, :fail and :error, the
  :failures, maps with their :type (fail or error), :ns, :var, :context,
  :message, :expected, :actual or :error and the error's :causes, as
  last-error has them, if the server says, :file and :line, and the
  :elapsed-ms. Its :status is failed when a test didn't pass. The
  :on-response gets a Testing line for each namespace too, as output, when
  the server says, as cider-nrepl does."
  [client session names {:keys [timeout] :as opts}]
  (let [failed? (= :failed names)
        ops? (supports? client (if failed? "retest" "test-var-query"))]
    (when (and (not failed?) (some qualified? names) (not-every? qualified? names))
      (throw (ex-info "Give namespaces or tests to run, not both" {:type ::failed})))
    (when-not ops?
      (cond
        failed? (throw (ex-info "Rerunning the tests that failed takes cider-nrepl or babashka on the server"
                                {:type ::failed}))
        (empty? names) (throw (ex-info "Running every test takes cider-nrepl or babashka on the server; name the namespaces to test"
                                       {:type ::failed}))))
    (let [check? (and (not failed?) (seq names))
          ;; The check and the eval ones run in a copy of the session, in its
          ;; language, so its *1 stays put.
          copy (when check? (nrepl/clone-session client session))
          report (atom nil)]
      (try
        (when check?
          (when-let [unknown (seq (unknown-tests client copy names {:timeout-ms (some-> timeout (* 1000) long)}))]
            (throw (ex-info (str "No test called " (str/join ", " unknown)) {:type ::failed}))))
        (let [{:keys [status] :as result}
              (-> (evaluate-for client
                                (test-msg session (when-not ops? copy) names)
                                (cond-> opts
                                  ;; It would cut the report short, which is the eval's value.
                                  (not ops?) (dissoc :max-output)
                                  (:on-response opts) (update :on-response comp progress))
                                #(cond
                                   (:summary %) (reset! report (op-report %))
                                   (and (not ops?) (:value %)) (reset! report (eval-report (:value %)))))
                  (dissoc :ns))
              {{:keys [fail error]} :summary :as report} @report]
          (cond
            ;; Only a run that ended by itself, since clojure.test reports
            ;; an interrupted test as an error and carries on.
            (not= "ok" status) result
            report (cond-> (assoc result :report (update report :failures #(->> (locate client session %)
                                                                                  (with-causes client session
                                                                                    (cond-> {}
                                                                                      timeout (assoc :timeout-ms (long (* 1000 timeout))))))))
                     (pos? (+ fail error)) (assoc :status "failed"))
            :else (throw (ex-info "The server didn't run the tests" {:type ::failed}))))
        (finally
          (when copy
            ;; There's nothing to close when the connection is gone.
            (try (nrepl/close-session client copy) (catch Exception _ nil))))))))

(defn- inspector-lines
  "What cider-nrepl's inspector renders, a list of strings, (:newline) and
  (:value text index), as lines: vectors of segments, maps with the :text and,
  for a value to inspect further, its :index, or :heading? for a section's
  heading. A value that spans lines is a segment on each, those after the
  first marked :continued."
  [rendered]
  (let [parts (edn/read-string rendered)
        _ (when-not (sequential? parts)
            (throw (ex-info (str "The server's inspector said " rendered) {:type ::failed})))
        segments (for [part parts
                       segment (cond
                                 (string? part) [(cond-> {:text part}
                                                   (str/starts-with? part "---") (assoc :heading? true))]
                                 (= '(:newline) part) [:newline]
                                 (and (sequential? part) (= :value (first part)))
                                 [{:text (str (second part)) :index (nth part 2 nil)}]
                                 ;; Whatever else it might come up with.
                                 (sequential? part) [{:text (str/join (filter string? part))}]
                                 :else [{:text (str part)}])]
                   segment)]
    (loop [lines []
           line []
           [segment & more :as segments] segments]
      (cond
        (empty? segments) (cond-> lines (seq line) (conj line))
        (= :newline segment) (recur (conj lines line) [] more)
        ;; Pretty printed values span lines, which are lines of the view too.
        (str/includes? (:text segment) "\n")
        (let [[first-line & others] (str/split (:text segment) #"\n" -1)
              piece #(assoc segment :text % :continued true)]
          (recur (into (conj lines (conj line (assoc segment :text first-line)))
                       (map (comp vector piece))
                       (butlast others))
                 [(piece (last others))]
                 more))
        :else (recur lines (conj line segment) more)))))

(defn- check-inspector [client]
  (when-not (supports? client "inspect-push")
    (throw (ex-info "Inspecting values takes cider-nrepl or babashka on the server" {:type ::failed}))))

(defn inspect
  "Evaluates code in session and starts inspecting its value, or the last
  form's when there are several, the way evaluation/evaluate evaluates, whose
  result it returns, whose opts it takes, and, when the evaluation worked,
  the :lines of the inspector's view, as inspector takes them. The inspector
  lives in the session on the server, which inspector moves around in."
  [client session code opts]
  (check-inspector client)
  (let [;; The last value's view, or ::failed.
        view (atom nil)
        {:keys [status] :as result}
        (evaluate-for client
                      {:op "eval" :session session :code code :inspect "true"}
                      ;; It would cut the view short, which is the value.
                      (dissoc opts :max-output)
                      #(cond
                         (:value %) (reset! view (:value %))
                         ;; cider-nrepl's way of saying eval-error.
                         (nrepl/status? % "inspect-eval-error") (reset! view ::failed)))]
    (cond
      (not= "ok" status) result
      (= ::failed @view) (assoc result :status "eval-error")
      @view (assoc result :lines (inspector-lines @view))
      :else (throw (ex-info "The server didn't inspect the value" {:type ::failed})))))

(defn inspector
  "Does what (:op msg), an inspect-push or the like, says to the inspector in
  session and returns the :lines of the view it ends up with, and the
  :status ok: vectors of segments, maps with the :text and, for values to
  inspect further, the :index that inspect-push takes, or :heading? for the
  heading of a section, like --- Contents:."
  ([client session msg] (inspector client session msg {}))
  ([client session msg opts]
   (check-inspector client)
   (let [responses (ask client (assoc msg :session session) opts)]
     (if-let [value (some :value responses)]
       {:status "ok" :lines (inspector-lines value)}
       (throw (ex-info (str "The server couldn't do " (:op msg)) {:type ::failed}))))))
