(ns ivarref.tcp-ws-proxy
  (:require [aleph.tcp :as tcp]
            [clojure.tools.logging :as log]
            [aleph.netty :as netty]
            [aleph.http :as http]
            [manifold.stream :as s]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import (java.net InetSocketAddress)
           (java.io File)
           (java.util UUID)))

(defonce num-connections (atom 0))

(defn handler [{:keys [endpoint secret-header secret-file remote-host remote-port]} local-sock]
  (log/info "Creating tunnel to" (str remote-host ":" remote-port) "...")
  (let [connection-id (str (UUID/randomUUID))
        headers {secret-header   (str/trim (slurp secret-file))
                 "connection-id" connection-id
                 "remote-host"   (str remote-host)
                 "remote-port"   (str remote-port)}]
    (when-let [ws (try
                    @(http/websocket-client endpoint {:headers headers})
                    (catch Throwable t
                      (log/error "error during websocket creation:" (ex-message t))
                      (s/close! local-sock)
                      nil))]
      (let [conns (swap! num-connections inc)]
        (log/info "Creating tunnel to" (str remote-host ":" remote-port) "... OK! Total number of connections:" conns))

      (s/on-closed
        ws
        (fn [& args]
          (s/close! local-sock)))

      (s/on-closed
        local-sock
        (fn [& args]
          (let [conns (swap! num-connections dec)]
            (log/info "Closing connection ... Total number of connections:" conns))
          (log/info connection-id "Closing remote ...")
          (try
            @(http/get (str/replace endpoint #"^ws" "http") {:headers (assoc headers :destroy-connection "true")})
            (log/info "Closing remote ... OK!")
            (catch Throwable t
              (log/warn "Closing remote failed:" (ex-message t))))
          (s/close! ws)))

      (s/consume
        (fn [chunk]
          (s/put! local-sock chunk))
        ws)

      (s/consume
        (fn [chunk]
          (s/put! ws chunk))
        local-sock))))

(defn make-server [{:keys [bind]
                    :as   opts}
                   {:keys [host port]}]
  (let [opts (assoc opts :remote-host host :remote-port port)
        server (tcp/start-server
                 (fn [s info] (handler opts s))
                 {:socket-address (InetSocketAddress. ^String bind ^Integer port)})]
    (log/info "Started proxy server on" (str bind "@" (netty/port server)))
    server))

(declare add-shutdown-hook!)

(defn start-server
  [{:keys [bind
           endpoint
           block?
           secret-header
           secret-file
           remotes]
    :or   {bind          "127.0.0.1"
           block?        true
           secret-header "nrepl-token"
           secret-file   ".nrepl-token"}
    :as   opts}]
  (assert (string? endpoint) "must be given :endpoint !")
  (assert (string? secret-header) "must be given :secret-header !")
  (assert (string? secret-file) "must be given :secret-file !")
  (assert (.exists ^File (io/file secret-file)) ":secret-file must exist!")
  (let [opts (assoc opts
               :bind bind
               :secret-header secret-header
               :secret-file secret-file)
        servers (mapv (partial make-server opts) remotes)]
    (add-shutdown-hook! servers)
    (when block?
      @(promise))))

(defn add-shutdown-hook! [servers]
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread.
      ^Runnable
      (fn []
        (doseq [server servers]
          (try
            (log/info "shutting down server")
            (.close server)
            (catch Throwable t
              (log/warn t "error during closing server"))))))))