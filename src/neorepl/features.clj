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

(defn last-error
  "The last error in session, as its causes, outermost first: maps with the
  :class, the :message, ex-data as :data text and the :stacktrace, frames with
  a :name, :file and :line, and :flags saying what kind of frame it is, like
  tooling for the frames running the code rather than of it. Nil when
  nothing's gone wrong yet."
  ([client session] (last-error client session {}))
  ([client session opts]
   (some->>
    (if (supports? client "analyze-last-stacktrace")
      (->> (ask client {:op "analyze-last-stacktrace" :session session} opts)
           (filter :class))
      ;; *e is in the session, so the code looking at it has to run there,
      ;; in a copy, so the session's *1 stays put.
      (let [copy (try
                   (nrepl/clone-session client session)
                   (catch clojure.lang.ExceptionInfo e
                     (throw (ex-info "The server couldn't copy the session" {:type ::failed} e))))
            ;; What runs the code rather than the code itself.
            tooling? #(re-find #"^(clojure\.lang\.Compiler|clojure\.main|nrepl\..+|java\.lang\.Thread)/" (:name %))]
        (try
          (some->> (eval-data client {:session copy}
                              ;; Printed here, with print settings of its own:
                              ;; the copy has the session's, which could cut it short.
                              "(when-let [e *e]
                                 (binding [*print-length* nil *print-level* nil *print-meta* false]
                                   (let [frame (fn [[class method file line]]
                                                 {:name (str class \"/\" method) :file file :line line})
                                         cause (fn [{:keys [type message data at]}]
                                                 {:class (str type)
                                                  :message message
                                                  :data (binding [*print-length* 20 *print-level* 5]
                                                          (some-> data pr-str))
                                                  :stacktrace (if at [(frame at)] [])})
                                         {:keys [via trace]} (Throwable->map e)]
                                     (pr-str (conj (mapv cause (pop via))
                                                   (assoc (cause (peek via)) :stacktrace (mapv frame trace)))))))"
                              [] opts)
                   edn/read-string
                   (map (fn [cause]
                          (update cause :stacktrace
                                  (partial mapv #(cond-> % (tooling? %) (assoc :flags ["tooling"])))))))
          (finally
            (nrepl/close-session client copy)))))
    (mapv (fn [{:keys [class message data stacktrace]}]
            {:class class
             :message (blank->nil message)
             :data (blank->nil data)
             :stacktrace (dups (mapv #(select-keys % [:name :file :line :flags]) stacktrace))}))
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
  ns/name, are or are in, and returns the names of tests that aren't any.
  Both test ops go quiet when asked for a test in a namespace that isn't
  loaded, and cider-nrepl's runs every test it can find for one that isn't
  there."
  [client names opts]
  (eval-data client {:ns "user"}
             "(let [names (map symbol %s)]
                (apply require (distinct (map (fn [n] (symbol (or (namespace n) (name n)))) names)))
                (vec (for [n names
                           :when (and (namespace n) (not (:test (meta (find-var n)))))]
                       (str n))))"
             [names] opts))

(def ^:private run-tests-code
  "Runs the tests in the namespaces that %s names, or the tests it names, the
  way clojure.test does, and returns the report, the way test-var-query's
  reads after test-results, printed. For servers without a test op."
  "(do
     (require 'clojure.test)
     (let [names (map symbol %s)
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
                                 (= :error type) (assoc :error (str actual)))))))
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
                :error (text :error)
                :file (text :file)
                :line line}))))

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
    (= :failed names) {:op "retest" :session session}
    copy {:op "eval" :session copy :code (format run-tests-code (pr-str (vec names)))}
    :else {:op "test-var-query"
           :session session
           :var-query (cond
                        (some qualified? names) {:exactly (vec names)}
                        (seq names) {:ns-query {:exactly (vec names)}}
                        ;; bencode has no booleans.
                        :else {:ns-query {:project? "true" :load-project-ns? "true"}})}))

(defn run-tests
  "Runs tests in session: those in the namespaces names names, or the tests
  it names, as ns/name, every test in the project when it's empty and the
  ones that didn't pass last time when it's :failed. Returns what
  evaluation/evaluate does, whose opts it takes, and, when the tests ran to
  the end, the :report: the :summary, counting the :ns, the tests as :var,
  the assertions as :test and how many :pass, :fail and :error, the
  :failures, maps with their :type (fail or error), :ns, :var, :context,
  :message, :expected, :actual or :error, :file and :line, and the
  :elapsed-ms. Its :status is failed when a test didn't pass."
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
    (when (and (not failed?) (seq names))
      (when-let [unknown (seq (unknown-tests client names {:timeout-ms (some-> timeout (* 1000) long)}))]
        (throw (ex-info (str "No test called " (str/join ", " unknown)) {:type ::failed}))))
    (let [;; The eval ones run in a copy of the session, so its *1 stays put.
          copy (when-not ops? (nrepl/clone-session client session))
          report (atom nil)
          on-response (:on-response opts (fn [_]))]
      (try
        (let [{:keys [status] :as result}
              (-> (evaluation/evaluate
                   client
                   (test-msg session copy names)
                   (-> opts
                       ;; It would cut the report short, which is the eval's value.
                       (cond-> copy (dissoc :max-output))
                       (assoc :on-response (fn [response]
                                             (cond
                                               (:summary response) (reset! report (op-report response))
                                               (and copy (:value response)) (reset! report (eval-report (:value response))))
                                             (on-response (cond-> response copy (dissoc :value)))))))
                  (dissoc :values :ns))
              {{:keys [fail error]} :summary :as report} @report]
          (cond
            ;; Only a run that ended by itself, since clojure.test reports
            ;; an interrupted test as an error and carries on.
            (not= "ok" status) result
            report (cond-> (assoc result :report (update report :failures #(locate client session %)))
                     (pos? (+ fail error)) (assoc :status "failed"))
            :else (throw (ex-info "The server didn't run the tests" {:type ::failed}))))
        (finally
          (when copy
            ;; There's nothing to close when the connection is gone.
            (try (nrepl/close-session client copy) (catch Exception _ nil))))))))
