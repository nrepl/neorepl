(ns neorepl.evaluation
  "Evaluating code on the server: following the responses, answering reads
  from *in*, interrupting when needed and summing up what came back. Front
  ends decide how to show it."
  (:require [clojure.string :as str]
            [neorepl.lexer :as lexer]
            [neorepl.nrepl :as nrepl]
            [neorepl.shutdown :as shutdown])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:private failure-statuses
  "The statuses that make an evaluation fail, the most telling first."
  ["disconnected" "interrupted" "eval-error" "error"])

(defn- add-response
  "Adds response to acc, keeping only the size of its output unless collect?"
  [acc {:keys [value out err ns ex root-ex status]} collect?]
  (cond-> (update acc :statuses into status)
    (and collect? value) (update :values conj value)
    (and collect? (seq out)) (update :out conj out)
    (and collect? (seq err)) (update :err conj err)
    out (update :out-size + (count out))
    err (update :err-size + (count err))
    ns (assoc :ns ns)
    (and ex (not (:ex acc))) (assoc :ex ex :root-ex root-ex)))

(defn- cut
  "s cut down to n characters, short of splitting a surrogate pair."
  [^String s n]
  (cond
    (<= (count s) n) s
    (and (pos? n) (Character/isHighSurrogate (.charAt s (dec n)))) (subs s 0 (dec n))
    :else (subs s 0 n)))

(defn- fit
  "Cuts response's value at max-output characters and its out and err to what
  acc leaves of max-output for them."
  [{:keys [out-size err-size]} max-output response]
  (cond-> response
    (:value response) (update :value cut max-output)
    (:out response) (update :out cut (- max-output out-size))
    (:err response) (update :err cut (- max-output err-size))))

(defn- result
  "What an evaluation came to, given its accumulated responses and, when
  neorepl stopped waiting for it, why: it ran out of time, the :timeout, with
  whether the interrupt worked, or it got :stopped."
  [{:keys [values out err ns ex root-ex statuses truncated?]} {:keys [timeout interrupted? stopped]}]
  (let [status (cond
                 timeout "timeout"
                 stopped "stopped"
                 :else (or (some statuses failure-statuses) "ok"))
        message (case status
                  "timeout" (str "Timed out after " timeout "s"
                                 (if interrupted?
                                   " and interrupted the evaluation"
                                   ", and interrupting the evaluation failed"))
                  "stopped" "Stopped waiting for the evaluation, which may still be running"
                  "interrupted" "The evaluation was interrupted"
                  "disconnected" "Lost the connection to the nREPL server"
                  "error" (str "The server reported " (str/join ", " (sort (disj statuses "done" "error"))))
                  nil)]
    (cond-> {:status status :values values :out (str/join out) :err (str/join err) :ns ns}
      truncated? (assoc :truncated true)
      ex (assoc :ex ex :root-ex root-ex)
      message (assoc :message message))))

(defn- send-stdin
  "Answers need-input with line, or end of file when it's nil."
  [client session line]
  (nrepl/send! client {:op "stdin" :session session :stdin (if line (str line "\n") "")}
               (fn [_])))

(defn- interrupt!
  "Interrupts the evaluation with message id, sent at the time sent-at, whose
  responses go to the queue responses, giving the server a second to
  confirm. Returns whether it did, or found it done already. A server can
  find the session idle while the evaluation runs elsewhere, as
  shadow-cljs's ClojureScript ones do, but one that's done has sent done
  before saying so. It can also find it idle right after the message went
  out, before the session's thread has taken the evaluation from its queue,
  so neorepl asks again for half a second after sending."
  [client session id sent-at responses]
  (loop []
    (case (try
            (nrepl/interrupt client session id {:timeout-ms 1000})
            (catch Exception _ :error))
      :interrupted true
      :idle (cond
              (some nrepl/done? responses) true
              (< (- (System/currentTimeMillis) sent-at) 500) (do (Thread/sleep 50)
                                                                 (recur))
              :else false)
      false)))

