(ns neorepl.sessions-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.nrepl :as nrepl]
            [neorepl.sessions :as sessions]
            [neorepl.test-server :as test-server :refer [*port* call-with-temp-dir spit-file with-client]])
  (:import (java.io StringWriter)))

(use-fixtures :once (test-server/fixture))

(deftest ensure!-test
  (call-with-temp-dir
   (fn [dir]
     (let [file (io/file dir "cache/sessions.edn")
           eval-in #(keep :value (nrepl/request %1 {:op "eval" :code %3 :session %2}))
           session (let [client (nrepl/connect {:port *port*})]
                     (try
                       (let [session (sessions/ensure! file client "agent")]
                         (eval-in client session "(def x 41)")
                         session)
                       (finally
                         (nrepl/close client))))
           client (nrepl/connect {:port *port*})]
       (try
         (testing "the session gets cached under the server's address"
           (is (= {(:address client) {"agent" session}} (edn/read-string (slurp file)))))
         (testing "the same name gets the same session from another connection"
           (is (= session (sessions/ensure! file client "agent")))
           (is (= ["42"] (eval-in client session "(inc x)"))))
         (testing "other names get other sessions"
           (let [other (sessions/ensure! file client "other")]
             (is (not= session other))
             (is (= other (sessions/ensure! file client "other")))
             (is (= {"agent" session "other" other} (sessions/named file client)))
             (testing "until they're closed"
               (is (= other (sessions/close! file client "other")))
               (is (= {"agent" session} (sessions/named file client)))
               (is (nil? (sessions/close! file client "other"))))))
         (testing "interrupting by name"
           (is (= :idle (sessions/interrupt file client "agent")))
           (is (= :unknown-session (sessions/interrupt file client "nope"))))
         (testing "a session the server lost gets replaced"
           (nrepl/close-session client session)
           (is (= :unknown-session (sessions/interrupt file client "agent")))
           (is (= {} (sessions/named file client)))
           (testing "unless the server can't tell us"
             (let [no-ls (update client :ops disj "ls-sessions")]
               (is (= session (sessions/ensure! file no-ls "agent")))
               (testing "until it gets asked to close it"
                 (is (nil? (sessions/close! file no-ls "agent")))
                 (is (not (contains? (get (edn/read-string (slurp file)) (:address client)) "agent"))))))
           (let [fresh (sessions/ensure! file client "agent")]
             (is (not= session fresh))
             (is (= fresh (sessions/ensure! file client "agent")))))
         (testing "saying a session the server lost is gone, and replaced"
           (let [lost (sessions/ensure! file client "lost")
                 replaced (atom 0)]
             (nrepl/close-session client lost)
             (is (= "Session lost is gone: the server has restarted since, or closed it"
                    (sessions/missing-message file client "lost")))
             (is (= "No session called nope" (sessions/missing-message file client "nope")))
             (is (not= lost (sessions/ensure! file client "lost" #(swap! replaced inc))))
             (is (= 1 @replaced))
             (testing "only then, not for one that's there or a new name"
               (sessions/ensure! file client "lost" #(swap! replaced inc))
               (sessions/ensure! file client "brand-new" #(swap! replaced inc))
               (is (= 1 @replaced)))))
         (testing "a broken cache file is as good as none"
           (spit file "{:oops")
           (let [fresh (sessions/ensure! file client "agent")]
             (is (= fresh (sessions/ensure! file client "agent")))))
         (testing "a cache that can't be written costs a warning, not the session"
           (spit-file dir "blocker" "")
           (let [err (StringWriter.)
                 session (binding [*err* err]
                           (sessions/ensure! (io/file dir "blocker/sessions.edn") client "agent"))]
             (is (= ["1"] (eval-in client session "1")))
             (is (str/includes? (str err) "Couldn't save the session"))))
         (finally
           (nrepl/close client)))))))

(deftest ensure!-at-the-same-time-test
  (call-with-temp-dir
   (fn [dir]
     (let [file (io/file dir "sessions.edn")
           names (map #(str "parallel-" %) (range 10))]
       (with-client [client {:port *port*}]
         (try
           (testing "sessions asked for at the same time all get cached, as parallel CLI calls ask for them"
             (let [sessions (doall (pmap #(sessions/ensure! file client %) names))]
               (is (= (zipmap names sessions) (sessions/named file client)))))
           (testing "a new name asked for at the same time gets one session"
             (is (= 1 (count (set (pmap (fn [_] (sessions/ensure! file client "same")) (range 5)))))))
           (finally
             (run! #(sessions/close! file client %) (cons "same" names)))))))))

(deftest lock-test
  (testing "the cache waits for another process that has it locked"
    (call-with-temp-dir
     (fn [dir]
       (let [file (io/file dir "sessions.edn")
             ;; The lock file as an argument, as Windows command lines mangle
             ;; quotes.
             holder (.start (ProcessBuilder.
                             ^java.util.List
                             ["bb" "-e" (str "(with-open [c (java.nio.channels.FileChannel/open"
                                             " (.toPath (java.io.File. (first *command-line-args*)))"
                                             " (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE"
                                             " java.nio.file.StandardOpenOption/WRITE]))]"
                                             " (.lock c) (println :locked) (flush) (Thread/sleep 400))")
                              (str file ".lock")]))]
         (try
           (is (= ":locked" (.readLine (io/reader (.getInputStream holder)))))
           (with-client [client {:port *port*}]
             (let [start (System/currentTimeMillis)
                   session (sessions/ensure! file client "waited")]
               (is (<= 200 (- (System/currentTimeMillis) start)))
               (is (= {"waited" session} (sessions/named file client)))
               (sessions/close! file client "waited")))
           (finally
             (.destroy holder))))))))
