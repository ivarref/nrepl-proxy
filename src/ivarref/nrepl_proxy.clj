(ns ivarref.nrepl-proxy
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as stream]
            [clojure.tools.logging :as log]
            [clj-http.client :as client]
            [clojure.string :as str]
            [aleph.netty :as netty])
  (:import (java.net InetSocketAddress SocketException ConnectException SocketException)
           (java.util Base64)
           (org.apache.http NoHttpResponseException)))

(defn bytes->base64-str [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defonce session->stream (atom {}))

(defn opts->extra-headers [{:keys [secret-header secret-value secret-file secret-prefix]}]
  {secret-header (str secret-prefix
                      (or secret-value
                          (str/trim (slurp secret-file))))})

(defn close-handler [{:keys [endpoint] :as opts} s info open? session-id]
  (when-not (stream/closed? s)
    (log/info "connection closed"))
  (reset! open? false)
  (try
    (client/post endpoint
                 {:form-params      {:op         "close"
                                     :session-id session-id}
                  :headers          (opts->extra-headers opts)
                  :as               :json
                  :throw-exceptions false
                  :content-type     :json})
    (catch Throwable t
      (log/debug t "failed to close remote")))
  (try
    (.close s)
    (catch Throwable t
      (log/debug t "failed to close local connection"))))

(defn gateway-error? [t]
  (when t
    (when-let [status (some->> (ex-data t) :status)]
      (when (contains? #{502 504} status)
        status))))

(defn consume-handler [{:keys [endpoint] :as opts} s info session-id arg]
  (log/debug "consume" arg)
  (try
    (client/post endpoint
                 {:form-params  {:op         "send"
                                 :session-id session-id
                                 :payload    (bytes->base64-str arg)}
                  :headers      (opts->extra-headers opts)
                  :as           :json
                  :content-type :json})
    (catch Throwable t
      (if-let [err (gateway-error? t)]
        (do (log/warn "got gateway error code" err "on send")
            nil)
        (throw t)))))

(defn poll [{:keys [endpoint
                    give-up-seconds
                    warn-after-seconds]
             :as   opts}
            s
            info
            open?
            session-id
            start-poll-time]
  (try
    (let [payload (->> (client/post endpoint
                                    {:form-params  {:op         "recv"
                                                    :session-id session-id}
                                     :headers      (opts->extra-headers opts)
                                     :as           :json
                                     :content-type :json})
                       :body
                       :payload)]
      (when (not-empty payload)
        (log/debug "got data..")
        (doseq [line (str/split-lines payload)]
          (stream/put! s (.decode (Base64/getDecoder) ^String line)))))
    (catch Throwable e
      (cond (false? @open?)
            (do (log/debug "expected close"))

            (= 404 (some->> e (ex-data) :status))
            (do
              (log/error "session is gone, aborting!")
              (reset! open? false)
              ::session-gone)

            (or (instance? ConnectException e)
                (instance? NoHttpResponseException e)
                (instance? SocketException e)
                (gateway-error? e))
            (let [ms-since-error (- (System/currentTimeMillis) start-poll-time)]
              (cond
                (>= ms-since-error (* 1000 give-up-seconds))
                (do (log/error "server seems to be down, giving up...!")
                    (reset! open? false)
                    ::abort)
                (>= ms-since-error (* 1000 warn-after-seconds))
                (log/warn "server is down for" (int (/ ms-since-error 1000)) "seconds"))
              (Thread/sleep 1000)
              (poll opts s info open? session-id start-poll-time))

            :else
            (throw e)))))

(defn handler [{:keys [endpoint] :as opts} s info]
  (log/info "starting new connection ...")
  (let [resp (client/post endpoint
                          {:form-params  {:op "init"}
                           :headers      (opts->extra-headers opts)
                           :as           :json
                           :content-type :json
                           :throw-exceptions false})]
    (if-not (= 200 (:status resp))
      (do (log/error "got status" (:status resp) "when trying to establish connection")
          (log/error "body was:\n" (:body resp))
          (stream/close! s))
      (let [session-id (->> resp
                            :body
                            :session-id)
            open? (atom true)]
        (log/info "new connection established")
        (swap! session->stream assoc session-id s)
        (stream/on-closed s (fn [& _] (close-handler opts s info open? session-id)))
        (stream/consume (fn [arg] (consume-handler opts s info session-id arg)) s)
        (future
          (while @open?
            (try
              (poll opts s info open? session-id (System/currentTimeMillis))
              (Thread/sleep 100)
              (catch Throwable t
                (log/warn "error while polling:" (.getMessage t))
                ;(def tt t)
                (Thread/sleep 500))))
          (close-handler opts s info open? session-id)
          (log/debug "poller exiting" session-id))))))

(defn start-server
  [{:keys [bind
           port
           endpoint
           secret-header
           secret-file
           secret-value
           secret-prefix
           block?
           give-up-seconds
           warn-after-seconds
           port-file]
    :or   {bind               "127.0.0.1"
           port               0
           secret-header      "authorization"
           secret-file        ".secret"
           secret-value       nil
           secret-prefix      ""
           block?             true
           give-up-seconds    60
           warn-after-seconds 5
           port-file          ".nrepl-proxy-port"}
    :as   opts}]
  (let [opts (assoc opts
               :bind bind
               :port port
               :secret-header secret-header
               :secret-file secret-file
               :secret-value secret-value
               :secret-prefix secret-prefix
               :give-up-seconds give-up-seconds
               :warn-after-seconds warn-after-seconds
               :port-file port-file)]
    (assert (string? endpoint) "must be given :endpoint!")
    (let [server (tcp/start-server (fn [s info] (handler opts s info)) {:socket-address (InetSocketAddress. ^String bind ^Integer port)})]
      (log/info "started proxy server on" (str bind "@" (netty/port server)))
      (log/info "using remote endpoint" endpoint)
      (spit port-file (netty/port server))
      (log/info "wrote port" (netty/port server) "to" port-file)
      (.addShutdownHook
        (Runtime/getRuntime)
        (Thread.
          ^Runnable (fn []
                      (try
                        (log/info "shutting down server")
                        (.close server)
                        (catch Throwable t
                          (log/warn t "error during closing server"))))))
      (when block?
        @(promise)))))

(comment
  (start-server
    {:endpoint        (str/trim (slurp ".nrepl-url"))
     :secret-header   "nrepl-token"
     :secret-file     ".nrepl-token"
     :give-up-seconds 10
     :block?          false}))