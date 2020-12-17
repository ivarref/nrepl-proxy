(ns ivarref.nrepl-proxy
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as stream]
            [clojure.tools.logging :as log]
            [clj-http.client :as client]
            [clojure.string :as str])
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
      (if (some->> (ex-data t) :status (= 502))
        (do (log/warn "got 502 bad gateway on send")
            nil)
        (throw t)))))

(defn poll [{:keys [endpoint give-up-seconds]
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
                (some->> e (ex-data) :status (= 502)))
            (let [ms-since-error (- (System/currentTimeMillis) start-poll-time)]
              (cond
                (>= ms-since-error (* 1000 give-up-seconds))
                (do (log/error "server seems to be down, giving up...!")
                    (reset! open? false)
                    ::abort)
                (>= ms-since-error 5000)
                (log/warn "server is down for" (int (/ ms-since-error 1000)) "seconds"))
              (Thread/sleep 1000)
              (poll opts s info open? session-id start-poll-time))

            :else
            (throw e)))))

(defn handler [{:keys [endpoint] :as opts} s info]
  (log/info "starting new connection...")
  (let [session-id (->> (client/post endpoint
                                     {:form-params  {:op "init"}
                                      :headers      (opts->extra-headers opts)
                                      :as           :json
                                      :content-type :json})
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
      (log/debug "poller exiting" session-id))))

(defn start-server
  [{:keys [bind
           port
           endpoint
           secret-header
           secret-file
           secret-value
           secret-prefix
           block?
           give-up-seconds]
    :or   {bind            "127.0.0.1"
           port            7777
           secret-header   "authorization"
           secret-file     ".secret"
           secret-value    nil
           secret-prefix   ""
           block?          true
           give-up-seconds 60}
    :as   opts}]
  (let [opts (assoc opts
               :bind bind
               :port port
               :secret-header secret-header
               :secret-file secret-file
               :secret-value secret-value
               :secret-prefix secret-prefix
               :give-up-seconds give-up-seconds)]
    (assert (string? endpoint) "must be given :endpoint!")
    (tcp/start-server (fn [s info] (handler opts s info)) {:socket-address (InetSocketAddress. ^String bind ^Integer port)})
    (log/info "started proxy server on" (str bind "@" port))
    (when block?
      @(promise))))

(comment
  (start-server
    {:endpoint        (str/trim (slurp ".nrepl-url"))
     :secret-header   "nrepl-token"
     :secret-file     ".nrepl-token"
     :give-up-seconds 10
     :block?          false}))