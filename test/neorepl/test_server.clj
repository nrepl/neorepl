(ns neorepl.test-server
  "An nREPL server and temporary directories for the tests that need them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.repl]
            [clojure.string :as str]
            [neorepl.main :as main]
            [neorepl.nrepl :as nrepl]
            [nrepl.middleware :as middleware]
            [nrepl.middleware.io :as nrepl-io]
            [nrepl.middleware.print :as print]
            [nrepl.server :as server]
            [nrepl.transport :as transport])
  (:import (java.io File StringWriter)
           (java.net ServerSocket)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *port* nil)

(def ^:private late-reads
  "The evaluations of (late-read) and (stubborn-read) that are running, by
  message id: their :session, whether they're :stubborn and the :answer
  they wait for, a line or :interrupted."
  (atom {}))

(defn- late-read
  "Answers msg, an eval, the way wrap-stuck says (late-read) and
  (stubborn-read) do."
  [{:keys [id session] :as msg} stubborn?]
  (let [answer (promise)]
    (swap! late-reads assoc id {:session session :stubborn? stubborn? :answer answer})
    (future
      (Thread/sleep 300)
      (transport/respond-to msg :status "need-input")
      (let [line (deref answer 5000 :interrupted)]
        (swap! late-reads dissoc id)
        (if (= :interrupted line)
          (transport/respond-to msg :status ["interrupted" "done"])
          (do (transport/respond-to msg :ns "user" :value (pr-str line))
              (transport/respond-to msg :status "done")))))))

