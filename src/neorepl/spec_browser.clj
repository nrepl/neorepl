(ns neorepl.spec-browser
  "The REPL's spec browser: the specs in the registry full-screen, to filter
  and go through their forms, from a spec to the ones its form names, and
  generate examples of them."
  (:require [clojure.string :as str]
            [neorepl.browser :as browser]
            [neorepl.lexer :as lexer]))

(def ^:private helps
  "What the keys do, by the kind of view."
  {:specs "↑↓ move  → form  / filter  q quit"
   :spec "↑↓ move  → spec  x example  ← back  q quit"})

(defn specs-view
  "The view of the specs called names, the ones with typed in their names,
  with the one called current selected, if it's there."
  [names current typed]
  (cond-> (browser/list-view {:kind :specs
                              :names (set names)
                              :filter typed
                              :heading "Specs"
                              :listed (mapv (fn [name] [{:text name :index name}]) names)
                              :help (helps :specs)}
                             current)
    (empty? names) (assoc :message "No specs in the registry")))

(defn- linked
  "line, the row'th of a form, as segments, with the specs it names, those
  in names, as values to move to, each with an index of its own, [row
  start spec], so that each is selected on its own."
  [row line names]
  (loop [at 0
         [{:keys [start end] :as token} & more :as refs]
         (filter #(and (#{:keyword :symbol} (:type %)) (names (lexer/text line %)))
                 (lexer/tokenize line))
         segments []]
    (if (seq refs)
      (recur end more (cond-> segments
                        (< at start) (conj {:text (subs line at start)})
                        true (conj (let [spec (lexer/text line token)]
                                     {:text spec :index [row start spec]}))))
      (cond-> segments (< at (count line)) (conj {:text (subs line at)})))))

(defn spec-view
  "The view of the spec called spec, whose form is text, with the specs it
  names, those in names, to move to, and an example under it, if there's
  one, with back, the view to go back to."
  [spec form names back example]
  (assoc (browser/view (concat [[{:text spec :heading? true}]]
                               (map-indexed #(linked %1 %2 names) (str/split-lines (str/trimr form)))
                               (when example
                                 (cons [] (cons [{:text "Example" :heading? true}]
                                                (for [line (str/split-lines (str/trimr example))]
                                                  [{:text line}]))))))
         :kind :spec
         :spec spec
         :form form
         :names names
         :help (helps :spec)
         :back back))

(defn handle
  "view after key, as editor/full-screen has keys, or (reduced nil) when
  the user's done. The views ask the server with the functions in ask: a
  spec's :form and an :example of it, given its name."
  [view key {:keys [form example]}]
  (browser/listing-handle
   view key
   (fn [{:keys [kind back names] :as view} key]
     (let [[_ picked] (browser/selected-value view)
           ;; The spec to show next.
           spec (case kind
                  :specs picked
                  :spec (peek picked))]
       (case [kind key]
         ([:specs :enter] [:specs :right] [:spec :enter] [:spec :right])
         (if spec
           (browser/asked view #(if-let [text (form spec)]
                                  (spec-view spec text names view nil)
                                  (assoc view :message (str "No spec called " spec))))
           view)
         [:spec "x"] (browser/asked view #(if-let [text (example (:spec view))]
                                            (assoc (spec-view (:spec view) (:form view) names back text)
                                                   :selected (:selected view))
                                            (assoc view :message (str "No spec called " (:spec view)))))
         view)))))

(defn browse
  "Shows the specs called names full-screen with browse, a browser/browse
  partly applied to the editor, until the user's done, asking the server
  with ask, as handle takes it. Given a spec's name, it starts at the
  spec's form, with the list to go back to, or with a name that isn't a
  spec's, at the list of those with it in their names."
  [browse names spec ask]
  (let [spec? (some #{spec} names)
        specs (specs-view names spec (when-not spec? spec))]
    ;; Each view has help of its own.
    (browse (if spec?
              (handle specs :right ask)
              specs)
            nil
            #(handle %1 %2 ask))))
