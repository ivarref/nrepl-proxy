(ns ivarref.tcp-ws-proxy
  (:require [aleph.tcp :as tcp]
            [clojure.tools.logging :as log]
            [aleph.netty :as netty]
            [aleph.http :as http]
            [manifold.stream :as s]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import (java.net InetSocketAddress)
           (java.io File)))

(defonce num-connections (atom 0))

(defn handler [{:keys [endpoint secret-header secret-file remote-host remote-port]} local-sock]
  (log/info "Creating tunnel to" (str remote-host ":" remote-port) "...")
  (let [ws @(http/websocket-client endpoint {:headers {secret-header (str/trim (slurp secret-file))
                                                       "remote-host" (str remote-host)
                                                       "remote-port" (str remote-port)}})]
    (let [conns (swap! num-connections inc)]
      (log/info "Creating tunnel to" (str remote-host ":" remote-port) "... OK! Total number of connections:" conns))
    (s/on-closed
      local-sock
      (fn [& args]
        (let [conns (swap! num-connections dec)]
          (log/info "Closing connection! Total number of connections:" conns))
        (s/close! ws)))
    (s/on-closed
      ws
      (fn [& args]
        (log/info "WebSocket closed.")
        (s/close! local-sock)))
    (s/connect ws local-sock)
    (s/connect local-sock ws)))

(defn make-server [{:keys [bind]
                    :as   opts}
                   {:keys [host port]}]
  (let [opts (assoc opts :remote-host host :remote-port port)
        server (tcp/start-server
                 (fn [s info] (handler opts s))
                 {:socket-address (InetSocketAddress. ^String bind ^Integer port)})]
    (log/info "Started proxy server on" (str bind "@" (netty/port server)))
    server))

(defn add-shutdown-hook! [servers]
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread.
      ^Runnable (fn []
                  (doseq [server servers]
                    (try
                      (log/info "shutting down server")
                      (.close server)
                      (catch Throwable t
                        (log/warn t "error during closing server"))))))))

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
