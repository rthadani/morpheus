(ns morpheus.system
  "System wiring. Start with (go!) in the REPL."
  (:require
   [aero.core            :as aero]
   [clojure.java.io      :as io]
   [taoensso.timbre      :as log]
   [org.httpkit.server   :refer [run-server]]
   [morpheus.ui.router   :as router]
   [morpheus.executor.store :as store]))

;; ──────────────────────────────────────────
;; Config
;; ──────────────────────────────────────────

(defn read-config []
  (aero/read-config (io/resource "config.edn")))

;; ──────────────────────────────────────────
;; System atom + lifecycle
;; ──────────────────────────────────────────

(defonce system (atom nil))

(defn start! []
  (let [cfg       (read-config)
        port      (or (:port cfg) 7777)
        run-store (store/create-store)
        rtr       (router/create-router run-store)
        server    (run-server rtr {:port port})]
    (log/info "Starting Morpheus on port" port)
    (reset! system {:port port :run-store run-store :router rtr :server server})))

(defn stop! []
  (when-let [sys @system]
    ((:server sys))
    (reset! system nil)))

(defn go! []
  (stop!)
  (start!))
