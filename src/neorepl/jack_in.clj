(ns neorepl.jack-in
  "Starting an nREPL server for a project, with cider-nrepl, the way CIDER's
  jack-in does: the project's own build tool runs it, so it has the
  project's classpath."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [neorepl.discovery :as discovery]
            [neorepl.shutdown :as shutdown])
  (:import (java.io BufferedReader File IOException)
           (java.util.regex Pattern)))

(def versions
  "What goes on the server's classpath: nREPL, newer than what the build tool
  might bring, cider-nrepl and piggieback, for ClojureScript REPLs.
  piggieback does nothing in projects without ClojureScript. shadow-cljs
  only gets cider-nrepl, since it comes with the nREPL it's made for and
  runs ClojureScript REPLs itself."
  {:nrepl "1.8.0"
   :cider-nrepl "0.63.1"
   :piggieback "0.8.0"})

(defn- builds-with-project?
  "Whether the shadow-cljs.edn in dir has shadow-cljs build with the
  deps.edn or project.clj next to it, as its :deps or :lein says."
  [dir]
  (let [config (try
                 ;; Tags like #shadow/env as they are.
                 (edn/read-string {:default (fn [_ v] v)} (slurp (io/file dir "shadow-cljs.edn")))
                 (catch Exception _ nil))]
    (or (and (:deps config) (.isFile (io/file dir "deps.edn")))
        (and (:lein config) (.isFile (io/file dir "project.clj"))))))

(def tools
  "The build tools jack-in knows, by the file a project of theirs has,
  whether they take :aliases?, deps.edn aliases or Leiningen profiles, and
  a :port?. A directory with several files goes with the first, unless
  (first? dir) says otherwise: a deps.edn next to a project.clj is usually
  there for tooling, a bb.edn next to either holds the project's tasks, and
  a shadow-cljs.edn builds the ClojureScript part, unless it builds with
  the project's deps.edn or project.clj, when its server has it all."
  [{:tool "lein" :file "project.clj" :aliases? true :port? true}
   {:tool "clojure" :file "deps.edn" :aliases? true :port? true}
   {:tool "bb" :file "bb.edn" :port? true}
   ;; It takes its port and aliases from shadow-cljs.edn.
   {:tool "shadow-cljs" :file "shadow-cljs.edn" :first? builds-with-project?}])

