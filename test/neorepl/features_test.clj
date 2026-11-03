(ns neorepl.features-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [neorepl.features :as features]
            [neorepl.nrepl :as nrepl]
            [neorepl.render :as render]
            [neorepl.test-server :as test-server :refer [*port* with-client]]
            [nrepl.middleware :as middleware]
            [nrepl.server :as server]
            [nrepl.transport :as transport]
            [orchard.namespace]))

(use-fixtures :once (test-server/fixture))

(def ^:private refresh-fails? (atom false))

(def ^:private refresh-lost?
  "What wrap-fake-cider's refresh can't find, of gone.ns, until refresh-clear:
  :gone, its own file, or :dependency, one it requires, or nothing."
  (atom nil))

(def ^:private source-file
  "The file wrap-fake-cider says from-a-file is in, on line 2."
  (atom nil))

(def ^:private test-requests
  "The test ops wrap-fake-cider got, and their var-queries."
  (atom []))

(defn- wrap-fake-cider
  "Answers info, complete and apropos the way cider-nrepl does, with canned
  results, to tell its rung of the ladder from the others."
  [handler]
  (fn [{:keys [op sym] :as msg}]
    (case op
      "info" (case sym
               "map" (transport/respond-to msg :status "done" :ns "clojure.core" :name "map"
                                           :arglists-str "[f]\n[f coll]" :doc "Cider's map."
                                           :file "file:/tmp/a%20b/core.clj" :line 1)
               "mapv" (transport/respond-to msg :status "done" :ns "clojure.core" :name "mapv"
                                            :file "jar:file:/x.jar!/clojure/core.clj" :line 2)
               "made-at-the-repl" (transport/respond-to msg :status "done" :ns "user" :name "made-at-the-repl"
                                                        :file "NO_SOURCE_PATH" :line 1)
               "if" (transport/respond-to msg :status "done" :name "if" :special-form "true"
                                          :forms-str "(if test then else?)")
               "broken" (transport/respond-to msg :status ["done" "info-error"])
               ;; As nbb answers for what it doesn't find, its nils as
               ;; empty lists.
               "not-in-nbb" (transport/respond-to msg :status "done" :ns nil :name nil :arglists-str "")
               "from-a-file" (transport/respond-to msg :status "done" :ns "user" :name "from-a-file"
                                                   :file (str (.toURI (io/file @source-file))) :line 2 :column 1)
               ;; Basilisp counts columns from 0.
               "from-column-0" (transport/respond-to msg :status "done" :ns "user" :name "from-a-file"
                                                     :file (str (.toURI (io/file @source-file))) :line 2 :column 0)
               ;; As for the macros ClojureScript takes from Clojure.
               "on-a-wrong-line" (transport/respond-to msg :status "done" :ns "user" :name "on-a-wrong-line"
                                                       :file (str (.toURI (io/file @source-file))) :line 5)
               ;; Java members come without a name, with their Javadoc in fragments,
               ;; as orchard makes them: the text with the whitespace it needs, the
               ;; HTML with a space between everything in it.
               "Thread/sleep" (transport/respond-to msg :status "done" :class "java.lang.Thread" :member "sleep"
                                                    :arglists-str "[millis]\n[millis nanos]"
                                                    :doc "Causes {@code this}."
                                                    :doc-fragments [{:type "text" :content "Causes the "}
                                                                    {:type "html" :content "<pre>Thread</pre> "}
                                                                    {:type "text" :content " to sleep.  Unlike "}
                                                                    {:type "html" :content "<i> wait </i>"}
                                                                    {:type "text" :content ", it keeps its\n"}
                                                                    {:type "html" :content "<pre>Map<K, V></pre> "}
                                                                    {:type "text" :content " "}
                                                                    {:type "html" :content "&amp;"}
                                                                    {:type "text" :content " monitors, as\n"}
                                                                    {:type "html" :content "<pre> \n the docs </pre>"}
                                                                    {:type "text" :content " say."}])
               "Character" (transport/respond-to msg :status "done" :class "java.lang.Character" :name "Character"
                                                 :doc-fragments
                                                 [{:type "text" :content "Characters:\n"}
                                                  {:type "html" :content (str "<ul> \n  <li> in <i> words </i> , like\n Fahrvergn &uuml; gen </li> \n"
                                                                              "  <li> and codes\n  <ul> <li> &#92; u0130 </li> </ul> </li> \n</ul>")}
                                                  {:type "text" :content "\n\nCases:\n"}
                                                  {:type "html" :content (str "<table> \n <caption style=\"display:none\"> Hidden </caption> \n"
                                                                              " <tr> <th> Lower </th> <th> Upper </th> </tr> \n"
                                                                              " <tr> <td> </td> <td> &#x130; </td> </tr> \n"
                                                                              " <tr> <td> i\n </td> <td> I </td> </tr> \n</table>")}
                                                  {:type "text" :content "\nIn code:\n"}
                                                  {:type "html" :content "<blockquote> <pre> \n     if (a  &lt;  b)\n       up(a);\n  </pre> </blockquote>"}
                                                  ;; As {@code} has it, inside a pre of its own.
                                                  {:type "text" :content "\nOr:\n"}
                                                  {:type "html" :content "<pre>   <pre>\n   Map<K, V> m = new HashMap<>();\n     m.clear();\n </pre>  </pre>"}])
               ;; As an orchard that joins its HTML without spaces and escapes code has it.
               "Object" (transport/respond-to msg :status "done" :class "java.lang.Object" :name "Object"
                                              :doc-fragments
                                              [{:type "text" :content "As of JDK"}
                                               {:type "html" :content "&nbsp;"}
                                               {:type "text" :content "1.1, "}
                                               {:type "html" :content "<CODE>a &amp; b</CODE><!-- a note -->"}
                                               {:type "text" :content " are:\n"}
                                               {:type "html" :content "<ul>\n<li><p>one &#x1F600;</p></li>\n<li><p>and <pre>List&lt;B&gt;</pre> </p></li>\n</ul>"}
                                               {:type "text" :content "\nLike so:\n"}
                                               {:type "html" :content "<pre>\n  a();\n  foo(<pre>bar</pre> );\n</pre>"}])
               ;; A namespace's names it twice, unlike a var named like its namespace.
               "clojure.string" (transport/respond-to msg :status "done" :ns "clojure.string" :name "clojure.string"
                                                      :doc "Strings." :line 1)
               "hello" (transport/respond-to msg :status "done" :ns "hello" :name "hello" :arglists-str "[]"
                                             :line 2 :column 1)
               ;; A Java field has no arglists.
               "Integer/MAX_VALUE" (transport/respond-to msg :status "done" :class "java.lang.Integer"
                                                         :member "MAX_VALUE" :arglists-str "nil" :doc "Biggest.")
               "String" (transport/respond-to msg :status "done" :class "java.lang.String" :name "String"
                                              :file "jar:file:/x/src.zip!/java/lang/String.java" :line 142
                                              :doc-fragments [{:type "text" :content "Strings."}])
               ;; Where the test that errs is.
               "neorepl.sample-suite/erring" (transport/respond-to msg :status "done" :ns "neorepl.sample-suite"
                                                                   :name "erring" :file "file:/src/neorepl/sample_suite.clj"
                                                                   :line 14)
               ;; Says which session it was asked in.
               "session" (transport/respond-to msg :status "done" :ns "user" :name "session"
                                               :doc (:session msg))
               (transport/respond-to msg :status ["done" "no-info"]))
      "complete" (transport/respond-to msg :status "done"
                                       :completions [{:candidate "map" :type "function" :ns "clojure.core"}])
      ;; Nothing for a var they don't know.
      "fn-refs" (transport/respond-to msg (cond-> {:status "done"}
                                            (= "greet" sym)
                                            (assoc :fn-refs [{:name "demo.core/welcome" :file "demo/core.clj"
                                                              :file-url "file:/src/demo/core.clj" :line 5 :column 1
                                                              :doc "(not documented)"}])))
      ;; One without its file's URL, from a REPL.
      "fn-deps" (transport/respond-to msg (cond-> {:status "done"}
                                            (= "welcome" sym)
                                            (assoc :fn-deps [{:name "demo.core/greet" :file "NO_SOURCE_PATH"
                                                              :line 3 :column 1}])))
      "apropos" (transport/respond-to msg :status "done"
                                      :apropos-matches [{:name "clojure.core/map" :type "function"
                                                         :doc "Cider's map.\nMore."}])
      "cider-version" (transport/respond-to msg :status "done" :cider-version {:version-string "0.63.1"})
      "macroexpand" (transport/respond-to msg :status "done"
                                          :expansion (str "Cider's " (:expander msg) (some->> (:session msg) (str " in "))))
      "ns-list" (transport/respond-to msg :status "done"
                                      :ns-list (if (:session msg)
                                                 ["clojure.core" "neorepl.features" "made.at.the.repl"
                                                  "nrepl.core" "cider.nrepl.middleware"]
                                                 ["clojure.core"]))
      "spec-list" (transport/respond-to msg :status "done" :spec-list [":cider/spec"])
      "spec-example" (if (= ":cider/spec" (:spec-name msg))
                       (transport/respond-to msg :status "done" :example "cider's example\n")
                       ;; As cider-nrepl fails without test.check.
                       (do (transport/respond-to msg :err "java.io.FileNotFoundException: Could not locate clojure/test/check/generators__init.class\n")
                           (transport/respond-to msg :status ["done" "spec-example-error"])))
      ;; With docs printed, as cider-nrepl has them.
      "ns-vars-with-meta" (transport/respond-to msg :status "done"
                                                :ns-vars-with-meta {:greet {:arglists "([who])" :doc "\"Says hi.\""}
                                                                    :state {}})
      ("test-var-query" "retest")
      (do (swap! test-requests conj (select-keys msg [:op :var-query :stream]))
          (when (= "true" (:stream msg))
            (transport/respond-to msg :test-event {:type "begin-ns" :ns "neorepl.sample-suite"}))
          (transport/respond-to msg :out "testing\n")
          (when (= "true" (:stream msg))
            (transport/respond-to msg :test-event {:type "end-ns" :ns "neorepl.sample-suite"
                                                   :elapsed-time {:ms 5 :humanized "Completed in 5 ms"}}))
          (transport/respond-to msg
                                :summary {:ns 1 :var 2 :test 3 :pass 1 :fail 1 :error 1}
                                :elapsed-time {:ms 5 :humanized "Completed in 5 ms"}
                                :results {"neorepl.sample-suite"
                                          {"passing" [{:type "pass" :index 0 :ns "neorepl.sample-suite"
                                                       :var "passing" :context [] :message ""}]
                                           "failing" [{:type "pass" :index 0 :ns "neorepl.sample-suite"
                                                       :var "failing" :context [] :message ""}
                                                      {:type "fail" :index 1 :ns "neorepl.sample-suite"
                                                       :var "failing" :context "with a context"
                                                       :message "adds up" :expected "3\n" :actual "2\n"
                                                       :file "sample_suite.clj" :line 12}]
                                           ;; With the file of the frame that threw, as cider-nrepl
                                           ;; gives it, and the test's line.
                                           "erring" [{:type "error" :index 0 :ns "neorepl.sample-suite"
                                                      :var "erring" :context [] :message ""
                                                      :expected "(= 1 (throw (ex-info \"boom\" {})))\n"
                                                      :error "clojure.lang.ExceptionInfo: boom {}"
                                                      :file "Numbers.java" :line 15}]}})
          (transport/respond-to msg :status "done"))
      "refresh-clear" (do (reset! refresh-lost? nil)
                          (transport/respond-to msg :status "done"))
      "refresh" (cond
                  @refresh-lost?
                  (do (transport/respond-to msg :err (str "Could not locate "
                                                          (if (= :gone @refresh-lost?) "gone/ns" "nope/core")
                                                          "__init.class on classpath.\n"))
                      (transport/respond-to msg :status "error" :error-ns "gone.ns")
                      (transport/respond-to msg :status "done"))

                  @refresh-fails?
                  ;; cider-nrepl prints the error, as the REPL would.
                  (do (transport/respond-to msg :reloading ["broken.ns"])
                      (transport/respond-to msg :err "Syntax error compiling at (broken/ns.clj:1:1).\n")
                      (transport/respond-to msg :status "error" :error-ns "broken.ns")
                      (transport/respond-to msg :status "done"))
                  :else
                  (do (transport/respond-to msg :reloading ["changed.ns"])
                      (transport/respond-to msg :err "Reflection warning, changed/ns.clj:3:1\n")
                      (transport/respond-to msg :status ["ok" "done"])))
      "inspect-last-exception" (do (swap! test-requests conj (select-keys msg [:op :index :ex-data :page-size]))
                                   (transport/respond-to msg :status "done"
                                                         :value (pr-str ["Class: " '(:value "clojure.lang.ExceptionInfo" 0)])))
      "test-stacktrace" (do (swap! test-requests conj (select-keys msg [:op :ns :var :index]))
                            (transport/respond-to msg :class "clojure.lang.ExceptionInfo" :message "boom" :data "{}"
                                                  :stacktrace [{:name "neorepl.sample_suite$fn__1/invokeStatic"
                                                                :var "neorepl.sample-suite/fn" :file "sample_suite.clj"
                                                                :line 15 :flags ["project" "clj"]}
                                                               {:name "orchard.test$test_var/invokeStatic"
                                                                :var "orchard.test/test-var" :file "test.clj"
                                                                :line 362 :flags ["dependency" "clj"]}])
                            (transport/respond-to msg :status "done"))
      "analyze-last-stacktrace" (do (transport/respond-to msg :class "clojure.lang.ExceptionInfo" :message "boom"
                                                          :data "{:k 1}" :phase "execution"
                                                          :stacktrace [{:name "user$f/invoke" :var "user/f" :file "x.clj" :line 1
                                                                        :file-url []}
                                                                       {:name "my.app$g/invoke" :var "my.app/g" :file "app.clj" :line 5
                                                                        :file-url "file:/src/my/app.clj"}
                                                                       ;; As babashka's server has them.
                                                                       {:name "user/g" :ns "user" :fn "g/fn"
                                                                        :file "y.clj" :line 2}])
                                    (transport/respond-to msg :class "java.lang.Exception" :message []
                                                          :stacktrace [])
                                    (transport/respond-to msg :status "done"))
      (handler msg))))

(middleware/set-descriptor! #'wrap-fake-cider {:handles {"info" {} "complete" {} "apropos" {} "ns-list" {} "cider-version" {}
                                                         "ns-vars-with-meta" {} "spec-list" {} "spec-example" {}
                                                         "macroexpand" {} "analyze-last-stacktrace" {} "test-stacktrace" {}
                                                         "refresh" {} "refresh-clear" {} "test-var-query" {} "retest" {}
                                                         "inspect-last-exception" {} "fn-refs" {} "fn-deps" {}}
                                          ;; As cider-nrepl says which it is.
                                          :describe-fn (fn [_] {:cider-version {:version-string "0.63.1"}})})

(def ^:private user
  {:ns "user"})

(defn- without
  "client, pretending its server doesn't have ops."
  [client & ops]
  (update client :ops #(apply disj % ops)))

(deftest info-test
  (with-client [client {:port *port*}]
    (doseq [[rung client] {"lookup" client "eval" (without client "lookup")}]
      (testing rung
        (let [info (features/info client "map" user)]
          (is (= {:ns "clojure.core" :name "map"}
                 (select-keys info [:ns :name])))
          (is (= ["[f]" "[f coll]"] (take 2 (:arglists info))))
          (is (str/starts-with? (:doc info) "Returns a lazy sequence"))
          (is (pos? (:line info))))
        (testing "macros"
          (is (:macro (features/info client "when" user))))
        (testing "nothing there"
          (is (nil? (features/info client "no-such-thing" user))))
        (testing "nowhere for what was made at a REPL"
          (nrepl/request client {:op "eval" :ns "user" :code "(defn made-at-the-repl [])"})
          (is (= {:ns "user" :name "made-at-the-repl"}
                 (select-keys (features/info client "made-at-the-repl" user) [:ns :name :file :line]))))
        (testing "nowhere for a var with no file, as intern makes"
          (nrepl/request client {:op "eval" :ns "user" :code "(intern 'user 'interned 1)"})
          (is (= {:ns "user" :name "interned"}
                 (select-keys (features/info client "interned" user) [:ns :name :file :line]))))
        (testing "symbols are only ever symbols"
          (is (nil? (features/info client "(System/exit 1)" user))))
        (testing "in a session, leaving its *1 alone"
          (let [session (nrepl/clone-session client)]
            (nrepl/request client {:op "eval" :session session :code "42"})
            (is (= "map" (:name (features/info client "map" (assoc user :session session)))))
            (is (= ["42"] (keep :value (nrepl/request client {:op "eval" :session session :code "*1"}))))))))
    (testing "special forms"
      (is (= {:special-form true :forms ["(if test then else?)"]}
             (select-keys (features/info client "if" user) [:special-form :forms])))
      ;; On babashka they're macros, without forms.
      (when-not (System/getProperty "babashka.version")
        (testing "with nested shapes and bare symbols"
          (is (= "(Classname/staticMethod args*) Classname/staticField"
                 (->> (features/info client "." user) :forms (take-last 2) (str/join " ")))))
        (testing "with an arity a list, rather than a vector, which bencode makes them both"
          (is (= ["(fn name? [params*] exprs*)" "(fn name? ([params*] exprs*) +)"]
                 (:forms (features/info client "fn" user))))))
      (testing "with an interop form a list"
        (is (= "(set! (. instance-expr instanceFieldName-symbol) expr)"
               (second (:forms (features/info client "set!" user)))))))))

(deftest references-test
  (testing "takes cider-nrepl, which cider-test fakes"
    (with-client [client {:port *port*}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^Cross-references take cider-nrepl on the server$"
                            (features/references client "greet" user false))))))

(deftest complete-test
  (with-client [client {:port *port*}]
    ;; Only the JVM's nREPL says what type a candidate is.
    (is (some #(= {:candidate "mapcat" :ns "clojure.core"} (dissoc % :type))
              (features/complete client "mapc" user)))
    (testing "no completion without the ops for it"
      (is (nil? (features/complete (without client "completions") "mapc" user))))))

(deftest source-test
  (with-client [client {:port *port*}]
    (is (str/starts-with? (features/source client "when" user) "(defmacro when"))
    (is (nil? (features/source client "no-such-thing" user)))
    (testing "of a file loaded from anywhere, read from where it is"
      (test-server/call-with-temp-dir
       (fn [dir]
         (let [file (test-server/spit-file dir "loaded.clj" "(ns neorepl.loaded)\n\n(defn greet\n  [x]\n  x)\n")]
           (nrepl/request client {:op "load-file" :file (slurp file) :file-path (str file) :file-name "loaded.clj"})
           (is (= "(defn greet\n  [x]\n  x)" (features/source client "neorepl.loaded/greet" user)))))))
    (testing "from a namespace that doesn't refer clojure.core, made by in-ns"
      (nrepl/request client {:op "eval" :ns "user" :code "(in-ns 'neorepl.bare)"})
      (doseq [[rung client] {"lookup" client "eval" (without client "lookup")}]
        (testing rung
          (is (str/starts-with? (features/source client "clojure.core/when" {:ns "neorepl.bare"}) "(defmacro when"))
          (is (= "clojure.core" (:ns (features/info client "clojure.core/map" {:ns "neorepl.bare"})))))))))

(deftest apropos-test
  (with-client [client {:port *port*}]
    (is (some #{{:name "clojure.core/mapcat"}} (features/apropos client "^mapc")))
    (testing "clojure.repl's own, which a server needn't have loaded"
      (is (= [{:name "clojure.repl/find-doc"}] (features/apropos client "^find-doc$"))))))

(deftest expand-test
  (with-client [client {:port *port*}]
    (is (= "(if x (do y))" (features/expand client "(when x y)" user false)))
    (is (str/includes? (features/expand client "(when-let [a 1] a)" user true) "let*"))
    (testing "from a namespace that doesn't refer clojure.core"
      (nrepl/request client {:op "eval" :ns "user" :code "(in-ns 'neorepl.bare)"})
      (is (= "(if x (do y))" (features/expand client "(clojure.core/when x y)" {:ns "neorepl.bare"} false))))
    (testing "or one that isn't there, which says so"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^No namespace called no.such.ns$"
                            (features/expand client "(when x y)" {:ns "no.such.ns"} false)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^No namespace called no.such.ns$"
                            (features/info client "map" {:ns "no.such.ns"}))))
    (testing "one form, only ever read, not evaluated"
      (are [text] (= ::features/failed
                     (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (features/expand client text user false))))))
        "#=(System/exit 1)"
        "(when x y) (+ 1 2)"
        "x) (spit \"/tmp/p\" 1"
        ""))))

