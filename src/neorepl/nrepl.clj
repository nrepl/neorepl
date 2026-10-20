(ns neorepl.nrepl
  "The client side of the nREPL protocol on top of nrepl.core's transports.

  A client owns one connection and one reader thread. The reader hands every
  response to the handler registered for its message id. Responses nobody
  claims go to the client's unclaimed handler, from `connect` or
  `on-unclaimed!`: typically output that a future prints after the
  evaluation that started it is done.

  Values stay strings. Reading them on the client would break on truncated
  output and on tagged literals the client doesn't know about."
  (:require [nrepl.core :as nrepl]
            [nrepl.misc :as misc]
            [nrepl.transport :as transport])
  (:import (java.io Closeable)
           (java.net InetSocketAddress Socket URI URISyntaxException)))

(def default-port
  "The port nREPL URLs without one point at, same as in nrepl.core."
  7888)

(defn parse-url
  "Connection options for an nREPL URL: nrepl://host[:port] or the
  nrepl+unix:/path form servers listening on a unix domain socket print."
  [^String url]
  (if-let [[_ path] (re-matches #"(?i)nrepl\+unix:(?!//)(.+)" url)]
    ;; Servers percent-encode the path when they print the URL.
    {:socket (try (.getSchemeSpecificPart (URI. url))
                  (catch URISyntaxException _ path))}
    (if-let [[_ ipv6 host port] (re-matches #"(?i)nrepl://(?:\[([^\]]+)\]|([^:/\[\]]+))(?::(\d+))?/?" url)]
      {:host (or ipv6 host)
       :port (if port (Long/parseLong port) default-port)}
      (throw (ex-info (str "Unsupported nREPL URL: " url) {:url url})))))

(defn status?
  "True when response carries status (a string such as \"done\")."
  [response status]
  (boolean (some #{status} (:status response))))

(defn done? [response]
  (status? response "done"))

(defn- read-responses
  "The reader thread's loop. Once the connection is gone, the pending handler
  map becomes nil and every handler that was still waiting gets a final
  response with the statuses done and disconnected. It calls
  before-read before each read."
  [{:keys [transport pending on-unclaimed before-read]} on-error]
  (let [call (fn [handler response]
               ;; A broken handler mustn't take the connection down with it.
               (try (handler response) (catch Throwable e (on-error e))))]
    (try
      ;; Not over nrepl/response-seq: on babashka, walking the lazy seq keeps
      ;; its head, and with it every response the connection ever got.
      (loop []
        (before-read)
        (when-let [{:keys [id] :as response} (transport/recv transport Long/MAX_VALUE)]
          (if-let [handler (get @pending id)]
            (do (when (done? response) (swap! pending dissoc id))
                (call handler response))
            (call @on-unclaimed response))
          (recur)))
      ;; The socket went away, the server sent garbage or close interrupted us.
      (catch Throwable _))
    (let [[waiting] (swap-vals! pending (constantly nil))]
      (doseq [[id handler] waiting]
        (call handler {:id id :status ["done" "disconnected"]})))))

(defn connected?
  "Whether client's connection is up, as far as it's heard: once the server
  goes away, nothing more can go out on it."
  [{:keys [pending]}]
  (some? @pending))

(defn send!
  "Sends msg with a fresh :id and calls (handler response) for each of its
  responses, the last one being the one with status done. Handlers run on the
  reader thread, so they must not block. Returns the id."
  [{:keys [transport pending]} msg handler]
  (let [id (misc/uuid)]
    (when-not (swap! pending #(some-> % (assoc id handler)))
      (throw (ex-info "Not connected to the nREPL server" {:type ::disconnected})))
    (try
      (transport/send transport (assoc msg :id id))
      (catch Throwable e
        (swap! pending dissoc id)
        (throw e)))
    id))

(defn request
  "Sends msg and waits for all of its responses, returning them as a vector.
  Throws when the connection drops or when the responses don't arrive within
  :timeout-ms (10s by default, as long as they take with nil). In the latter
  case the ex-data has the message :id, for interrupting it, and later
  responses are dropped."
  ([client msg] (request client msg {}))
  ([client msg {:keys [timeout-ms] :or {timeout-ms 10000}}]
   (let [responses (atom [])
         result (promise)
         id (send! client msg (fn [response]
                                (when-not (realized? result)
                                  (let [all (swap! responses conj response)]
                                    (when (done? response)
                                      (deliver result all))))))
         all (or (if timeout-ms (deref result timeout-ms nil) @result)
                 ;; Too late: from now on the handler ignores everything,
                 ;; unless done slipped in just now.
                 (do (deliver result nil) @result))]
     (cond
       (nil? all)
       (throw (ex-info (format "No response to %s within %dms" (:op msg) timeout-ms)
                       {:type ::timeout :id id :msg msg :responses @responses}))

       (status? (peek all) "disconnected")
       (throw (ex-info "Lost the connection to the nREPL server"
                       {:type ::disconnected :msg msg :responses all}))

       :else all))))

(defn close
  "Closes the client's connection. Requests still waiting get the
  disconnected status."
  [{:keys [transport ^Thread reader]}]
  (.close ^Closeable transport)
  ;; Closing doesn't wake up a reader blocked in recv, but interrupting does.
  (.interrupt reader))

(def ^:private connect-timeout-ms
  "How long to wait for a server to take a connection. A host that doesn't
  answer at all takes over a minute to fail otherwise."
  10000)

(def ^:private quickack
  "Linux's TCP_QUICKACK on the JVM, or nil where there's none, as on
  babashka. Servers write an eval's done right after its value, and nREPL's
  sockets hold a write back until the one before it is acknowledged, which
  Linux delays by up to 40ms otherwise."
  (try
    (let [option (.get (.getField (Class/forName "jdk.net.ExtendedSocketOptions") "TCP_QUICKACK") nil)]
      (when (.contains (.supportedOptions (Socket.)) option)
        option))
    (catch Throwable _ nil)))

(defn- tcp-socket
  "A socket connected to the server at host and port."
  ^Socket [^String host port]
  (let [socket (Socket.)]
    (try
      (doto socket
        (.setTcpNoDelay true)
        (.connect (InetSocketAddress. host (int port)) (int connect-timeout-ms)))
      (catch Exception e
        (.close socket)
        (throw e)))))

(defn- open [{:keys [host port socket]} on-unclaimed on-error]
  (let [tcp (when-not socket (tcp-socket host port))
        client {:transport (if socket
                             (nrepl/connect :socket socket)
                             (transport/bencode tcp))
                :pending (atom {})
                :on-unclaimed (atom on-unclaimed)
                ;; Linux leaves quick acknowledgements once the client
                ;; answers what it got, as it does, so it's back each time.
                :before-read (if (and tcp quickack)
                               #(try (.setOption tcp quickack true) (catch Exception _ nil))
                               (fn []))}]
    (assoc client :reader (doto (Thread. ^Runnable #(read-responses client on-error)
                                         "neorepl-nrepl-reader")
                            (.setDaemon true)
                            (.start)))))

(defn connect
  "Connects to an nREPL server and returns a client. Takes :host (127.0.0.1 by
  default) and :port, a unix domain socket path as :socket, or a :url.

  Optional callbacks, both called on the reader thread: :on-unclaimed gets
  every response no request claims, :on-error every exception a response
  handler throws (by default it's printed to stderr). And :timeout-ms, how
  long the server gets to describe itself, as request takes it.

  The client carries what describe reports: the server's :ops, a set of op
  names, and its :versions, a sorted map of names such as :clojure and
  :nrepl to version strings. And its :address (host:port or the socket
  path)."
  [{:keys [url on-unclaimed on-error]
    :or {on-unclaimed (fn [_])
         on-error #(binding [*out* *err*] (println "neorepl: response handler failed:" (str %)))}
    :as opts}]
  (let [{:keys [host port socket] :or {host "127.0.0.1"} :as where} (if url (parse-url url) opts)
        address (or socket (str host ":" port))
        no-server #(ex-info (str "No nREPL server answering at " address) {:type ::no-server} %)
        client (try
                 (open (assoc where :host host) on-unclaimed on-error)
                 (catch Exception e (throw (no-server e))))]
    (try
      (let [{:keys [ops versions]} (apply merge (request client {:op "describe"} (select-keys opts [:timeout-ms])))]
        (assoc client
               :address address
               ;; The transport turns op names into keywords: cider/info into :cider/info.
               :ops (set (map (comp str symbol) (keys ops)))
               ;; JVM nREPL describes each version as a map, babashka its own
               ;; as a string.
               :versions (into (sorted-map)
                               (for [[k v] versions
                                     :let [version (if (map? v) (:version-string v) v)]
                                     :when version]
                                 [k version]))))
      (catch Throwable e
        (close client)
        (throw (no-server e))))))

(defn on-unclaimed!
  "Has f handle the responses nobody claims from now on."
  [client f]
  (reset! (:on-unclaimed client) f))

(defn unclaimed
  "Hands response to whatever handles the responses nobody claims, as if
  the server had sent it unasked."
  [client response]
  (@(:on-unclaimed client) response))

(defn clone-session
  "Creates a session, a copy of session when given one, and returns its id."
  ([client] (clone-session client nil))
  ([client session]
   (let [responses (request client (cond-> {:op "clone"}
                                     session (assoc :session session)))]
     (or (some :new-session responses)
         (throw (ex-info "Couldn't create a session" {:session session :responses responses}))))))

(defn close-session
  "Closes session. Returns false when the server didn't know it."
  [client session]
  (not-any? #(status? % "unknown-session") (request client {:op "close" :session session})))

(defn session-ns
  "The name of the namespace session is in, or nil when the server doesn't
  say."
  [client session]
  (some #(get-in % [:aux :current-ns]) (request client {:op "describe" :session session})))

(defn evaluation-ns
  "The name of the namespace code sent to session evaluates in, user when
  the server doesn't say. Not necessarily user otherwise: shadow-cljs starts
  sessions in shadow.user, and a named session is wherever it was left. In
  ClojureScript it's the Clojure namespace under it, which describe has,
  rather than the one the ClojureScript code runs in."
  [client session]
  (or (session-ns client session) "user"))

(defn interrupt
  "Asks the server to interrupt the evaluation with message id in session, or
  whichever is running there when id is nil. Returns how that went:
  :interrupted, :idle when nothing was running, :unknown-session, :mismatch
  when an evaluation other than id was running, or :error. The interrupted
  evaluation reports the status interrupted to its own handler. opts are the
  same as for `request`."
  ([client session id] (interrupt client session id {}))
  ([client session id opts]
   (let [statuses (into #{} (mapcat :status)
                        (request client
                                 (cond-> {:op "interrupt" :session session}
                                   id (assoc :interrupt-id id))
                                 opts))]
     (cond
       (statuses "session-idle") :idle
       (statuses "unknown-session") :unknown-session
       (statuses "interrupt-id-mismatch") :mismatch
       (statuses "error") :error
       :else :interrupted))))
