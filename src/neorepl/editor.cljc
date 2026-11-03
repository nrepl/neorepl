(ns neorepl.editor
  "The REPL's line editor, on JLine. This is the only namespace that touches
  JLine, so when its API moves again, the damage stays here."
  (:refer-clojure :exclude [read-line])
  (:require [clojure.string :as str]
            [neorepl.lexer :as lexer])
  (:import (java.lang.reflect Field)
           (java.nio.charset StandardCharsets)
           (java.util List)
           (java.util.function Supplier)
           (org.jline.keymap BindingReader KeyMap)
           (org.jline.reader Candidate Completer CompletingParsedLine EOFError EndOfFileException
                             Highlighter LineReader LineReader$Option
                             LineReaderBuilder ParsedLine Parser Parser$ParseContext Reference
                             UserInterruptException Widget)
           (org.jline.terminal Attributes Attributes$LocalFlag Size Terminal Terminal$Signal
                               Terminal$SignalHandler TerminalBuilder)
           (org.jline.utils AttributedString AttributedStringBuilder AttributedStyle Display
                            InfoCmp$Capability)))

(defn- fg ^AttributedStyle [color]
  (.foreground AttributedStyle/DEFAULT (int color)))

(def ^:private colors
  "Styles for lexer token types, for :err output and :notice messages, for
  the delimiter that pairs with the one at the cursor, its :match, for the
  REPL's :commands, for the :headings of full-screen views, and for eldoc's
  pieces, as render/eldoc-line has them."
  (let [faint (.faint AttributedStyle/DEFAULT)
        green (fg AttributedStyle/GREEN)
        magenta (fg AttributedStyle/MAGENTA)
        yellow (fg AttributedStyle/YELLOW)
        special (.bold (fg AttributedStyle/BLUE))]
    {:comment faint
     :discard faint
     :string green
     :open-string green
     :regex green
     :char green
     :keyword (fg AttributedStyle/CYAN)
     :number magenta
     :constant magenta
     :special special
     :reader-macro yellow
     :macro-char yellow
     :err (fg AttributedStyle/RED)
     :match (.inverse AttributedStyle/DEFAULT)
     ;; The REPL's own special forms, which red stands out from in every
     ;; theme, as an unknown command is.
     :command special
     :notice faint
     :heading (.bold AttributedStyle/DEFAULT)
     ;; Eldoc's parameter for the argument at the cursor, and the arglists
     ;; that can't take it.
     :eldoc-arg (.bold AttributedStyle/DEFAULT)
     :eldoc-faint faint}))

(def ^:private specials
  "Symbols highlighted as :special: the special forms, the ones macros
  expand into among them, and the defining, binding and threading macros
  that read like them."
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol" "defrecord"
    "deftype" "definterface" "defonce" "defstruct" "declare" "deftest" "testing"
    "fn" "fn*" "let" "let*" "letfn" "letfn*" "loop" "loop*" "recur" "if" "if-let"
    "if-some" "if-not" "when" "when-let" "when-some" "when-not" "when-first" "while"
    "do" "cond" "condp" "case" "case*" "ns" "quote" "try" "catch" "finally" "throw"
    "var" "new" "set!" "monitor-enter" "monitor-exit" "locking" "binding" "doseq"
    "dotimes" "for" "doto" "with-open" "with-redefs" "with-local-vars" "reify"
    "reify*" "deftype*" "proxy" "extend" "extend-type" "extend-protocol" "->" "->>"
    "as->" "cond->" "cond->>" "some->" "some->>"})

(defn- style ^AttributedStyle [styles k]
  (styles k AttributedStyle/DEFAULT))

(defn- styled
  "The lexed code as an AttributedString, with its tokens styled, and the
  ones marks has, by where they start, in the style it says."
  ^AttributedString [styles {:keys [^String text tokens]} marks]
  (let [sb (AttributedStringBuilder.)
        end (reduce (fn [pos {:keys [type start end]}]
                      (let [token (subs text start end)]
                        (.append sb (subs text pos start))
                        (.styled sb (style styles (or (marks start)
                                                      (if (and (= :symbol type) (specials token)) :special type)))
                                 token)
                        end))
                    0
                    tokens)]
    (.append sb (subs text end))
    (.toAttributedString sb)))