(defn wrap-stuck
  "Never answers an evaluation of (stuck), the way shadow-cljs doesn't one
  that keeps the JavaScript runtime busy, and answers one of (slow) after a
  second. nREPL can't interrupt either, since they don't run in the
  session. One of (hang-up) closes the connection. One of (late-read) reads
  a line from *in*, but only says so after 300ms, as if the server had been
  slow to say it, and ends once interrupted. One of (stubborn-read) does
  the same, but takes 500ms to say it can't be interrupted. One of
  (nbb-throw) fails the way nbb's server says code threw, with :ex but no
  eval-error."
  [handler]
  (fn [{:keys [op code session interrupt-id stdin] :as msg}]
    (let [interrupted (get @late-reads interrupt-id)
          reading (some #(when (= session (:session %)) %) (vals @late-reads))]
      (cond
        (and (= "interrupt" op) interrupted)
        (if (:stubborn? interrupted)
          (future
            (Thread/sleep 500)
            (transport/respond-to msg :status ["session-idle" "done"]))
          (do (deliver (:answer interrupted) :interrupted)
              (transport/respond-to msg :status "done")))
        (and (= "stdin" op) reading)
        (do (deliver (:answer reading) (str/trim-newline stdin))
            (transport/respond-to msg :status "done"))
        (not= "eval" op) (handler msg)
        (= "(stuck)" code) nil
        (= "(hang-up)" code) (.close ^java.io.Closeable (:transport msg))
        (= "(slow)" code) (future
                            (Thread/sleep 1000)
                            (transport/respond-to msg :ns "user" :value ":slow")
                            (transport/respond-to msg :status "done"))
        (= "(late-read)" code) (late-read msg false)
        (= "(stubborn-read)" code) (late-read msg true)
        (= "(nbb-throw)" code) (do (transport/respond-to msg :err "bad\n")
                                   (transport/respond-to msg :ex "#error {:message \"bad\"}")
                                   (transport/respond-to msg :status "done"))
        :else (handler msg)))))

(middleware/set-descriptor! #'wrap-stuck {:expects #{"eval" "interrupt" "stdin"}})

(def ^:private inspected
  "What wrap-fake-inspector inspects, per session: the values it went
  through, the last one being the one on show."
  (atom {}))

(def page-sizes
  "How many items of a collection each session's inspector shows a page."
  (atom {}))

(defn- inspector-view
  "How cider-nrepl's inspector would show value, roughly, with page-size
  items of a collection, the first page, and the values it marks, by
  index."
  [value page-size]
  (let [marked (atom [])
        mark #(list :value (pr-str (peek (swap! marked conj %))) (dec (count @marked)))
        class-name (mark (symbol (.getName (class value))))
        rows (cond
               (map? value) (mapv (fn [[k v]] ["  " (mark k) " = " (mark v)]) value)
               (coll? value) (vec (map-indexed #(vector "  " (str %1 ". ") (mark %2)) (take page-size value)))
               :else [["Value: " (mark value)]])
        pages (when (coll? value) (quot (+ (count value) (dec page-size)) page-size))
        view (concat ["Class: " class-name '(:newline)]
                     (when (coll? value) ["Count: " (str (count value)) '(:newline)])
                     ['(:newline) "--- Contents:" '(:newline)]
                     (mapcat #(concat % ['(:newline)]) rows)
                     (when (and pages (< 1 pages))
                       ['(:newline) "--- Page Info:" '(:newline)
                        "  " (format "Page size: %d, showing page: 1 of %d" page-size pages) '(:newline)]))]
    [(pr-str view) @marked]))

(defn- view-in
  "What's on show in session's inspector, as inspector-view has it."
  [session]
  (inspector-view (peek (get @inspected session)) (get @page-sizes session 32)))

(defn- show
  "Responds to msg with what's on show in its session's inspector."
  [{:keys [session] :as msg}]
  (transport/respond-to msg :status "done" :value (first (view-in session))))

(def ^:private inspect-ops
  #{"inspect-push" "inspect-pop" "inspect-refresh" "inspect-next-page" "inspect-prev-page"
    "inspect-toggle-view-mode" "inspect-toggle-pretty-print" "inspect-def-current-value"})

(defn wrap-fake-inspector
  "Inspects values the way cider-nrepl does, roughly, for evaluations with
  :inspect and the inspect ops neorepl sends, saying inspect-eval-error
  rather than eval-error, as it does, and never answering an inspect-push
  to an index it doesn't have. Values are data the reader can take back
  from how they're printed."
  [handler]
  (fn [{:keys [op session inspect idx transport page-size ns var-name] :as msg}]
    (when (and page-size (#{"eval" "inspect-refresh"} op))
      (swap! page-sizes assoc session page-size))
    (case op
      "eval" (if inspect
               (handler (assoc msg :transport
                               (reify transport/Transport
                                 (recv [_] (transport/recv transport))
                                 (recv [_ timeout] (transport/recv transport timeout))
                                 (send [this {:keys [value status] :as response}]
                                   (transport/send
                                    transport
                                    (cond
                                      value (let [value (edn/read-string value)]
                                              (swap! inspected assoc session [value])
                                              (assoc response :value (first (view-in session))))
                                      ;; cider-nrepl says eval-error this way.
                                      status (assoc response :status (map #(if (= "eval-error" (name %)) "inspect-eval-error" %)
                                                                          status))
                                      :else response))
                                   this))))
               (handler msg))
      ;; An index it doesn't have gets no answer, as from a stuck server.
      "inspect-push" (when-some [value (get (second (view-in session)) idx)]
                       (swap! inspected update session conj value)
                       (show msg))
      "inspect-pop" (do (swap! inspected update session #(cond-> % (< 1 (count %)) pop))
                        (show msg))
      "inspect-def-current-value" (do (intern (symbol ns) (symbol var-name) (peek (get @inspected session)))
                                      (show msg))
      (if (inspect-ops op)
        ;; The rest show what's on show.
        (show msg)
        (handler msg)))))

(middleware/set-descriptor! #'wrap-fake-inspector
                            {:expects #{"eval" #'print/wrap-print}
                             :handles (zipmap inspect-ops (repeat {}))})

(def ^:private traced
  "The vars wrap-fake-tracer traces, and what they held before."
  (atom {}))

(def ^:private traced-nses (atom #{}))

(def ^:private trace-subscriptions
  "The trace-subscribe messages wrap-fake-tracer sends events for, by id."
  (atom {}))

(def refuse-trace-subscriptions?
  "Whether wrap-fake-tracer answers trace-subscribe with an error."
  (atom false))

(def trace-subscriptions-done-at-end?
  "Whether wrap-fake-tracer sends a trace-subscribe's done when the
  subscription ends, as cider-nrepl should, rather than with its answer, as it
  does."
  (atom false))

(defn- traced-call
  "Calls f, the var called name traces, with args, saying so the way
  cider-nrepl does: printing the call and its value, unless there are
  subscriptions, which get events instead. Nested calls don't get indented."
  [name f args]
  (let [subscriptions (vals @trace-subscriptions)
        tell (fn [event]
               (doseq [msg subscriptions]
                 (transport/respond-to msg "cider/trace-event" (assoc event :name name :depth 0)
                                       :status "cider/trace-event")))]
    (if (seq subscriptions)
      (tell {:phase "call" :args (mapv pr-str args)})
      (println (str "(" (str/join " " (cons name (map pr-str args))) ")")))
    (let [value (apply f args)]
      (if (seq subscriptions)
        (tell {:phase "return" :value (pr-str value)})
        (println (str "└─→ " (pr-str value))))
      value)))

(defn- toggle-trace!
  "Traces v, or stops tracing it, and returns which: traced or untraced."
  [v]
  (if-let [f (get @traced v)]
    (do (alter-var-root v (constantly f))
        (swap! traced dissoc v)
        "untraced")
    (let [name (str (symbol v))]
      (swap! traced assoc v @v)
      (alter-var-root v (fn [f] (fn [& args] (traced-call name f args))))
      "traced")))

(defn- traceable? [v]
  (and (var? v) (fn? @v) (not (:macro (meta v)))))

(defn wrap-fake-tracer
  "Traces functions the way cider-nrepl does, roughly, with its trace ops,
  and sends trace events after the trace-subscribe's done, as it does."
  [handler]
  (fn [{:keys [op ns sym subscription] :as msg}]
    (case op
      "toggle-trace-var"
      (let [v (try (ns-resolve (symbol ns) (symbol sym)) (catch Exception e e))]
        (cond
          ;; As when ns isn't there.
          (instance? Exception v) (transport/respond-to msg :ex (str (class v)) :err (str v "\n")
                                                        :status ["done" "toggle-trace-error"])
          (not (var? v)) (transport/respond-to msg :var-status "not-found" :status ["done" "toggle-trace-error"])
          (not (traceable? v)) (transport/respond-to msg :var-name (str v) :var-status "not-traceable" :status "done")
          :else (transport/respond-to msg :var-name (str v) :var-status (toggle-trace! v) :status "done")))
      "toggle-trace-ns"
      (if-let [the-ns (find-ns (symbol ns))]
        (let [on? (not (contains? @traced-nses the-ns))]
          (swap! traced-nses (if on? conj disj) the-ns)
          (doseq [v (filter traceable? (vals (ns-interns the-ns)))
                  :when (= on? (not (contains? @traced v)))]
            (toggle-trace! v))
          (transport/respond-to msg :ns-status (if on? "traced" "untraced") :status "done"))
        (transport/respond-to msg :ns-status "not-found" :status "done"))
      "cider/list-traced"
      (transport/respond-to msg :traced-vars (mapv str (keys @traced)) :traced-nses (mapv str @traced-nses)
                            :status "done")
      "cider/untrace-all"
      (let [n (count @traced)]
        (run! toggle-trace! (keys @traced))
        (reset! traced-nses #{})
        (transport/respond-to msg :untraced-count n :status "done"))
      "cider/trace-subscribe"
      (if @refuse-trace-subscriptions?
        (transport/respond-to msg :status ["done" "trace-subscribe-error"])
        (let [id (str (java.util.UUID/randomUUID))]
          (swap! trace-subscriptions assoc id msg)
          (if @trace-subscriptions-done-at-end?
            (transport/respond-to msg "cider/trace-subscribe" id)
            (transport/respond-to msg "cider/trace-subscribe" id :status "done"))))
      "cider/trace-unsubscribe"
      (let [[old] (swap-vals! trace-subscriptions dissoc subscription)]
        (when-let [subscribed (and @trace-subscriptions-done-at-end? (get old subscription))]
          (transport/respond-to subscribed :status "done"))
        (transport/respond-to msg "cider/trace-unsubscribe" subscription :status "done"))
      (handler msg))))

(def trace-ops
  #{"toggle-trace-var" "toggle-trace-ns" "cider/list-traced" "cider/untrace-all"
    "cider/trace-subscribe" "cider/trace-unsubscribe"})

(middleware/set-descriptor! #'wrap-fake-tracer {:handles (zipmap trace-ops (repeat {}))})

(defn trace-subscribers
  "How many trace subscriptions wrap-fake-tracer has."
  []
  (count @trace-subscriptions))

(def ^:private tapped-values
  "The values tapped while wrap-fake-taps had a subscription, in order."
  (atom []))

(def ^:private tap-subscriptions
  "The add-tap functions of wrap-fake-taps' subscriptions, by id."
  (atom {}))

(defn tap-subscribers
  "How many tap subscriptions wrap-fake-taps has."
  []
  (count @tap-subscriptions))

(def ^:private tap-ops
  #{"cider/tap-subscribe" "cider/tap-unsubscribe" "cider/tap-inspect"})

(defn wrap-fake-taps
  "cider-nrepl's tap ops, roughly: a subscription gets a summary of each
  value tapped in this process, and tap-inspect puts one on show in the
  session's inspector."
  [handler]
  (fn [{:keys [op subscription idx] :as msg}]
    (case op
      "cider/tap-subscribe"
      (let [id (str (java.util.UUID/randomUUID))
            f (fn [value]
                (let [idx (dec (count (swap! tapped-values conj value)))]
                  (transport/respond-to msg "cider/tap-value" {:summary (pr-str value)
                                                               :type (if (nil? value) "nil" (.getName (class value)))
                                                               :idx idx}
                                        :status "cider/tap-value")))]
        (swap! tap-subscriptions assoc id f)
        (add-tap f)
        (transport/respond-to msg "cider/tap-subscribe" id :status "done"))
      "cider/tap-unsubscribe"
      (do (some-> (get @tap-subscriptions subscription) remove-tap)
          (swap! tap-subscriptions dissoc subscription)
          (transport/respond-to msg "cider/tap-unsubscribe" subscription :status "done"))
      "cider/tap-inspect"
      (if-some [value (get @tapped-values (Long/parseLong idx))]
        (do (swap! inspected assoc (:session msg) [value])
            (show msg))
        (transport/respond-to msg :status ["done" "tap-value-not-retained"]))
      (handler msg))))

(middleware/set-descriptor! #'wrap-fake-taps {:handles (zipmap tap-ops (repeat {}))})

(def ^:private profiled
  "The vars wrap-fake-profiler profiles, and what they held before."
  (atom {}))

(def ^:private profiled-nses (atom #{}))

(def ^:private profiled-calls
  "How many times each var wrap-fake-profiler profiles got called."
  (atom {}))

(defn- toggle-profile!
  "Profiles v, or stops profiling it, and returns which: profiled or
  unprofiled."
  [v]
  (if-let [f (get @profiled v)]
    (do (alter-var-root v (constantly f))
        (swap! profiled dissoc v)
        "unprofiled")
    (do (swap! profiled assoc v @v)
        (alter-var-root v (fn [f]
                            (fn [& args]
                              (swap! profiled-calls update v (fnil inc 0))
                              (apply f args))))
        "profiled")))

(def ^:private profile-ops
  #{"cider/profile-toggle-var" "cider/profile-toggle-ns" "cider/profile-summary" "cider/profile-clear"})

(defn wrap-fake-profiler
  "Profiles functions the way cider-nrepl does, roughly, with its profile
  ops, counting calls rather than timing them. Its summary is a list of
  maps on show in the session's inspector, which fails when it's empty, as
  cider-nrepl 0.64.0's does."
  [handler]
  (fn [{:keys [op ns sym] :as msg}]
    (case op
      "cider/profile-toggle-var"
      (let [v (try (ns-resolve (symbol ns) (symbol sym)) (catch Exception e e))]
        (cond
          ;; As when ns isn't there.
          (instance? Exception v) (transport/respond-to msg :ex (str (class v)) :err (str v "\n")
                                                        :status ["done" "cider/profile-toggle-var-error"])
          (not (var? v)) (transport/respond-to msg :status ["done" "profile-no-such-var"])
          ;; As orchard does, which profiles maps too.
          (or (:macro (meta v)) (not (ifn? @v))) (transport/respond-to msg :status ["done" "profile-invalid-var"])
          :else (transport/respond-to msg :value (toggle-profile! v) :status "done")))
      "cider/profile-toggle-ns"
      (if-let [the-ns (find-ns (symbol ns))]
        (let [on? (not (contains? @profiled-nses the-ns))]
          (swap! profiled-nses (if on? conj disj) the-ns)
          (doseq [v (filter traceable? (vals (ns-interns the-ns)))
                  :when (= on? (not (contains? @profiled v)))]
            (toggle-profile! v))
          (transport/respond-to msg :value (if on? "profiled" "unprofiled") :status "done"))
        (transport/respond-to msg :ex "class java.lang.Exception" :err (str "java.lang.Exception: No namespace: " ns " found\n")
                              :status ["done" "cider/profile-toggle-ns-error"]))
      "cider/profile-summary"
      (if-let [calls (seq @profiled-calls)]
        (do (swap! inspected assoc (:session msg) [(vec (for [[v n] (sort-by (comp str key) calls)]
                                                          {:name (str (symbol v)) :n n}))])
            (show msg))
        (transport/respond-to msg :ex "class clojure.lang.ExceptionInfo"
                              :err "clojure.lang.ExceptionInfo: Precondition failed: (view-mode-supported? inspector mode)\n"
                              :status ["done" "cider/profile-summary-error"]))
      "cider/profile-clear"
      (do (reset! profiled-calls {})
          (transport/respond-to msg :status ["done" "profile-cleared"]))
      (handler msg))))

(middleware/set-descriptor! #'wrap-fake-profiler {:handles (zipmap profile-ops (repeat {}))})

(def reloads
  "How many times wrap-fake-reload has reloaded, which code evaluated on the
  server can read."
  (atom 0))

(def reload-answer
  "How wrap-fake-reload answers: :ok, :error, as cider-nrepl does when a
  file doesn't compile, or :clojure-only, as it does in a ClojureScript
  session."
  (atom :ok))

(defn answering-reloads
  "Calls f with wrap-fake-reload answering as answer, as reload-answer says."
  [answer f]
  (reset! reload-answer answer)
  (try
    (f)
    (finally
      (reset! reload-answer :ok))))

(defn- remake-reloaded!
  "Makes the namespace neorepl.reloaded anew, as clj-reload does the ones it
  reloads, with a var it didn't have before, named after what reloads
  holds, as reloaded-1."
  []
  (remove-ns 'neorepl.reloaded)
  (let [n (create-ns 'neorepl.reloaded)]
    (binding [*ns* n]
      (refer 'clojure.core))
    (intern n (symbol (str "reloaded-" @reloads)) true)))

(defn wrap-fake-reload
  "cider-nrepl's cider.clj-reload/reload, answering as reload-answer says,
  with what cider-nrepl 0.64.0 sends. It reloads neorepl.reloaded."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "cider.clj-reload/reload" op)
      (case @reload-answer
        :ok (do (swap! reloads inc)
                (remake-reloaded!)
                (transport/respond-to msg :progress "Reloading 1 namespaces...")
                (transport/respond-to msg :progress "Reloaded 1 namespaces in 3 ms")
                (transport/respond-to msg :status "ok")
                (transport/respond-to msg :status "done"))
        :error (do (transport/respond-to msg :progress "Reloading 1 namespaces...")
                   (transport/respond-to msg :progress "  failed to load demo.core Syntax error compiling at (demo/core.clj:5:19).")
                   (transport/respond-to msg :status "error" :error [])
                   (transport/respond-to msg :err "Syntax error compiling at (demo/core.clj:5:19).\nUnable to resolve symbol: whom in this context\n")
                   (transport/respond-to msg :status "done"))
        :clojure-only (transport/respond-to msg :status ["done" "clojure-only"]
                                            :err "The \"cider.clj-reload/reload\" op is Clojure-only and isn't available in a ClojureScript REPL.\n"))
      (handler msg))))

(middleware/set-descriptor! #'wrap-fake-reload {:handles {"cider.clj-reload/reload" {}}})

(def ^:private forwarding
  "The last forward-system-output message, which wrap-fake-system-output
  sends what print-system! prints to."
  (atom nil))

(defn print-system!
  "Has wrap-fake-system-output send text, as printed to System/out, or to
  System/err when stream is :err, to the last session that asked for it."
  [stream text]
  (some-> @forwarding (transport/respond-to stream text :source "system")))

(defn wrap-fake-system-output
  "nREPL's forward-system-output, for what print-system! prints, rather
  than for what this process does, the tests' output among it."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "forward-system-output" op)
      (do (reset! forwarding msg)
          (transport/respond-to msg :status "done"))
      (handler msg))))

(middleware/set-descriptor! #'wrap-fake-system-output
                            {:requires #{"clone"}
                             :expects #{#'nrepl-io/wrap-out}
                             :handles {"forward-system-output" {}}})

(def ^:private builtin-sources
  "babashka finding the source of its built-ins, as source-fn does for the
  servers in this process. It resolves its own dependencies for that the
  first time, which on a fresh machine is a download that can outlast the
  timeout of whichever test asks first (babashka#2220). On the JVM, the
  source is in Clojure's jar."
  (delay (clojure.repl/source-fn 'when)))

(defn fixture
  "A :once fixture that runs the tests with an nREPL server listening on
  *port*, with wrap-stuck, wrap-fake-inspector, wrap-fake-tracer,
  wrap-fake-profiler, wrap-fake-reload, wrap-fake-system-output and
  wrap-fake-taps unless opts, which go to nrepl.server/start-server, give
  it a :handler."
  [& opts]
  (fn [f]
    @builtin-sources
    (let [srv (apply server/start-server :port 0 :handler (server/default-handler #'wrap-stuck #'wrap-fake-inspector #'wrap-fake-tracer
                                                                                  #'wrap-fake-profiler #'wrap-fake-reload
                                                                                  #'wrap-fake-system-output #'wrap-fake-taps)
                     opts)]
      (try
        (binding [*port* (:port srv)]
          (f))
        (finally
          (server/stop-server srv))))))

(defmacro with-client
  "Evaluates body with sym bound to a client connected with opts."
  [[sym opts] & body]
  `(let [~sym (nrepl/connect ~opts)]
     (try ~@body (finally (nrepl/close ~sym)))))

(defn windows? []
  (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn lf
  "s with \n for the \r\n that println ends lines with on Windows."
  [s]
  (some-> s (str/replace "\r\n" "\n")))

(defn run-in
  "Runs neorepl with args, and ctx for main/run's, with stdin as standard
  input. Returns the exit :status and what went to :out and :err, their
  lines ending in \n."
  [ctx stdin & args]
  (let [err (StringWriter.)
        status (atom nil)
        out (with-out-str
              (binding [*err* err]
                (with-in-str stdin
                  (reset! status (main/run args ctx)))))]
    {:status @status :out (lf out) :err (lf (str err))}))

(defn clojure-command
  "The command that runs Clojure the way the tests run, on babashka or the
  JVM, with their classpath, to which clojure.main's arguments go."
  []
  (let [;; Absolute, since the process may run somewhere else.
        classpath (->> (str/split (System/getProperty "java.class.path") (re-pattern File/pathSeparator))
                       (map #(.getAbsolutePath (io/file %)))
                       (str/join File/pathSeparator))]
    (if (System/getProperty "babashka.version")
      ["bb" "-cp" classpath]
      [(str (io/file (System/getProperty "java.home") "bin" "java")) "-cp" classpath "clojure.main"])))

(defn free-port
  "A port nothing listens on, for the time being."
  []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn call-with-temp-dir
  "Calls f with a fresh temporary directory, deleting it afterwards."
  [f]
  (let [dir (.toFile (Files/createTempDirectory "neorepl-test" (make-array FileAttribute 0)))]
    (try
      (f dir)
      (finally
        ;; Children come after their parents in file-seq.
        (run! #(.delete ^File %) (reverse (file-seq dir)))))))

(defn spit-file
  "Writes content to path under dir, creating the directories in between, and
  returns the file."
  ^File [dir path content]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f content)
    f))
