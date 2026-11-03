(ns neorepl.ns-browser
  "The REPL's namespace browser: the loaded namespaces full-screen, the
  project's or all of them, to switch to one, or to go through its public
  vars and their docs, and open one in an editor."
  (:require [clojure.string :as str]
            [neorepl.browser :as browser]))

(def ^:private helps
  "What the keys do, by the kind of view."
  {:namespaces "↑↓ move  Enter switch  → vars  e edit  a all  / filter  q quit"
   :vars "↑↓ move  → doc  e edit  ← back  / filter  q quit"
   :doc "Space page  e edit  ← back  q quit"})

(defn namespaces-view
  "The view of the namespaces called names, the project's, or all of them
  if all?, the ones with typed in their names, with the one called current
  selected, if it's there."
  [names all? current typed]
  (browser/list-view {:kind :namespaces
                      :all? all?
                      :filter typed
                      :heading (if all? "Loaded namespaces" "The project's namespaces")
                      :listed (mapv (fn [name] [{:text name :index name}]) names)
                      :help (helps :namespaces)}
                     current))

(defn start-view
  "The view the browser starts at, with the project's namespaces, called
  names, and the one called current selected."
  [names current]
  (cond-> (namespaces-view names false current nil)
    (empty? names) (assoc :message "None of the project's namespaces are loaded, a shows the rest")))

(defn vars-view
  "The view of vars, the public vars of the namespace called ns, as
  features/ns-vars has them, each with the first line of its doc, with
  back, the view to go back to."
  [ns vars back]
  (browser/list-view {:kind :vars
                      :ns ns
                      :heading ns
                      :listed (mapv (fn [{:keys [name doc]}]
                                      (cond-> [{:text name :index name}]
                                        doc (conj {:text (str "  " (first (str/split-lines (str/trim doc))))
                                                   :style :notice})))
                                    vars)
                      :help (helps :vars)
                      :back back}
                     nil))

(defn doc-view
  "The view of text, the doc of the var called sym, with back, the view to
  go back to."
  [sym text back]
  (assoc (browser/text-view text) :kind :doc :sym sym :help (helps :doc) :back back))

(defn handle
  "view after key, as editor/full-screen has keys, or (reduced x), x being
  what the user picked, with the :view to come back to: the namespace to
  switch to, as its :ns, or the namespace or var, qualified, to :edit, or
  nil when they're done. The views ask the server with the functions in
  ask: its :namespaces, given all?, a namespace's :vars, given its name,
  and a var's :doc, given its qualified name."
  [{:keys [kind back all?] :as view} key {:keys [namespaces vars doc]}]
  (let [view (dissoc view :message)
        [_ picked] (browser/selected-value view)
        ;; What e opens.
        target (case kind
                 :namespaces picked
                 :vars (some->> picked (str (:ns view) "/"))
                 :doc (:sym view))]
    (if (:prompt view)
      (browser/filtered view key)
      (or (browser/moved view key)
          (case key
        ("q" :escape :ctrl-c) (reduced nil)
        (:left :backspace) (or back view)
        "e" (if target (reduced {:edit target :view view}) view)
        "/" (if (= :doc kind) view (browser/filtering view))
        (case [kind key]
          [:namespaces :enter] (if picked (reduced {:ns picked :view view}) view)
          [:namespaces :right] (if picked (browser/asked view #(vars-view picked (vars picked) view)) view)
          [:namespaces "a"] (browser/asked view #(namespaces-view (namespaces (not all?)) (not all?) picked (:filter view)))
          ([:vars :enter] [:vars :right]) (if target
                                            (browser/asked view #(if-let [text (doc target)]
                                                                   (doc-view target text view)
                                                                   (assoc view :message (str "Nothing called " target))))
                                            view)
          view))))))

(defn browse
  "Shows the namespaces called names, the project's, full-screen with
  browse, a browser/browse partly applied to the editor, with the one
  called current selected, and returns what the user picked, as handle has
  it, asking the server with ask, as handle takes it. It starts at view,
  one handle gave back, if there's one."
  [browse names current ask {:keys [view]}]
  ;; Each view has help of its own.
  (browse (or view (start-view names current)) nil #(handle %1 %2 ask)))
