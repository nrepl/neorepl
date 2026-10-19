(ns neorepl.mcp-test
  "neorepl mcp over pipes, against the test server."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.json :as json]
            [neorepl.main :as main]
            [neorepl.mcp :as mcp]
            [neorepl.nrepl :as nrepl]
            [neorepl.test-server :as test-server :refer [*port* call-with-temp-dir spit-file]])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter PipedInputStream PipedOutputStream PrintWriter
                    StringReader StringWriter)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(use-fixtures :once (test-server/fixture))

(def ^:private ^:dynamic *dir* nil)

(use-fixtures :each (fn [f] (call-with-temp-dir #(binding [*dir* %] (f)))))

(defn- sessions-file []
  (io/file *dir* "sessions.edn"))

(defn- call-with-mcp
  "Calls (f send receive) with neorepl mcp serving the test server: send
  sends a message, receive waits for the next one back, or nil after
  timeout-ms, ten seconds without it. Then ends the input and waits for the
  server to finish."
  [f]
  (let [;; The server's threads don't get the binding.
        port *port*
        to-server (PipedOutputStream.)
        server-in (BufferedReader. (InputStreamReader. (PipedInputStream. to-server) "UTF-8"))
        server-out (PipedOutputStream.)
        from-server (BufferedReader. (InputStreamReader. (PipedInputStream. server-out) "UTF-8"))
        received (LinkedBlockingQueue.)
        reader (future
                 (doseq [line (line-seq from-server)]
                   (.put received (json/read-str line))))
        done (future (mcp/serve server-in (OutputStreamWriter. server-out "UTF-8")
                                {:version main/version :dir (str *dir*) :sessions-file (sessions-file)
                                 :timeout 10 :max-output 1000
                                 :connect #(nrepl/connect {:port (or % port)})}))
        writer (PrintWriter. (OutputStreamWriter. to-server "UTF-8") true)]
    (try
      (f #(.println writer (json/write-str (assoc % :jsonrpc "2.0")))
         (fn receive
           ([] (receive 10000))
           ([timeout-ms] (.poll received timeout-ms TimeUnit/MILLISECONDS))))
      (finally
        (.close writer)
        (deref done 10000 :timeout)
        (.close server-out)
        (deref reader 1000 nil)))))

(defn- tool-call [id name arguments]
  {:id id :method "tools/call" :params {:name name :arguments arguments}})

(defn- text [response]
  (get-in response [:result :content 0 :text]))

(deftest protocol-test
  (call-with-mcp
   (fn [send receive]
     (testing "initialize, in the version the client asks for"
       (send {:id 1 :method "initialize" :params {:protocolVersion "2025-06-18"}})
       (let [{:keys [id result]} (receive)]
         (is (= 1 id))
         (is (= "2025-06-18" (:protocolVersion result)))
         (is (= {:name "neorepl" :version main/version} (:serverInfo result)))
         (is (= {:tools {}} (:capabilities result)))))
     (testing "or the latest, for one it doesn't know"
       (send {:id 2 :method "initialize" :params {:protocolVersion "1999-01-01"}})
       (is (= "2025-11-25" (get-in (receive) [:result :protocolVersion]))))
     (testing "notifications get no answer"
       (send {:method "notifications/initialized"})
       (send {:id 3 :method "ping"})
       (is (= {:jsonrpc "2.0" :id 3 :result {}} (receive))))
     (testing "the curated tools"
       (send {:id 4 :method "tools/list"})
       (let [tools (get-in (receive) [:result :tools])]
         (is (= ["eval" "load" "doc" "source" "where" "apropos" "macroexpand" "reload" "test" "retest"
                 "pst" "interrupt" "check" "ports"]
                (map :name tools)))
         (is (every? (comp #{"object"} :type :inputSchema) tools))
         (is (= ["code"] (:required (:inputSchema (first tools)))))))
     (testing "what it can't do"
       (send {:id 5 :method "resources/list"})
       (is (= -32601 (get-in (receive) [:error :code])))
       (send (tool-call 6 "nope" {}))
       (is (= -32602 (get-in (receive) [:error :code])))
       (send (tool-call 7 "eval" {}))
       (let [{:keys [result]} (receive)]
         (is (:isError result))
         (is (= "Missing code" (text {:result result})))))))
  (testing "what isn't JSON"
    (let [out (StringWriter.)]
      (mcp/serve (BufferedReader. (StringReader. "{oops\n")) out {})
      (is (= {:jsonrpc "2.0" :id nil :error {:code -32700 :message "That's not JSON"}}
             (json/read-str (str out)))))))

(deftest eval-test
  (call-with-mcp
   (fn [send receive]
     (testing "what it printed and its values"
       (send (tool-call 1 "eval" {:code "(println :hi) (+ 1 2)"}))
       (let [{{:keys [content structuredContent isError]} :result} (receive)]
         (is (= [{:type "text" :text ":hi\nnil\n3"}] content))
         (is (= {:status "ok" :values ["nil" "3"] :out ":hi\n" :err "" :ns "user"}
                (update structuredContent :out test-server/lf)))
         (is (false? isError))))
     (testing "in the session it's in, from call to call"
       (send (tool-call 2 "eval" {:code "(* *1 2)"}))
       (is (= "6" (text (receive)))))
     (testing "an error, with what it says"
       (send (tool-call 3 "eval" {:code "(/ 1 0)"}))
       (let [{{:keys [isError structuredContent] :as result} :result} (receive)]
         (is isError)
         (is (= "eval-error" (:status structuredContent)))
         (is (str/includes? (text {:result result}) "Divide by zero"))))
     (testing "and its stack trace"
       (send (tool-call 4 "pst" {}))
       (let [response (receive)]
         (is (str/starts-with? (text response) "java.lang.ArithmeticException: Divide by zero"))
         (is (= "java.lang.ArithmeticException" (get-in response [:result :structuredContent :causes 0 :class])))))
     (testing "output cut short"
       (send (tool-call 5 "eval" {:code "(apply str (repeat 2000 \"x\"))"}))
       (let [response (receive)]
         (is (get-in response [:result :structuredContent :truncated]))
         (is (str/ends-with? (text response) "(Cut at 1000 characters)"))))
     (testing "a lookup"
       (send (tool-call 6 "doc" {:symbol "inc"}))
       (is (str/starts-with? (text (receive)) "clojure.core/inc\n([x])"))
       (send (tool-call 7 "doc" {:symbol "nope"}))
       (let [{{:keys [isError structuredContent]} :result} (receive)]
         (is (false? isError))
         (is (= "not-found" (:status structuredContent))))
       (send (tool-call 10 "apropos" {:regex "^mapcat$"}))
       (is (= "clojure.core/mapcat" (get-in (receive) [:result :structuredContent :vars 0 :name])))))))

(deftest sessions-test
  (testing "shared with the CLI"
    (with-out-str
      (is (zero? (main/run ["eval" "-p" (str *port*) "-s" "shared" "(def shared-x 41)"]
                           {:dir (str *dir*) :sessions-file (sessions-file)}))))
    (call-with-mcp
     (fn [send receive]
       ;; *1 is the session's, where def isn't.
       (send (tool-call 1 "eval" {:code "*1" :session "shared"}))
       (is (= "#'user/shared-x" (text (receive))))
       (send (tool-call 2 "eval" {:code "*1"}))
       (is (not= "#'user/shared-x" (text (receive))))))))

(def started
  "Delivered by code that neorepl evaluates on the test server, to say it's
  running."
  (atom nil))

(def interrupted
  "Delivered by code that neorepl evaluates on the test server, to say it got
  interrupted."
  (atom nil))

(deftest cancel-test
  (reset! started (promise))
  (reset! interrupted (promise))
  (call-with-mcp
   (fn [send receive]
     (send (tool-call 1 "eval" {:code (str "(try (deliver @neorepl.mcp-test/started true) (Thread/sleep 30000)"
                                           " (catch InterruptedException _ (deliver @neorepl.mcp-test/interrupted true)))")}))
     (is (deref @started 10000 false))
     (send {:method "notifications/cancelled" :params {:requestId 1}})
     (testing "interrupts the evaluation, which goes unanswered"
       (is (deref @interrupted 10000 false))
       ;; Only now: nREPL drops the evaluations queued behind one it
       ;; interrupts, and :next could be one of them.
       (send (tool-call 2 "eval" {:code ":next"}))
       (let [response (receive)]
         (is (= 2 (:id response)))
         (is (= ":next" (text response))))
       (is (nil? (receive 1000)))))))

(deftest test-test
  (call-with-mcp
   (fn [send receive]
     (send (tool-call 1 "test" {:names ["neorepl.sample-suite"]}))
     (let [{{:keys [isError structuredContent] :as result} :result} (receive)]
       (testing "what didn't pass, which isn't the tool failing"
         (is (false? isError))
         (is (= "failed" (:status structuredContent)))
         (is (= {:ns 1 :var 3 :test 3 :pass 1 :fail 1 :error 1} (:summary structuredContent)))
         (is (str/ends-with? (text {:result result}) "Ran 3 tests containing 3 assertions.\n1 failure, 1 error.")))))))

(deftest check-test
  (let [good (str (spit-file *dir* "good.clj" "(ns good)\n(defn f [] 1)\n"))
        bad (str (spit-file *dir* "bad.clj" "(ns bad)\n(defn f [] 1\n"))]
    (call-with-mcp
     (fn [send receive]
       (send (tool-call 1 "check" {:files [good bad (str *dir* "/missing.clj")]}))
       (let [{{:keys [structuredContent] :as result} :result} (receive)]
         (is (= [{:file bad :line 2 :column 1 :message "Unclosed ("}
                 {:file (str *dir* "/missing.clj") :message "No such file"}]
                (:problems structuredContent)))
         (is (str/starts-with? (text {:result result}) (str bad ":2:1: Unclosed ("))))
       (send (tool-call 2 "check" {:files [good]}))
       (is (= "Every delimiter balances" (text (receive))))
       (testing "and repairing them"
         (send (tool-call 3 "check" {:files [bad] :fix true}))
         (is (= (str bad ":2:1: Unclosed (, repaired from the indentation") (text (receive))))
         (is (= "(ns bad)\n(defn f [] 1)\n" (slurp bad))))))))
