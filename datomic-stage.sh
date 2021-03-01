#!/usr/bin/env bash

set -ex

clojure -Sdeps '{}' \
        -J-Dclojure.main.report=stderr \
        -X ivarref.tcp-ws-proxy/start-server \
        :endpoint '"wss://api-stage.nsd.no/dev/pvo-backend-service/tunnel"' \
        :bind '"0.0.0.0"' \
        :remotes '[{:host "psql-stage-we.postgres.database.azure.com" :port 5432}
                   {:host "datomic-transactor.private.nsd.no" :port 4334}]'