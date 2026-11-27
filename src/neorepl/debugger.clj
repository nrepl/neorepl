(ns neorepl.debugger
  "Stepping through code cider-nrepl's debugger instruments, in the REPL:
  what a breakpoint shows, and the commands it takes, as keys, the ones
  CIDER has for them, or lines without a terminal."
  (:require [clojure.string :as str]
            [neorepl.cli :as cli]
            [neorepl.features :as features]
            [neorepl.lexer :as lexer]
            [neorepl.nrepl :as nrepl]
            [neorepl.render :as render]))

(def commands
  "The commands a breakpoint takes, as [key name what-it-does], name being
  the server's, but for locals, which shows the ones the breakpoint came
  with."
  [["n" "next" "Go to the next step"]
   ["i" "in" "Step into the function called"]
   ["o" "out" "Step out of the form"]
   ["c" "continue" "Go on to the next breakpoint"]
   ["C" "continue-all" "Go on, past every breakpoint"]
   ["e" "eval" "Evaluate an expression with the locals"]
   ["j" "inject" "Have the form return an expression's value"]
   ["l" "locals" "Show the locals"]
   ["p" "inspect" "Inspect the value"]
   ["P" "inspect-prompt" "Inspect an expression's value"]
   ["s" "stacktrace" "Show the stack trace"]
   ["t" "trace" "Go on, printing each step"]
   ["q" "quit" "Stop the evaluation"]])

(def ^:private prompts
  "What the commands that take an expression ask for it with."
  {"eval" "eval> " "inject" "inject> " "inspect-prompt" "inspect> "})

(defn stop-name
  "What the breakpoint, a need-debug-input, is in: the function, or what's
  defined, qualified, or its namespace."
  [{:keys [code original-ns]}]
  (or (some->> (lexer/defined code) second (str original-ns "/")) original-ns))

(defn- untagged
  "code, with span, a map of the :start and :end of a part of it, without
  the #dbg tags in it, as ,break-at puts them in front of forms, as [code
  span], the span moved to the same part of what's left."
  [code span]
  (let [cuts (for [{:keys [type start end] :as token} (lexer/tokenize code)
                   :when (and (= :reader-macro type) (= "#dbg" (lexer/text code token)))]
               [start (cond-> end (= \space (get code end)) inc)])
        moved (fn [i] (- i (reduce + (for [[start end] cuts :when (<= end i)] (- end start)))))]
    [(reduce (fn [code [start end]] (str (subs code 0 start) (subs code end))) code (reverse cuts))
     (-> span (update :start moved) (update :end moved))]))

