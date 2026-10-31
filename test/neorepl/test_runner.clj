(ns neorepl.test-runner
  "Runs the tests the same way on the JVM and on babashka. Without arguments it
  runs every namespace under test/ ending in -test, or under the directory
  `--dir DIR` names; otherwise just the namespaces given."
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

(defn -main [& args]
  (let [[dir namespaces] (if (= "--dir" (first args)) [(second args) (nnext args)] ["test" args])
        namespaces (if (seq namespaces) (map symbol namespaces) (test-namespaces dir))]
    (apply require namespaces)
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
