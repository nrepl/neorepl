(ns neorepl.test-runner
  "Runs the tests the same way on the JVM and on babashka. Without arguments it
  runs every namespace under test/ ending in -test, or under the directory
  `--dir DIR` names; otherwise just the namespaces given. A test that runs
  for five minutes is taken for hung."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn- test-namespaces [dir]
  (->> (file-seq (io/file dir))
       (map #(str/replace (str %) "\\" "/"))
       (filter #(str/ends-with? % "_test.clj"))
       (map #(-> %
                 (subs (inc (count dir)))
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(def ^:private hang-limit
  "How long a test can run, in milliseconds, before the runner takes it for
  hung."
  (* 5 60 1000))

(defn- print-threads []
  (doseq [[^Thread thread frames] (Thread/getAllStackTraces)]
    (println (str "\"" (.getName thread) "\" " (.getState thread)))
    (doseq [frame frames]
      (println "    at" (str frame)))
    (println)))

(defn- watch-for-hangs
  "Once a test has run for longer than hang-limit, prints which and the
  stack of every thread, and exits, rather than leave CI to cancel the job
  without a word on where it was. The tests running are the ones
  clojure.test began and hasn't ended, as tests can run tests."
  []
  (let [running (atom {})]
    (defmethod test/report :begin-test-var [{:keys [var]}]
      (swap! running assoc var (System/currentTimeMillis)))
    (defmethod test/report :end-test-var [{:keys [var]}]
      (swap! running dissoc var))
    (doto (Thread. (fn []
                     (Thread/sleep 5000)
                     (let [[var start] (first (sort-by val @running))]
                       (if (and start (< hang-limit (- (System/currentTimeMillis) start)))
                         (do (println (str "\n" (symbol var) " has run for " (quot hang-limit 60000)
                                           " minutes, so it's taken for hung. The threads:\n"))
                             (print-threads)
                             (flush)
                             (System/exit 1))
                         (recur)))))
      (.setDaemon true)
      (.start))))

(defn -main [& args]
  (let [[dir namespaces] (if (= "--dir" (first args)) [(second args) (nnext args)] ["test" args])
        namespaces (if (seq namespaces) (map symbol namespaces) (test-namespaces dir))]
    (watch-for-hangs)
    (apply require namespaces)
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
