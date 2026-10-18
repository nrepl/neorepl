(ns neorepl.editor
  "The REPL's line editor, on JLine. This is the only namespace that touches
  JLine, so when its API moves again, the damage stays here."
  (:refer-clojure :exclude [read-line])
  (:require [clojure.string :as str]
            [neorepl.lexer :as lexer])
  (:import (java.nio.charset StandardCharsets)
           (java.util List)
           #?@(:bb []
               :clj [(org.jline.reader CompletingParsedLine)])
           (org.jline.keymap BindingReader KeyMap)
           (org.jline.reader Candidate Completer EOFError EndOfFileException Highlighter
                             LineReader LineReader$Option LineReader$SuggestionType
                             LineReaderBuilder ParsedLine Parser Parser$ParseContext Reference
                             UserInterruptException Widget)
           (org.jline.terminal Attributes Attributes$LocalFlag Size Terminal Terminal$Signal
                               Terminal$SignalHandler TerminalBuilder)
           (org.jline.utils AttributedString AttributedStringBuilder AttributedStyle Display
                            InfoCmp$Capability)))

(defn- fg ^AttributedStyle [color]
  (.foreground AttributedStyle/DEFAULT (int color)))

(def ^:private colors
  "Styles for lexer token types, for :err output and :notice messages, and
  for the :headings of full-screen views."
  (let [faint (.faint AttributedStyle/DEFAULT)
        green (fg AttributedStyle/GREEN)
        magenta (fg AttributedStyle/MAGENTA)
        yellow (fg AttributedStyle/YELLOW)]
    {:comment faint
     :discard faint
     :string green
     :open-string green
     :regex green
     :char green
     :keyword (fg AttributedStyle/CYAN)
     :number magenta
     :constant magenta
     :special (.bold (fg AttributedStyle/BLUE))
     :reader-macro yellow
     :macro-char yellow
     :err (fg AttributedStyle/RED)
     :notice faint
     :heading (.bold AttributedStyle/DEFAULT)}))

(def ^:private specials
  "Symbols highlighted as :special: the special forms and the defining and
  binding macros that read like them."
  #{"def" "defn" "defn-" "defmacro" "defmulti" "defmethod" "defprotocol" "defrecord"
    "deftype" "definterface" "defonce" "fn" "fn*" "let" "letfn" "loop" "recur" "if"
    "if-let" "if-some" "if-not" "when" "when-let" "when-some" "when-not" "do" "cond"
    "condp" "case" "ns" "quote" "try" "catch" "finally" "throw" "var" "new" "set!"
    "binding" "doseq" "dotimes" "for"})

(defn- style ^AttributedStyle [styles k]
  (styles k AttributedStyle/DEFAULT))

(defn- highlight
  "code as an AttributedString, with its tokens styled."
  ^AttributedString [styles ^String code]
  (let [sb (AttributedStringBuilder.)
        end (reduce (fn [pos {:keys [type start end]}]
                      (let [text (subs code start end)]
                        (.append sb (subs code pos start))
                        (.styled sb (style styles (if (and (= :symbol type) (specials text)) :special type))
                                 text)
                        end))
                    0
                    (lexer/tokenize code))]
    (.append sb (subs code end))
    (.toAttributedString sb)))

(defn- parsed-line
  "A ParsedLine for line whose word, the one completion completes, is what's
  before the cursor of the symbol or keyword it's in. On the JVM it's a
  CompletingParsedLine, without which JLine warns, but babashka doesn't have
  that interface."
  [^String line cursor]
  (let [start (or (:start (lexer/symbol-at (lexer/lex line) cursor)) cursor)
        word (subs line start cursor)]
    (reify #?(:bb ParsedLine :clj CompletingParsedLine)
      (word [_] word)
      (wordCursor [_] (count word))
      (wordIndex [_] 0)
      (words [_] [word])
      (line [_] line)
      (cursor [_] cursor)
      #?@(:bb []
          :clj [(escape [_ candidate _] candidate)
                (rawWordCursor [_] (count word))
                (rawWordLength [_] (count word))]))))

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

