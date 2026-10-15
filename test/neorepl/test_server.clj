(ns neorepl.test-server
  "An nREPL server and temporary directories for the tests that need them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.nrepl :as nrepl]
            [nrepl.middleware :as middleware]
            [nrepl.middleware.print :as print]
            [nrepl.server :as server]
            [nrepl.transport :as transport])
  (:import (java.io File)
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
  the same, but takes 500ms to say it can't be interrupted."
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
        :else (handler msg)))))

(middleware/set-descriptor! #'wrap-stuck {:expects #{"eval" "interrupt" "stdin"}})

(def ^:private inspected
  "What wrap-fake-inspector inspects, per session: the values it went
  through, the last one being the one on show."
  (atom {}))

(defn- inspector-view
  "How cider-nrepl's inspector would show value, roughly, and the values it
  marks, by index."
  [value]
  (let [marked (atom [])
        mark #(list :value (pr-str (peek (swap! marked conj %))) (dec (count @marked)))
        class-name (mark (symbol (.getName (class value))))
        rows (cond
               (map? value) (mapv (fn [[k v]] ["  " (mark k) " = " (mark v)]) value)
               (coll? value) (vec (map-indexed #(vector "  " (str %1 ". ") (mark %2)) value))
               :else [["Value: " (mark value)]])
        view (concat ["Class: " class-name '(:newline)]
                     (when (coll? value) ["Count: " (str (count value)) '(:newline)])
                     ['(:newline) "--- Contents:" '(:newline)]
                     (mapcat #(concat % ['(:newline)]) rows))]
    [(pr-str view) @marked]))

(defn- show
  "Responds to msg with what's on show in its session's inspector."
  [{:keys [session] :as msg}]
  (transport/respond-to msg :status "done" :value (first (inspector-view (peek (get @inspected session))))))

(def ^:private inspect-ops
  #{"inspect-push" "inspect-pop" "inspect-refresh" "inspect-next-page" "inspect-prev-page"
    "inspect-toggle-view-mode" "inspect-toggle-pretty-print"})

(defn wrap-fake-inspector
  "Inspects values the way cider-nrepl does, roughly, for evaluations with
  :inspect and the inspect ops neorepl sends, saying inspect-eval-error
  rather than eval-error, as it does, and never answering an inspect-push
  to an index it doesn't have. Values are data the reader can take back
  from how they're printed."
  [handler]
  (fn [{:keys [op session inspect idx transport] :as msg}]
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
                                              (assoc response :value (first (inspector-view value))))
                                      ;; cider-nrepl says eval-error this way.
                                      status (assoc response :status (map #(if (= "eval-error" (name %)) "inspect-eval-error" %)
                                                                          status))
                                      :else response))
                                   this))))
               (handler msg))
      ;; An index it doesn't have gets no answer, as from a stuck server.
      "inspect-push" (when-some [value (get (second (inspector-view (peek (get @inspected session)))) idx)]
                       (swap! inspected update session conj value)
                       (show msg))
      "inspect-pop" (do (swap! inspected update session #(cond-> % (< 1 (count %)) pop))
                        (show msg))
      (if (inspect-ops op)
        ;; The rest show what's on show.
        (show msg)
        (handler msg)))))

(middleware/set-descriptor! #'wrap-fake-inspector
                            {:expects #{"eval" #'print/wrap-print}
                             :handles (zipmap inspect-ops (repeat {}))})

(defn fixture
  "A :once fixture that runs the tests with an nREPL server listening on
  *port*, with wrap-stuck and wrap-fake-inspector unless opts, which go to
  nrepl.server/start-server, give it a :handler."
  [& opts]
  (fn [f]
    (let [srv (apply server/start-server :port 0 :handler (server/default-handler #'wrap-stuck #'wrap-fake-inspector) opts)]
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
