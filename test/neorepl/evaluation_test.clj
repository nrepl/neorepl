(ns neorepl.evaluation-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [neorepl.evaluation :as evaluation]
            [neorepl.nrepl :as nrepl]
            [neorepl.test-server :as test-server :refer [*port* with-client]]))

(use-fixtures :once (test-server/fixture))

(deftest interrupt-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)]
      (testing "right after sending, before the session's thread has started the evaluation"
        (let [result (future (evaluation/evaluate client {:op "eval" :code "(Thread/sleep 30000)" :session session}
                                                  {:on-send (fn [_ interrupt] (interrupt))}))]
          (is (= "interrupted" (:status (deref result 10000 nil)))))))))

(deftest forms-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)
          evaluate #(evaluation/evaluate client (assoc %1 :session session) %2)
          values #(:values (evaluate {:op "eval" :code %} {}))]
      (testing "each top-level form goes out on its own, starting where it does"
        (is (= ["1" "#'user/defined-on-line-3"] (values "1 ; one\n\n  (defn defined-on-line-3 [] 2)")))
        (is (= ["[3 3]"] (values "((juxt :line :column) (meta #'defined-on-line-3))"))))
      (testing "with what the forms' prefixes take"
        (is (= ["3" "[1 2]"] (values "#_#_ 1 (/ 1 0) 3 ^:foo [1 2]")))
        (is (= ["{:foo true}"] (values "(meta *1)"))))
      (testing "the forms after one that throws still run, as they would on the server"
        (let [{:keys [status values]} (evaluate {:op "eval" :code "(/ 1 0) :after"} {})]
          (is (= "eval-error" status))
          (is (= [":after"] values))))
      (testing "but not after one that's interrupted"
        (values "(def ran (atom nil))")
        (is (= "timeout" (:status (evaluate {:op "eval" :code "(Thread/sleep 5000) (reset! ran :ran)"} {:timeout 0.2}))))
        (is (= ["nil"] (values "@ran")))
        (is (= "interrupted" (:status (evaluate {:op "eval" :code "(Thread/sleep 5000) (reset! ran :ran)"}
                                                {:on-send (fn [_ interrupt] (interrupt))}))))
        (is (= ["nil"] (values "@ran"))))
      (testing "code that doesn't balance goes out as it is, for the server to say so"
        (let [{:keys [status values]} (evaluate {:op "eval" :code "1 (+ 1"} {})]
          (is (= "eval-error" status))
          (is (= ["1"] values))))
      (testing "shadow-cljs's word for ClojureScript code that threw"
        (is (= "eval-error" (:status (evaluate {:op "eval" :code ":repl/exception!"} {}))))))))

(deftest many-forms-test
  (with-client [client {:port *port*}]
    (testing "a form at a time doesn't wait on the server's socket, where TCP_QUICKACK has it acknowledged"
      (let [started (System/currentTimeMillis)
            {:keys [values]} (evaluation/evaluate client {:op "eval" :code (str/join " " (range 100))
                                                          :session (nrepl/clone-session client)}
                                                  {})
            elapsed (- (System/currentTimeMillis) started)]
        (is (= 100 (count values)))
        (when @#'nrepl/quickack
          (is (< elapsed 2000)))))))

(deftest read-line-test
  (with-client [client {:port *port*}]
    (testing "a read from *in* that blocks doesn't outlast the timeout"
      (let [started (System/currentTimeMillis)
            {:keys [status]} (evaluation/evaluate client {:op "eval" :code "(read-line)" :session (nrepl/clone-session client)}
                                                  {:timeout 0.3 :read-line #(do (Thread/sleep 5000) "late")})]
        (is (= "timeout" status))
        (is (< (- (System/currentTimeMillis) started) 3000))))))

(deftest text-printer-test
  (let [printed (fn [& responses]
                  (let [print-response (evaluation/text-printer)]
                    ;; stdout and stderr on one terminal, with println's line
                    ;; endings as on Unix.
                    (-> (with-out-str
                          (binding [*err* *out*]
                            (doseq [response responses]
                              (if (= :prompt response)
                                (do (print "user=> ")
                                    (print-response))
                                (print-response response)))))
                        (str/replace "\r\n" "\n"))))]
    (testing "each value goes on a line of its own"
      (is (= "hi\nnil\n3\n" (printed {:out "hi\n"} {:value "nil"} {:value "3"})))
      (is (= "partial\n3\n" (printed {:out "partial"} {:value "3"}))))
    (testing "the value after a prompt goes right after it, whatever came before"
      (is (= "partialboom\nuser=> 3\n" (printed {:out "partial"} {:err "boom\n"} :prompt {:value "3"}))))
    (testing "a line stderr leaves open ends before what goes to stdout"
      (is (= "warn\n42\n" (printed {:err "warn"} {:value "42"})))
      (is (= "warn\nout" (printed {:err "warn"} {:out "out"})))
      (testing "and when the evaluation's over"
        (is (= "warn\n" (printed {:err "warn"} :end)))
        (is (= "warn\n" (printed {:err "warn\n"} :end)))))))
