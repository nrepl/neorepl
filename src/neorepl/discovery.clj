(ns neorepl.discovery
  "Finds nREPL servers through the port files they write into the directory
  they were started in."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.nrepl :as nrepl])
  (:import (java.io File)))

(def port-files
  "Where servers write their port, relative to the project directory: nREPL,
  Leiningen, babashka and friends use the first, shadow-cljs the second."
  [".nrepl-port" ".shadow-cljs/nrepl.port"])

(defn- read-port [^File f]
  (when (.isFile f)
    (try
      (Long/parseLong (str/trim (slurp f)))
      ;; Unreadable, or not a number: either way there's no port in it.
      (catch Exception _ nil))))

(defn dirs
  "dir and its parents, nearest first, the way projects nest."
  [dir]
  (take-while some? (iterate #(.getParentFile ^File %) (.getCanonicalFile (io/file dir)))))

(defn find-ports
  "The port files in dir and its parents, nearest first, as maps with the
  :file, the :port in it and the project :dir it's for. Stale files
  included. opts can limit them to some :port-files, the names in
  port-files."
  ([dir] (find-ports dir {}))
  ([dir {names :port-files :or {names port-files}}]
   (for [d (dirs dir)
         name names
         :let [f (io/file d name)
               port (read-port f)]
         :when port]
     {:file (str f) :port port :dir (str d)})))

(defn- try-connect
  "A client for the server on port, or nil when nothing answers there."
  [port]
  (try
    ;; A server describes itself at once, so a port file pointing at
    ;; something else, which never answers, shouldn't take long to give up
    ;; on.
    (nrepl/connect {:port port :timeout-ms 3000})
    (catch clojure.lang.ExceptionInfo e
      (when-not (= ::nrepl/no-server (:type (ex-data e)))
        (throw e)))))

(defn- no-server [dir found {names :port-files :or {names port-files}}]
  (ex-info (if (seq found)
             (str "No nREPL server answering at the ports in "
                  (str/join ", " (map :file found)))
             (str "No nREPL port file (" (str/join ", " names) ") in "
                  (.getCanonicalPath (io/file dir)) " or its parents"))
           {:type ::nrepl/no-server :ports found}))

(defn connect
  "Connects to the nearest server that answers, going by the port files in
  dir and its parents, as find-ports finds them with opts. The client gets
  the :project-dir of the port file."
  ([dir] (connect dir {}))
  ([dir opts]
   (let [found (find-ports dir opts)]
     (or (some (fn [{:keys [port dir]}]
                 (some-> (try-connect port) (assoc :project-dir dir)))
               found)
         (throw (no-server dir found opts))))))

(defn probe
  "The port files in dir and its parents, as find-ports finds them with
  opts, plus whether a server is :live on their port and, if so, its
  :versions as nrepl/connect has them. Throws when there are no port files
  at all."
  ([dir] (probe dir {}))
  ([dir opts]
   (let [found (find-ports dir opts)]
     (when (empty? found)
       (throw (no-server dir found opts)))
     ;; All at once, since something other than nREPL on a port can take a
     ;; while to give up on.
     (->> found
          (mapv (fn [{:keys [port] :as port-file}]
                  (future
                    (if-let [client (try-connect port)]
                      (try
                        (assoc port-file :live true :versions (:versions client))
                        (finally
                          (nrepl/close client)))
                      (assoc port-file :live false)))))
          (mapv deref)))))