(defn- after
  "Where the reader is after text, read from where it was, at the :line and
  :column of at."
  [{:keys [line column] :as at} text]
  (let [[lines columns] (lexer/position text (count text))]
    (assoc at
           :line (+ line lines -1)
           :column (if (= 1 lines) (+ column columns -1) columns))))

(defn- messages
  "msg as the messages to send for it: an eval of several top-level forms
  as an eval for each, with the :line and :column the form starts at, so
  that what an error says and the definitions' metadata stay the same.
  Anything else, and code whose delimiters don't balance, for the server to
  report, as msg alone."
  [{:keys [op code line column] :or {line 1 column 1} :as msg}]
  (let [lexed (when (= "eval" op) (lexer/lex code))
        spans (some-> lexed lexer/form-spans)]
    (if (and (next spans) (not (lexer/delimiter-error lexed)))
      (->> spans
           (reductions (fn [{:keys [end] :as at} [start form-end]]
                         (assoc (after at (subs code end start)) :start start :end form-end))
                       {:line line :column column :end 0})
           rest
           (mapv (fn [{:keys [start end line column]}]
                   (assoc msg :code (subs code start end) :line line :column column))))
      [msg])))

(def ^:private stopping-statuses
  "The statuses after which the forms left don't go out: an eval-error is
  only the form's, as the server sees it too."
  #{"interrupted" "disconnected" "error"})

(defn evaluate
  "Sends msg, an eval or load-file, and returns its result: a map with the
  :status (ok, eval-error, interrupted, disconnected, error, timeout or
  stopped), the printed :values, the :out and :err output and the session's
  :ns after it. Failed evaluations add the :ex and :root-ex classes, and a
  :message unless :err already tells.

  The top-level forms of an eval go out one at a time, the next once the
  one before it is done, since a server goes on to the forms after one it
  interrupts. Interrupting, running out of time or neorepl getting killed
  interrupts the one that's out and stops there. Options:

    :on-send      called with the message id once each message is out, and
                  a function that interrupts the evaluation, for Ctrl-C: it
                  asks the server to, or, once the server's said it can't,
                  stops waiting for the evaluation
    :on-interrupt-failed
                  called when the server can't interrupt the evaluation
    :on-response  called with each response as it arrives
    :read-line    called when the code reads *in*, for the next line, nil
                  at end of input or :interrupted to leave the read
                  unanswered, since the evaluation's being interrupted,
                  until the interrupt fails (read-line by default)
    :timeout      seconds after which to interrupt the evaluation
    :max-output   cuts each value at that many characters, and stdout and
                  stderr too, marking the result :truncated
    :collect?     false to leave :values, :out and :err empty, for callers
                  that print the responses as they come (true by default)

  Responses that come after it stops waiting, on a timeout or when told to,
  go where the client's unclaimed responses go."
  [client {:keys [session] :as msg} {:keys [on-send on-interrupt-failed on-response read-line timeout max-output collect?]
                                     :or {on-send (fn [_ _])
                                          on-interrupt-failed (fn [])
                                          on-response (fn [_])
                                          read-line clojure.core/read-line
                                          collect? true}}]
  (let [[msg & more] (messages msg)
        deadline (when timeout (+ (System/currentTimeMillis) (long (* 1000 timeout))))
        responses (LinkedBlockingQueue.)
        given-up (promise)
        ;; Under the lock: what interrupts the message that's out, and
        ;; whether the ones after it are to stay unsent.
        lock (Object.)
        out (atom (constantly true))
        halted? (atom false)
        halt! #(locking lock
                 (reset! halted? true)
                 @out)
        finished (promise)
        ;; Otherwise the evaluation keeps the session busy for later calls.
        ;; In place before the message goes out, since neorepl can get
        ;; killed right after.
        cleanup (shutdown/add! #(do ((halt!))
                                    ;; Its server may stop next, and shouldn't
                                    ;; while it's still answering.
                                    (deref finished 1000 nil)))]
    (when-not cleanup
      ;; neorepl's exiting, so nothing more goes out: this waits for the
      ;; JVM to halt.
      @(promise))
    (try
      (let [interrupt-failed (promise)
            interrupt #(if (realized? interrupt-failed)
                         (.put responses ::stop)
                         (let [try-interrupt (halt!)]
                           ;; Not on the thread that delivers signals and keystrokes.
                           (future
                             (when-not (try-interrupt)
                               (.put responses ::interrupt-failed)))))
            ;; Sends msg, unless halted, and says so.
            send-msg (fn [msg]
                       (when-let [id (locking lock
                                       (when-not @halted?
                                         ;; The server cuts values itself,
                                         ;; sparing us an endless (range), but
                                         ;; not every server has the print
                                         ;; middleware.
                                         (let [id (nrepl/send! client (cond-> msg max-output (assoc :nrepl.middleware.print/quota max-output))
                                                               #(if (realized? given-up)
                                                                  (nrepl/unclaimed client %)
                                                                  (.put responses %)))]
                                           (reset! out (partial interrupt! client session id (System/currentTimeMillis) responses))
                                           id)))]
                         (on-send id interrupt)
                         true))
            give-up #(do (deliver given-up true)
                         (result %1 %2))
            declined-read (volatile! false)
            answer-read #(let [line (read-line)]
                           (vreset! declined-read (= :interrupted line))
                           (when-not @declined-read
                             (send-stdin client session line)))
            interrupted #(result (update % :statuses conj "interrupted") nil)
            acc {:values [] :out [] :err [] :out-size 0 :err-size 0 :statuses #{}}]
        (if-not (send-msg msg)
          (interrupted acc)
          (loop [more more
                 acc acc]
            (let [response (if deadline
                             (.poll responses (- deadline (System/currentTimeMillis)) TimeUnit/MILLISECONDS)
                             (.take responses))]
              (cond
                (nil? response)
                (give-up acc {:timeout timeout :interrupted? ((halt!))})

                (= ::stop response)
                (give-up acc {:stopped true})

                (= ::interrupt-failed response)
                (do (when-not (realized? interrupt-failed)
                      (deliver interrupt-failed true)
                      (on-interrupt-failed)
                      ;; The evaluation's still waiting for that line.
                      (when @declined-read
                        (answer-read)))
                    (recur more acc))

                :else
                (let [fitted (cond->> response max-output (fit acc max-output))
                      truncated? (and max-output
                                      (or (not= fitted response)
                                          (nrepl/status? response "nrepl.middleware.print/truncated")))
                      acc (cond-> (add-response acc fitted collect?) truncated? (assoc :truncated? true))]
                  (on-response fitted)
                  (when (nrepl/status? response "need-input")
                    (answer-read))
                  (if-not (nrepl/done? response)
                    (recur more acc)
                    (do
                      ;; There's nothing left to interrupt.
                      (locking lock
                        (reset! out (constantly true)))
                      (cond
                        (or (empty? more) (some stopping-statuses (:statuses acc))) (result acc nil)
                        (and deadline (<= deadline (System/currentTimeMillis))) (give-up acc {:timeout timeout :interrupted? true})
                        ;; Halted by an interrupt that found nothing to interrupt.
                        (not (send-msg (first more))) (interrupted acc)
                        :else (recur (rest more) acc))))))))))
      (finally
        (deliver finished true)
        (shutdown/remove! cleanup)))))

(defn text-printer
  "A response handler printing output to stdout and stderr, and each value on
  a line of its own. Called without a response, once a REPL has read the
  code after its prompt, it lets the next value start right there, as in
  clojure.main's REPL."
  []
  (let [fresh-line? (volatile! true)]
    (fn
      ([]
       (vreset! fresh-line? true))
      ([{:keys [out err value]}]
       (when (seq out)
         (print out)
         (flush)
         (vreset! fresh-line? (str/ends-with? out "\n")))
       (when err
         (binding [*out* *err*]
           (print err)
           (flush)))
       (when value
         (when-not @fresh-line? (newline))
         (println value)
         (vreset! fresh-line? true))))))
