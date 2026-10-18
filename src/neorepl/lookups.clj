(ns neorepl.lookups
  "The lookups neorepl's commands and the REPL's have in common, so both
  work and read the same: what each takes, how it finds its answer, how that
  reads as text and what it says when there's nothing to find."
  (:require [clojure.string :as str]
            [neorepl.features :as features]
            [neorepl.render :as render]))

(defn- untraceable?
  "Whether tracing, or untracing, didn't find something to trace."
  [{:keys [results]}]
  (boolean (some #(#{"not-found" "not-traceable"} (:status %)) results)))

(def all
  "Each lookup takes the :arg it names, unless it has none: :code-arg? says
  that's code, :args? that it's several, as a seq, and :optional? that it can
  be left out. Its :find takes the client, a context with the :ns to look in,
  the :session and the session's :session-name, if it has one, as
  neorepl.features has it, and the argument. :text turns what it finds into
  text, code when :code? says so, and :none says what finding nothing means.
  :failed? says whether what it found means it failed after all."
  [{:name "doc" :arg "SYM" :help "Show the documentation of SYM"
    :find (fn [client ctx sym] (features/info client sym ctx))
    :text render/doc-text
    :none (fn [{:keys [ns]} sym] (str "Nothing called " sym " in " ns))}
   {:name "source" :arg "SYM" :help "Show the source of SYM"
    :find (fn [client ctx sym]
            (some->> (features/source client sym ctx) (hash-map :source)))
    :text :source
    :code? true
    :none (fn [{:keys [ns]} sym] (str "No source for " sym " in " ns))}
   {:name "apropos" :arg "REGEX" :help "List the vars whose names match REGEX"
    :find (fn [client _ regex] (not-empty (features/apropos client regex)))
    :text render/apropos-text
    :none (fn [_ regex] (str "Nothing matches " regex))}
   {:name "macroexpand" :arg "FORM" :code-arg? true :help "Expand the macro FORM calls"
    :find (fn [client ctx form] {:expansion (features/expand client form ctx false)})
    :text :expansion
    :code? true}
   {:name "macroexpand-all" :arg "FORM" :code-arg? true :help "Expand every macro in FORM"
    :find (fn [client ctx form] {:expansion (features/expand client form ctx true)})
    :text :expansion
    :code? true}
   {:name "where" :arg "SYM" :help "Show where SYM is defined"
    :find (fn [client ctx sym]
            (let [{:keys [file] :as info} (features/info client sym ctx)]
              (when file
                (select-keys info [:file :line :column]))))
    :text render/location-text
    :none (fn [{:keys [ns]} sym] (str "Nowhere to find " sym " in " ns))}
   {:name "reload" :arg "NS..." :args? true :optional? true
    :help "Reload the namespaces NS, or the ones that changed"
    :find (fn [client _ nses] (features/reload client nses))
    :text (fn [{:keys [reloaded output]}]
            (str (some-> output str/trimr (str "\n"))
                 (if (seq reloaded)
                   (str "Reloaded " (str/join ", " reloaded))
                   "Nothing changed")))}
   {:name "trace" :arg "SYM..." :args? true :optional? true
    :help "Trace the functions SYM, or a namespace's, or list what's traced"
    :find (fn [client ctx syms]
            (if (seq syms)
              {:results (features/trace client syms ctx true)}
              (features/traced client)))
    :text render/tracing-text
    :failed? untraceable?}
   {:name "untrace" :arg "SYM..." :args? true :optional? true
    :help "Stop tracing the functions SYM, or a namespace's, or everything"
    :find (fn [client ctx syms]
            (if (seq syms)
              {:results (features/trace client syms ctx false)}
              (features/untrace-all client)))
    :text render/tracing-text
    :failed? untraceable?}
   {:name "pst" :help "Show the stack trace of the last error"
    :find (fn [client {:keys [session]} _] (features/last-error client session))
    :text render/error-text
    :none (fn [{:keys [session-name]} _]
            (str "No error yet" (some->> session-name (str " in session "))))}])

(defn lookup [name]
  (some #(when (= name (:name %)) %) all))

(defn run
  "Does the lookup called name: {:found what-it-found :text as-text}, with
  :failed? true when what it found means it failed, or {:none
  what-that-means} when it finds nothing."
  [name client ctx arg]
  (let [{:keys [find text none failed?]} (lookup name)]
    (if-let [found (find client ctx arg)]
      (cond-> {:found found :text (text found)}
        (and failed? (failed? found)) (assoc :failed? true))
      {:none (none ctx arg)})))