(deftest last-error-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)]
      (testing "nothing yet"
        (is (nil? (features/last-error client session))))
      (nrepl/request client {:op "eval" :session session
                             :code "(defn boom [] (throw (ex-info \"boom\" {:k 1} (ArithmeticException. \"inner\")))) (boom)"})
      (let [[outer inner :as causes] (features/last-error client session)]
        (is (= 2 (count causes)))
        (is (= {:class "clojure.lang.ExceptionInfo" :message "boom" :data "{:k 1}"}
               (select-keys outer [:class :message :data])))
        (is (= {:class "java.lang.ArithmeticException" :message "inner"}
               (select-keys inner [:class :message])))
        (is (seq (:stacktrace inner)))
        ;; On babashka, the frames are its interpreter's.
        (when-not (System/getProperty "babashka.version")
          (testing "with the vars of Clojure's frames"
            (is (= "user/boom" (:var (first (:stacktrace outer)))))
          (is (= "user/boom" (:var (first (:stacktrace inner)))))
          (is (nil? (:var (first (filter #(str/starts-with? (:name %) "clojure.lang.Compiler/") (:stacktrace inner))))))))
        (testing "with frames that follow on in the same function flagged dup"
          (let [shown (remove #(some #{"dup" "tooling"} (:flags %)) (:stacktrace inner))
                fn-of #(first (str/split (:name %) #"/"))]
            (is (every? (fn [[a b]] (not= (fn-of a) (fn-of b))) (partition 2 1 shown))))))
      (testing "leaves the session's *1 alone"
        (is (= ["#'user/boom"] (keep :value (nrepl/request client {:op "eval" :session session :code "*1"}))))))))

(deftest frame-var-test
  (are [frame expected] (= expected (#'features/frame-var frame))
    {:var "user/f" :name "user$f/invoke"} "user/f"
    {:ns "user" :name "user/f"} "user/f"
    {:ns "user"} nil
    {:name "my_app.core$f$fn__123/invoke"} "my-app.core/f"
    {:name "user$eval123/invokeStatic"} "user/eval123"
    {:name "java.util.concurrent.ThreadPoolExecutor$Worker/run"} nil
    {:name "clojure.lang.Compiler/eval"} nil
    {} nil))

(deftest above-test-runner-test
  (testing "a test's frames, down to the runner's"
    (is (= [{:name "my.app-test/fn"}]
           (#'features/above-test-runner [{:name "my.app-test/fn"} {:name "clojure.test$test_var/invokeStatic"}
                                          {:name "my.app-test/fixture"}]))))
  (testing "or all of them, for an error in the runner"
    (is (= [{:name "orchard.test$test_var/invoke"} {:name "nrepl.SessionThread/run"}]
           (#'features/above-test-runner [{:name "orchard.test$test_var/invoke"} {:name "nrepl.SessionThread/run"}])))))

(deftest test-results-test
  (testing "ClojureScript's errors, which come with the stack"
    (is (= [{:type "error" :ns "app.core-test" :var "errs" :expected "(= 1 (throw (ex-info \"boom\" {})))"
             :actual "#error {:message \"boom\", :data {}}" :error "boom" :line 6 :index 0}]
           (#'features/test-results {:app.core-test {:errs [{:type "error" :index 0 :ns "app.core-test" :var "errs"
                                                             :expected "(= 1 (throw (ex-info \"boom\" {})))"
                                                             :actual "#error {:message \"boom\", :data {}}"
                                                             :error {:message "boom" :stack "Error: boom\n    at x.js:1:2"}
                                                             :line 6}]}}))))
  (testing "an error outside an assertion, whose line ClojureScript doesn't know"
    (is (= [{:type "error" :ns "app.core-test" :var "throws" :error "boom" :index 0}]
           (#'features/test-results {:app.core-test {:throws [{:type "error" :index 0 :ns "app.core-test" :var "throws"
                                                               :error {:message "boom" :stack "Error: boom"}
                                                               :line "NaN"}]}})))))

(deftest reload-test
  (with-client [client {:port *port*}]
    (testing "the namespaces given"
      (is (= {:reloaded ["clojure.set"]} (features/reload client ["clojure.set"]))))
    (testing "what changed, which takes cider-nrepl"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes cider-nrepl" (features/reload client []))))))

(deftest namespaces-test
  (with-client [client {:port *port*}]
    (let [names (features/namespaces client user)]
      (testing "the project's, whose source isn't in a jar"
        (is (some #{"neorepl.features"} names))
        (is (not-any? #{"clojure.core" "nrepl.server"} names)))
      (is (= names (sort names))))
    (testing "all of them, but nREPL's own"
      (let [names (features/loaded-namespaces client user {})]
        (is (every? (set names) ["clojure.core" "neorepl.features"]))
        (is (not-any? #(re-find #"^nrepl" %) names))
        (is (= names (sort names)))))
    (testing "a namespace's public vars, sorted, without cider-nrepl"
      (let [vars (features/ns-vars client "clojure.string" user {})
            {:keys [arglists doc]} (some #(when (= "join" (:name %)) %) vars)]
        (is (= vars (sort-by :name vars)))
        (is (= "([coll] [separator coll])" arglists))
        (is (str/starts-with? doc "Returns a string"))
        (testing "and no namespace"
          (is (thrown? clojure.lang.ExceptionInfo (features/ns-vars client "no.such.ns" user {}))))))))

(deftest specs-test
  (with-client [client {:port *port*}]
    (nrepl/request client {:op "eval" :code (str "(require '[clojure.spec.alpha :as s])"
                                                 "(s/def :neorepl.specs/age pos-int?)"
                                                 "(s/def :neorepl.specs/person (s/keys :req [:neorepl.specs/age]))"
                                                 "(s/def :neorepl.specs/alias :neorepl.specs/age)"
                                                 "(s/def :neorepl.specs/f fn?)")})
    (testing "the registry's, without cider-nrepl"
      (let [names (features/specs client {})]
        (is (every? (set names) [":neorepl.specs/age" ":neorepl.specs/person"]))
        (is (= names (sort names)))))
    (testing "a spec's form, with s/ for clojure.spec.alpha and without clojure.core"
      (is (= "(s/keys :req [:neorepl.specs/age])\n" (features/spec-form client ":neorepl.specs/person" 80 {})))
      (is (= "pos-int?\n" (features/spec-form client ":neorepl.specs/age" 80 {})))
      (testing "and an alias's target, rather than its form"
        (is (= ":neorepl.specs/age\n" (features/spec-form client ":neorepl.specs/alias" 80 {}))))
      (testing "and none for one that isn't there"
        (is (nil? (features/spec-form client ":no/such" 80 {})))))
    (testing "an example, which takes test.check on the server"
      (if (try (require 'clojure.test.check.generators) true (catch Exception _ false))
        (do (is (re-matches #"#:neorepl.specs\{:age \d+\}\n" (features/spec-example client ":neorepl.specs/person" 80 {})))
            (testing "or a line saying why there's none"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^[^\n]*Unable to construct gen[^\n]*$"
                                    (features/spec-example client ":neorepl.specs/f" 80 {})))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^Generating examples takes test.check on the server$"
                              (features/spec-example client ":neorepl.specs/person" 80 {})))))))

(deftest enter-ns-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)
          eval-in (fn [code] (some :value (nrepl/request client {:op "eval" :session session :code code})))
          enter #(features/enter-ns client session % {})]
      (eval-in "(+ 1 2)")
      (is (= {:status "ok" :ns "user"} (select-keys (enter "user") [:status :ns])))
      (testing "keeping the value before, behind in-ns's"
        (is (= "3" (eval-in "*2"))))
      (testing "loading the namespace first"
        (eval-in "(+ 2 2)")
        (is (= {:status "ok" :ns "clojure.data"} (select-keys (enter "clojure.data") [:status :ns])))
        (testing "in a copy of the session, so the require's nil isn't behind in-ns's value"
          (is (= "4" (eval-in "*2"))))
        (is (= "{:a 1}" (eval-in "(first (diff {:a 1} {}))"))))
      (testing "out of a namespace that doesn't refer clojure.core"
        (eval-in "(in-ns 'empty.one)")
        (is (= "ok" (:status (enter "user"))))
        (is (= "user" (nrepl/session-ns client session))))
      (testing "not into one that doesn't load"
        (let [{:keys [status err]} (features/enter-ns client session "no.such.ns" {:collect? true})]
          (is (= "eval-error" status))
          (is (str/includes? err "Could not locate no/such/ns")))
        (is (= "user" (nrepl/session-ns client session)))
        (is (= "nil" (eval-in "(find-ns 'no.such.ns)"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not the name of a namespace: a/b"
                            (enter "a/b"))))))

(deftest ensure-ns-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)
          eval-in (fn [code] (some :value (nrepl/request client {:op "eval" :session session :code code})))
          ns-msg (fn [code] {:op "eval" :code code :file "/tmp/ensured.clj" :line 3 :column 1})]
      (testing "nothing to do for a namespace that's there"
        (is (nil? (features/ensure-ns client session "clojure.string" nil {}))))
      (testing "loading one that isn't"
        (eval-in "(+ 1 2)")
        (is (nil? (features/ensure-ns client session "clojure.walk" nil {})))
        (testing "leaving the session's namespace and *1 as they were"
          (is (= "3" (eval-in "*1")))
          (is (= "user" (nrepl/session-ns client session))))
        (is (= "true" (eval-in "(some? (find-ns 'clojure.walk))"))))
      (testing "not one that doesn't load, for its error"
        (let [{:keys [status err]} (features/ensure-ns client session "no.such.ns" nil {:collect? true})]
          (is (= "eval-error" status))
          (is (str/includes? err "Could not locate no/such/ns"))))
      (testing "its ns form, whether it's there or not, so what it requires is too"
        (is (nil? (features/ensure-ns client session "neorepl.ensured"
                                      (ns-msg "(ns neorepl.ensured (:require [clojure.set :as set]))") {})))
        (is (= "\"clojure.set\"" (eval-in "(str (ns-name (get (ns-aliases 'neorepl.ensured) 'set)))")))
        (is (nil? (features/ensure-ns client session "neorepl.ensured"
                                      (ns-msg "(ns neorepl.ensured (:require [clojure.set :as set] [clojure.data :as data]))") {})))
        (is (= "\"clojure.data\"" (eval-in "(str (ns-name (get (ns-aliases 'neorepl.ensured) 'data)))")))
        (testing "and its error, when it fails"
          (let [{:keys [status err]} (features/ensure-ns client session "neorepl.ensured"
                                                         (ns-msg "(ns neorepl.ensured (:require [no.such.lib]))") {:collect? true})]
            (is (= "eval-error" status))
            (is (str/includes? err "no/such/lib")))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not the name of a namespace: a/b"
                            (features/ensure-ns client session "a/b" nil {}))))))

(deftest evaluate-in-ns-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)
          evaluate #(features/evaluate-in-ns client {:op "eval" :session session :code %1} %2 %3 (merge {:collect? true} %4))]
      (testing "in the namespace, loaded first"
        (is (= {:status "ok" :values ["{:a 1}"]}
               (select-keys (evaluate "(first (clojure.data/diff {:a 1} {}))" "clojure.data" nil {}) [:status :values])))
        (is (= {:status "ok" :values ["\"clojure.data\""]}
               (select-keys (evaluate "(str *ns*)" "clojure.data" nil {}) [:status :values]))))
      (testing "with its ns form first"
        (is (= ["#{1 2}"]
               (:values (evaluate "(set/union #{1} #{2})" "neorepl.in-ns"
                                  {:op "eval" :code "(ns neorepl.in-ns (:require [clojure.set :as set]))"} {})))))
      (testing "leaving the session in its own"
        (is (= "user" (nrepl/session-ns client session))))
      (testing "or where it is, without one"
        (is (= ["\"user\""] (:values (evaluate "(str *ns*)" nil nil {})))))
      (testing "not when it doesn't load"
        (let [{:keys [status err]} (evaluate "1" "no.such.ns" nil {})]
          (is (= "eval-error" status))
          (is (str/includes? err "Could not locate no/such/ns"))))
      (testing "with the timeout covering the loading too"
        (is (= "timeout" (:status (evaluate ":never" "neorepl.slow-ns"
                                            {:op "eval" :code "(ns neorepl.slow-ns) (Thread/sleep 5000)"} {:timeout 0.5})))))
      (testing "after reloading what changed"
        (let [before @test-server/reloads]
          (is (= [(str (inc before))] (:values (evaluate "@neorepl.test-server/reloads" nil nil {:reload? true}))))
          (testing "and not when that fails"
            (let [{:keys [status message values]}
                  (test-server/answering-reloads :error
                    #(evaluate "(swap! neorepl.test-server/reloads inc)" nil nil {:reload? true}))]
              (is (= "error" status))
              (is (= "Reloading what changed failed" message))
              (is (empty? values)))
            (is (= (inc before) @test-server/reloads))))))))

(deftest reload-changed-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)
          printed (atom [])
          reload #(features/reload-changed client session {:collect? true :on-response (partial swap! printed conj)})
          values #(keep :value (nrepl/request client {:op "eval" :session session :code %}))]
      (testing "nothing, once it's reloaded"
        (is (nil? (reload))))
      (testing "with the session back in its namespace, made anew, and *1, *2 and *3 as they were"
        (values "(in-ns 'neorepl.reloaded)")
        (values "1 2 3")
        (reload)
        (is (= ["[true 3 2 1]"] (values (str "[reloaded-" @test-server/reloads " *1 *2 *3]")))))
      (testing "the server's error, when a file doesn't compile"
        (let [{:keys [status err message]} (test-server/answering-reloads :error reload)]
          (is (= "error" status))
          (is (str/includes? err "Unable to resolve symbol: whom"))
          (is (= "Reloading what changed failed" message))))
      (testing "and in a ClojureScript session, that it takes Clojure, in place of what the server says"
        (reset! printed [])
        (let [{:keys [status clojurescript? message]} (test-server/answering-reloads :clojure-only reload)]
          (is (= "error" status))
          (is (true? clojurescript?))
          (is (= "Reloading what changed takes a Clojure session" message))
          (is (not-any? :err @printed))))
      (testing "that it takes cider-nrepl"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^Reloading what changed takes cider-nrepl on the server$"
                              (features/reload-changed (without client "cider.clj-reload/reload") session {}))))
      (testing "the first reload, in the background, for a server neorepl starts"
        (let [before @test-server/reloads]
          (.join ^Thread (features/note-changes "127.0.0.1" *port*) 5000)
          (is (= (inc before) @test-server/reloads)))))))

(deftest run-tests-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)
          run #(features/run-tests client session % {})
          sample-file? #(str/ends-with? (str/replace (str %) "\\" "/") "test/neorepl/sample_suite.clj")]
      (testing "namespaces, through clojure.test without the test ops"
        (let [{:keys [status out report]} (run ["neorepl.sample-suite"])
              {:keys [summary failures]} report]
          (is (= "failed" status))
          (is (= "passing prints\n" (test-server/lf out)))
          (is (= {:ns 1 :var 3 :test 3 :pass 1 :fail 1 :error 1} summary))
          (is (= [{:type "error" :ns "neorepl.sample-suite" :var "erring"
                   :expected "(= 1 (throw (ex-info \"boom\" {})))" :error "clojure.lang.ExceptionInfo: boom {}"}
                  {:type "fail" :ns "neorepl.sample-suite" :var "failing" :context "with a context"
                   :message "adds up" :expected "(= 3 (+ 1 1))" :actual "(not (= 3 2))" :line 12}]
                 (map #(dissoc % :file :causes (when (= "error" (:type %)) :line)) failures)))
          (testing "with the files the tests are in"
            (is (every? (comp sample-file? :file) failures)))
          (testing "and the error's stack trace"
            (let [[{:keys [class message stacktrace]}] (:causes (first failures))]
              (is (= ["clojure.lang.ExceptionInfo" "boom"] [class message]))
              (is (seq stacktrace))))))
      (testing "a fixture failing outside the tests"
        (is (= [{:type "error" :ns "neorepl.sample-fixture" :error "clojure.lang.ExceptionInfo: no database {}"}]
               (map #(select-keys % [:type :ns :var :error]) (:failures (:report (run ["neorepl.sample-fixture"])))))))
      (testing "tests"
        (is (= {:ns 1 :var 1 :test 1 :pass 1 :fail 0 :error 0}
               (:summary (:report (run ["neorepl.sample-suite/passing"]))))))
      (testing "the session's *1 stays put"
        (nrepl/request client {:op "eval" :session session :code ":before"})
        (run ["neorepl.sample-suite/passing"])
        (is (= [":before"] (keep :value (nrepl/request client {:op "eval" :session session :code "*1"})))))
      (testing "a namespace without tests, which can't be what was meant"
        (is (= {:status "failed" :message "No tests in clojure.string"}
               (select-keys (run ["clojure.string"]) [:status :message]))))
      (testing "within the timeout, loading the tests too"
        (let [started (System/currentTimeMillis)]
          (is (= {:status "timeout" :message "Timed out after 0.5s, loading the tests"}
                 (features/run-tests client session ["neorepl.sample-slow-load"] {:timeout 0.5})))
          (is (< (- (System/currentTimeMillis) started) 2500))))
      (are [names message] (thrown-with-msg? clojure.lang.ExceptionInfo message (run names))
        ["neorepl.sample-suite/nope"] #"No test called neorepl.sample-suite/nope"
        ["neorepl.sample-suite" "neorepl.sample-suite/passing"] #"not both"
        ["no.such-suite"] #"(?s)Couldn't load no.such-suite: .*no/such_suite"
        ["neorepl.sample-throws"] #"(?s)Couldn't load neorepl.sample-throws: .*Doesn't load"
        ;; They go into the code as they are.
        ["neorepl.sample-suite) (System/exit 1"] #"Not the name of a namespace or a test"
        ["neorepl.sample-suite/passing 1"] #"Not the name of a namespace or a test"
        [] #"Running every test takes cider-nrepl"
        :failed #"Rerunning the tests that failed takes cider-nrepl"))))

(deftest error-test
  (with-client [client {:port *port*}]
    (testing "errors aren't the same as finding nothing"
      (are [f] (= ::features/failed (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (f client))))))
        #(features/source % "map" {:ns "no.such.ns"})
        #(features/apropos % "(")
        #(features/reload % ["no.such.ns"])))))

(deftest cider-test
  (let [srv (server/start-server :port 0 :handler (server/default-handler #'wrap-fake-cider #'test-server/wrap-fake-inspector))]
    (try
      (with-client [client {:port (:port srv)}]
        (testing "cross-references"
          (let [ctx {:ns "demo.core"}]
            (is (= [{:name "demo.core/welcome" :file (str (io/file "/src/demo/core.clj")) :line 5 :column 1}]
                   (features/references client "greet" ctx false)))
            (testing "the other way, without where one made at a REPL is"
              (is (= [{:name "demo.core/greet"}] (features/references client "welcome" ctx true))))
            (testing "none for a var nothing uses"
              (is (= [] (features/references client "map" ctx false))))
            (testing "and nothing called that"
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^Nothing called lonely in demo.core$"
                                    (features/references client "lonely" ctx false))))))
        (testing "cider-nrepl's ops come first"
          (is (= {:ns "clojure.core" :name "map" :arglists ["[f]" "[f coll]"] :doc "Cider's map."
                  :file (str (io/file "/tmp/a b/core.clj")) :line 1}
                 (features/info client "map" user)))
          (testing "with files in jars as they are, and none for what was made at a REPL"
            (is (= "jar:file:/x.jar!/clojure/core.clj" (:file (features/info client "mapv" user))))
            (is (= {:ns "user" :name "made-at-the-repl"} (features/info client "made-at-the-repl" user))))
          (is (= {:name "if" :special-form true :forms ["(if test then else?)"]}
                 (features/info client "if" user)))
          (is (nil? (features/info client "no-such-thing" user)))
          (is (nil? (features/info client "not-in-nbb" user)))
          (testing "in the session asked for, where it's in ClojureScript, say"
            (let [session (nrepl/clone-session client)]
              (is (= session (:doc (features/info client "session" (assoc user :session session)))))))
          (is (= ::features/failed
                 (:type (ex-data (is (thrown? clojure.lang.ExceptionInfo (features/info client "broken" user)))))))
          (is (= [{:candidate "map" :type "function" :ns "clojure.core"}]
                 (features/complete client "ma" user)))
          (testing "namespaces in the session's language"
            (let [ctx (assoc user :session (nrepl/clone-session client))]
              (is (= ["neorepl.features"] (features/namespaces client ctx)))
              (is (= ["clojure.core" "made.at.the.repl" "neorepl.features"] (features/loaded-namespaces client ctx {})))))
          (is (= [{:name "greet" :arglists "([who])" :doc "Says hi."} {:name "state"}]
                 (features/ns-vars client "demo.core" user {})))
          (testing "specs"
            (is (= [":cider/spec"] (features/specs client {})))
            (is (= "cider's example\n" (features/spec-example client ":cider/spec" 80 {})))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^Generating examples takes test.check on the server$"
                                  (features/spec-example client ":demo/age" 80 {}))))
          (is (= [{:name "clojure.core/map" :type "function" :doc "Cider's map.\nMore."}]
                 (features/apropos client "map")))
          (is (= "Cider's macroexpand-all" (features/expand client "(when x y)" user true)))
          (testing "in a copy of the session, whose *1 it would change otherwise"
            (let [session (nrepl/clone-session client)
                  expansion (features/expand client "(when x y)" (assoc user :session session) false)
                  [_ copy] (re-find #" in (.+)$" expansion)]
              (is (some? copy))
              (is (not= session copy))
              (is (not (some #{copy} (some :sessions (nrepl/request client {:op "ls-sessions"})))))))
          (testing "Java members and classes, with their Javadoc as text"
            (is (= {:ns "java.lang.Thread" :name "sleep" :class "java.lang.Thread" :arglists ["[millis]" "[millis nanos]"]
                    :doc "Causes the Thread to sleep. Unlike wait, it keeps its\n  Map<K, V> & monitors, as\n  the docs say."}
                   (features/info client "Thread/sleep" user)))
            (testing "laid out as a browser would, indented as a docstring is"
              (is (= (str "Characters:\n\n"
                          "  - in words, like\n"
                          "    Fahrvergn\u00fcgen\n"
                          "  - and codes\n"
                          "    - \\u0130\n\n"
                          "  Cases:\n\n"
                          "  Lower | Upper\n"
                          "   | \u0130\n"
                          "  i | I\n\n"
                          "  In code:\n\n"
                          "      if (a < b)\n"
                          "        up(a);\n\n"
                          "  Or:\n\n"
                          "      Map<K, V> m = new HashMap<>();\n"
                          "        m.clear();")
                     (:doc (features/info client "Character" user))))
              (testing "but for the spaces around an entity, from a cider-nrepl that doesn't add them"
                (is (= "0.63.1" (:cider-version client)))
                (doseq [version ["0.62.2" "0.64.0-alpha1" "0.64.0-alpha2" nil]]
                  (is (str/includes? (:doc (features/info (assoc client :cider-version version) "Character" user))
                                     "Fahrvergnügen")))
                (doseq [version ["0.64.0-alpha3" "0.64.0" "0.65.0-SNAPSHOT"]]
                  (is (str/includes? (:doc (features/info (assoc client :cider-version version) "Character" user))
                                     "Fahrvergn ü gen")))
                (is (= (str "As of JDK 1.1, a & b are:\n\n"
                            "  - one 😀\n\n"
                            "  - and List<B>\n\n"
                            "  Like so:\n\n"
                            "      a();\n"
                            "      foo(bar );")
                       (:doc (features/info (assoc client :cider-version "0.64.0") "Object" user))))))
            (is (= {:name "java.lang.String" :class "java.lang.String" :doc "Strings."
                    :file "jar:file:/x/src.zip!/java/lang/String.java" :line 142}
                   (features/info client "String" user)))
            (is (= {:ns "java.lang.Integer" :name "MAX_VALUE" :class "java.lang.Integer" :doc "Biggest."}
                   (features/info client "Integer/MAX_VALUE" user)))
            (testing "but no source, which isn't a form"
              (is (nil? (features/source client "String" user)))))
          (testing "a namespace, named once, unlike a var named like its namespace"
            (is (= {:name "clojure.string" :doc "Strings."} (features/info client "clojure.string" user)))
            (is (= "hello" (:ns (features/info client "hello" user)))))
          (testing "source from the file info finds"
            (test-server/call-with-temp-dir
             (fn [dir]
               (reset! source-file (test-server/spit-file dir "src.clj" "(ns user)\n(defn from-a-file []\n  1)\n(def after 2)\n(println :not-a-definition)\n"))
               (is (= "(defn from-a-file []\n  1)" (features/source client "from-a-file" user)))
               (is (= "(defn from-a-file []\n  1)" (features/source client "from-column-0" user)))
               (testing "if it's a definition, rather than whatever the line has"
                 (is (nil? (features/source client "on-a-wrong-line" user)))))))
          (testing "or the server's classpath, for a file this machine can't read"
            (is (str/starts-with? (features/source client "mapv" user) "(defn mapv")))
          (is (= {:reloaded ["changed.ns"] :output "Reflection warning, changed/ns.clj:3:1\n"}
                 (features/reload client [])))
          (testing "after forgetting a namespace whose file is gone, which cider-nrepl keeps after"
            (reset! refresh-lost? :gone)
            (is (= {:reloaded ["changed.ns"] :output "Reflection warning, changed/ns.clj:3:1\n"}
                   (features/reload client [])))
            (is (nil? @refresh-lost?))
            (testing "but not for one it requires, when forgetting them all reloads them all for nothing"
              (reset! refresh-lost? :dependency)
              (try
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Could not locate nope/core" (features/reload client [])))
                (is (= :dependency @refresh-lost?))
                (finally
                  (reset! refresh-lost? nil)))))
          (reset! refresh-fails? true)
          (try
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"refresh: Syntax error compiling at \(broken/ns\.clj"
                                  (features/reload client [])))
            (finally
              (reset! refresh-fails? false)))
          (testing "tests"
            (reset! test-requests [])
            (let [session (nrepl/clone-session client)
                  out (atom [])
                  {:keys [status report]} (features/run-tests client session ["neorepl.sample-suite"]
                                                              {:on-response #(some->> (:out %) (swap! out conj))})]
              (is (= "failed" status))
              (testing "with each namespace's Testing line, from the events it streams"
                (is (= ["Testing neorepl.sample-suite\n" "testing\n"] @out)))
              (is (= {:summary {:ns 1 :var 2 :test 3 :pass 1 :fail 1 :error 1}
                      :failures [{:type "error" :ns "neorepl.sample-suite" :var "erring"
                                  :expected "(= 1 (throw (ex-info \"boom\" {})))"
                                  :error "clojure.lang.ExceptionInfo: boom {}"
                                  :causes [{:class "clojure.lang.ExceptionInfo" :message "boom" :data "{}"
                                            :stacktrace [{:name "neorepl.sample_suite$fn__1/invokeStatic"
                                                          :var "neorepl.sample-suite/fn" :file "sample_suite.clj"
                                                          :line 15 :flags ["project" "clj"]}]}]
                                  :file (str (io/file "/src/neorepl/sample_suite.clj")) :line 15}
                                 {:type "fail" :ns "neorepl.sample-suite" :var "failing" :context "with a context"
                                  :message "adds up" :expected "3" :actual "2"
                                  :file "sample_suite.clj" :line 12}]
                      :elapsed-ms 5}
                     report))
              (features/run-tests client session ["neorepl.sample-suite/passing" "neorepl.sample-suite/failing"] {})
              (features/run-tests client session [] {})
              (features/run-tests client session :failed {})
              (is (= [{:op "test-var-query" :var-query {:ns-query {:exactly ["neorepl.sample-suite"]}} :stream "true"}
                      {:op "test-var-query" :var-query {:exactly ["neorepl.sample-suite/passing"
                                                                  "neorepl.sample-suite/failing"]}
                       :stream "true"}
                      {:op "test-var-query" :var-query {:ns-query {:project? "true"}} :stream "true"}
                      {:op "retest" :stream "true"}]
                     (remove (comp #{"test-stacktrace"} :op) @test-requests)))
              (testing "a failure's file, on babashka before 1.13.227, where it's whichever file the session loaded last"
                (is (= (str (io/file "/src/neorepl/sample_suite.clj"))
                       (:file (first (#'features/locate (assoc client :versions {:babashka "1.13.226"}) session true
                                                        [{:type "fail" :ns "neorepl.sample-suite" :var "erring"
                                                          :file "/elsewhere/loaded.clj" :line 3}])))))
                (testing "but not from 1.13.227 on, where it's the assertion's, in a helper, say"
                  (doseq [version ["1.13.227" "1.13.227-SNAPSHOT" "1.14.0"]]
                    (is (= "/elsewhere/helper.clj"
                           (:file (first (#'features/locate (assoc client :versions {:babashka version}) session true
                                                            [{:type "fail" :ns "neorepl.sample-suite" :var "erring"
                                                              :file "/elsewhere/helper.clj" :line 3}]))))
                        version)))
                (testing "but not from clojure.test's report, whose errors' are where they threw, in a helper, say"
                  (is (= "helper.clj"
                         (:file (first (#'features/locate client session false
                                                          [{:type "error" :ns "neorepl.sample-suite" :var "erring"
                                                            :file "helper.clj" :line 3}]))))))
                (testing "and the test's line, for an error ClojureScript knows none for"
                  (is (= 14 (:line (first (#'features/locate client session true
                                                             [{:type "error" :ns "neorepl.sample-suite" :var "erring"}])))))))
              (testing "every test in the project, its namespaces loaded first, as orchard finds them"
                ;; One that's never there, since one that threw is loaded as far
                ;; as require is concerned, its ns form having said so.
                (reset! orchard.namespace/project ['neorepl.sample-suite 'neorepl.not-there])
                (try
                  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                        #"(?s)^The project's namespaces don't all load:\n  neorepl.not-there: .*neorepl/not_there"
                                        (features/run-tests client session [] {})))
                  (finally
                    (reset! orchard.namespace/project [])))
                (testing "or by the server, without orchard"
                  (reset! test-requests [])
                  (reset! orchard.namespace/missing? true)
                  (try
                    (features/run-tests client session [] {})
                    (is (= {:ns-query {:project? "true" :load-project-ns? "true"}}
                           (:var-query (first @test-requests))))
                    (finally
                      (reset! orchard.namespace/missing? false)))))
              (testing "with the stack trace of each error, by its index"
                (is (= {:op "test-stacktrace" :ns "neorepl.sample-suite" :var "erring" :index 0}
                       (some #(when (= "test-stacktrace" (:op %)) %) @test-requests))))))
          (is (= [{:class "clojure.lang.ExceptionInfo" :message "boom" :data "{:k 1}"
                   :stacktrace [{:name "user$f/invoke" :file "x.clj" :line 1 :var "user/f"}
                                {:name "my.app$g/invoke" :file "app.clj" :line 5 :var "my.app/g"
                                 :path (str (java.io.File. (java.net.URI. "file:/src/my/app.clj")))}
                                {:name "user/g" :file "y.clj" :line 2 :var "user/g"}]}
                  ;; cider-nrepl sends nil as an empty list.
                  {:class "java.lang.Exception" :message nil :data nil :stacktrace []}]
                 (features/last-error client (nrepl/clone-session client))))
          (testing "inspecting the error's causes"
            (reset! test-requests [])
            (is (features/inspects-errors? client))
            (is (= [[{:text "Class: "} {:text "clojure.lang.ExceptionInfo" :index 0}]]
                   (:lines (features/inspect-error client (nrepl/clone-session client) 1 true 10))))
            (is (= [{:op "inspect-last-exception" :index 1 :ex-data "true" :page-size 10}] @test-requests)))))
      (finally
        (server/stop-server srv)))))

(deftest frames-test
  (testing "the frames of what runs the code, Clojure's REPL among them"
    (are [name tooling?] (= tooling? (boolean (#'features/tooling? {:name name})))
      "clojure.main$repl$read_eval_print__9248/invoke" true
      "clojure.core$eval/invokeStatic" true
      "clojure.lang.AFn/applyToHelper" true
      "clojure.lang.Compiler/eval" true
      "nrepl.middleware.interruptible_eval$evaluate/invokeStatic" true
      "clojure.core$map/invoke" false
      "my.app$f/invoke" false))
  (testing "a function's frame, after one of its anonymous fn's, which has its var"
    (is (= [nil nil]
           (map :flags (:stacktrace (first (#'features/normalize-causes
                                            [{:class "java.lang.ArithmeticException"
                                              :stacktrace [{:name "user$outer$inner__1/invoke" :file "NO_SOURCE_FILE" :line 1}
                                                           {:name "user$outer/invokeStatic" :file "NO_SOURCE_FILE" :line 1}]}])))))))
  (testing "duplicates by their function, not the file and line cider-nrepl goes by, which a REPL's share"
    (is (= [nil ["dup"] nil]
           (map :flags (:stacktrace (first (#'features/normalize-causes
                                            [{:class "java.lang.ArithmeticException"
                                              :stacktrace [{:name "user$inner/invokeStatic" :file "NO_SOURCE_FILE" :line 1
                                                            :var "user/inner"}
                                                           {:name "user$inner/invoke" :file "NO_SOURCE_FILE" :line 1
                                                            :var "user/inner" :flags ["dup"]}
                                                           {:name "user$outer/invokeStatic" :file "NO_SOURCE_FILE" :line 1
                                                            :var "user/outer" :flags ["dup"]}]}]))))))
    (testing "and babashka's, whose names are their vars"
      (is (= [nil nil]
             (map :flags (:stacktrace (first (#'features/normalize-causes
                                              [{:class "java.lang.ArithmeticException"
                                                :stacktrace [{:name "user/inner" :ns "user" :file "NO_SOURCE_PATH" :line 1}
                                                             {:name "user/outer" :ns "user" :file "NO_SOURCE_PATH" :line 1}]}]))))))
      (testing "with its cider-nrepl's frames as tooling, which its flags leave out"
        (is (= ["clj" "tooling"]
               (:flags (first (:stacktrace (first (#'features/normalize-causes
                                                   [{:class "java.lang.ArithmeticException"
                                                     :stacktrace [{:name "cider.nrepl.middleware.util.eval/fn"
                                                                   :flags ["clj"]}]}])))))))))))

(deftest trace-summary-test
  (testing "cider-nrepl's stack trace for inspected code that throws, as eval would say it"
    (is (= (str "clojure.lang.Compiler$CompilerException: Syntax error compiling at (1:1).\n"
                "Caused by: java.lang.RuntimeException: Unable to resolve symbol: nosuch in this context\n")
           (#'features/trace-summary
            (str "clojure.lang.Compiler$CompilerException: Syntax error compiling at (1:1).\n"
                 "#:clojure.error{:phase :compile-syntax-check, :line 1, :column 1}\n"
                 " at clojure.lang.Compiler.analyze (Compiler.java:7397)\n"
                 "Caused by: java.lang.RuntimeException: Unable to resolve symbol: nosuch in this context\n"
                 " at clojure.lang.Util.runtimeException (Util.java:221)\n"))))))

(deftest inspect-test
  (with-client [client {:port *port*}]
    (let [session (nrepl/clone-session client)
          text #(render/inspector-text (:lines %))]
      (testing "a value, with what the code printed"
        (let [{:keys [status out] :as result} (features/inspect client session "(println :hi) {:a [1 2]}" {})]
          (is (= "ok" status))
          (is (= ":hi\n" (test-server/lf out)))
          (is (= "Class: [0]clojure.lang.PersistentArrayMap\nCount: 1\n\n--- Contents:\n  [1]:a = [2][1 2]"
                 (text result)))))
      (testing "moving around in it"
        (is (str/starts-with? (text (features/inspector client session {:op "inspect-push" :idx 2}))
                              "Class: [0]clojure.lang.PersistentVector\nCount: 2\n"))
        (is (str/starts-with? (text (features/inspector client session {:op "inspect-pop"}))
                              "Class: [0]clojure.lang.PersistentArrayMap\n")))
      (testing "code that fails, the way cider-nrepl says it does"
        (let [{:keys [status lines]} (features/inspect client session "(/ 1 0)" {})]
          (is (= "eval-error" status))
          (is (nil? lines))))
      (testing "without an inspector on the server"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes cider-nrepl or babashka"
                              (features/inspect (without client "inspect-push") session "1" {})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes cider-nrepl or babashka"
                              (features/inspector (without client "inspect-push") session {:op "inspect-pop"})))))))

(deftest inspector-lines-test
  (testing "values that span lines are on each of them"
    (is (= [[{:text "Value: "} {:text "{:a 1," :index 1}]
            [{:text " :b 2}" :index 1 :continued true} {:text " and so on"}]
            [{:text "--- Path:" :heading? true}]]
           (#'features/inspector-lines
            "(\"Value: \" (:value \"{:a 1,\\n :b 2}\" 1) \" and so on\" (:newline) \"--- Path:\" (:newline))"))))
  (testing "what else might be in there"
    (is (= [[{:text "42"} {:text "x"} {:text "ab"}]]
           (#'features/inspector-lines "(42 x (\"a\" \"b\"))"))))
  (testing "what isn't a view"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"The server's inspector said 42"
                          (#'features/inspector-lines "42")))))

(deftest trace-test
  (let [events (atom [])]
    (with-client [client {:port *port* :on-unclaimed #(some->> (:cider/trace-event %) (swap! events conj))}]
      (nrepl/request client {:op "eval" :code "(ns trace.me) (defn f [x] (inc x)) (defn g [x] (f x)) (defmacro m [] 1)"})
      (try
        (testing "vars, the way the namespace has them"
          (is (= [{:var "trace.me/f" :status "traced"}
                  {:name "nope" :status "not-found"}
                  {:var "trace.me/m" :status "not-traceable"}]
                 (features/trace client ["f" "nope" "m"] {:ns "trace.me"} true))))
        (testing "but not the functions the server's own tools call, which would take it down"
          (is (= [{:var "clojure.core/map" :status "refused"}
                  {:ns "clojure.string" :status "refused"}]
                 (features/trace client ["map" "clojure.string"] {:ns "trace.me"} true)))
          (testing "only those: macros aren't traced anyway, and the likes of clojure.core.async can be"
            (is (= [{:var "clojure.core/when" :status "not-traceable"}
                    {:name "clojure.core.not-there" :status "not-found"}]
                   (features/trace client ["when" "clojure.core.not-there"] {:ns "trace.me"} true))))
          (is (= {:nses [] :vars ["trace.me/f"]} (features/traced client))))
        (testing "only when they aren't traced yet"
          (is (= [{:var "trace.me/f" :status "already-traced"}]
                 (features/trace client ["trace.me/f"] {:ns "user"} true)))
          (is (= {:nses [] :vars ["trace.me/f"]} (features/traced client))))
        (testing "namespaces, which take the place of their vars in the list"
          (is (= [{:ns "trace.me" :status "traced"}] (features/trace client ["trace.me"] {:ns "user"} true)))
          (is (= {:nses ["trace.me"] :vars []} (features/traced client))))
        (testing "calls print in the evaluation's output"
          (is (= "(trace.me/g 1)\n(trace.me/f 1)\n└─→ 2\n└─→ 2\n"
                 (test-server/lf (str/join (keep :out (nrepl/request client {:op "eval" :code "(trace.me/g 1)"})))))))
        (testing "untracing, only when they're traced"
          (is (= [{:var "trace.me/g" :status "untraced"}] (features/trace client ["trace.me/g"] {:ns "user"} false)))
          (is (= [{:var "trace.me/g" :status "not-traced"}] (features/trace client ["trace.me/g"] {:ns "user"} false))))
        (testing "everything at once"
          (is (= {:untraced 1} (features/untrace-all client)))
          (is (= {:nses [] :vars []} (features/traced client))))
        (testing "namespaces, without toggling what's traced in them"
          (features/trace client ["trace.me/f"] {:ns "user"} true)
          (is (= [{:ns "trace.me" :status "not-traced"}] (features/trace client ["trace.me"] {:ns "user"} false)))
          (is (= {:nses [] :vars ["trace.me/f"]} (features/traced client)))
          (is (= [{:ns "trace.me" :status "traced"}] (features/trace client ["trace.me"] {:ns "user"} true)))
          (is (= [{:ns "trace.me" :status "already-traced"}] (features/trace client ["trace.me"] {:ns "user"} true)))
          (is (= [{:name "no.such.ns" :status "not-found"}] (features/trace client ["no.such.ns"] {:ns "user"} false))))
        (testing "in a namespace that isn't there"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"couldn't trace f: .*No namespace: not.loaded"
                                (features/trace client ["f"] {:ns "not.loaded"} true))))
        (features/untrace-all client)
        (testing "events instead of printing, while subscribed"
          (features/trace client ["trace.me/f"] {:ns "user"} true)
          (doseq [done-at-end? [false true]]
            (testing (if done-at-end? "with the subscription's done when it ends" "with its done straight away")
              (reset! events [])
              (reset! test-server/trace-subscriptions-done-at-end? done-at-end?)
              (try
                (let [start (System/nanoTime)
                      subscription (features/subscribe-traces client)]
                  (is (string? subscription))
                  (is (< (- (System/nanoTime) start) 5e9) "without waiting for its done")
                  (is (= "2" (some :value (nrepl/request client {:op "eval" :code "(trace.me/f 1)"}))))
                  (is (= [{:phase "call" :name "trace.me/f" :depth 0 :args ["1"]}
                          {:phase "return" :name "trace.me/f" :depth 0 :value "2"}]
                         @events))
                  (features/unsubscribe-traces client subscription)
                  (is (zero? (test-server/trace-subscribers))))
                (finally
                  (reset! test-server/trace-subscriptions-done-at-end? false))))))
        (finally
          (features/untrace-all client))))))

(deftest trace-without-cider-test
  (let [srv (server/start-server :port 0)]
    (try
      (with-client [client {:port (:port srv)}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Tracing takes cider-nrepl"
                              (features/trace client ["inc"] {:ns "user"} true)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Tracing takes cider-nrepl" (features/traced client)))
        (is (nil? (features/subscribe-traces client))))
      (finally
        (server/stop-server srv)))))

(deftest taps-test
  (let [tapped (promise)]
    (with-client [client {:port *port* :on-unclaimed #(some->> (:cider/tap-value %) (deliver tapped))}]
      (let [subscription (features/subscribe-taps client)
            session (nrepl/clone-session client)]
        (try
          (is (string? subscription))
          (testing "a summary of each value tapped, as responses nobody claims"
            (nrepl/request client {:op "eval" :code "(tap> {:tapped [1 2]})"})
            (let [{:keys [idx] :as value} (deref tapped 5000 nil)]
              (is (= {:summary "{:tapped [1 2]}" :type "clojure.lang.PersistentArrayMap"}
                     (dissoc value :idx)))
              (testing "which the inspector takes by its number"
                (is (str/includes? (render/inspector-text (:lines (features/inspect-tap client session idx 10)))
                                   ":tapped")))
              (testing "while the server keeps it"
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"doesn't keep that value anymore"
                                      (features/inspect-tap client session 99999 10))))))
          (finally
            (features/unsubscribe-taps client subscription)))
        (testing "until it's over"
          (is (zero? (test-server/tap-subscribers)))))
      (testing "nil when the server can't"
        (is (nil? (features/subscribe-taps (assoc client :ops {}))))))))

(deftest forward-system-output-test
  (let [printed (atom [])]
    (with-client [client {:port *port* :on-unclaimed #(swap! printed conj (select-keys % [:out :err]))}]
      (let [session (features/forward-system-output client)]
        (testing "in a session of its own"
          (is (string? session))
          (is (= "user" (nrepl/session-ns client session))))
        (testing "what's printed coming as responses nobody claims"
          (test-server/print-system! :out "elsewhere\n")
          (test-server/print-system! :err "and there\n")
          ;; Which come before the answer to what's asked after.
          (nrepl/request client {:op "describe"})
          (is (= [{:out "elsewhere\n"} {:err "and there\n"}] @printed)))
        (testing "nil when the server can't"
          (is (nil? (features/forward-system-output (assoc client :ops {})))))
        (testing "or when what it prints comes out here already"
          (is (nil? (features/forward-system-output (assoc client :shares-output? true)))))))))
