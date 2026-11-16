(ns neorepl.tool
  "neorepl as a Clojure CLI tool, installed from git:

    clojure -Ttools install-latest :lib io.github.nrepl/neorepl :as neorepl
    clojure -Tneorepl repl

  Each of neorepl's commands is a function here, which -T calls with a map
  of the command's long options and its arguments, as a vector under :args,
  so clojure -Tneorepl eval :session agent :args '[\"(+ 1 2)\"]' is
  neorepl eval --session agent '(+ 1 2)'."
  ;; The functions named like these come from defcommands, which clj-kondo
  ;; can't see into.
  {:clj-kondo/config '{:linters {:unused-excluded-var {:level :off}}}}
  (:refer-clojure :exclude [eval load macroexpand test])
  (:require [clojure.string :as str]
            [neorepl.cli :as cli]
            [neorepl.main :as main]))

(defn- argv
  "The command line neorepl takes for command with opts: its long options,
  by name, with their values, and its arguments under :args, one or a
  vector of them. An option that takes no value takes true, and false or
  nil leave any option out. A string stays
  as it is, a keyword's value is its name, so :format :json works, and
  anything else is printed, so a symbol is its name and (+ 1 2) is
  \"(+ 1 2)\". The arguments go after --, so code like -1 isn't taken for an
  option."
  [command {:keys [args] :as opts}]
  (let [text #(if (string? %) % (pr-str %))
        takes-value (into #{}
                          (comp (filter :arg) (map cli/option-key))
                          (some #(when (= command (:name %)) (:options %)) @#'main/commands))
        args (cond (nil? args) [] (vector? args) args :else [args])]
    (cond-> (into [command]
                  (mapcat (fn [[k v]]
                            (let [option (str "--" (name k))]
                              (cond (or (nil? v) (false? v)) []
                                    (takes-value k) [option (if (keyword? v) (name v) (text v))]
                                    :else [option]))))
                  (sort-by key (dissoc opts :args)))
      (seq args) (into (cons "--" (map text args))))))

(defn- native-access?
  "Whether the JVM lets classpath code call native code without a warning,
  which only Java 22 and later print."
  []
  #?(:bb true
     :clj (or (< (.feature (Runtime/version)) 22)
              ;; Module has the method from 22 on, as the check makes sure.
              (.isNativeAccessEnabled (.getModule clojure.lang.RT)))))

(defn- terminal-properties
  "The JLine properties the tool's REPL takes, given whether the JVM has
  native-access and the os it runs on. An installed tool can't give the JVM
  --enable-native-access, without which JLine's terminals print the JDK's
  warning about native access, so then it takes JLine's exec terminal,
  which runs stty, and has that make the pipes it needs without native code
  too. Windows has no stty and keeps the warning."
  [native-access os]
  (if (or native-access (str/starts-with? os "Windows"))
    {}
    {"org.jline.terminal.provider" "exec"
     "org.jline.terminal.exec.redirectPipeCreationMode" "reflection"}))

(defn- run
  "Runs neorepl's command called command with opts, as -T gives them, and
  exits with its status."
  [command opts]
  (doseq [[k v] (terminal-properties (native-access?) (System/getProperty "os.name"))]
    (System/setProperty k v))
  (apply main/-main (argv command opts)))

(defmacro ^:private defcommands
  "A function for each of neorepl's commands, named after it, and its
  summary as the docstring, but for the REPL's \"(the default)\", which -T
  has none of."
  []
  `(do ~@(for [{:keys [name summary]} @#'main/commands]
           `(defn ~(symbol name)
              ~(str (str/replace summary #" \(the default\)$" "") ". :help true shows its options.")
              [~'opts]
              (run ~name ~'opts)))))

(defcommands)