(defn- highlighter
  "Highlights code, and has (eldoc text cursor) say what to show after the
  cursor. JLine highlights on every redisplay, so eldoc sees every change to
  the line, whatever made it."
  [styles raw? eldoc]
  (reify Highlighter
    (highlight [_ reader buffer]
      (if @raw?
        (AttributedString. buffer)
        (do (when eldoc
              (let [cursor (.cursor (.getBuffer ^LineReader reader))]
                (.setTailTip ^LineReader reader
                             ;; JLine only shows it with the cursor at the end.
                             (or (when (= cursor (count buffer))
                                   (eldoc buffer cursor))
                                 ""))))
            (highlight styles buffer))))
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

(defn- completer
  "Completes the word at the cursor with what (complete prefix) returns:
  maps with the :candidate and maybe its :type."
  [complete]
  (reify Completer
    (complete [_ _ line candidates]
      (let [prefix (.word ^ParsedLine line)]
        ;; Everything would be too much.
        (when (seq prefix)
          (doseq [{:keys [candidate type]} (complete prefix)]
            (.add ^List candidates (Candidate. candidate candidate nil type nil nil false))))))))

(defn- widget ^Widget [f]
  (reify Widget
    (apply [_] (boolean (f)))))

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
    :eldoc         (eldoc text cursor) for the text to show after the
                   cursor while typing, like the arglists of the function
                   being called
    :eldoc?        false to show nothing there after all
    :doc           (doc text cursor) for the documentation C-x C-d shows
    :input, :output, :width and :type
                   a terminal on these streams instead, for tests"
  [{:keys [history-file color? incomplete? complete eldoc eldoc? doc input]
    :or {color? true incomplete? lexer/incomplete? eldoc? true}
    :as opts}]
  (when (or input (maybe-terminal?))
    (let [terminal (terminal opts)]
      (if (str/starts-with? (.getType terminal) "dumb")
        (do (.close terminal)
            nil)
        (let [raw? (atom false)
              styles (if color? colors {})
              builder (-> (LineReaderBuilder/builder)
                          (.terminal terminal)
                          (.appName "neorepl")
                          (.parser (parser raw? incomplete?))
                          (.highlighter (highlighter styles raw? (when eldoc? (some-> eldoc safely))))
                          (.variable LineReader/SECONDARY_PROMPT_PATTERN "%P #_=> ")
                          ;; ! is just a character in Clojure code.
                          (.option LineReader$Option/DISABLE_EVENT_EXPANSION true))]
          (when history-file
            (.variable builder LineReader/HISTORY_FILE (str history-file)))
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
                        :above (atom (AttributedString. ""))}]
            (when (and eldoc eldoc?)
              (.setAutosuggestion reader LineReader$SuggestionType/TAIL_TIP))
            (when doc
              (let [doc (safely doc)]
                (bind-widget reader "neorepl-doc" (str (KeyMap/ctrl \X) (KeyMap/ctrl \D))
                             #(let [buffer (.getBuffer reader)]
                                (when-let [text (doc (str buffer) (.cursor buffer))]
                                  ;; Straight to JLine: widgets run with its lock held,
                                  ;; and print-text would take the editor's on top.
                                  (.printAbove reader ^String text))
                                true))))
            editor))))))

(defn close [{:keys [^Terminal terminal]}]
  (.close terminal))

(defn suspended
  "Calls f with the terminal let go of, for another program to have it, like
  an editor, and takes it back after. Output waits till then."
  [{:keys [^Terminal terminal lock suspended?]} f]
  (locking lock
    (reset! suspended? true)
    (.pause terminal true)
    (try
      (f)
      (finally
        (.resume terminal)
        (reset! suspended? false)))))

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

(defn- emit
  "Prints s right away, or above the prompt while a line is being read.
  Printing above the prompt ends the text with a newline, so that only gets
  whole lines, and the rest of a line waits for the newline ending it."
  [{:keys [^Terminal terminal ^LineReader reader lock line above]} ^AttributedString s]
  (locking lock
    (let [^AttributedString s (if (pos? (.length ^AttributedString @above))
                                (.toAttributedString (doto (AttributedStringBuilder.)
                                                       (.append ^AttributedString @above)
                                                       (.append s)))
                                s)
          text (str s)]
      (if (.isReading reader)
        (if-let [end (str/last-index-of text "\n")]
          (do (.printAbove reader (.subSequence s 0 (int end)))
              (reset! above (.subSequence s (int (inc end)) (count text))))
          (reset! above s))
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
                                            (if @suspended?
                                              ;; The program that has the terminal has the lock
                                              ;; too, but isn't using it, or the ^C would be its own.
                                              (doto (.writer terminal) (.write "\n") (.flush))
                                              (print-text editor nil "\n"))
                                            (f)))))

(defn- read-with
  "Reads a line with prompt. JLine always ends that line."
  [{:keys [^Terminal terminal ^LineReader reader line]} prompt]
  ;; Whatever eldoc showed last time is stale.
  (.setTailTip reader "")
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
      (reset! line ""))))

(defn read-code
  "Reads code from the user with prompt. Enter only submits it once its forms
  are complete, and Ctrl-C starts over. Returns nil at the end of input, which
  is Ctrl-D on an empty line."
  [editor prompt]
  (loop []
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

(defn- puts [^Terminal terminal capability]
  (.puts terminal capability (object-array 0)))

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