(defn- highlight
  "code as an AttributedString, with its tokens styled."
  ^AttributedString [styles code]
  (styled styles (lexer/lex code) {}))

(defn- delimiter-marks
  "The marks for styled that show what the delimiter at the cursor pairs
  with, as lexer/delimiter-match has it: its :match, or an :err for a
  closer that pairs with nothing or with the wrong kind. An opener that
  isn't closed is just unfinished."
  [{:keys [at match mismatched?]}]
  (cond
    mismatched? {(:start at) :err (:start match) :err}
    match {(:start match) :match}
    (= :close (:type at)) {(:start at) :err}
    :else {}))

(defn- parsed-line
  "A ParsedLine for line whose word, the one completion completes, is what's
  before the cursor of the symbol or keyword it's in. It's a
  CompletingParsedLine, without which JLine warns."
  [^String line cursor]
  (let [start (or (:start (lexer/symbol-at (lexer/lex line) cursor)) cursor)
        word (subs line start cursor)]
    (reify CompletingParsedLine
      (word [_] word)
      (wordCursor [_] (count word))
      (wordIndex [_] 0)
      (words [_] [word])
      (line [_] line)
      (cursor [_] cursor)
      (escape [_ candidate _] candidate)
      (rawWordCursor [_] (count word))
      (rawWordLength [_] (count word)))))

(defn- parser
  "Tells JLine to keep reading while (incomplete? line), unless it's reading
  a raw line."
  [raw? incomplete?]
  (reify Parser
    (parse [_ line cursor context]
      (when (and (= Parser$ParseContext/ACCEPT_LINE context)
                 (not @raw?)
                 (incomplete? line))
        (throw (EOFError. -1 -1 "Incomplete form")))
      (parsed-line line cursor))))