(defn tool
  "The entry in tools for the tool called name."
  [name]
  (some #(when (= name (:tool %)) %) tools))

(defn project-files
  "The files that make a project tool's, or any tool's without one, as text."
  [tool-name]
  (let [files (map :file (if tool-name [(tool tool-name)] tools))]
    (if (next files)
      (str (str/join ", " (butlast files)) " or " (last files))
      (first files))))

(defn- tools-in
  "The tools with their files in dir, in the order to pick them."
  [dir]
  (sort-by #(not (when-let [first? (:first? %)] (first? dir)))
           (filter #(.isFile (io/file dir (:file %))) tools)))

(defn find-project
  "The project dir is in: the nearest directory, dir or a parent, with a file
  of tool's, or of any tool's without one, as its :dir and the :tool to use.
  Nil when there's none."
  [dir tool-name]
  (first (for [d (discovery/dirs dir)
               t (tools-in d)
               :when (or (nil? tool-name) (= tool-name (:tool t)))]
           {:dir (str d) :tool (:tool t)})))

(defn no-project
  "The error for there being no project for find-project to find."
  [dir tool-name]
  (ex-info (str "No " (project-files tool-name) " in " (.getCanonicalPath (io/file dir)) " or its parents")
           {:type ::no-project}))

(defn command
  "The command line that starts an nREPL server with cider-nrepl for tool,
  on port, or any port without one, for the tools that take one. aliases are
  the deps.edn aliases, or Leiningen profiles, to start it with, as names
  without the colon, for the tools that take them."
  [tool-name {:keys [aliases port]}]
  (let [{:keys [nrepl cider-nrepl piggieback]} versions]
    (case tool-name
      "clojure" (cond-> ["clojure"
                         ;; The same every time, so the CLI's classpath cache
                         ;; keeps working.
                         "-Sdeps" (binding [*print-namespace-maps* false]
                                    (pr-str {:deps {'nrepl/nrepl {:mvn/version nrepl}
                                                    'cider/cider-nrepl {:mvn/version cider-nrepl}
                                                    'cider/piggieback {:mvn/version piggieback}}
                                             :aliases {:neorepl/jack-in
                                                       {:main-opts ["-m" "nrepl.cmdline"
                                                                    "--middleware"
                                                                    "[cider.nrepl/cider-middleware cider.piggieback/wrap-cljs-repl]"]}}}))
                         ;; The jack-in alias goes last, so its :main-opts win.
                         (str "-M" (str/join (map #(str ":" %) (concat aliases ["neorepl/jack-in"]))))]
                  port (conj "--port" (str port)))
      "lein" (concat ["lein"
                      "update-in" ":dependencies" "conj" (str "[nrepl/nrepl " (pr-str nrepl) "]") "--"
                      "update-in" ":dependencies" "conj" (str "[cider/piggieback " (pr-str piggieback) "]") "--"
                      "update-in" ":repl-options:nrepl-middleware" "conj" "cider.piggieback/wrap-cljs-repl" "--"
                      "update-in" ":plugins" "conj" (str "[cider/cider-nrepl " (pr-str cider-nrepl) "]") "--"]
                     (when (seq aliases)
                       ;; Added to the default profiles, unless they say otherwise.
                       ["with-profile" (str/join "," (map #(if (re-find #"^[+-]" %) % (str "+" %)) aliases))])
                     ["repl" ":headless"]
                     (when port [":port" (str port)]))
      ;; babashka's server has CIDER's essentials built in.
      "bb" ["bb" "nrepl-server" (str "127.0.0.1:" (or port 0))]
      ;; Its server loads cider-nrepl when it's there.
      "shadow-cljs" ["npx" "shadow-cljs" "-d" (str "cider/cider-nrepl:" cider-nrepl) "server"])))

(defn- address
  "Where the server that printed line says it's listening, if it does: nREPL,
  Leiningen and shadow-cljs say it their way, babashka its own."
  [line]
  (if-let [[_ port host] (re-find #"nREPL server started on port (\d+)(?: on host (\S+))?" line)]
    {:host (or host "127.0.0.1") :port (Long/parseLong port)}
    (when-let [[_ host port] (re-find #"Started nREPL server at (\S+):(\d+)" line)]
      {:host host :port (Long/parseLong port)})))

(def ^:private windows?
  (str/starts-with? (System/getProperty "os.name") "Windows"))

(defn on-path?
  "Whether program is on the PATH, with one of the extensions Windows runs
  programs by there."
  [program]
  (let [extensions (if windows?
                     (cons "" (str/split (or (System/getenv "PATHEXT") ".COM;.EXE;.BAT;.CMD") #";"))
                     [""])]
    (boolean (some (fn [dir]
                     (some #(let [file (io/file dir (str program %))]
                              (and (.isFile file) (.canExecute file)))
                           extensions))
                   (str/split (or (System/getenv "PATH") "") (re-pattern (Pattern/quote File/pathSeparator)))))))

(def ^:private detached
  "A Node script running the command in its arguments in a session of its
  own, where the terminal's signals don't reach it. It ignores SIGINT
  itself and passes the signals that stop it on to the command, so the
  command doesn't outlive it."
  "process.on('SIGINT', () => {});
const [command, ...args] = process.argv.slice(1);
const child = require('child_process').spawn(command, args, {stdio: 'inherit', detached: true})
  .on('error', e => { console.error(e.message); process.exit(127); })
  .on('exit', code => process.exit(code ?? 1));
for (const signal of ['SIGHUP', 'SIGTERM', 'SIGQUIT'])
  process.on(signal, () => { try { process.kill(-child.pid, signal); } catch {} });")

(defn- launch-command
  "The command line that runs command so that the server ignores SIGINT,
  which a terminal sends everything in the foreground on Ctrl-C, when it's
  meant for whatever runs the server. A signal ignored before exec stays
  ignored, and the JVM leaves it that way. But Node resets the signals it
  starts with, so the JVM that npx starts needs a session of its own."
  [command]
  (cond
    windows? command
    (= "npx" (first command)) (concat ["node" "-e" detached "--"] command)
    :else (concat ["sh" "-c" "trap '' INT; exec \"$@\"" "sh"] command)))

(defn- stop
  "Stops process, and the processes it started, the way they would on
  SIGTERM, giving them a few seconds to before killing them."
  [^Process process]
  ;; Build tools like Leiningen run the project in a process of its own.
  ;; Their handles go without type hints, since babashka doesn't know
  ;; ProcessHandle as a class.
  (let [all (cons (.toHandle process) (iterator-seq (.iterator (.descendants process))))
        deadline (+ (System/currentTimeMillis) 5000)]
    (run! #(.destroy %) all)
    (doseq [p all]
      (when (= ::timeout (deref (.onExit p) (max 0 (- deadline (System/currentTimeMillis))) ::timeout))
        (.destroyForcibly p)))))

(defn with-server
  "Starts the server command describes in dir and, once it says where it
  listens, calls (f server) with its :host, :port and :process, returning
  what f does. The server stops when f returns, or when neorepl exits
  first, even before the server's up. Each line it prints goes to
  (on-line line), on a thread of its own, the last of them before this
  returns. Throws ::failed when the server can't start, or ends before it's
  up. Ctrl-C in the terminal doesn't stop it, see launch-command."
  [dir command on-line f]
  (let [;; Rather than the shell's word for it, exit status 127, or Java's.
        missing #(when-not (on-path? (first command))
                   (ex-info (str "Couldn't start the server: " (first command) " isn't installed, or isn't on the PATH")
                            {:type ::failed}))
        process (try
                  (-> (ProcessBuilder. ^java.util.List (vec (launch-command command)))
                      (.directory (io/file dir))
                      (.redirectErrorStream true)
                      .start)
                  (catch IOException e
                    (throw (or (missing)
                               (ex-info (str "Couldn't start the server: " (ex-message e)) {:type ::failed})))))
        up (promise)
        output (doto (Thread. (fn []
                                (with-open [^BufferedReader r (io/reader (.getInputStream process))]
                                  ;; Not doseq over line-seq, which keeps every line
                                  ;; on babashka.
                                  (loop []
                                    (when-let [line (.readLine r)]
                                      (on-line line)
                                      (when-not (realized? up)
                                        (some->> (address line) (deliver up)))
                                      (recur))))
                                (.waitFor process)
                                (deliver up nil))
                              "neorepl-server-output")
                 (.setDaemon true))]
    ;; Nothing for it to read.
    (.close (.getOutputStream process))
    (shutdown/call-with-cleanup
     ;; What it says as it stops goes out too.
     #(do (stop process)
          (.join output 1000))
     (fn []
       (.start output)
       (if-let [address @up]
         (f (assoc address :process process))
         (let [status (.waitFor process)]
           (throw (or (when (= 127 status) (missing))
                      (ex-info (str "The server ended before it started, with exit status " status)
                               {:type ::failed})))))))))
