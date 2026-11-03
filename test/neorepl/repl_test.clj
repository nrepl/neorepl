(ns neorepl.repl-test
  "The REPL with its line editor, on a fake terminal: tests type keys and look
  at what shows up."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.editor :as editor]
            [neorepl.features :as features]
            [neorepl.main :as main]
            [neorepl.nrepl :as nrepl]
            [neorepl.repl :as repl]
            [neorepl.sessions :as sessions]
            [neorepl.test-server :as test-server :refer [*port* call-with-temp-dir lf with-client]])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream PipedInputStream PipedOutputStream)
           (org.jline.reader CompletingParsedLine Parser Parser$ParseContext)
           (org.jline.utils AttributedString)
           (java.util.regex Pattern)
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
  contents. The named sessions are in opts' :sessions-file, if it has one,
  and its :editor-command is the REPL's.

  Eldoc is off unless opts say :eldoc? true, since what it shows after the
  cursor gets in the way of reading the screen, and so are colors unless
  they say :color? true, since showing the delimiter that pairs with the
  one at the cursor redraws the line."
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
                                     :editor-command (:editor-command opts)
                                     :terminal (merge {:input in :output out :width 80 :eldoc? false :color? false}
                                                      (dissoc opts :sessions-file :editor-command))}))
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
          (call-with-repl {:color? true}
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
        (testing "each indented for the form it's in"
          ;; Lined up with the 1.
          (is (screen #"#_=> {4}$")))
        (type "2)\r")
        (is (screen #"(?m)^3\nuser=> $"))
        (type "(let [a 1]\r")
        ;; A body, by two.
        (is (screen #"#_=> {3}$"))
        (type "a)\r")
        (is (screen #"(?m)^1\nuser=> $"))
        (testing "but a paste keeps its own"
          (type "\u001b[200~(let [a 2]\r a)\u001b[201~\r")
          (is (screen #"#_=>  a\)\n2\nuser=> $"))))))
  (testing "special forms in bold, the macros that read like them too"
    (let [{:keys [out]} (call-with-repl {:color? true}
                          (fn [type screen]
                            (type "(-> 1 inc)\r")
                            (screen #"(?m)^2\nuser=> $")))]
      (is (str/includes? out "\u001b[34;1m->"))))
  (testing "commands highlighted"
    (let [{:keys [out]} (call-with-repl {:color? true}
                          (fn [type screen]
                            (type ",doc map")
                            (screen #",doc map$")
                            (type "\u0003")
                            (screen #"\nuser=> $")))]
      (is (str/includes? out "\u001b[34;1m")))
    (let [styled #(.toAnsi ^AttributedString (#'editor/command-styled @#'editor/colors #{"doc"} % (AttributedString. ^String %)))]
      (is (= "\u001b[34;1m,doc\u001b[0m map" (styled ",doc map")))
      (testing "and one that isn't there as an error"
        (is (= "\u001b[31m,dco\u001b[0m map" (styled ",dco map"))))
      (testing "but not while it's being typed"
        (is (= ",d" (styled ",d"))))
      (testing "and code isn't a command"
        (is (= "(doc map)" (styled "(doc map)"))))))
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

(deftest delimiter-pair-test
  (let [typed (fn [line end]
                ;; What the REPL wrote, in color, as line was typed and then
                ;; end, Enter or Ctrl-C.
                (:out (call-with-repl {:color? true}
                        (fn [type screen]
                          (type line)
                          ;; JLine redraws from the opener the pair
                          ;; changes, so the line's end is all to go by.
                          (screen (re-pattern (str (Pattern/quote (subs line (dec (count line)))) "$")))
                          (type end)
                          (screen #"\nuser=> $")))))
        submitted #(typed % "\r")
        reversed "\u001b[7m("
        ;; Whether the opener out showed reversed last is drawn again, plain,
        ;; before the line ends.
        unreversed? (fn [out]
                      (let [shown (str/last-index-of out reversed)]
                        (and shown
                             (str/includes? (subs out (+ shown (count reversed)) (str/index-of out "\r\n" shown))
                                            "("))))]
    (testing "the delimiter that pairs with the one at the cursor shows reversed"
      (let [out (submitted "(+ 1 2)")]
        (is (str/includes? out reversed))
        (testing "until the line's accepted, so it doesn't stay in the scrollback"
          (is (unreversed? out)))
        (testing "or given up on"
          (is (unreversed? (typed "(a (b)" "\u0003"))))))
    (testing "a closer of the wrong kind shows red, with its opener"
      (let [out (submitted "(a]")]
        (is (str/includes? out "\u001b[31m("))
        (is (str/includes? out "\u001b[31m]"))))
    (testing "and so does one with nothing to close"
      (is (str/includes? (submitted ":a)") "\u001b[31m)")))
    (testing "not without colors"
      (let [{:keys [out]} (call-with-repl {:color? false}
                            (fn [type screen]
                              (type "(+ 1 2)\r")
                              (screen #"(?m)^3\nuser=> $")))]
        (is (not (str/includes? out "\u001b[7m")))))))

(deftest inputrc-test
  (call-with-temp-dir
   (fn [dir]
     (testing "readline's settings count, so the vi keymap is there"
       (call-with-repl {:inputrc (test-server/spit-file dir "vi" "set editing-mode vi\n")}
         (fn [type screen]
           ;; Escape, then x deleting the character under the cursor.
           (type "12\u001bx\r")
           (is (screen #"(?m)^1\nuser=> $")))))
     (testing "and so do neorepl's own, like highlighting input past 1000 characters"
       (let [line (str "(str \"" (apply str (repeat 1100 "a")) "\")")
             {:keys [out]} (call-with-repl {:inputrc (test-server/spit-file dir "long" "$if neorepl\n  set features-max-buffer-size 10000\n$endif\n")
                                            :color? true}
                             (fn [type screen]
                               (type line)
                               (screen #"\)$")
                               (type "\r")
                               (screen #"\nuser=> $")))]
         (is (str/includes? out "\u001b[7m(")))))))

(deftest edit-input-test
  (testing "C-x C-e edits the input in the editor"
    (call-with-repl {}
      (fn [type screen]
        (type "(+ 1 1)\u0018\u0005")
        (is (screen #"Set EDITOR, or VISUAL, to the editor to open\n"))
        (type "\u0003"))))
  ;; The editors are shell scripts.
  (when-not (test-server/windows?)
    (call-with-temp-dir
     (fn [dir]
       (testing "taking back what's saved"
         (call-with-repl {:editor-command (str (doto (test-server/spit-file dir "vi" "#!/bin/sh\nprintf '(+ 20 22)\\n' > \"$1\"\n")
                                                 (.setExecutable true)))}
           (fn [type screen]
             (type "(+ 1 1)\u0018\u0005\r")
             (is (screen #"(?m)^42\nuser=> $")))))
       (testing "or leaving the input be when the editor fails"
         (call-with-repl {:editor-command "false"}
           (fn [type screen]
             (type "(+ 1 1)\u0018\u0005")
             (is (screen #"The editor exited with status 1\n"))
             (type "\r")
             (is (screen #"(?m)^2\nuser=> $")))))))))

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
        ;; Well within the quarter of a second, which a slow machine
        ;; stretched 100ms to.
        (type "(do (future (Thread/sleep 200) (dotimes [i 6] (print (str \"step\" i \"...\")) (flush) (Thread/sleep 20) (println \"done\"))) nil)\r")
        (is (= 6 (count (re-seq #"step\d\.\.\.done\n" (screen #"step5\.\.\.done\nuser=> $"))))))))
  (testing "and what the server prints elsewhere, on threads of its own"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (future (Thread/sleep 200) (neorepl.test-server/print-system! :out \"elsewhere\\n\")) nil)\r")
        (is (screen #"elsewhere\nuser=> $")))))
  (testing "or before what's printed next, if that's sooner"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (future (Thread/sleep 50) (print \"partial\") (flush)) nil)\r")
        (screen #"nil\nuser=> $")
        ;; Past the print, but well within the moment.
        (Thread/sleep 150)
        (type "1\r")
        (is (re-find #"partial\n" (screen #"(?m)^1\nuser=> $"))))))
  (testing "what's typed while an evaluation runs shows up at the next prompt only"
    (call-with-repl {}
      (fn [type screen]
        (type "(do (Thread/sleep 300) :done)\r")
        (screen #":done\)\n$")
        (type "(+ 1 1)")
        (is (re-find #"\)\n:done\nuser=> \(\+ 1 1\)$" (screen #":done\nuser=> \(\+ 1 1\)$")))
        (type "\r")
        (is (screen #"(?m)^2\nuser=> $")))))
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
       (is (re-matches #"user=> 3\nuser=> (?s).*neorepl\.repl-test\n.*user=> \n"
              (lf (with-out-str
                (with-in-str "(+ 1 2)\n,ns\n"
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
  (testing "the word completion completes is what's before the cursor of the symbol it's in"
    (let [line (.parse ^Parser (#'editor/parser (atom false) (constantly false))
                       "(str/jo x" 7 Parser$ParseContext/COMPLETE)]
      ;; Which JLine warns about otherwise.
      (is (instance? CompletingParsedLine line))
      (is (= ["str/jo" 6 6] [(.word ^CompletingParsedLine line)
                             (.rawWordCursor ^CompletingParsedLine line)
                             (.rawWordLength ^CompletingParsedLine line)]))
      (is (= "str/join" (str (.escape ^CompletingParsedLine line "str/join" false))))))
  (testing "the completion menu goes without colors too"
    (let [{:keys [out]} (call-with-repl {:color? false}
                          (fn [type screen]
                            (type "(map-")
                            (type "\t")
                            (is (screen #"map-indexed"))
                            (type "\u0003")))]
      (is (not (re-find #"\u001b\[[0-9;]*m" out)))))
  (testing "the completion menu shows a candidate's type and namespace"
    (let [description #'editor/description]
      (is (= "function, clojure.core" (description {:candidate "map" :type "function" :ns "clojure.core"})))
      (is (= "function, clojure.core" (description {:candidate "/" :type "function" :ns "clojure.core"})))
      (testing "but not the namespace of one that names it"
        (is (= "function" (description {:candidate "str/join" :type "function" :ns "clojure.string"}))))
      (testing "and nothing for one the server says nothing about"
        (is (nil? (description {:candidate "map"}))))))
  (testing "eldoc shows the arglists of the function being called, under the input"
    (call-with-repl {:eldoc? true}
      (fn [type screen]
        (type "(mapcat ")
        (is (screen #"user=> \(mapcat \nclojure\.core/mapcat: \(\[f\] \[f & colls\]\)"))
        (type "\u0003"))))
  (testing "eldoc wherever the cursor is, rather than only at the end"
    (call-with-repl {:eldoc? true}
      (fn [type screen]
        (type "(inc (mapcat x))")
        ;; Ctrl-B twice, back into (mapcat x), after the x.
        (type "\u0002\u0002")
        ;; In place of inc's, which JLine redraws only from where they
        ;; differ, after clojure.core/.
        (is (screen #"mapcat: \(\[f\] \[f & colls\]\)"))
        (type "\u0003"))))
  (testing "eldoc in bold for the parameter the argument at the cursor goes into, and faint for the arglists that can't take it"
    (let [{:keys [out]} (call-with-repl {:eldoc? true :color? true}
                          (fn [type screen]
                            (type "(mapcat inc ")
                            (screen #"\nclojure\.core/mapcat: ")
                            ;; For the keys typed after the lookup came back.
                            (Thread/sleep 100)
                            (type "\u0003")))]
      (is (re-find #"\u001b\[[0-9;]*2m\[f\]" out))
      (is (re-find #"\u001b\[[0-9;]*1m& colls" out))))
  (testing "eldoc matches a macro's arguments by their shape"
    (let [{:keys [out]} (call-with-repl {:eldoc? true :color? true}
                          (fn [type screen]
                            (type "(defn f \"Does f.\" ")
                            ;; Too long for its namespace.
                            (screen #"\ndefn: \(\[name doc-string\? attr-map\? ")
                            (Thread/sleep 100)
                            (type "\u0003")))]
      ;; Past the docstring, rather than at the third parameter.
      (is (re-find #"\u001b\[[0-9;]*1mattr-map\?" out))))
  (testing "eldoc cut to fit, without the namespace first"
    (call-with-repl {:eldoc? true :width 30}
      (fn [type screen]
        (type "(mapcat ")
        (is (screen #"\nmapcat: \(\[f\] \[f & colls\]\)"))
        (type "\u0003")))
    (testing "then with an ellipsis, a column short of the width"
      (call-with-repl {:eldoc? true :width 20}
        (fn [type screen]
          (type "(mapcat ")
          (is (screen #"\nmapcat: \(\[f\] \[f & …"))
          (type "\u0003")))))
  (testing "eldoc's line goes once the input's accepted, so it doesn't stay in the scrollback"
    (let [{:keys [out]} (call-with-repl {:eldoc? true}
                          (fn [type screen]
                            (type "(mapcat ")
                            (screen #"\nclojure\.core/mapcat: ")
                            (type "list [[1]])\r")
                            (screen #"(?m)^\(\[1\]\)\nuser=> $")))
          shown (str/last-index-of out "clojure.core/mapcat: ")]
      (is (str/includes? (subs out shown (str/index-of out "([1])" shown)) "\u001b[K"))))
  (testing "the completion menu shows in eldoc's place"
    (call-with-repl {:eldoc? true}
      (fn [type screen]
        (type "(mapcat map-")
        (screen #"\nclojure\.core/mapcat: ")
        (type "\t")
        (is (screen #"map-indexed"))
        (type "\u0003"))))
  (testing "eldoc's line comes back once the input changes after C-d's list of completions, or C-x ='s position"
    (call-with-repl {:eldoc? true}
      (fn [type screen]
        (type "(mapcat map-")
        (screen #"\nclojure\.core/mapcat: ")
        (type "\u0004")
        (screen #"map-indexed")
        (type "i")
        (is (screen #"map-indexed[^\n]*\n(?s).*clojure\.core/mapcat: \(\[f\] \[f & colls\]\)$"))
        (type "\u0003")
        (type "(mapcat x")
        (screen #"\nclojure\.core/mapcat: ")
        (type "\u0018=")
        (screen #"point 10 of 10")
        (type "y")
        (is (screen #"point 10 of 10[^\n]*\n(?s).*clojure\.core/mapcat: \(\[f\] \[f & colls\]\)$"))
        (type "\u0003"))))
  (testing "output from another thread goes above the prompt, with eldoc's line still under the input"
    (call-with-repl {:eldoc? true}
      (fn [type screen]
        (type "(do (future (Thread/sleep 300) (println :later)) nil)\r")
        (screen #"nil\nuser=> $")
        (type "(mapcat ")
        (screen #"\nclojure\.core/mapcat: ")
        (is (screen #":later\n(?s).*user=> \(mapcat \nclojure\.core/mapcat: \(\[f\] \[f & colls\]\)"))
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
  (testing "C-x C-s shows the source"
    (call-with-repl {}
      (fn [type screen]
        (type "(when")
        (type "\u0018\u0013")
        (is (screen #"\(defmacro when\n  \"Evaluates test"))
        (type "\u0003"))))
  (testing "C-x C-a shows the vars whose names have the symbol in them"
    (call-with-repl {}
      (fn [type screen]
        (type "(mapca")
        (type "\u0018\u0001")
        (is (screen #"clojure\.core/mapcat"))
        (type "\u0003"))))
  (testing "Ctrl-R searches the history, its prompt in eldoc's place"
    (call-with-repl {:eldoc? true}
      (fn [type screen]
        (type "(+ 40 2)\r")
        (screen #"(?m)^42\nuser=> $")
        (type "(+ 1 1)\r")
        (screen #"(?m)^2\nuser=> $")
        (type "(mapcat ")
        (screen #"\nclojure\.core/mapcat: ")
        (type "\u0012")
        (is (screen #"bck-i-search: _"))
        (testing "and Ctrl-R again finds the match before"
          (type "+")
          (type "\u0012")
          (type "\r")
          (is (screen #"(?m)^42\nuser=> $")))))))

(deftest eldoc-lookups-test
  (with-client [client {:port *port*}]
    (let [{:keys [eldoc in-ns]} (#'repl/lookups client)
          text #(some->> % (map first) (apply str))
          refreshed (promise)]
      (in-ns {:ns "user"})
      (testing "in the background, so typing doesn't wait on the server"
        (is (nil? (eldoc "(mapcat " 8 #(deliver refreshed :refreshed))))
        (testing "and showing what it found once it's back"
          (is (= :refreshed (deref refreshed 5000 :timeout)))
          (is (= "clojure.core/mapcat: ([f] [f & colls])" (text (eldoc "(mapcat " 8 (fn []))))))
        (testing "nothing to show for nothing found"
          (let [looked (promise)]
            (is (nil? (eldoc "(no-such-fn " 12 #(deliver looked :refreshed))))
            (is (= :not-refreshed (deref looked 500 :not-refreshed))))))
      (testing "until the next prompt, as evaluating code can change what names mean"
        (in-ns {:ns "user"})
        (is (nil? (eldoc "(mapcat " 8 (fn []))))))))

(deftest commands-test
  (call-with-repl {}
    (fn [type screen]
      (type ",doc when\r")
      (is (screen #"clojure\.core/when\n\(\[test & body\]\)\nMacro\n"))
      (type ",apropos ^mapcat$\r")
      (is (screen #"clojure\.core/mapcat\nuser=> $"))
      (type ",macroexpand (when x\r")
      (is (screen #"#_=> +$"))
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
      (testing "and without a name, browsing the project's namespaces"
        (type ",ns\r")
        ;; More than fit on the screen, with every test's loaded.
        (is (screen #"The project's namespaces(?s).*q quit"))
        (testing "filtered, with the vars of the one left"
          ;; The screen is drawn by moving the cursor, not line by line.
          (type "/.repl-test\r\u001b[C")
          (is (screen #"(?s)lookup-test.*← back"))
          (type "\u001b[D"))
        (testing "switching to the one picked"
          (type "\r")
          (is (screen #"neorepl\.repl-test=> $")))))))

(deftest autoreload-test
  (call-with-repl {}
    (fn [type screen]
      (let [before @test-server/reloads
            reloads #(re-pattern (str "\n" (+ before %) "\nuser=> $"))]
        (testing "staying off when reloading fails"
          (test-server/answering-reloads :error
            #(do (type ",autoreload\r")
                 (is (screen #"whom in this context\nReloading what changed failed\nNot reloading before evaluations\nuser=> $"))))
          (type "@neorepl.test-server/reloads\r")
          (is (screen (reloads 0))))
        (testing "reloading right away"
          (type ",autoreload\r")
          (is (screen #"\nReloading what changed before each evaluation\nuser=> $"))
          (is (= (inc before) @test-server/reloads)))
        (testing "and before each evaluation"
          (type "@neorepl.test-server/reloads\r")
          (is (screen (reloads 2))))
        (testing "not evaluating when that fails"
          (test-server/answering-reloads :error
            #(do (type "(swap! neorepl.test-server/reloads inc)\r")
                 (is (screen #"whom in this context\nReloading what changed failed\nuser=> $"))))
          (is (= (+ before 2) @test-server/reloads)))
        (testing "but evaluating in a ClojureScript session, with nothing to reload"
          (test-server/answering-reloads :clojure-only
            #(do (type "(+ 40 2)\r")
                 (is (screen #"\n42\nuser=> $")))))
        (testing "nor reloading once it's off"
          (type ",autoreload off\r")
          (is (screen #"\nNot reloading before evaluations\nuser=> $"))
          (type "@neorepl.test-server/reloads\r")
          (is (screen (reloads 2))))
        (testing "and saying how it goes"
          (type ",autoreload maybe\r")
          (is (screen #"\nUsage: ,autoreload \[on\|off\]\nuser=> $")))))))

(deftest spec-test
  (call-with-repl {}
    (fn [type screen]
      (type "(require '[clojure.spec.alpha :as s]) (s/def :neorepl.repl-specs/age pos-int?) (s/def :neorepl.repl-specs/person (s/keys :req [:neorepl.repl-specs/age]))\r")
      (screen #":neorepl\.repl-specs/person\nuser=> $")
      (testing "a spec's form, from the list of them"
        (type ",spec repl-specs\r")
        (is (screen #"Specs(?s).*:neorepl\.repl-specs/age.*:neorepl\.repl-specs/person.*q quit"))
        (type "\u001b[B\r")
        (is (screen #"(?s)\(s/keys :req \[:neorepl\.repl-specs/age\]\).*x example"))
        (testing "and the ones it names"
          ;; The screen is drawn by moving the cursor, not line by line.
          (type "\r")
          ;; The help line's the same, so it isn't drawn again.
          (is (screen #"(?s)x example.*pos-int\?")))
        (type "q")
        (is (screen #"user=> $")))
      (testing "without a terminal, printed"
        (call-with-temp-dir
         (fn [dir]
           (is (= "user=> (s/keys :req [:neorepl.repl-specs/age])\nuser=> :neorepl.repl-specs/age\nuser=> \n"
                  (lf (with-out-str
                        (with-in-str ",spec :neorepl.repl-specs/person\n,spec REPL-SPECS/A\n"
                          (main/run ["repl" "-p" (str *port*)]
                                    {:dir dir
                                     :sessions-file (io/file dir "sessions.edn")
                                     :history-dir (io/file dir "history")
                                     :terminal {:input (ByteArrayInputStream. (byte-array 0))
                                                :output (ByteArrayOutputStream.)
                                                :width 80
                                                :type "dumb"}}))))))))))))

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
