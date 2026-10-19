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
      (is (= "partialboom\nuser=> 3\n" (printed {:out "partial"} {:err "boom\n"} :prompt {:value "3"}))))))