(defn- eldoc-string
  "pieces, render/eldoc-line's, styled, and cut to fit in columns, if it
  has any: without the namespace first, then with an ellipsis for what's
  left out."
  ^AttributedString [styles pieces columns]
  (let [styled (fn [pieces]
                 (let [b (AttributedStringBuilder.)]
                   (doseq [[text k] pieces]
                     (.styled b (style styles k) ^CharSequence text))
                   (.toAttributedString b)))
        fits? #(or (not (pos? columns)) (<= (.columnLength ^AttributedString %) columns))
        whole (styled pieces)
        short (delay (styled (remove #(= :eldoc-ns (second %)) pieces)))]
    (cond
      (fits? whole) whole
      (fits? @short) @short
      (< columns 2) (AttributedString. "")
      :else (.toAttributedString (doto (AttributedStringBuilder.)
                                   (.append (.columnSubSequence ^AttributedString @short 0 (dec columns)))
                                   (.append "…"))))))

(def ^:private post
  "LineReaderImpl's post, what it shows under the input, like the
  completion menu, which only it sets otherwise: there's no setter."
  (delay (doto (.getDeclaredField (Class/forName "org.jline.reader.impl.LineReaderImpl") "post")
           (.setAccessible true))))

(defn- show-under
  "Shows line, an AttributedString, under the input, or nothing for nil,
  through JLine's post, in the place under, from the editor, unless JLine
  shows something of its own there, as for a search or the completion
  menu, or what a widget left there, under :left, along with the input
  then, until buffer, the input, changes, see leaving-under."
  [^LineReader reader {:keys [^Supplier supplier shown left]} buffer line]
  (let [^Field field @post
        current (.get field reader)
        [left-post left-buffer] @left]
    (when (or (nil? current)
              (identical? supplier current)
              (and (identical? left-post current) (not= left-buffer buffer)))
      (reset! shown line)
      (.set field reader (when line supplier)))))

(defn- redisplay
  "Redisplays the line reader is reading, from any thread, with JLine's
  lock, which output printed above the prompt takes too. Nothing for one
  it's done reading."
  [^LineReader reader]
  (try
    (.callWidget reader LineReader/REDISPLAY)
    (catch IllegalStateException _ nil)))

(defn- command-styled
  "code, with styled-code its tokens styled, with the REPL command it starts
  with, a comma and one of commands' names, in the :command style, or as an
  :err when the name is no command's, nor the start of one, which it is
  while it's being typed."
  ^AttributedString [styles commands ^String code ^AttributedString styled-code]
  (let [[_ name] (when (seq commands) (re-find #"^\s*,([a-z][a-z-]*)" code))
        k (cond
            (nil? name) nil
            (commands name) :command
            (not-any? #(str/starts-with? % name) commands) :err)]
    (if k
      (.styleMatches styled-code #"^\s*,[a-z][a-z-]*" (style styles k))
      styled-code)))

(defn- highlighter
  "Highlights code, with what the delimiter at the cursor pairs with, and
  has (eldoc text cursor refresh) say what to show under it, as
  render/eldoc-line's pieces, cut to the terminal's width. refresh shows it
  again, for eldoc to call when what it shows changes, like when a lookup
  it started comes back. JLine highlights on every redisplay, so eldoc sees
  every change to the line, whatever made it, and the pair follows the
  cursor. JLine's last redisplay of the line, once it's done reading it,
  goes without the pair or eldoc, so they don't stay behind in the
  scrollback. The names of the REPL's commands, a set, show as
  command-styled has them. under is where eldoc's line goes, see
  show-under."
  [styles raw? eldoc commands under]
  (reify Highlighter
    (highlight [_ reader buffer]
      (if @raw?
        (AttributedString. buffer)
        (let [^LineReader reader reader
              cursor (.cursor (.getBuffer reader))
              lexed (lexer/lex buffer)
              reading? (.isReading reader)]
          (when eldoc
            (show-under reader under buffer
                        (when-let [pieces (when reading? (eldoc buffer cursor #(redisplay reader)))]
                          ;; A column to spare, as a terminal can wrap a line
                          ;; that fills the last one.
                          (eldoc-string styles pieces (dec (.getWidth (.getTerminal reader)))))))
          (command-styled styles commands buffer
                          (styled styles lexed (if reading?
                                                 (delimiter-marks (lexer/delimiter-match lexed cursor))
                                                 {}))))))
    (setErrorPattern [_ _])
    (setErrorIndex [_ _])))

(defn- safely
  "f, returning nil rather than throwing: a lookup going wrong mustn't take
  the line being edited down with it."
  [f]
  (fn [& args]
    (try
      (apply f args)
      (catch Exception _ nil))))

(defn- description
  "What the completion menu shows next to a completion: its :type, and its
  :ns unless the :candidate names it already, as with an alias, the way
  CIDER does."
  [{:keys [candidate type ns]}]
  (not-empty (str/join ", " (remove nil? [type (when-not (re-find #"./" candidate) ns)]))))

(defn- completer
  "Completes the word at the cursor with what (complete prefix) returns:
  maps with the :candidate and maybe its :type and :ns."
  [complete]
  (reify Completer
    (complete [_ _ line candidates]
      (let [prefix (.word ^ParsedLine line)]
        ;; Everything would be too much.
        (when (seq prefix)
          (doseq [{:keys [candidate] :as completion} (complete prefix)]
            (.add ^List candidates (Candidate. candidate candidate nil (description completion) nil nil false))))))))

(defn- puts [^Terminal terminal capability]
  (.puts terminal capability (object-array 0)))

(defn- handed-over
  "Calls f with the terminal let go of, for another program to have it, like
  an editor, and takes it back after, turning back on the modes JLine reads
  in, which an editor turns off as it exits, when JLine's reading. Output
  waits till then. It takes no lock, for widgets, which run with JLine's
  held, see suspended. A terminal on streams of its own, as in tests, is no
  other program's to take."
  [{:keys [^Terminal terminal ^LineReader reader suspended? system?]} f]
  (reset! suspended? true)
  (when system?
    (.pause terminal true))
  (try
    (f)
    (finally
      (when system?
        (.resume terminal)
        (when (.isReading reader)
          (puts terminal InfoCmp$Capability/keypad_xmit)
          (when (.isSet reader LineReader$Option/BRACKETED_PASTE)
            (doto (.writer terminal)
              (.write "\u001b[?2004h")
              (.flush)))))
      (reset! suspended? false))))

(defn- widget ^Widget [f]
  (reify Widget
    (apply [_] (boolean (f)))))

(defn- leaving-under
  "Has the widgets that show something under the input and leave it there
  till the line's done, like C-d's list of completions at its end, say
  what they leave in under, so it goes once the input changes, for eldoc's
  line to come back. JLine's tail tip used to clear them."
  [^LineReader reader {:keys [left]}]
  (doseq [name [LineReader/LIST_CHOICES LineReader/DELETE_CHAR_OR_LIST LineReader/WHAT_CURSOR_POSITION]
          :let [^Widget builtin (.get (.getBuiltinWidgets reader) name)]]
    (.put (.getWidgets reader) name
          (widget #(let [done (.apply builtin)]
                     (reset! left [(.get ^Field @post reader) (str (.getBuffer reader))])
                     done)))))

(defn- bind-widget
  "Adds f as a widget called name, on keys in the Emacs and vi insert keymaps."
  [^LineReader reader name keys f]
  (.put (.getWidgets reader) name (widget f))
  (doseq [keymap [LineReader/EMACS LineReader/VIINS]]
    (.bind ^KeyMap (.get (.getKeyMaps reader) keymap) (Reference. name) ^CharSequence keys)))

(defn- terminal
  "The system terminal, or one on input and output for tests."
  ^Terminal [{:keys [input output width type] :or {type "xterm-256color"}}]
  (if input
    (-> (TerminalBuilder/builder)
        (.system false)
        ;; The other providers wrap the streams in a pty.
        (.provider "exec")
        (.streams input output)
        ;; Not the platform's, which on Windows can't take box drawing.
        ;; JLine takes stdout.encoding over encoding, so all of them.
        (.encoding StandardCharsets/UTF_8)
        (.stdinEncoding StandardCharsets/UTF_8)
        (.stdoutEncoding StandardCharsets/UTF_8)
        (.type ^String type)
        (.size (Size. (int width) 24))
        (.build))
    (-> (TerminalBuilder/builder)
        (.system true)
        ;; Without a terminal, a dumb one rather than a warning.
        (.dumb true)
        (.build))))

(defn- maybe-terminal?
  "False when there's clearly no terminal to edit on, which is quicker to tell
  than having JLine find out. Since Java 22 there's always a console, so it's
  only quick before that."
  []
  (boolean (and (System/console)
                (not= "dumb" (System/getenv "TERM")))))

(defn open
  "A line editor on the terminal, or nil when there's no terminal worth
  editing on: when standard input or output isn't one, or it's a dumb one, as
  in Emacs' M-x shell. opts:

    :history-file  where to keep the history
    :color?        false for no colors
    :incomplete?   (incomplete? text) for whether Enter goes on to another
                   line (lexer/incomplete? by default)
    :complete      (complete prefix) for the completions of prefix, as
                   features/complete has them
    :eldoc         (eldoc text cursor refresh) for what to show under
                   the input while typing, like the arglists of the
                   function being called, as render/eldoc-line's pieces,
                   calling (refresh) when that changes, from any thread
    :eldoc?        false to show nothing there after all
    :doc           (doc text cursor) for the documentation C-x C-d shows
    :source        (source text cursor) for the code C-x C-s shows
    :apropos       (apropos text cursor) for what C-x C-a shows
    :commands      the names of the REPL's commands, a set, to highlight
    :edit          (edit text) for what C-x C-e leaves in place of the input,
                   as {:text text}, or {:message message} saying why it
                   leaves it be
    :inputrc       readline's settings, a file JLine reads the way readline
                   would, $if neorepl taking in what's for neorepl alone
    :input, :output, :width and :type
                   a terminal on these streams instead, for tests"
  [{:keys [history-file color? incomplete? complete eldoc eldoc? commands edit inputrc input]
    :or {color? true incomplete? lexer/incomplete? eldoc? true}
    :as opts}]
  (when (or input (maybe-terminal?))
    (let [terminal (terminal opts)]
      (if (str/starts-with? (.getType terminal) "dumb")
        (do (.close terminal)
            nil)
        (let [raw? (atom false)
              styles (if color? colors {})
              shown (atom nil)
              under {:shown shown
                     :supplier (reify Supplier
                                 (get [_] @shown))
                     :left (atom nil)}
              builder (-> (LineReaderBuilder/builder)
                          (.terminal terminal)
                          (.appName "neorepl")
                          (.parser (parser raw? incomplete?))
                          (.highlighter (highlighter styles raw? (when eldoc? (some-> eldoc safely)) commands under))
                          (.variable LineReader/SECONDARY_PROMPT_PATTERN "%P #_=> ")
                          ;; The highlighter shows the pair, rather than JLine
                          ;; moving the cursor to it after a closer is typed.
                          (.variable LineReader/BLINK_MATCHING_PAREN 0)
                          ;; ! is just a character in Clojure code.
                          (.option LineReader$Option/DISABLE_EVENT_EXPANSION true))]
          (when-not color?
            ;; The completion menu's. JLine takes a style that comes out
            ;; as none for no style at all, and uses its own instead.
            (doseq [style [LineReader/COMPLETION_STYLE_STARTING LineReader/COMPLETION_STYLE_DESCRIPTION
                           LineReader/COMPLETION_STYLE_GROUP]]
              (.variable builder style "fg:default")))
          (when history-file
            (.variable builder LineReader/HISTORY_FILE (str history-file)))
          (when inputrc
            (.variable builder LineReader/INPUT_RC_FILE_NAME (str inputrc)))
          (when complete
            (.completer builder (completer (safely complete))))
          (let [reader (.build builder)
                editor {:terminal terminal
                        :reader reader
                        :raw? raw?
                        :styles styles
                        :lock (Object.)
                        :suspended? (atom false)
                        ;; What's been printed of the line the cursor's on.
                        :line (atom "")
                        :above (atom (AttributedString. ""))
                        ;; A token for the line waiting there.
                        :waiting (atom nil)
                        :system? (nil? input)}]
            ;; Enter in an unfinished form goes on to a line indented for it,
            ;; rather than to JLine's, which starts at the margin.
            (.put (.getWidgets reader) LineReader/ACCEPT_LINE
                  (widget #(let [buffer (.getBuffer reader)
                                 text (str buffer)]
                             (if (and (not @raw?) (incomplete? text))
                               (.write buffer ^CharSequence (apply str "\n" (repeat (lexer/indentation (subs text 0 (.cursor buffer))) \space)))
                               ;; JLine's own.
                               (.callWidget reader (str "." LineReader/ACCEPT_LINE)))
                             true)))
            (when (and eldoc eldoc?)
              (leaving-under reader under))
            ;; bash's and zsh's key for editing what's typed in EDITOR.
            (when edit
              (bind-widget reader "neorepl-edit" (str (KeyMap/ctrl \X) (KeyMap/ctrl \E))
                           #(let [buffer (.getBuffer reader)
                                  {:keys [text message]} (handed-over editor (partial edit (str buffer)))]
                              (if text
                                (do (.clear buffer)
                                    (.write buffer ^CharSequence text))
                                (.printAbove reader (AttributedString. ^CharSequence message)))
                              true)))
            ;; rebel-readline's keys, which babashka's REPL has too.
            (doseq [[k key code?] [[:doc \D] [:source \S true] [:apropos \A]]
                    :let [f (some-> (k opts) safely)]
                    :when f]
              (bind-widget reader (str "neorepl-" (name k)) (str (KeyMap/ctrl \X) (KeyMap/ctrl key))
                           #(let [buffer (.getBuffer reader)]
                              (when-let [text (f (str buffer) (.cursor buffer))]
                                ;; Straight to JLine: widgets run with its lock held,
                                ;; and print-text would take the editor's on top.
                                (.printAbove reader ^AttributedString (if code?
                                                                        (highlight styles text)
                                                                        (AttributedString. ^CharSequence text))))
                              true)))
            editor))))))

(defn close [{:keys [^Terminal terminal]}]
  (.close terminal))

(defn suspended
  "Calls f with the terminal let go of, as handed-over does, holding the
  editor's lock, which a widget mustn't take: a thread printing above the
  prompt can hold it while it waits for JLine's."
  [{:keys [lock] :as editor} f]
  (locking lock
    (handed-over editor f)))

(defn quietly
  "Calls f with what's typed meanwhile not echoed, so that what's typed
  ahead of the next prompt shows up there only, rather than in the output
  too."
  [{:keys [^Terminal terminal]} f]
  (let [attributes (.getAttributes terminal)]
    (.setAttributes terminal (doto (Attributes. attributes)
                               (.setLocalFlag Attributes$LocalFlag/ECHO false)))
    (try
      (f)
      (finally
        (.setAttributes terminal attributes)))))

(defn width
  "The terminal's width in columns."
  [{:keys [^Terminal terminal]}]
  (let [width (.getWidth terminal)]
    (if (pos? width) width 80)))

(defn height
  "The terminal's height in lines."
  [{:keys [^Terminal terminal]}]
  (let [height (.getHeight terminal)]
    (if (pos? height) height 24)))

(defn- print-above
  "Prints what's waiting to go above the prompt, ending its line, if it's
  the line that started waiting as token and the line's still being read."
  [{:keys [^LineReader reader lock above waiting]} token]
  (locking lock
    (when (and (identical? token @waiting) (.isReading reader) (pos? (.length ^AttributedString @above)))
      (.printAbove reader ^AttributedString @above)
      (reset! above (AttributedString. "")))))

(defn- emit
  "Prints s right away, or above the prompt while a line is being read.
  Printing above the prompt ends the text with a newline, so that only gets
  whole lines, and the rest of a line waits for the newline ending it, or a
  quarter of a second, since it might be a question or dots showing
  progress, and gets a line of its own then."
  [{:keys [^Terminal terminal ^LineReader reader lock line above waiting] :as editor} ^AttributedString s]
  (locking lock
    (let [waiting? (pos? (.length ^AttributedString @above))
          ^AttributedString s (if waiting?
                                (.toAttributedString (doto (AttributedStringBuilder.)
                                                       (.append ^AttributedString @above)
                                                       (.append s)))
                                s)
          text (str s)]
      (if (.isReading reader)
        (let [end (str/last-index-of text "\n")]
          (if end
            (do (.printAbove reader (.subSequence s 0 (int end)))
                (reset! above (.subSequence s (int (inc end)) (count text))))
            (reset! above s))
          ;; A line that starts waiting gets its quarter of a second.
          (when (and (pos? (.length ^AttributedString @above)) (or end (not waiting?)))
            (let [token (reset! waiting (Object.))]
              (future
                (Thread/sleep 250)
                (print-above editor token)))))
        (do (reset! above (AttributedString. ""))
            (when (seq text)
              (.print s terminal)
              (.flush terminal)
              (swap! line #(if-let [end (str/last-index-of text "\n")]
                             (subs text (inc end))
                             (str % text)))))))))

(defn print-text
  "Prints text in the style k names, such as :err, or nil for none."
  [{:keys [styles] :as editor} k text]
  (emit editor (AttributedString. ^CharSequence text (style styles k))))

(defn print-code
  "Prints code highlighted."
  [{:keys [styles] :as editor} code]
  (emit editor (highlight styles code)))

(defn fresh-line
  "Ends the line output left unfinished, if any."
  [{:keys [line] :as editor}]
  (when (seq @line)
    (print-text editor nil "\n")))

(defn on-interrupt
  "Calls f on Ctrl-C, unless a line is being read: then Ctrl-C clears it."
  [{:keys [^Terminal terminal suspended?] :as editor} f]
  (.handle terminal Terminal$Signal/INT (reify Terminal$SignalHandler
                                          (handle [_ _]
                                            ;; Ends the line the terminal echoed ^C on.
                                            (cond
                                              ;; The program that has the terminal has the lock
                                              ;; too, but isn't using it, or the ^C would be its own.
                                              @suspended? (doto (.writer terminal) (.write "\n") (.flush))
                                              ;; Unless it didn't, with echo off, as quietly has it.
                                              (not (.getLocalFlag (.getAttributes terminal) Attributes$LocalFlag/ECHO))
                                              (fresh-line editor)
                                              :else (print-text editor nil "\n"))
                                            (f)))))

(defn- read-with
  "Reads a line with prompt. JLine always ends that line."
  [{:keys [^Terminal terminal ^LineReader reader line lock] :as editor} prompt]
  (try
    (.readLine reader ^String prompt)
    (catch UserInterruptException e
      ;; On babashka, Ctrl-C during a read arrives as a signal and as a
      ;; character too, and the character would interrupt the next read.
      (let [input (.reader terminal)]
        (when (= 3 (.peek input 20))
          (.read input 20)))
      (throw e))
    (finally
      (locking lock
        (reset! line "")
        ;; What's still waiting to go above the prompt goes after it now,
        ;; rather than at the start of what's printed next.
        (emit editor (AttributedString. ""))))))

(defn read-code
  "Reads code from the user with prompt, on a line of its own. Enter only
  submits it once its forms are complete, and Ctrl-C starts over. Returns
  nil at the end of input, which is Ctrl-D on an empty line."
  [editor prompt]
  (loop []
    (fresh-line editor)
    (let [line (try
                 (read-with editor prompt)
                 (catch UserInterruptException _ ::interrupted)
                 (catch EndOfFileException _ nil))]
      (if (= ::interrupted line)
        (recur)
        line))))

(defn read-line
  "Reads a line as typed, for code reading *in*, leaving it out of the
  history. Returns nil at the end of input and ::interrupted on Ctrl-C."
  [{:keys [^LineReader reader raw? line] :as editor}]
  (reset! raw? true)
  (.setVariable reader LineReader/DISABLE_HISTORY true)
  (try
    ;; JLine redraws the line it reads, so whatever's been printed on it,
    ;; like a question the code asks, goes in the prompt.
    (read-with editor @line)
    (catch EndOfFileException _ nil)
    (catch UserInterruptException _ ::interrupted)
    (finally
      (.setVariable reader LineReader/DISABLE_HISTORY false)
      (reset! raw? false))))

(def ^:private keys-by-capability
  {:up InfoCmp$Capability/key_up
   :down InfoCmp$Capability/key_down
   :left InfoCmp$Capability/key_left
   :right InfoCmp$Capability/key_right
   :home InfoCmp$Capability/key_home
   :end InfoCmp$Capability/key_end
   :page-up InfoCmp$Capability/key_ppage
   :page-down InfoCmp$Capability/key_npage
   :backtab InfoCmp$Capability/key_btab})

(def ^:private key-sequences
  "What the keys send, for when the terminal's description leaves them out,
  in both the normal and the application cursor modes."
  {:up ["\u001b[A" "\u001bOA"]
   :down ["\u001b[B" "\u001bOB"]
   :right ["\u001b[C" "\u001bOC"]
   :left ["\u001b[D" "\u001bOD"]
   :home ["\u001b[H" "\u001bOH" "\u001b[1~"]
   :end ["\u001b[F" "\u001bOF" "\u001b[4~"]
   :page-up ["\u001b[5~"]
   :page-down ["\u001b[6~"]
   :backtab ["\u001b[Z"]
   :enter ["\r" "\n"]
   :backspace [(KeyMap/del) "\b"]
   :tab ["\t"]
   :escape ["\u001b"]
   :ctrl-c [(str (KeyMap/ctrl \C))]})

(defn- key-map
  "Reads keys as keywords, like :up and :enter, and the printable characters
  as one-character strings. Everything else is :other."
  ^KeyMap [terminal]
  (let [keymap (KeyMap.)]
    (doseq [c (range 32 127)]
      (.bind keymap (str (char c)) ^CharSequence (str (char c))))
    (doseq [[k sequences] key-sequences
            sequence sequences]
      (.bind keymap k ^CharSequence sequence))
    (doseq [[k capability] keys-by-capability
            :let [sequence (KeyMap/key terminal capability)]
            :when (seq sequence)]
      (.bind keymap k ^CharSequence sequence))
    (.setNomatch keymap :other)
    ;; How long a lone Esc waits to be sure it's not the start of a key.
    (.setAmbiguousTimeout keymap 100)
    keymap))

(defn- screen-line
  "segments as an AttributedString cut at width columns."
  [styles width segments]
  (let [sb (AttributedStringBuilder.)]
    (doseq [{k :style :keys [text code? selected?]} segments]
      (cond
        ;; Even without colors.
        selected? (.styled sb (.inverse AttributedStyle/DEFAULT) ^CharSequence text)
        code? (.append sb (highlight styles text))
        :else (.styled sb (style styles k) ^CharSequence text)))
    (.columnSubSequence (.toAttributedString sb) 0 width)))

(defn full-screen
  "Takes the whole terminal for a view, the way less does, until (handle key)
  returns :done. (draw width height) says what the view shows: up to height
  lines of segments, maps with the :text and its :style, as for print-text,
  or with :code? to highlight it as code, or :selected? to show it reversed.
  handle gets each key typed, as one of :up, :down, :left,
  :right, :home, :end, :page-up, :page-down, :tab, :backtab, :enter,
  :backspace, :escape, :ctrl-c, a printable character as a string or
  :other. Output waits till the view's done."
  [{:keys [^Terminal terminal styles lock] :as editor} draw handle]
  (locking lock
    (let [attributes (.enterRawMode terminal)
          display (Display. terminal true)
          drawing (Object.)
          ;; Display takes the cursor to be at the top left of a blank
          ;; screen, which a resize, or entering the alternate screen, doesn't
          ;; leave it.
          clear? (atom true)
          redraw #(locking drawing
                    (let [width (width editor)
                          height (max 1 (.getHeight terminal))]
                      (when (compare-and-set! clear? true false)
                        (.clear display))
                      (.resize display height width)
                      (.update display
                               ^List (mapv (partial screen-line styles width) (take height (draw width height)))
                               0
                               true)))
          previous (.handle terminal Terminal$Signal/WINCH (reify Terminal$SignalHandler
                                                              (handle [_ _]
                                                                (reset! clear? true)
                                                                (redraw))))]
      (try
        ;; Ctrl-C is a key like the others here.
        (.setAttributes terminal (doto (Attributes. (.getAttributes terminal))
                                   (.setLocalFlag Attributes$LocalFlag/ISIG false)))
        (puts terminal InfoCmp$Capability/enter_ca_mode)
        (puts terminal InfoCmp$Capability/keypad_xmit)
        (puts terminal InfoCmp$Capability/cursor_invisible)
        (let [reader (BindingReader. (.reader terminal))
              keymap (key-map terminal)]
          (loop []
            (redraw)
            (let [key (.readBinding reader keymap)]
              ;; Nil when the input ends.
              (when-not (or (nil? key) (= :done (handle key)))
                (recur)))))
        (finally
          (puts terminal InfoCmp$Capability/cursor_normal)
          (puts terminal InfoCmp$Capability/keypad_local)
          (puts terminal InfoCmp$Capability/exit_ca_mode)
          (.flush terminal)
          (.setAttributes terminal attributes)
          (.handle terminal Terminal$Signal/WINCH previous))))))
