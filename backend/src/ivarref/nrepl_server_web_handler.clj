(ns ivarref.nrepl-server-web-handler
  (:require [clojure.tools.logging :as log]
            [aleph.tcp :as tcp]
            [manifold.stream :as stream])
  (:import (java.util UUID Base64)))

(defonce session->conn (atom {}))
(defonce session->pending (atom {}))

(defn on-closed-handler [session-id conn]
  (try
    (.close conn)
    (catch Exception e
      (log/trace e "exception while closing connection")))
  (swap! session->conn dissoc session-id)
  (swap! session->pending dissoc session-id)
  (log/trace "connection closed for session id" session-id))

(defn consume-handler [session-id conn arg]
  (if-let [pending (@session->pending session-id)]
    (do
      (log/trace "consume for session id" session-id)
      (swap! pending conj arg))
    (do
      (log/trace "consume called for missing session-id" session-id))))

(defonce bootstrapped (atom [false nil]))

(defn response [{:keys [host port bootstrap!]
                 :or   {host       "localhost"
                        port       9999
                        bootstrap! (fn [] true)}}
                {:keys                           [response]
                 {:keys [op session-id payload]} :body}]
  (swap! bootstrapped (fn [[bootstrapped? v]]
                        (if bootstrapped?
                          [true v]
                          [true (bootstrap!)])))
  (if (nil? (second @bootstrapped))
    (do
      (log/trace "not found")
      (assoc response
        :status 404
        :body {:message "Not found"}))
    (let [conn (get @session->conn session-id)]
      (cond (= op "init")
            (let [session-id (str (UUID/randomUUID))
                  conn @(tcp/client {:host host :port port})]
              (swap! session->conn assoc session-id conn)
              (swap! session->pending assoc session-id (atom []))
              (stream/on-closed conn (fn [& args] (on-closed-handler session-id conn)))
              (stream/consume (fn [arg] (consume-handler session-id conn arg)) conn)
              (log/trace "created new session")
              (assoc response
                :status 200
                :body {:message    "OK"
                       :session-id session-id}))

            (and (string? session-id) (nil? conn))
            (do
              (log/trace "missing session for id" session-id)
              (assoc response
                :status 404
                :body {:message    "Missing session"
                       :op         op
                       :session-id session-id}))

            (= op "close")
            (do
              (on-closed-handler session-id conn)
              (assoc response
                :status 200
                :body {:message    "Closed"
                       :session-id session-id}))

            (= op "send")
            (do
              (log/trace "sending data to server...")
              (let [payload-bytes (.decode (Base64/getDecoder) ^String payload)]
                (stream/put! conn payload-bytes))
              (assoc response
                :status 200
                :body {:message    "OK"
                       :session-id session-id}))

            (= op "recv")
            (let [pending-atom (@session->pending session-id)
                  pending @pending-atom
                  s (StringBuilder.)]
              (if (not-empty pending)
                (do
                  (log/trace "sending data back to client...")
                  (doseq [bytes pending]
                    (.append s (.encodeToString (Base64/getEncoder) bytes))
                    (.append s "\n"))
                  (swap! pending-atom (fn [v] (vec (drop (count pending) v))))
                  (assoc response
                    :status 200
                    :body {:message    "OK"
                           :payload    (.toString s)
                           :session-id session-id}))
                (do
                  (log/trace "sending empty data back to client")
                  (assoc response
                    :status 200
                    :body {:message    "OK"
                           :payload    ""
                           :session-id session-id}))))

            :else
            (do
              (log/trace "unknown op" op)
              (assoc response
                :status 400
                :body {:message    "Unknown op"
                       :session-id session-id
                       :op         op}))))))

