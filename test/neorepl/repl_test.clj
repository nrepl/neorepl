(ns neorepl.repl-test
  "The REPL with its line editor, on a fake terminal: tests type keys and look
  at what shows up."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.features :as features]
            [neorepl.main :as main]
            [neorepl.nrepl :as nrepl]
            [neorepl.repl :as repl]
            [neorepl.sessions :as sessions]
            [neorepl.test-server :as test-server :refer [*port* call-with-temp-dir lf with-client]])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream PipedInputStream PipedOutputStream)
           (java.util.zip ZipEntry ZipOutputStream)))

(use-fixtures :once (test-server/fixture))

(def ^:private line-drawing
  "The box drawing characters JLine draws with the terminal's line drawing
  set, by the characters it sends for them."
  {\j "┘" \k "┐" \l "┌" \m "└" \q "─" \t "├" \u "┤" \v "┴" \w "┬" \x "│"})

(defn- strip-ansi
  "s without the ANSI codes, and with the line drawing set's characters as
  the box drawing ones they stand for."
  [s]
  (-> s
      (str/replace #"\u001b\(0([^\u001b]*)\u001b\(B" #(str/escape (second %) line-drawing))
      (str/replace #"\u001b\[[0-9;?]*[A-Za-z]|\u001b[=>]|\r" "")))

(defn- call-with-repl
  "Starts a REPL on a fake terminal, with the editor options in opts, and
  calls (f type screen): type types a string, screen waits for a regex to
  show up in the output, ANSI codes stripped, and returns it. Then ends the
  REPL with Ctrl-D and returns its raw output and the history file's
  contents. The named sessions are in opts' :sessions-file, if it has one.

  Eldoc is off unless opts say :eldoc? true, since what it shows after the
  cursor gets in the way of reading the screen."
  [opts f]
  (call-with-temp-dir
   (fn [dir]
     (with-open [in (PipedInputStream.)
                 keys (PipedOutputStream. in)]
       (let [out (ByteArrayOutputStream.)
             repl (future (main/run ["repl" "-p" (str *port*)]
                                    {:dir dir
                                     :sessions-file (or (:sessions-file opts) (io/file dir "sessions.edn"))
                                     :history-dir (io/file dir "history")
                                     :terminal (merge {:input in :output out :width 80 :eldoc? false}
                                                      (dissoc opts :sessions-file))}))
             type (fn [s]
                    (.write keys (.getBytes ^String s "UTF-8"))
                    (.flush keys))
             screen (fn [re]
                      (loop [tries 1000]
                        (let [s (strip-ansi (str out))]
                          (cond
                            (re-find re s) s
                            (zero? tries) (throw (ex-info (str "Never saw " re) {:screen s}))
                            :else (do (Thread/sleep 5) (recur (dec tries)))))))]
         (try
           (screen #"user=> $")
           (f type screen)
           (type "\u0004")
           (is (= 0 (deref repl 10000 :timeout)))
           {:out (str out)
            :history (some-> (first (.listFiles (io/file dir "history"))) slurp)}
           (finally
             (future-cancel repl))))))))

(deftest repl-test
  (testing "evaluating"
    (let [{:keys [out history]}
          (call-with-repl {}
            (fn [type screen]
              (type "(+ 40 2)\r")
              (is (screen #"(?m)^42\nuser=> $"))))]
      (testing "with a banner"
        (is (str/includes? (strip-ansi out) (str "neorepl connected to 127.0.0.1:" *port*))))
      (testing "with values highlighted"
        (is (str/includes? out "\u001b[35m42")))
      (testing "and kept in the history"
        (is (str/includes? history "(+ 40 2)")))))
  (testing "forms span lines until they're complete"
    (call-with-repl {}
      (fn [type screen]
        (type "(+ 1\r")
        (is (screen #"#_=> $"))
        (type "2)\r")
        (is (screen #"(?m)^3\nuser=> $")))))
  (testing "the prompt follows the namespace"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (in-ns 'term.scratch) nil)\r")
        (is (screen #"term\.scratch=> $")))))
  (testing "no colors"
    (let [{:keys [out]} (call-with-repl {:color? false}
                          (fn [type screen]
                            (type "42\r")
                            (screen #"(?m)^42\nuser=> $")))]
      (is (not (str/includes? out "\u001b[35m"))))))

(deftest output-test
  (testing "stdout and stderr get lines of their own"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (print :out) (flush) (binding [*out* *err*] (println :err)) :value)\r")
        (is (screen #":out\n:err\n:value\nuser=> $")))))
  (testing "output after the evaluation is done goes above the prompt, line by line"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (future (Thread/sleep 200) (print \"a\") (flush) (print \"b\") (flush) (println \"c\")) nil)\r")
        ;; The prompt gets redrawn below, which screen doesn't see.
        (is (screen #"abc\nuser=> $")))))
  (testing "and the start of a line, a moment after"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (future (Thread/sleep 200) (print \"Sure? \") (flush)) nil)\r")
        (is (screen #"Sure\? \nuser=> $")))))
  (testing "but not when it ends in time"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (future (Thread/sleep 200) (dotimes [i 6] (print (str \"step\" i \"...\")) (flush) (Thread/sleep 100) (println \"done\"))) nil)\r")
        (is (= 6 (count (re-seq #"step\d\.\.\.done\n" (screen #"step5\.\.\.done\nuser=> $"))))))))
  (testing "or before what's printed next, if that's sooner"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (future (Thread/sleep 50) (print \"partial\") (flush)) nil)\r")
        (screen #"nil\nuser=> $")
        ;; Past the print, but well within the moment.
        (Thread/sleep 150)
        (type "1\r")
        (is (re-find #"partial\n" (screen #"(?m)^1\nuser=> $"))))))
  (testing "messages from the server"
    (with-client [client {:port *port*}]
      (call-with-repl {}
        (fn [type screen]
          ;; Closing every session but this client's takes the REPL's.
          (let [mine (nrepl/clone-session client)
                others (remove #{mine} (some :sessions (nrepl/request client {:op "ls-sessions"})))]
            (doseq [session others]
              (nrepl/close-session client session)))
          (type "1\r")
          (is (screen #"The server reported unknown-session\nuser=> $")))))))

(deftest stdin-test
  (testing "reads from *in* get a line of their own"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (print \"Name: \") (flush) (read-line))\r")
        (type "bob\r")
        (is (screen #"Name: bob\n\"bob\"\nuser=> $")))))
  (testing "Ctrl-C while code is reading interrupts it"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (read-line) :read)\r")
        (screen #":read\)\n$")
        ;; Time for it to go out and ask for the line.
        (Thread/sleep 200)
        (type "\u0003")
        (is (screen #"The evaluation was interrupted\nuser=> $"))
        ;; Time for the value to show up, if it was going to.
        (Thread/sleep 200)
        (is (not (re-find #"(?m)(^|=> ):read$" (screen #"user=> $")))))))
  (testing "even when it comes before the code asks for a line"
    (call-with-repl {}
      (fn [type screen]
        (type "(late-read)\r")
        (screen #"\(late-read\)\n$")
        ;; Time for it to go out, but not for the server to ask.
        (Thread/sleep 100)
        (type "\u0003")
        (is (screen #"The evaluation was interrupted\nuser=> $")))))
  (testing "and still gets its line when the interrupt fails after the server asked"
    (call-with-repl {}
      (fn [type screen]
        (type "(stubborn-read)\r")
        (screen #"\(stubborn-read\)\n$")
        (Thread/sleep 100)
        (type "\u0003")
        (screen #"The server can't interrupt the evaluation, Ctrl-C again stops waiting for it\n")
        (type "bob\r")
        (is (screen #"\"bob\"\nuser=> $"))))))

(deftest interrupt-test
  (testing "Ctrl-C at the prompt clears the line"
    (call-with-repl {}
      (fn [type screen]
        (type "(+ 1")
        (screen #"\(\+ 1$")
        (type "\u0003")
        (type "(+ 2 2)\r")
        (is (screen #"(?m)^4\nuser=> $")))))
  (testing "a Ctrl-C that arrives twice clears the line once"
    ;; As happens on babashka, where it comes as a signal and a character.
    (call-with-repl {}
      (fn [type screen]
        (type "(+ 1")
        (screen #"\(\+ 1$")
        (type "\u0003\u0003")
        (Thread/sleep 100)
        (type "(+ 2 2)\r")
        (is (= 3 (count (re-seq #"(?m)^user=> " (screen #"(?m)^4\nuser=> $"))))))))
  (testing "Ctrl-C interrupts an evaluation"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (println :sleeping) (Thread/sleep 20000))\r")
        (screen #":sleeping\n$")
        (type "\u0003")
        (is (screen #"The evaluation was interrupted\nuser=> $"))
        (type "(+ 3 3)\r")
        (is (screen #"(?m)^6\nuser=> $")))))
  (testing "Ctrl-C interrupts the tests ,test runs"
    (call-with-repl {}
      (fn [type screen]
        (type ",test neorepl.sample-slow\r")
        (screen #":sleeping\n$")
        (type "\u0003")
        (is (screen #"The evaluation was interrupted\nuser=> $"))
        (type "(+ 3 3)\r")
        (is (screen #"(?m)^6\nuser=> $")))))
  (testing "what an interrupted evaluation says after it's left out, even once the next one's started"
    (call-with-repl {}
      (fn [type screen]
        ;; Catching the interrupt, as clojure.test does.
        (type "(try (println :sleeping) (Thread/sleep 20000) (catch InterruptedException _ (Thread/sleep 500) (println (str \"dying\" \" words\"))))\r")
        (screen #":sleeping\n$")
        (type "\u0003")
        (screen #"The evaluation was interrupted\nuser=> $")
        (type "(do (Thread/sleep 1000) :next)\r")
        (is (not (re-find #"(?m)^dying words$" (screen #"(?m)^:next\nuser=> $")))))))
  (testing "Ctrl-C again stops waiting for an evaluation the server can't interrupt"
    (call-with-repl {}
      (fn [type screen]
        (type "(stuck)\r")
        (screen #"\(stuck\)\n$")
        (Thread/sleep 100)
        (type "\u0003")
        (is (screen #"The server can't interrupt the evaluation, Ctrl-C again stops waiting for it\n$"))
        (type "\u0003")
        (is (screen #"Stopped waiting for the evaluation, which may still be running\nuser=> $"))
        (type "(+ 3 3)\r")
        (is (screen #"(?m)^6\nuser=> $")))))
  (testing "and shows what it says after all, when it's only slow"
    (call-with-repl {}
      (fn [type screen]
        (type "(slow)\r")
        (screen #"\(slow\)\n$")
        (Thread/sleep 100)
        (type "\u0003")
        (is (screen #"Ctrl-C again stops waiting for it\n:slow\nuser=> $")))))
  (testing ":repl/quit"
    (call-with-repl {}
      (fn [type _]
        (type ":repl/quit\r")))))

(deftest dumb-terminal-test
  (testing "a dumb terminal gets the plain REPL"
    (call-with-temp-dir
     (fn [dir]
       (is (= "user=> 3\nuser=> \n"
              (lf (with-out-str
                (with-in-str "(+ 1 2)\n"
                  (main/run ["repl" "-p" (str *port*)]
                            {:dir dir
                             :sessions-file (io/file dir "sessions.edn")
                             :history-dir (io/file dir "history")
                             :terminal {:input (ByteArrayInputStream. (byte-array 0))
                                        :output (ByteArrayOutputStream.)
                                        :width 80
                                        :type "dumb"}}))))))))))

(deftest lookup-test
  (testing "TAB completes"
    (call-with-repl {}
      (fn [type screen]
        (type "(mapc")
        (type "\t")
        (type " list [[1]])\r")
        (is (screen #"(?m)^\(\[1\]\)\nuser=> $")))))
  (testing "eldoc shows the arglists of the function being called"
    (call-with-repl {:eldoc? true}
      (fn [type screen]
        (type "(mapcat ")
        (is (screen #"\[f\] \[f & colls\]"))
        (type "\u0003"))))
  (testing "eldoc for something that isn't a function"
    (call-with-repl {:eldoc? true}
      (fn [type screen]
        (type "(def cfg {:a 1})\r")
        (screen #"#'user/cfg\nuser=> $")
        (type "(cfg :a)\r")
        (is (screen #"(?m)^1\nuser=> $")))))
  (testing "C-x C-d on a keyword shows the call's documentation"
    (call-with-repl {}
      (fn [type screen]
        (type "(assoc {} :k")
        (type "\u0018\u0004")
        (is (screen #"clojure\.core/assoc\n"))
        (type "\u0003"))))
  (testing "C-x C-d shows the documentation"
    (call-with-repl {}
      (fn [type screen]
        (type "(when")
        (type "\u0018\u0004")
        (is (screen #"clojure\.core/when\n\(\[test & body\]\)\nMacro\n  Evaluates test"))
        (type "\u0003"))))
  (testing "Ctrl-R searches the history, with eldoc on too"
    (call-with-repl {:eldoc? true}
      (fn [type screen]
        (type "(+ 40 2)\r")
        (screen #"(?m)^42\nuser=> $")
        (type "(+ 1 1)\r")
        (screen #"(?m)^2\nuser=> $")
        (type "\u0012")
        (is (screen #"bck-i-search: _"))
        (testing "and Ctrl-R again finds the match before"
          (type "+")
          (type "\u0012")
          (type "\r")
          (is (screen #"(?m)^42\nuser=> $")))))))

(deftest commands-test
  (call-with-repl {}
    (fn [type screen]
      (type ",doc when\r")
      (is (screen #"clojure\.core/when\n\(\[test & body\]\)\nMacro\n"))
      (type ",apropos ^mapcat$\r")
      (is (screen #"clojure\.core/mapcat\nuser=> $"))
      (type ",macroexpand (when x\r")
      (is (screen #"#_=> $"))
      (type "y)\r")
      (is (screen #"\(if x \(do y\)\)\nuser=> $"))
      (testing "and code pasted after a command runs after it"
        (type "\u001b[200~,doc inc\n(+ 40 2)\u001b[201~\r")
        (is (screen #"clojure\.core/inc\n(?s).*\n42\nuser=> $"))))))

(deftest ns-test
  (call-with-repl {}
    (fn [type screen]
      (testing "TAB completing the name"
        (type ",ns clojure.se\t\r")
        (is (screen #"\nclojure\.set=> $")))
      (testing "and the lookups go on there"
        (type ",doc union\r")
        (is (screen #"clojure\.set/union\n")))
      (testing "but not into a namespace that doesn't load"
        (type ",ns no.such.ns\r")
        (is (screen #"Could not locate no/such/ns(?s).*\nclojure\.set=> $")))
      (testing "with code after it running there"
        (type "\u001b[200~,ns user\n(str *ns*)\u001b[201~\r")
        (is (screen #"\n\"user\"\nuser=> $")))
      (type ",ns\r")
      (is (screen #"\nneorepl\.repl-test\n(?s).*user=> $")))))

(deftest sessions-test
  (call-with-temp-dir
   (fn [dir]
     (let [sessions-file (io/file dir "sessions.edn")]
       (with-client [client {:port *port*}]
         (let [agent (sessions/ensure! sessions-file client "agent")]
           (try
             (nrepl/request client {:op "eval" :session agent
                                    :code "(ns agent.scratch) (def answer \"The answer.\" 42) answer"})
             (call-with-repl {:sessions-file sessions-file}
               (fn [type screen]
                 (type ":mine\r")
                 (screen #":mine\nuser=> $")
                 (testing "listed, the REPL's own first"
                   (type ",sessions\r")
                   (is (screen #"\* -\s+user\s+this REPL's\n  agent\s+agent\.scratch\nuser=> $")))
                 (testing "switching to one, which the prompt says"
                   (type ",sessions agent\r")
                   (is (screen #"Reading \*in\* won't work in agent(?s).*\nagent:agent\.scratch=> $"))
                   (type "*1\r")
                   (is (screen #"(?m)^42\nagent:agent\.scratch=> $")))
                 (testing "where the lookups go too"
                   (type ",doc answer\r")
                   (is (screen #"agent\.scratch/answer\n(?s).*agent:agent\.scratch=> $"))
                   (type ",pst\r")
                   (is (screen #"No error yet in session agent\nagent:agent\.scratch=> $")))
                 (testing "and back"
                   (type ",sessions -\r")
                   (is (screen #"\nuser=> $"))
                   (type "*1\r")
                   (is (screen #"(?m)^:mine\nuser=> $")))
                 (testing "but not to one that isn't there"
                   (type ",sessions nope\r")
                   (is (screen #"No session called nope, neorepl eval -s nope CODE makes one\nuser=> $")))
                 (type ",sessions agent\r")
                 (screen #"agent:agent\.scratch=> $")))
             (testing "which outlives the REPL that switched to it"
               (is (= agent (get (sessions/named sessions-file client) "agent"))))
             (finally
               (nrepl/close-session client agent)))))))))

(deftest trace-test
  (with-client [client {:port *port*}]
    (try
      (call-with-repl {}
        (fn [type screen]
          (type "(defn traced-in-repl [x] (inc x))\r")
          (type ",trace traced-in-repl\r")
          (is (screen #"Tracing user/traced-in-repl\nuser=> $"))
          (is (= 1 (test-server/trace-subscribers)))
          (type "(traced-in-repl 1)\r")
          (is (screen #"\(user/traced-in-repl 1\)\n└─→ 2\n2\nuser=> $"))
          (testing "on other threads, after the evaluation"
            (type "(do (future (Thread/sleep 200) (traced-in-repl 2)) nil)\r")
            ;; Each goes above the prompt, which gets redrawn below.
            (is (screen #"\(user/traced-in-repl 2\)\nuser=> └─→ 3\nuser=> $")))
          (type ",trace\r")
          (is (screen #"\nuser/traced-in-repl\nuser=> $"))
          (type ",untrace\r")
          (is (screen #"Stopped tracing 1 function\nuser=> $"))
          (testing "and the server prints them again once nothing's traced"
            (is (zero? (test-server/trace-subscribers))))
          (type ",trace traced-in-repl\r")
          (is (screen #"Tracing user/traced-in-repl\nuser=> $"))
          (is (= 1 (test-server/trace-subscribers)))))
      (testing "or once the REPL's done"
        (is (zero? (test-server/trace-subscribers))))
      (testing "the server printing them when there's no subscribing"
        (reset! test-server/refuse-trace-subscriptions? true)
        (call-with-repl {}
          (fn [type screen]
            (type ",trace traced-in-repl\r")
            (is (screen #"Already tracing user/traced-in-repl\nuser=> $"))
            (type "(traced-in-repl 1)\r")
            (is (screen #"\(user/traced-in-repl 1\)\n└─→ 2\n2\nuser=> $")))))
      (finally
        (reset! test-server/refuse-trace-subscriptions? false)
        (features/untrace-all client)))))

(deftest inspect-test
  (let [{:keys [out]}
        (call-with-repl {}
          (fn [type screen]
            (type ",inspect {:a [1 2]}\r")
            (is (screen #"--- Contents:(?s).*q quit"))
            (testing "defining it as a var in the REPL's namespace"
              (type "jdinspected-in-repl\r")
              ;; The screen only gets what changed on the bottom line, the
              ;; name typed a character at a time, then the rest of it.
              (is (screen #"#'user/inspected-in-repl")))
            (testing "digging into the value selected, which that left alone"
              (type "\r")
              (is (screen #"0\. 1\s+1\. 2")))
            (testing "and back to the REPL, which carries on"
              (type "q")
              (is (screen #"user=> $"))
              (type "inspected-in-repl\r")
              (is (screen #"(?m)^\{:a \[1 2\]\}\nuser=> $")))))]
    (testing "on a screen of its own, which goes away after"
      (is (str/includes? out "\u001b[?1049h"))
      (is (str/includes? out "\u001b[?1049l"))))
  (testing "a page of a collection to fill the screen"
    (call-with-repl {}
      (fn [type screen]
        (type ",inspect (range 100)\r")
        ;; 24 lines, less the help, the 7 lines around the items and one
        ;; for the ... the pages after the first start with.
        (is (screen #"14\. 14(?s).*Page size: 15, showing page: 1 of 7.*q quit"))
        (type "q")
        (is (screen #"user=> $"))))))

(deftest stacktrace-test
  (testing "the last error, on a screen of its own"
    (call-with-repl {}
      (fn [type screen]
        (type "(/ 1 0)\r")
        (screen #"Divide by zero.*\nuser=> $")
        (type ",stacktrace\r")
        (is (screen #"ArithmeticException: Divide by zero(?s).*q quit"))
        (testing "without inspecting its causes, which takes cider-nrepl"
          (is (not (re-find #"i inspect" (screen #"q quit")))))
        (type "q")
        (is (screen #"user=> $")))))
  (testing "or the last test run's, until an evaluation fails"
    (call-with-repl {}
      (fn [type screen]
        (type ",test neorepl.sample-suite\r")
        (screen #"1 failure, 1 error\.\nuser=> $")
        (type ",stacktrace\r")
        (is (screen #",stacktrace\nERROR in neorepl\.sample-suite/erring(?s).*clojure\.lang\.ExceptionInfo: boom.*q quit"))
        (type "q")
        (screen #"user=> $")
        (type "(/ 1 0)\r")
        (screen #"Divide by zero.*\nuser=> $")
        (type ",stacktrace\r")
        (is (screen #",stacktrace\njava\.lang\.ArithmeticException: Divide by zero(?s).*q quit"))
        (type "q")
        (is (screen #"user=> $")))))
  (testing "staying when a frame can't be opened"
    (call-with-repl {}
      (fn [type screen]
        (type "(/ 1 0)\r")
        (screen #"Divide by zero.*\nuser=> $")
        (type ",stacktrace\r")
        (screen #"q quit")
        (type "\r")
        (is (screen #"(No source to open for .*|Set EDITOR, or VISUAL, to the editor to open)$"))
        (type "q")
        (is (screen #"user=> $")))))
  ;; The editor's a shell script, and the frames on babashka are its
  ;; interpreter's. The fake terminal can't take an editor, since pausing
  ;; it for one ends its input.
  (when-not (or (test-server/windows?) (System/getProperty "babashka.version"))
    (testing "opening a frame's source"
      (call-with-temp-dir
       (fn [dir]
         (let [file (test-server/spit-file dir "st/demo.clj" "(ns st.demo)\n\n(defn f []\n  (/ 1 0))\n")
               edited (io/file dir "edited")
               editor (doto (test-server/spit-file dir "vi" (str "#!/bin/sh\necho \"$@\" > '" edited "'\n"))
                        (.setExecutable true))]
           (with-client [client {:port *port*}]
             (let [session (nrepl/clone-session client)
                   state {:client client :session session :ns "user" :editor-command (str editor)
                          :suspended #(%)}
                   [{:keys [stacktrace]}] (do (nrepl/request client {:op "eval" :session session
                                                                    :code (str "(load-file " (pr-str (str file)) ") (st.demo/f)")})
                                              (features/last-error client session))
                   open #(#'repl/open-frame state (first (filter % stacktrace)))]
               (testing "at the frame's line"
                 (is (nil? (open #(= "st.demo/f" (:var %)))))
                 (is (= (str "+4 " (.getAbsolutePath file) "\n") (slurp edited))))
               (testing "or at the frame's own path, when the server says"
                 (is (nil? (#'repl/open-frame state {:path (str file) :line 2})))
                 (is (= (str "+2 " file "\n") (slurp edited))))
               (testing "which a Java frame hasn't"
                 (is (= "No source to open for clojure.lang.Numbers/divide (Numbers.java:190)"
                        (str/replace (open #(str/starts-with? (:name %) "clojure.lang.Numbers/")) #"\d+\)" "190)"))))))))))))

(deftest edit-test
  ;; The editor's a shell script.
  (when-not (test-server/windows?)
    (testing "a file in a jar opens as a copy"
      (call-with-temp-dir
       (fn [dir]
         (let [jar (io/file dir "demo.jar")
               edited (io/file dir "edited")
               editor (doto (test-server/spit-file dir "vi" (str "#!/bin/sh\necho \"$@\" > '" edited "'\n"))
                        (.setExecutable true))
               sources (io/file dir "sources")
               state {:editor-command (str editor) :suspended #(%) :sources-dir sources}
               spit-jar (fn [jar code]
                          (io/make-parents jar)
                          (with-open [zip (ZipOutputStream. (io/output-stream jar))]
                            (.putNextEntry zip (ZipEntry. "st/demo.clj"))
                            (.write zip (.getBytes ^String code "UTF-8"))))
               open #(#'repl/open state (str "jar:" (.toURI ^java.io.File %1) "!/" %2) 2)
               ;; The copy the editor got, at line 2.
               opened #(second (re-matches #"\+2 (.*)\n" (slurp edited)))]
           (spit-jar jar "(ns st.demo)\n(def x 1)\n")
           (is (nil? (open jar "st/demo.clj")))
           (let [copy (opened)]
             (is (str/starts-with? copy (str sources "/demo.jar-")))
             (is (str/ends-with? copy "/st/demo.clj"))
             (is (= "(ns st.demo)\n(def x 1)\n" (slurp copy)))
             (testing "again"
               (is (nil? (open jar "st/demo.clj")))
               (is (= copy (opened))))
             (testing "and another jar with the same name gets copies of its own"
               (let [other (io/file dir "elsewhere" "demo.jar")]
                 (spit-jar other "(ns st.demo)\n(def x 2)\n")
                 (is (nil? (open other "st/demo.clj")))
                 (is (not= copy (opened)))
                 (is (= "(ns st.demo)\n(def x 2)\n" (slurp (opened)))))))
           (testing "unless it isn't there"
             (is (str/starts-with? (open jar "st/gone.clj") "Can't open jar:file:")))))))))
