(ns neorepl.discovery-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.discovery :as discovery]
            [neorepl.nrepl :as nrepl]
            [neorepl.test-server :as test-server :refer [*port* call-with-temp-dir spit-file]])
  (:import (java.net InetAddress ServerSocket)))

(use-fixtures :once (test-server/fixture))

(defn- canonical [dir path]
  (str (.getCanonicalFile (io/file dir path))))

(deftest find-ports-test
  (call-with-temp-dir
   (fn [dir]
     (spit-file dir ".nrepl-port" "1111")
     (spit-file dir "app/.nrepl-port" "2222\n")
     (spit-file dir "app/.shadow-cljs/nrepl.port" "3333")
     (spit-file dir "app/src/.nrepl-port" "not a port")
     (testing "nearest directory first, .nrepl-port before shadow-cljs's"
       (is (= [{:file (canonical dir "app/.nrepl-port") :port 2222 :dir (canonical dir "app")}
               {:file (canonical dir "app/.shadow-cljs/nrepl.port") :port 3333 :dir (canonical dir "app")}
               {:file (canonical dir ".nrepl-port") :port 1111 :dir (canonical dir ".")}]
              (take 3 (discovery/find-ports (io/file dir "app/src")))))))))

(deftest connect-test
  (call-with-temp-dir
   (fn [dir]
     (testing "no port files anywhere up the tree"
       ;; Unless a parent of the temp dir happens to have one.
       (when (empty? (discovery/find-ports dir))
         (let [e (is (thrown? clojure.lang.ExceptionInfo (discovery/connect dir)))]
           (is (= ::nrepl/no-server (:type (ex-data e))))
           (is (re-find #"No nREPL port file" (ex-message e))))
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No nREPL port file" (discovery/probe dir)))))
     (testing "stale port files are skipped"
       (spit-file dir ".nrepl-port" (str *port*))
       (spit-file dir "app/.nrepl-port" (str (test-server/free-port)))
       (let [client (discovery/connect (io/file dir "app"))]
         (try
           (is (= (str "127.0.0.1:" *port*) (:address client)))
           (is (= (canonical dir ".") (:project-dir client)))
           (finally
             (nrepl/close client)))))
     (testing "probing tells the live from the stale"
       (let [[stale live] (discovery/probe (io/file dir "app"))]
         (is (= {:file (canonical dir "app/.nrepl-port") :live false} (dissoc stale :port :dir)))
         (is (= {:file (canonical dir ".nrepl-port") :port *port* :live true} (dissoc live :versions :dir)))
         (is (string? (:nrepl (:versions live))))))
     (testing "or only some port files"
       (spit-file dir "app/.shadow-cljs/nrepl.port" (str (test-server/free-port)))
       (let [e (is (thrown? clojure.lang.ExceptionInfo
                            (discovery/connect (io/file dir "app") {:port-files [".shadow-cljs/nrepl.port"]})))]
         (is (re-find #"No nREPL server answering at the ports in .*app[/\\]\.shadow-cljs[/\\]nrepl\.port$" (ex-message e)))))
     (testing "or the parents up to a directory"
       (is (= [(canonical dir "app/.nrepl-port")]
              (map :file (discovery/find-ports (io/file dir "app") {:until (canonical dir "app") :port-files [".nrepl-port"]}))))
       (let [e (is (thrown? clojure.lang.ExceptionInfo
                            (discovery/connect (io/file dir "app/src") {:until (canonical dir "app") :port-files [".nrepl-port"]})))]
         (is (re-find #"No nREPL server answering at the ports in .*app[/\\]\.nrepl-port$" (ex-message e)))))
     (testing "only stale port files"
       (spit-file dir ".nrepl-port" (str (test-server/free-port)))
       (let [e (is (thrown? clojure.lang.ExceptionInfo (discovery/connect (io/file dir "app"))))]
         (is (= ::nrepl/no-server (:type (ex-data e))))
         (is (re-find #"No nREPL server answering at the ports in .*app" (ex-message e))))))))

(deftest not-nrepl-test
  (testing "a port file for something that isn't nREPL, which never answers, gets given up on soon"
    (call-with-temp-dir
     (fn [dir]
       (with-open [listener (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
         (spit-file dir ".nrepl-port" (str (.getLocalPort listener)))
         (let [start (System/nanoTime)
               [port-file] (discovery/probe dir)]
           (is (= {:file (canonical dir ".nrepl-port") :live false} (select-keys port-file [:file :live])))
           (is (< (- (System/nanoTime) start) 6e9))))))))
