(ns neorepl.repair
  "Repairs unbalanced delimiters from the indentation, the way parinfer's
  indent mode does: a form's children are indented past its opening
  delimiter, so a line's indentation closes the delimiters opened at its
  column or after it. The closers at the ends of lines follow from that, and
  closers that close nothing go.

  It only touches the top-level forms that don't balance, and leaves a file
  alone when it can't tell how to repair it: an unterminated string, which
  indentation says nothing about, or tabs in the indentation of a form it
  would change."
  (:require [clojure.string :as str]
            [neorepl.lexer :as lexer])
  (:import (java.util Collections)))

(defn- line-starts
  "The index each line of s starts at."
  [^String s]
  (into [0] (keep-indexed (fn [i c] (when (= \newline c) (inc i)))) s))

(defn- line-of
  "The line, counting from 0, that index is on, given the line-starts."
  [starts index]
  (let [i (Collections/binarySearch starts index)]
    (if (neg? i) (- (- i) 2) i)))

(defn- indentation
  "The index of the first character on the line starting at start that
  isn't a space or a tab."
  [^String s start]
  (loop [i start]
    (if (and (< i (.length s)) (#{\space \tab} (.charAt s i)))
      (recur (inc i))
      i)))

(defn- close-to
  "state with the delimiters opened at column col or after it closed, at
  the end of the last line of code."
  [{:keys [point] :as state} col]
  (loop [{:keys [stack] :as state} state]
    (let [top (peek stack)]
      (if (and top (>= (:col top) col))
        (recur (-> state
                   (update :stack pop)
                   (update-in [:inserts point] str (:closer top))))
        state))))

(defn- indent-fix
  "The closers to delete from s, as the set of their indexes, and those to
  insert, as a map of the indexes they go in at to them, for the delimiters
  of tokens, a top-level form's, to close the way its indentation says."
  [^String s starts tokens]
  (-> (reduce
       (fn [state ts]
         (let [line-start (nth starts (line-of starts (:start (first ts))))
               col #(- (:start %) line-start)
               [first-token] ts
               ;; A line in a multi-line string doesn't start with a token.
               state (if (and (= (:start first-token) (indentation s line-start))
                              (not= :close (:type first-token)))
                       (close-to state (col first-token))
                       state)
               ;; The closers ending the line follow from the indentation.
               trail (- (count ts) (count (take-while #(= :close (:type %)) (reverse ts))))
               state (reduce (fn [{:keys [stack] :as state} [k {:keys [type start] :as token}]]
                               (case type
                                 :open (update state :stack conj {:col (col token) :closer (lexer/closer s token)})
                                 :close (if (and (< k trail) (= (:closer (peek stack)) (lexer/text s token)))
                                          (update state :stack pop)
                                          (update state :deletes conj start))
                                 state))
                             state
                             (map-indexed vector ts))]
           (cond-> state
             (pos? trail) (assoc :point (:end (nth ts (dec trail)))))))
       {:stack [] :deletes #{} :inserts {} :point nil}
       (partition-by #(line-of starts (:start %)) (remove #(= :comment (:type %)) tokens)))
      (close-to -1)
      (select-keys [:deletes :inserts])))

(defn- edit
  "s with the closers starting at the indexes in deletes left out, the
  strings in inserts put in at their indexes, and the lines that only had
  deleted closers dropped."
  [^String s starts tokens deletes inserts]
  (let [emptied (set (for [ts (partition-by #(line-of starts (:start %)) tokens)
                           :let [line (line-of starts (:start (first ts)))]
                           :when (every? #(contains? deletes (:start %)) ts)
                           ;; Not one with the end of a multi-line string.
                           :when (= (:start (first ts)) (indentation s (nth starts line)))]
                       line))
        sb (StringBuilder.)]
    (doseq [[line start] (map-indexed vector starts)
            :when (not (emptied line))
            i (range start (or (get starts (inc line)) (.length s)))]
      (some->> (get inserts i) (.append sb))
      (when-not (contains? deletes i)
        (.append sb (.charAt s i))))
    (some->> (get inserts (.length s)) (.append sb))
    (str sb)))

(defn- parts
  "tokens split at the lines that may start top-level forms, those that
  start in column 0 with code other than a closer, as [line tokens]."
  [starts tokens]
  (reduce (fn [parts {:keys [type start] :as token}]
            (let [line (line-of starts start)]
              (if (or (empty? parts)
                      (and (= start (nth starts line)) (not (#{:comment :close} type))))
                (conj parts [line [token]])
                (update-in parts [(dec (count parts)) 1] conj token))))
          []
          tokens))

(defn- run-length
  "How many of parts, from the first one on, balance together, or nil when
  no run of them does, which makes the first one broken. A closer that
  doesn't match ends the search, as no later part can make up for it."
  [^String s parts]
  (loop [stack [] [[_ ts] & more] parts n 1]
    (let [stack (reduce (fn [stack {:keys [type] :as token}]
                          (case type
                            :open (conj stack (lexer/closer s token))
                            :close (if (= (peek stack) (lexer/text s token))
                                     (pop stack)
                                     (reduced nil))
                            stack))
                        stack
                        ts)]
      (cond
        (nil? stack) nil
        (empty? stack) n
        (seq more) (recur stack more (inc n))
        :else nil))))

(defn- tabbed?
  "Whether a line of tokens is indented with a tab."
  [^String s starts tokens]
  (some (fn [line]
          (let [start (nth starts line)]
            (str/includes? (subs s start (indentation s start)) "\t")))
        (distinct (map #(line-of starts (:start %)) tokens))))

(defn repair
  "s with the delimiters of its top-level forms that don't balance repaired
  from their indentation, as {:text repaired}, or {:error why} when it
  can't repair them."
  [^String s]
  (let [{:keys [tokens]} (lexer/lex s)
        starts (line-starts s)]
    ;; An unterminated string runs to the end, so it's the last token.
    (if (= :open-string (:type (peek tokens)))
      {:error "Can't tell where an unterminated string or regex should end"}
      (let [broken (loop [ps (parts starts tokens) broken []]
                     (if (seq ps)
                       (if-let [n (run-length s ps)]
                         (recur (drop n ps) broken)
                         (recur (rest ps) (conj broken (first ps))))
                       broken))]
        (if-let [[line] (some (fn [[_ ts :as part]] (when (tabbed? s starts ts) part)) broken)]
          {:error (str "Can't tell the indentation apart from tabs, in the form at line " (inc line))}
          (let [{:keys [deletes inserts]} (apply merge-with into {:deletes #{} :inserts {}}
                                                 (for [[_ ts] broken] (indent-fix s starts ts)))
                text (edit s starts tokens deletes inserts)]
            (if-let [{:keys [message line column]} (lexer/delimiter-problem text)]
              {:error (str "Repairing it from the indentation leaves it unbalanced: " message " at " line ":" column)}
              {:text text})))))))

(defn fix-text
  "text, and the first delimiter problem in it, as lexer/delimiter-problem
  has it, if there's one, repaired: [repaired problem], the problem :fixed
  when that worked, and with a :fix-error saying why it didn't, when it
  didn't."
  [text]
  (if-let [problem (lexer/delimiter-problem text)]
    (let [{repaired :text :keys [error]} (repair text)]
      (if repaired
        [repaired (assoc problem :fixed true)]
        [text (assoc problem :fix-error error)]))
    [text nil]))

(defn fix-file
  "The first delimiter problem in the file at path, as lexer/file-problem
  has it, after repairing it, as fix-text does, and writing it back."
  [path]
  (if-let [text (try (slurp path) (catch java.io.IOException _ nil))]
    (let [[repaired problem] (fix-text text)]
      (when (:fixed problem)
        (spit path repaired))
      (some->> problem (into {:file path})))
    ;; It can't be read, which lexer/file-problem says.
    (lexer/file-problem path)))