(defn stop-text
  "What a breakpoint, a need-debug-input, shows: what it's in and where,
  the line of the form it stopped at with the form marked, unless that's
  wider than width, and the form's value, or what it threw, for one #dbg!
  or #break! stop at. The line leaves out the #dbg tags in it, which
  ,break-at puts in front of the forms on its lines."
  [{:keys [code coor debug-value file line caught-msg] :as stop} width]
  (let [[start end] (some->> coor seq (lexer/coordinate-span code))
        at (+ (or line 1) -1 (first (lexer/position code (or start 0))))
        ;; A REPL's code has no file, which comes as an empty list.
        file (when (string? file) (not-empty file))]
    (str/join "\n" (concat [(str "● " (stop-name stop)
                                 (when file (str "  " (render/location-text {:file file :line at}))))]
                           (when start
                             (let [[shown span] (untagged code {:start start :end (min end (or (str/index-of code "\n" start) end))})]
                               (render/marked-lines shown span width)))
                           [(if caught-msg
                              (str "  ✗ " (some-> (re-find #"^#error\[(\S+)" (str debug-value)) second (str ": ")) caught-msg)
                              (str "  => " debug-value))]))))

(defn locals-text [locals]
  (if (seq locals)
    (cli/indented-columns locals)
    "  No locals here"))

(defn help-text
  "The keys a breakpoint takes, the ones available? says it does."
  [available?]
  (str (cli/indented-columns (for [[key name help] commands :when (available? name)]
                               [key help]))
       "\n  Enter repeats the last step, and ( starts an expression to evaluate. At a"
       "\n  form that threw, j returns a value in its place, and c throws it"))

(defn- available?
  "Whether the breakpoint stop takes the command called name: locals when
  it has them, the others when the server offers them."
  [{:keys [input-type locals]} name]
  (if (= "locals" name)
    (boolean (seq locals))
    (contains? (set input-type) name)))

(def ^:private steps
  "The commands Enter repeats."
  #{"next" "in" "out"})

(defn- command-key
  "The key k stands for: itself, or the key of the command an expression
  that's a command's name names, as typed without a terminal."
  [k]
  (or (when (vector? k)
        (some (fn [[key name]] (when (= (second k) name) key)) commands))
      k))

(defn breakpoint?
  "Whether response, what the debugger says, is a breakpoint asking for a
  command, rather than for an expression or saying something."
  [{:keys [input-type] :as response}]
  (and (nrepl/status? response "need-debug-input") (sequential? input-type)))

(defn let-go
  "Answers response, what the debugger says, as features/start-debugger
  has it, for session, when there's no one to ask: a breakpoint goes on,
  past the ones after it in what it's evaluating."
  [client session {:keys [input-type key] :as response}]
  (when (nrepl/status? response "need-debug-input")
    (features/debug-input client session key (if (sequential? input-type) ":continue-all" "nil"))))

(defn said
  "What the debugger says in response, as text: the error an expression it
  evaluated threw, or the stack trace asked for. Nil for anything else."
  [{:keys [causes] :as response}]
  (cond
    (nrepl/status? response "eval-error") (str/join "\n" (map #(str "  " (render/cause-text 0 %)) causes))
    (nrepl/status? response "stack") (render/error-text (features/normalize-causes causes))))

(defn handle
  "Answers what the debugger says in response, as features/start-debugger
  has it, for session, the debugger's: a breakpoint gets shown and the
  user's command sent, read with io's :read-key, given a prompt, which
  returns a key, :enter, :ctrl-c, [:expression text] or nil at the end of
  the input, and :read-form, given one and the text to start with, for an
  expression, or nil. io's :print-line prints a line, :echo, the command a
  key's for, where the key's typed, :inspect-lines shows the inspector's
  lines, defining vars in a namespace, and :width says how wide the
  terminal is. state, an atom, keeps the last command, the expression for
  the one that takes it, and whether the user quit the evaluation."
  [client session {:keys [input-type key inspect debug-value locals original-ns] :as response}
   {print :print-line :keys [read-key read-form echo inspect-lines width] :or {echo (fn [_])}} state]
  (let [send! (fn [name]
                (swap! state #(cond-> (assoc % :last name) (= "quit" name) (assoc :quit true)))
                (features/debug-input client session key (str ":" name)))
        ;; Asked for after its command, so that one the user cancels sends nothing.
        with-expression (fn [name text]
                          (swap! state assoc :expression text)
                          (send! name))]
    (cond
      (not (nrepl/status? response "need-debug-input")) (some-> (said response) print)

      ;; Asking for the expression its command went out with.
      (not (sequential? input-type))
      (features/debug-input client session key (or (:expression (first (swap-vals! state dissoc :expression))) "nil"))

      :else
      (do (when inspect
            (inspect-lines (features/inspector-lines inspect) original-ns))
          (case (:last @state)
            "eval" (print (str "  => " debug-value))
            ;; The same stop, after what was asked for.
            ("inspect" "inspect-prompt" "stacktrace") nil
            (print (stop-text response (width))))
          (loop []
            (let [k (command-key (read-key "debug> "))
                  [_ name] (some #(when (= k (first %)) %) commands)]
              (cond
                (nil? k) (send! "continue-all")
                (= :ctrl-c k) (send! (if (available? response "quit") "quit" "continue-all"))
                (= :enter k) (let [name (or (steps (:last @state)) "next")]
                               (echo name)
                               (send! name))
                (vector? k) (with-expression "eval" (second k))
                (= "(" k) (if-let [text (read-form (prompts "eval") "(")]
                            (with-expression "eval" text)
                            (recur))
                (= "?" k) (do (print (help-text #(available? response %)))
                              (recur))
                (not (and name (available? response name))) (do (echo "? lists the keys")
                                                                 (recur))
                (= "locals" name) (do (echo name)
                                      (print (locals-text locals))
                                      (recur))
                (prompts name) (do (echo name)
                                   (if-let [text (read-form (prompts name) nil)]
                                     (with-expression name text)
                                     (recur)))
                :else (do (echo name)
                          (send! name)))))))))
