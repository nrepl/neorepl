(ns neorepl.main-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [neorepl.discovery :as discovery]
            [neorepl.main :as main]
            [neorepl.nrepl :as nrepl]
            [neorepl.sessions :as sessions]
            [neorepl.test-server :as test-server :refer [*port* call-with-temp-dir lf spit-file with-client]])
  (:import (java.io StringWriter)
           (java.util.concurrent TimeUnit)))

(def ^:dynamic *dir*
  "A directory without port files, holding the session cache as if it was
  XDG_CACHE_HOME."
  nil)

(defn- sessions-file
  "Where neorepl keeps sessions with XDG_CACHE_HOME set to *dir*."
  []
  (io/file *dir* "neorepl" "sessions.edn"))

(use-fixtures :once
  (test-server/fixture)
  (fn [f] (call-with-temp-dir #(binding [*dir* %] (f)))))

(defn- run-in
  "Runs neorepl with args in dir, with stdin as standard input. Returns the
  exit :status and what went to :out and :err, their lines ending in \n."
  [dir stdin & args]
  (let [err (StringWriter.)
        status (atom nil)
        out (with-out-str
              (binding [*err* err]
                (with-in-str stdin
                  (reset! status (main/run args {:dir dir :sessions-file (sessions-file)})))))]
    {:status @status :out (lf out) :err (lf (str err))}))

(defn- run-command
  "Runs a neorepl command against the test server with args."
  [command & args]
  (apply run-in *dir* "" command "-p" (str *port*) args))

(defn- run
  "Runs neorepl eval against the test server with args."
  [& args]
  (apply run-command "eval" args))

(deftest text-format-test
  (testing "values on lines of their own, after any output"
    (is (= {:status 0 :out "a\nnil\n3\nb\nnil\n" :err ""}
           (run "(print \"a\") (+ 1 2) (println \"b\")"))))
  (testing "stderr output"
    (is (= {:status 0 :out "nil\n" :err "oops\n"}
           (run "(binding [*out* *err*] (println \"oops\"))"))))
  (testing "evaluation errors"
    (let [{:keys [status out err]} (run "(/ 1 0) :after")]
      (is (= 1 status))
      (is (= ":after\n" out))
      (is (str/includes? err "Divide by zero"))))
  (testing "warnings start on a line of their own"
    (is (= {:status 124 :out "nil\n" :err "oops\nneorepl: Timed out after 0.1s and interrupted the evaluation\n"}
           (run "-s" "warn-line" "--timeout" "0.1" "(binding [*out* *err*] (print \"oops\") (flush)) (Thread/sleep 5000)"))))
  (testing "and so does whatever comes after the output"
    (is (= {:status 0 :out "nil\n" :err "oops\n"}
           (run "(binding [*out* *err*] (print \"oops\") (flush))"))))
  (testing "losing the connection"
    (let [{:keys [status err]} (run "(hang-up)")]
      (is (= 3 status))
      (is (str/includes? err "Lost the connection")))))

(deftest structured-format-test
  (testing "edn"
    (let [{:keys [status out]} (run "--format" "edn" "(println \"hi\") 42")]
      (is (zero? status))
      (is (= {:status "ok" :values ["nil" "42"] :out "hi\n" :err "" :ns "user"}
             (update (edn/read-string out) :out lf)))))
  (testing "edn for an error"
    (let [{:keys [status out]} (run "--format=edn" "(/ 1 0)")
          result (edn/read-string out)]
      (is (= 1 status))
      (is (= "eval-error" (:status result)))
      (is (= "class java.lang.ArithmeticException" (:ex result)))))
  (testing "json"
    (let [{:keys [status out]} (run "--format" "json" "(+ 1 2) \"s\"")]
      (is (zero? status))
      (is (str/starts-with? out "{\"status\":\"ok\",\"values\":[\"3\",\"\\\"s\\\"\"],"))
      (is (str/ends-with? out "}\n")))))

(deftest session-test
  (testing "a session keeps its namespace between calls"
    (run "-s" "ns-test" "(in-ns 'neorepl.scratch)")
    (is (= "neorepl.scratch" (:ns (edn/read-string (:out (run "-s" "ns-test" "--format" "edn" "1"))))))
    (testing "and other sessions don't see it"
      (is (= "user" (:ns (edn/read-string (:out (run "-s" "other" "--format" "edn" "1"))))))))
  (testing "*1 carries over"
    (run "-s" "star-test" "(+ 40 2)")
    (is (= "42\n" (:out (run "-s" "star-test" "*1")))))
  (testing "a session the server lost gets replaced"
    (run "-s" "lost" "1")
    (with-client [client {:port *port*}]
      (let [lost (sessions/ensure! (sessions-file) client "lost")]
        (nrepl/close-session client lost)
        (is (= {:status 0 :out "2\n" :err ""} (run "-s" "lost" "2")))
        (is (not= lost (sessions/ensure! (sessions-file) client "lost")))))))

(deftest sessions-command-test
  (run "-s" "listed" "(in-ns 'neorepl.listed)")
  (run "-s" "listed-too" "1")
  (let [ids (with-client [client {:port *port*}]
              (sessions/named (sessions-file) client))
        listed #(set (edn/read-string (:out (run-command "sessions" "--format" "edn"))))]
    (testing "listing"
      (is (set/subset? #{{:name "listed" :id (ids "listed")} {:name "listed-too" :id (ids "listed-too")}}
                       (listed)))
      (let [{:keys [status out]} (run-command "sessions")]
        (is (zero? status))
        (is (re-find (re-pattern (str "(?m)^listed +" (ids "listed") "$")) out))))
    (testing "closing"
      (is (= {:status 0 :out "" :err ""} (run-command "sessions" "close" "listed")))
      (is (not-any? #(= "listed" (:name %)) (listed)))
      (testing "starts the name over"
        (is (= "user" (:ns (edn/read-string (:out (run "-s" "listed" "--format" "edn" "1"))))))))
    (testing "closing what isn't there"
      (is (= {:status 1 :out "" :err "neorepl: No session called nope\n"}
             (run-command "sessions" "close" "nope"))))))

(deftest timeout-test
  (testing "an evaluation running too long gets interrupted"
    (let [{:keys [status err]} (run "-s" "slow" "--timeout" "0.2" "(Thread/sleep 10000)")]
      (is (= 124 status))
      (is (str/includes? err "Timed out after 0.2s"))))
  (testing "and the session is free again"
    (is (= {:status 0 :out "1\n" :err ""}
           (run "-s" "slow" "--timeout" "5" "1"))))
  (testing "an evaluation the server can't interrupt isn't said to be interrupted"
    (let [{:keys [status err]} (run "-s" "stuck" "--timeout" "0.2" "(stuck)")]
      (is (= 124 status))
      (is (str/includes? err "Timed out after 0.2s, and interrupting the evaluation failed"))))
  (testing "timeouts show up in structured output"
    (let [{:keys [status out]} (run "-s" "slow" "--timeout" "0.2" "--format" "edn" "(Thread/sleep 10000)")]
      (is (= 124 status))
      (is (= "timeout" (:status (edn/read-string out)))))))

(deftest max-output-test
  (testing "values get cut"
    (is (= {:status 0 :out "(0 1 \n:x\n" :err "neorepl: Cut the output at 5 characters\n"}
           (run "--max-output" "5" "(range 10) :x"))))
  (testing "stdout and stderr get cut once they've had their share"
    (is (= {:status 0 :out "abcde\n:x\n" :err "12345\nneorepl: Cut the output at 5 characters\n"}
           (run "--max-output" "5" "(do (print \"abc\") (flush) (print \"defgh\") (binding [*out* *err*] (print 1234567)) :x)"))))
  (testing "surrogate pairs stay whole"
    (is (= "a\n:x\n" (:out (run "--max-output" "2" "(do (print \"a\uD83D\uDE00b\") :x)")))))
  (testing "the server stops printing at the limit, so endless values are fine"
    (is (= {:status 0 :out "(0 1 2 3 4\n" :err "neorepl: Cut the output at 10 characters\n"}
           (run "--max-output" "10" "--timeout" "10" "(range)"))))
  (testing "structured output says when something got cut"
    (let [cut (edn/read-string (:out (run "--max-output" "3" "--format" "edn" "(println 12345) :abcd")))]
      (is (= {:status "ok" :values ["nil" ":ab"] :out "123" :err "" :ns "user" :truncated true} cut)))
    (is (not (contains? (edn/read-string (:out (run "--max-output" "3" "--format" "edn" "1")))
                        :truncated)))
    (testing "but not about cuts the session asked for itself"
      (run "-s" "quota" "(set! nrepl.middleware.print/*quota* 3)")
      (is (= {:status 0 :out "(0 \n" :err ""} (run "-s" "quota" "(range 10)"))))))

(def started
  "Delivered by code that neorepl evaluates on the test server, to say it's
  running."
  (atom nil))

(deftest interrupt-test
  (testing "interrupting an evaluation, here one whose client went away"
    (reset! started (promise))
    (with-client [client {:port *port*}]
      (nrepl/send! client {:op "eval"
                           :session (sessions/ensure! (sessions-file) client "stuck")
                           ;; One form, so there's no value on its way back when
                           ;; the client goes, which could take the session's thread
                           ;; down with it.
                           :code "(do (deliver @neorepl.main-test/started true) (Thread/sleep 20000))"}
                   (fn [_]))
      (is (deref @started 10000 false)))
    (is (= {:status 0 :out "" :err ""} (run-command "interrupt" "-s" "stuck")))
    (testing "frees the session"
      (is (= {:status 0 :out "1\n" :err ""} (run "-s" "stuck" "--timeout" "5" "1")))))
  (testing "nothing to interrupt"
    (is (= {:status 1 :out "" :err "neorepl: Nothing is running in session stuck\n"}
           (run-command "interrupt" "-s" "stuck")))
    (is (= {:status "idle" :message "Nothing is running in session stuck"}
           (edn/read-string (:out (run-command "interrupt" "-s" "stuck" "--format" "edn"))))))
  (testing "no such session"
    (is (= {:status 1 :out "" :err "neorepl: No session called nope\n"}
           (run-command "interrupt" "-s" "nope")))))

(defn- start-neorepl-in
  "Runs neorepl with args in dir, in a process of its own, on the runtime
  running the tests, with the test session cache."
  ^Process [dir & args]
  (let [builder (doto (ProcessBuilder. ^java.util.List (into (test-server/clojure-command) (list* "-m" "neorepl.main" args)))
                  (.directory (io/file dir)))]
    (.put (.environment builder) "XDG_CACHE_HOME" (str *dir*))
    (.start builder)))

(defn- start-neorepl
  "Runs neorepl with args, as start-neorepl-in does, in the test's directory."
  ^Process [& args]
  (apply start-neorepl-in *dir* args))

(deftest kill-test
  ;; Windows has no SIGTERM, so destroy skips the shutdown hooks there.
  (when-not (test-server/windows?)
    (testing "killing neorepl interrupts its evaluation, freeing the session"
      (reset! started (promise))
      (let [process (start-neorepl "eval" "-p" (str *port*) "-s" "killed"
                                   "(deliver @neorepl.main-test/started true) (Thread/sleep 20000)")]
        (is (deref @started 30000 false))
        (.destroy process)
        (is (.waitFor process 10 TimeUnit/SECONDS))
        (is (= {:status 0 :out "1\n" :err ""}
               (run "-s" "killed" "--timeout" "5" "1")))))))

(deftest input-test
  (testing "code from a file"
    (let [file (spit-file *dir* "code.clj" "(+ 1 2)\n*file*")]
      (is (= {:status 0 :out (str "3\n" (pr-str (.getAbsolutePath file)) "\n") :err ""}
             (run "-f" (str file))))))
  (testing "code from stdin"
    (is (= {:status 0 :out "3\n" :err ""}
           (run-in *dir* "(+ 1 2)" "eval" "-p" (str *port*) "-"))))
  (testing "reads from *in* get a line of stdin each"
    ;; A fresh session, since servers send need-input to the connection that
    ;; created the session.
    (is (= {:status 0 :out "\"hello\"\n\"world\"\nnil\n" :err ""}
           (run-in *dir* "hello\nworld\n" "eval" "-p" (str *port*) "-s" "stdin-test"
                   "(read-line) (read-line) (read-line)"))))
  (testing "but they get end of input when the code came from stdin"
    (is (= {:status 0 :out "nil\n" :err ""}
           (run-in *dir* "(read-line)" "eval" "-p" (str *port*) "-s" "stdin-code" "-")))
    (testing "which leaves the session free"
      (is (= {:status 0 :out "1\n" :err ""}
             (run "-s" "stdin-code" "--timeout" "5" "1"))))))

(deftest load-test
  (let [file (spit-file *dir* "src/demo/core.clj"
                        "(ns demo.core)\n(println :loading)\n(defn answer [] 42)\n(answer)")]
    (testing "only the last value comes back"
      (is (= {:status 0 :out ":loading\n42\n" :err ""}
             (run-command "load" "-s" "load-test" (str file)))))
    (testing "the session stays in its namespace"
      (is (= {:status "ok" :values ["42"] :out "" :err "" :ns "user"}
             (edn/read-string (:out (run "-s" "load-test" "--format" "edn" "(demo.core/answer)")))))))
  (testing "loading stops at the first error, which points at the file"
    (let [file (spit-file *dir* "src/demo/broken.clj" "(ns demo.broken)\n(/ 1 0)\n(println :after)")
          {:keys [status out err]} (run-command "load" (str file))]
      (is (= 1 status))
      (is (= "" out))
      (is (str/includes? err "broken.clj:2"))))
  (testing "structured output"
    (let [file (spit-file *dir* "src/demo/value.clj" "(ns demo.value)\n:a\n(range 10)")]
      ;; load-file doesn't say which namespace the session's in.
      (is (= {:status "ok" :values ["(0 1 2"] :out "" :err "" :ns nil :truncated true}
             (edn/read-string (:out (run-command "load" "--format" "edn" "--max-output" "6" (str file)))))))))

(deftest repl-test
  (let [repl #(apply run-in *dir* %1 "repl" "-p" (str *port*) %&)]
    (testing "without a terminal, prompts and results go to stdout"
      (is (= {:status 0
              :out "user=> 3\nuser=> :hi\nnil\nuser=> #'user/repl-x\nuser=> 42\nuser=> \n"
              :err ""}
             (repl "(+ 1 2)\n(println :hi)\n(def repl-x\n  42)\nrepl-x\n"))))
    (testing "the prompt follows the namespace"
      (is (= "user=> nil\nrepl.scratch=> \n"
             (:out (repl "(do (in-ns 'repl.scratch) nil)\n")))))
    (testing "values get pretty printed"
      (is (re-find #"(?m)^ :b" (:out (repl "{:a (range 30) :b (range 30)}\n")))))
    (testing "values go right after the prompt, even after output that didn't end its line"
      (is (= "user=> partialuser=> 3\nuser=> \n"
             (:out (repl "(do (print \"partial\") (throw (ex-info \"boom\" {})))\n(+ 1 2)\n")))))
    (testing "reads from *in* take the next line"
      (is (= "user=> \"hello\"\nuser=> \n" (:out (repl "(read-line)\nhello\n")))))
    (testing ":repl/quit"
      (is (= "user=> \n" (:out (repl ":repl/quit\n(println :unreachable)\n")))))
    (testing "a session of its own, closed at the end"
      (with-client [client {:port *port*}]
        (let [sessions #(set (some :sessions (nrepl/request client {:op "ls-sessions"})))
              before (sessions)]
          (repl "(in-ns 'repl.elsewhere)\n")
          (is (= before (sessions)))
          (is (= "user" (:ns (edn/read-string (:out (run "--format" "edn" "1")))))))))
    (testing "or a named one"
      (repl "(in-ns 'repl.named)\n" "-s" "repl-named")
      (is (= "repl.named" (:ns (edn/read-string (:out (run "-s" "repl-named" "--format" "edn" "1"))))))
      (testing "which the prompt starts in"
        (is (= "repl.named=> \n" (:out (repl "" "-s" "repl-named")))))))
  (testing "the default command"
    (is (= "user=> 3\nuser=> \n" (:out (run-in *dir* "(+ 1 2)\n" "-p" (str *port*)))))))

(deftest jack-in-test
  (testing "without a server, the REPL starts one for the project"
    (call-with-temp-dir
     (fn [dir]
       (spit-file dir "bb.edn" "{}")
       (let [{:keys [status out err]} (run-in dir "(+ 1 2)\n" "repl")]
         (is (= 0 status))
         (is (= "user=> 3\nuser=> \n" out))
         (is (str/includes? err "neorepl: Starting an nREPL server in "))
         (is (str/includes? err "Started nREPL server"))
         ;; Windows has no SIGTERM, so the server has no say in it there.
         (when-not (test-server/windows?)
           (testing "and stops it after"
             (is (not (.exists (io/file dir ".nrepl-port"))))))))))
  (testing "nor a project to start one for"
    (call-with-temp-dir
     (fn [dir]
       (let [{:keys [status err]} (run-in dir "" "repl")]
         (is (= 3 status))
         (is (str/includes? err "nor a project.clj, deps.edn, bb.edn or shadow-cljs.edn to start one for"))))))
  (testing "or one in neorepl's own process"
    (is (= {:status 0 :out "user=> 3\nuser=> \n" :err ""}
           (run-in *dir* "(+ 1 2)\n" "repl" "--in-process"))))
  (testing "options that don't go together"
    (are [args message] (let [{:keys [status err]} (apply run-in *dir* "" "repl" args)]
                          (and (= 2 status) (str/includes? err message)))
      ["--in-process" "-p" "1234"] "--in-process doesn't go with --port"
      ["--aliases" ":dev" "-p" "1234"] "--tool and --aliases don't go with"
      ["--tool" "bb" "--in-process"] "--tool and --aliases don't go with")))

(deftest kill-jack-in-test
  ;; Windows has no SIGTERM, so destroy skips the shutdown hooks there.
  (when-not (test-server/windows?)
    (testing "killing a REPL mid-evaluation stops the server it started"
      (call-with-temp-dir
       (fn [dir]
         (spit-file dir "bb.edn" "{}")
         (let [process (start-neorepl-in dir "repl")
               out (io/reader (.getInputStream process))
               ;; Read as it comes, so the pipe can't fill up.
               err (future (slurp (.getErrorStream process)))]
           (try
             (doto (io/writer (.getOutputStream process))
               (.write "(do (println :sleeping) (Thread/sleep 20000))\n")
               (.flush))
             (is (str/ends-with? (.readLine ^java.io.BufferedReader out) ":sleeping"))
             (is (.exists (io/file dir ".nrepl-port")))
             ;; Not Process's destroy, which closes the streams on Linux.
             (.destroy (.toHandle process))
             (is (.waitFor process 10 TimeUnit/SECONDS))
             (is (not (.exists (io/file dir ".nrepl-port"))))
             (let [err (deref err 10000 "neorepl didn't exit")]
               (is (not (str/includes? err "Exception")) err))
             (finally
               (.destroyForcibly process)))))))))

(deftest server-test
  ;; Windows has no SIGTERM, so stopping neorepl there leaves the server.
  (when-not (test-server/windows?)
    (call-with-temp-dir
     (fn [dir]
       (spit-file dir "bb.edn" "{}")
       (let [process (start-neorepl-in dir "server" "--format" "edn")
             {:keys [port tool]} (edn/read-string (.readLine (io/reader (.getInputStream process))))]
         (try
           (is (= "bb" tool))
           (with-client [client {:port port}]
             (is (= ["3"] (keep :value (nrepl/request client {:op "eval" :code "(+ 1 2)"})))))
           (is (.exists (io/file dir ".nrepl-port")))
           (finally
             (.destroy process)
             (.waitFor process 10 TimeUnit/SECONDS)))
         (testing "stopping neorepl stops the server"
           (is (not (.exists (io/file dir ".nrepl-port"))))
           (is (= ::nrepl/no-server
                  (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (nrepl/connect {:port port}))))))))))))
  (testing "without a project"
    (call-with-temp-dir
     (fn [dir]
       (let [{:keys [status err]} (run-in dir "" "server")]
         (is (= 1 status))
         (is (str/includes? err "No project.clj, deps.edn, bb.edn or shadow-cljs.edn in ")))
       (testing "of the kind asked for"
         (spit-file dir "bb.edn" "{}")
         (is (str/includes? (:err (run-in dir "" "server" "--tool" "lein")) "No project.clj in "))))))
  (testing "with one running already"
    (call-with-temp-dir
     (fn [dir]
       (spit-file dir "bb.edn" "{}")
       (spit-file dir ".nrepl-port" (str *port*))
       (let [{:keys [status err]} (run-in dir "" "server")]
         (is (= 1 status))
         (is (str/includes? err (str "running already, on port " *port*)))))))
  (testing "babashka projects take no aliases"
    (call-with-temp-dir
     (fn [dir]
       (spit-file dir "bb.edn" "{}")
       (is (= 2 (:status (run-in dir "" "server" "--aliases" ":dev")))))))
  (testing "shadow-cljs takes no port or aliases, having its own"
    (call-with-temp-dir
     (fn [dir]
       (spit-file dir "shadow-cljs.edn" "{}")
       (let [{:keys [status err]} (run-in dir "" "server" "--port" "7888")]
         (is (= 2 status))
         (is (str/includes? err "--port doesn't go with shadow-cljs, which takes the port from shadow-cljs.edn")))
       (is (= 2 (:status (run-in dir "" "server" "--aliases" ":dev"))))))))

(deftest doc-test
  (testing "like clojure.repl/doc"
    (let [{:keys [status out]} (run-command "doc" "when")]
      (is (zero? status))
      (is (str/starts-with? out "clojure.core/when\n([test & body])\nMacro\n  Evaluates test."))))
  (testing "in a namespace"
    (is (str/starts-with? (:out (run-command "doc" "--ns" "clojure.core" "str")) "clojure.core/str\n")))
  (testing "structured"
    (is (= {:ns "clojure.core" :name "map"}
           (select-keys (edn/read-string (:out (run-command "doc" "--format" "edn" "map"))) [:ns :name]))))
  (testing "nothing there"
    (is (= {:status 1 :out "" :err "neorepl: Nothing called nope in user\n"}
           (run-command "doc" "nope")))))

(deftest source-test
  (is (= {:status 0
          :out "(defmacro when\n  \"Evaluates test. If logical true, evaluates body in an implicit do.\"\n  {:added \"1.0\"}\n  [test & body]\n  (list 'if test (cons 'do body)))\n"
          :err ""}
         (run-command "source" "when")))
  (is (= "not-found" (:status (edn/read-string (:out (run-command "source" "--format" "edn" "nope"))))))
  (is (= 1 (:status (run-command "source" "nope")))))

(deftest complete-test
  (is (re-find #"(?m)^mapcat\b" (:out (run-command "complete" "mapc"))))
  (is (some #(= "mapcat" (:candidate %)) (edn/read-string (:out (run-command "complete" "--format" "edn" "mapc"))))))

(deftest apropos-test
  (is (re-find #"(?m)^clojure\.core/mapcat$" (:out (run-command "apropos" "^mapc"))))
  (testing "nothing matching"
    (is (= {:status 1 :out "" :err "neorepl: Nothing matches ^nope-nope$\n"}
           (run-command "apropos" "^nope-nope$"))))
  (testing "a regex the server can't make sense of"
    (let [{:keys [status err]} (run-command "apropos" "(")]
      (is (= 1 status))
      (is (str/includes? err "The server couldn't do eval")))))

(deftest macroexpand-test
  (is (= {:status 0 :out "(if x (do y))\n" :err ""} (run-command "macroexpand" "(when x y)")))
  (is (str/includes? (:out (run-command "macroexpand" "--all" "(when-let [a 1] a)")) "let*"))
  (is (= {:expansion "(if x (do y))"}
         (edn/read-string (:out (run-command "macroexpand" "--format" "edn" "(when x y)"))))))

(deftest where-test
  (let [{:keys [status out]} (run-command "where" "map")]
    (is (zero? status))
    (is (re-find #"core\.clj:\d+" out)))
  (is (= 1 (:status (run-command "where" "nope"))))
  (testing "nowhere for what was made at a REPL"
    ;; In a session of its own: nREPL's keep the file loaded last as theirs.
    (run "-s" "where-made" "(defn made-at-the-repl [])")
    (is (= 1 (:status (run-command "where" "made-at-the-repl"))))))

(deftest reload-test
  (is (= {:status 0 :out "Reloaded clojure.set\n" :err ""} (run-command "reload" "clojure.set")))
  (let [{:keys [status err]} (run-command "reload" "no.such.ns")]
    (is (= 1 status))
    (is (str/includes? err "Could not locate no/such/ns")))
  (testing "what changed takes cider-nrepl"
    (let [{:keys [status err]} (run-command "reload")]
      (is (= 1 status))
      (is (str/includes? err "takes cider-nrepl")))))

(deftest pst-test
  (run "-s" "pst" "(throw (ex-info \"boom\" {:k 1}))")
  (let [{:keys [status out]} (run-command "pst" "-s" "pst")]
    (is (zero? status))
    (is (str/starts-with? out "clojure.lang.ExceptionInfo: boom\n  {:k 1}\n")))
  (is (= "clojure.lang.ExceptionInfo"
         (:class (first (edn/read-string (:out (run-command "pst" "-s" "pst" "--format" "edn")))))))
  (testing "no error yet"
    (run "-s" "pst-clean" "1")
    (is (= {:status 1 :out "" :err "neorepl: No error yet in session pst-clean\n"}
           (run-command "pst" "-s" "pst-clean"))))
  (testing "no such session"
    (is (= 1 (:status (run-command "pst" "-s" "pst-nope")))))
  (testing "whatever the session's print settings"
    (run "-s" "pst-short" "(set! *print-length* 2) (set! *print-level* 1)")
    (run "-s" "pst-short" "(throw (ex-info \"boom\" {:xs (range) :deep {:er 1}}))")
    (is (str/starts-with? (:out (run-command "pst" "-s" "pst-short")) "clojure.lang.ExceptionInfo: boom\n"))))

(deftest test-test
  (testing "what didn't pass, and how many did"
    (let [{:keys [status out err]} (run-command "test" "neorepl.sample-suite")]
      (is (= 1 status))
      (is (= "" err))
      (is (str/starts-with? out "passing prints\nERROR in neorepl.sample-suite/erring ("))
      (is (str/includes? out "\n\nFAIL in neorepl.sample-suite/failing ("))
      (is (str/ends-with? out "\nRan 3 tests containing 3 assertions.\n1 failure, 1 error.\n"))))
  (testing "passing"
    (is (= {:status 0 :out "passing prints\nRan 1 test containing 1 assertion.\n0 failures, 0 errors.\n" :err ""}
           (run-command "test" "neorepl.sample-suite/passing"))))
  (testing "json"
    (let [{:keys [status out]} (run-command "test" "--format" "json" "neorepl.sample-suite/failing")]
      (is (= 1 status))
      (is (str/starts-with? out "{\"status\":\"failed\",\"out\":\"\",\"err\":\"\",\"summary\":{\"ns\":1,\"var\":1,\"test\":1,\"pass\":0,\"fail\":1,\"error\":0},\"failures\":[{"))))
  (testing "what can't run"
    (is (= {:status 1 :out "" :err "neorepl: No test called neorepl.sample-suite/nope\n"}
           (run-command "test" "neorepl.sample-suite/nope")))
    (is (= {:status 1 :out "" :err "neorepl: Rerunning the tests that failed takes cider-nrepl or babashka on the server\n"}
           (run-command "retest")))
    (is (= 2 (:status (run-command "retest" "neorepl.sample-suite"))))))

(deftest repl-commands-test
  (let [repl #(:out (run-in *dir* % "repl" "-p" (str *port*)))]
    (testing "commands"
      (is (str/starts-with? (repl ",doc map\n") "user=> clojure.core/map\n([f] [f coll]"))
      (is (str/starts-with? (repl ",source when\n") "user=> (defmacro when\n"))
      (is (= "user=> (if x (do y))\nuser=> \n" (repl ",macroexpand (when x\n  y)\n")))
      (is (str/includes? (repl "(/ 1 0)\n,pst\n") "user=> java.lang.ArithmeticException: Divide by zero\n"))
      (is (= "user=> passing prints\nRan 1 test containing 1 assertion.\n0 failures, 0 errors.\nuser=> \n"
             (repl ",test neorepl.sample-suite/passing\n")))
      (is (= "user=> Rerunning the tests that failed takes cider-nrepl or babashka on the server\nuser=> \n"
             (repl ",retest\n"))))
    (testing "a command's trouble doesn't end the REPL"
      (is (str/ends-with? (repl "(set! *print-length* 2)\n(throw (ex-info \"boom\" {:xs (range 10)}))\n,pst\n(+ 1 2)\n")
                          "user=> 3\nuser=> \n")))
    (testing "an argument that isn't code ends with its line"
      (is (= "user=> clojure.core/mapcat\nuser=> 3\nuser=> \n" (repl ",apropos ^mapcat$\n(+ 1 2)\n"))))
    (testing ",where and ,reload"
      (is (re-find #"^user=> \S*core\.clj:\d+" (repl ",where map\n")))
      (is (= "user=> Reloaded clojure.set\nuser=> \n" (repl ",reload clojure.set\n")))
      (testing "which leaves the REPL in the namespace it was in, even when that's made anew"
        (is (= "user=> nil\nrepl.anew=> nil\nrepl.anew=> Reloaded clojure.set\nrepl.anew=> true\nrepl.anew=> \n"
               ;; in-ns refers nothing to the namespace it makes on the JVM.
               (repl (str "(do (in-ns 'repl.anew) nil)\n"
                          "(do (clojure.core/remove-ns 'repl.anew) (clojure.core/create-ns 'repl.anew) nil)\n"
                          ",reload clojure.set\n"
                          "(clojure.core/identical? clojure.core/*ns* (clojure.core/find-ns 'repl.anew))\n"))))))
    (testing "help, mistakes and quitting"
      (is (str/includes? (repl ",help\n") ",doc SYM"))
      (is (= "user=> Usage: ,doc SYM\nuser=> \n" (repl ",doc\n")))
      (is (= "user=> No command called ,nope, ,help lists them\nuser=> \n" (repl ",nope\n")))
      (is (= "user=> \n" (repl ",quit\n(+ 1 2)\n"))))))

(deftest edit-test
  ;; The editors are shell scripts.
  (when-not (test-server/windows?)
    (let [file (spit-file *dir* "src/edit/demo.clj" "(ns edit.demo)\n\n(defn f [] 1)\n")
          edited (io/file *dir* "edited")
          editor (fn [path]
                   (doto (spit-file *dir* path (str "#!/bin/sh\necho \"$@\" > '" edited "'\n"))
                     (.setExecutable true)))
          edit #(with-out-str
                  (with-in-str ",edit edit.demo/f\n"
                    (main/run ["repl" "-p" (str *port*)]
                              {:dir *dir* :sessions-file (sessions-file) :editor-command %})))]
      (run-command "load" (str file))
      (testing "at the line, the way vi and Emacs take it"
        (edit (str "'" (editor "an editor/vi") "' -f"))
        (is (= (str "-f +3 " (.getAbsolutePath file) "\n") (slurp edited))))
      (testing "or the way VS Code does"
        (edit (str (editor "bin/code") " -w"))
        (is (= (str "-w -g " (.getAbsolutePath file) ":3\n") (slurp edited))))
      (testing "with an editor that fails"
        (is (= "user=> The editor exited with status 1\nuser=> \n" (edit "false")))))))

(deftest discovery-test
  (call-with-temp-dir
   (fn [dir]
     (spit-file dir ".nrepl-port" (str *port*))
     (is (= {:status 0 :out "3\n" :err ""}
            (run-in (io/file dir "sub") "" "eval" "(+ 1 2)"))))))

(deftest ports-test
  (call-with-temp-dir
   (fn [dir]
     (testing "no port files"
       ;; Unless a parent of the temp dir happens to have one.
       (when (empty? (discovery/find-ports dir))
         (let [{:keys [status err]} (run-in dir "" "ports")]
           (is (= 3 status))
           (is (str/includes? err "No nREPL port file")))))
     (let [app (io/file dir "app")
           stale (.getCanonicalPath (spit-file app ".nrepl-port" (str (test-server/free-port))))
           live (.getCanonicalPath (spit-file dir ".nrepl-port" (str *port*)))]
       (testing "nearest first, live or not"
         (let [{:keys [status out]} (run-in app "" "ports")]
           (is (zero? status))
           (is (re-find (re-pattern (str "^\\Q" stale "\\E +\\d+ +not answering\n"
                                         "\\Q" live "\\E +" *port* " +.*nrepl \\d")) out))))
       (testing "structured"
         (let [[s l] (edn/read-string (:out (run-in app "" "ports" "--format" "edn")))]
           (is (= {:file stale :live false} (dissoc s :port :dir)))
           (is (= {:file live :port *port* :live true} (dissoc l :versions :dir)))
           (is (string? (get-in l [:versions :nrepl])))))
       (testing "nothing answering"
         (spit live (str (test-server/free-port)))
         (is (= 3 (:status (run-in app "" "ports")))))))))

(deftest check-test
  (let [good (str (spit-file *dir* "check/good.clj" "(ns good)\n\n(defn f [x]\n  (str \"(\" x))\n"))
        unclosed (str (spit-file *dir* "check/unclosed.clj" "(ns unclosed)\n\n(defn f [x]\n  (inc x)\n"))
        mismatched (str (spit-file *dir* "check/mismatched.clj" "(let [x 1)\n  x)"))]
    (testing "all good"
      (is (= {:status 0 :out "" :err ""} (run-in *dir* "" "check" good))))
    (testing "a problem per file"
      (is (= {:status 1
              :out (str unclosed ":3:1: Unclosed (\n"
                        mismatched ":1:10: Mismatched ): expected ] to close the [ at 1:6\n")
              :err ""}
             (run-in *dir* "" "check" good unclosed mismatched))))
    (testing "files that can't be read are problems too"
      (is (= {:status 1
              :out (str "/does/not/exist.clj: No such file\n"
                        *dir* ": Can't read the file\n"
                        unclosed ":3:1: Unclosed (\n")
              :err ""}
             (run-in *dir* "" "check" good "/does/not/exist.clj" (str *dir*) unclosed))))
    (testing "standard input"
      (is (= {:status 1 :out "<stdin>:1:8: Unmatched )\n" :err ""}
             (run-in *dir* "(+ 1 2))" "check" "-")))
      (is (= {:status 1 :out "<stdin>:1:6: Unterminated string\n" :err ""}
             (run-in *dir* "(str \"abc)" "check" "-"))))
    (testing "structured"
      (is (= [{:file unclosed :line 3 :column 1 :message "Unclosed ("}]
             (edn/read-string (:out (run-in *dir* "" "check" "--format" "edn" good unclosed)))))
      (is (= "[]\n" (:out (run-in *dir* "" "check" "--format" "json" good)))))))

(deftest errors-test
  (testing "usage errors"
    (are [args message] (let [{:keys [status out err]} (apply run-in *dir* "" args)]
                          (and (= 2 status)
                               (= "" out)
                               (str/includes? err message)))
      ["eval" "--bogus" "1"] "Unknown option: --bogus"
      ["eval" "-p"] "-p needs a value"
      ["eval" "-p" "http" "1"] "Invalid value for -p: http"
      ["eval" "--timeout" "-1" "1"] "Invalid value for --timeout"
      ["eval" "--format" "yaml" "1"] "Invalid value for --format: yaml"
      ["eval" "--timeout" "1234567890" "1"] "Invalid value for --timeout"
      ["eval" "--max-output" "0" "1"] "Invalid value for --max-output"
      ["eval" "--max-output" "1.5" "1"] "Invalid value for --max-output"
      ["eval" "--url" "http://localhost:1" "1"] "Invalid value for --url"
      ["eval" "--url" "nrepl://localhost:1" "-p" "1" "1"] "--url doesn't go with --host or --port"
      ["eval" "--host" "localhost" "1"] "--host needs a --port"
      ["eval" "--host=" "-p" "1" "1"] "Invalid value for --host"
      ["eval" "-s" "" "1"] "Invalid value for -s"
      ["eval" "-p" "1"] "Nothing to evaluate"
      ["eval" "-p" "1" "(+" "1)"] "Too many arguments"
      ["eval" "-f" "x.clj" "1"] "not both"
      ["eval" "-f" "/does/not/exist.clj"] "No such file"
      ["load" "-p" "1"] "Nothing to load"
      ["load" "-p" "1" "a.clj" "b.clj"] "Load one file at a time"
      ["load" "-p" "1" "/does/not/exist.clj"] "No such file"
      ["load" "-f" "x.clj"] "Unknown option: -f"
      ["interrupt" "-p" "1" "now"] "Unexpected argument: now"
      ["repl" "-p" "1" "now"] "Unexpected argument: now"
      ["-p" "1" "--format" "edn"] "Unknown option: --format"
      ["sessions" "-p" "1" "open"] "Unexpected argument: open"
      ["sessions" "-p" "1" "close"] "Give the NAME of the session to close"
      ["sessions" "-p" "1" "close" "a" "b"] "Unexpected argument: b"
      ["ports" "here"] "Unexpected argument: here"
      ["ports" "-p" "1"] "Unknown option: -p"
      ["check"] "Nothing to check"
      ["doc" "-p" "1"] "Give a SYM"
      ["doc" "-p" "1" "map" "inc"] "Unexpected argument: inc"
      ["source" "-p" "1"] "Give a SYM"
      ["complete" "-p" "1"] "Give a PREFIX"
      ["apropos" "-p" "1" "--ns" "user" "x"] "Unknown option: --ns"
      ["evil"] "Unknown command: evil"))
  (testing "usage errors point at the right help"
    (is (str/includes? (:err (run-in *dir* "" "load" "--bogus")) "Run neorepl load --help"))
    (is (str/includes? (:err (run-in *dir* "" "evil")) "Run neorepl --help")))
  (testing "no server"
    (let [{:keys [status err]} (run-in *dir* "" "eval" "-p" (str (test-server/free-port)) "1")]
      (is (= 3 status))
      (is (str/includes? err "No nREPL server answering")))))

(deftest help-test
  (are [args text] (let [{:keys [status out]} (apply run-in *dir* "" args)]
                     (and (zero? status) (str/includes? out text)))
    ["help"] "Commands:"
    ["--help"] "Commands:"
    ["eval" "--help"] "Usage: neorepl eval"
    ["eval" "-h"] "--timeout SECS"
    ["-h"] "load "
    ["load" "--help"] "Usage: neorepl load"
    ["interrupt" "--help"] "Usage: neorepl interrupt"
    ["sessions" "--help"] "Usage: neorepl sessions"
    ["ports" "--help"] "Usage: neorepl ports"
    ["check" "--help"] "Usage: neorepl check"
    ["repl" "--help"] "Usage: neorepl [repl]"
    ["doc" "--help"] "Usage: neorepl doc"
    ["source" "--help"] "Usage: neorepl source"
    ["complete" "--help"] "Usage: neorepl complete"
    ["apropos" "--help"] "Usage: neorepl apropos"
    ["macroexpand" "--help"] "Usage: neorepl macroexpand"
    ["pst" "--help"] "Usage: neorepl pst"
    ["where" "--help"] "Usage: neorepl where"
    ["reload" "--help"] "Usage: neorepl reload"
    ["test" "--help"] "Usage: neorepl test"
    ["retest" "--help"] "Usage: neorepl retest"
    ["server" "--help"] "Usage: neorepl server"))

(deftest version-test
  (is (= {:status 0 :out (str "neorepl " main/version "\n") :err ""}
         (run-in *dir* "" "--version"))))
