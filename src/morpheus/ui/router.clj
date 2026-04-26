(ns morpheus.ui.router
  "Reitit ring router. All routes return hiccup rendered to HTML
   except the SSE stream which returns text/event-stream."
  (:require
   [reitit.ring            :as ring]
   [ring.middleware.params :as params]
   [clojure.java.io        :as io]
   [clojure.string         :as str]
   [hiccup2.core           :as h]
   [clojure.core.async     :as async :refer [go-loop <!]]
   [taoensso.timbre        :as log]
   [org.httpkit.server     :refer [send! with-channel on-close]]
   [morpheus.executor.engine  :as engine]
   [morpheus.executor.wiggum  :as wiggum]
   [morpheus.executor.store   :as store]
   [morpheus.ui.components    :as ui]))

;; ──────────────────────────────────────────
;; Helpers
;; ──────────────────────────────────────────

(defn html-resp [hiccup]
  {:status  200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str (h/html hiccup))})

(def ^:private inline-css
  (delay
    (or (some-> (io/resource "public/style.css") slurp)
        (slurp "resources/public/style.css"))))

(defn page-resp [hiccup]
  (let [html-str (str "<!DOCTYPE html>" (h/html hiccup))
        with-css (str/replace
                   html-str
                   #"<link[^>]+/style\.css[^>]*>"
                   (str "<style>" @inline-css "</style>"))]
    {:status  200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body    with-css}))

(defn sse-event
  "Format a server-sent event string.
   Handles multi-line data by prefixing each line with 'data: '."
  [event-name data-html]
  (let [data-lines (->> (str/split data-html #"\n")
                        (map #(str "data: " %))
                        (str/join "\n"))]
    (str "event: " event-name "\n"
         data-lines "\n\n")))

;; ──────────────────────────────────────────
;; SSE — DAG run fragments
;; ──────────────────────────────────────────

(defn- dag-fragment [run run-id event]
  (case (:type event)
    :state-change
    (let [nodes     (-> @(:graph-atom run) :graph/nodes)
          state-map @(:state run)
          node      (first (filter #(= (:id %) (:node-id event)) nodes))]
      (when node
        (str ;; Replace the whole canvas div — individual <g> OOB swaps fail because
             ;; HTMX parses them as HTML unknowns inside #sse-sink (a div), not as SVG
             (h/html (update (ui/dag-canvas nodes state-map)
                             1 assoc :hx-swap-oob "outerHTML:#dag-canvas"))
             (when (= :running (:state event))
               (h/html [:div#node-output.node-output
                        {:hx-swap-oob "outerHTML:#node-output"}
                        [:div.detail-label [:span (str (name (:id node)) " running…")]]
                        [:pre#live-output.detail-pre "Claude Code working…"]])))))

    :checkpoint
    (str (h/html
           (update (ui/checkpoint-panel run-id (:node-id event)
                                        (pr-str (:presented event)))
                   1 assoc :hx-swap-oob "outerHTML:#checkpoint-panel")))

    :node-complete
    (str (h/html
           [:div {:id "log-lines" :hx-swap-oob "beforeend"}
            (ui/log-line :ok (str "✓ " (name (:node-id event))
                                  " (" (:duration event) "ms)"))]))

    :node-output
    (str (h/html
           [:div#node-output.node-output {:hx-swap-oob "outerHTML:#node-output"}
            [:div.detail-label
             [:span (str (name (:node-id event)) " output")]
             (when-let [ex (:exit event)]
               [:span {:class (str "exit-badge" (when (pos? ex) " exit-nonzero"))}
                (str "exit " ex)])]
            [:pre.detail-pre (or (:stdout event) "")]]))

    :node-output-line
    (str (h/html
           [:span {:id "live-output" :hx-swap-oob "beforeend"}
            (str (:line event) "\n")
            [:br]]))

    :node-error
    (str (h/html
           [:div {:id "log-lines" :hx-swap-oob "beforeend"}
            (ui/log-line :err (str "✗ " (name (:node-id event))
                                   " — " (:message event)))]))

    :steer-queued
    (let [text (:text event "")]
      (str (h/html
             [:div {:id "log-lines" :hx-swap-oob "beforeend"}
              (ui/log-line :info
                           (str "↳ steer queued"
                                (when (seq text)
                                  (str ": " (if (> (count text) 60)
                                              (str (subs text 0 60) "…")
                                              text)))))])))

    :run-complete
    (str (h/html
           [:span {:id "run-status" :hx-swap-oob "outerHTML:#run-status"
                   :class "status-pill status-done"}
            "done"])
         (h/html
           [:div {:id "log-lines" :hx-swap-oob "beforeend"}
            (ui/log-line :ok "✅ done")])
         (h/html
           (update (ui/abort-button run-id :disabled) 1
                   assoc :hx-swap-oob "outerHTML:#abort-btn")))

    :run-aborted
    (str (h/html
           [:span {:id "run-status" :hx-swap-oob "outerHTML:#run-status"
                   :class "status-pill status-aborted"}
            "aborted"])
         (h/html
           [:div {:id "log-lines" :hx-swap-oob "beforeend"}
            (ui/log-line :err "✗ aborted")])
         (h/html
           (update (ui/abort-button run-id :disabled) 1
                   assoc :hx-swap-oob "outerHTML:#abort-btn")))

    nil))

;; ──────────────────────────────────────────
;; SSE — Wiggum run fragments
;; ──────────────────────────────────────────

(defn- wiggum-fragment [run-id event run-store]
  (case (:type event)
    :state-change
    (str (h/html
           [:span {:id          "run-status"
                   :hx-swap-oob "outerHTML:#run-status"
                   :class       (str "status-pill status-" (name (:state event)))}
            (name (:state event))]))

    :iteration-started
    (str (h/html
           [:div {:hx-swap-oob "outerHTML:#control-packet-panel"}
            (ui/control-packet-panel (:control-packet event))])
         ;; replace the running-row placeholder (always present in DOM)
         (h/html
           (update (ui/iteration-running-row run-id (:iteration event))
                   1 assoc :hx-swap-oob "outerHTML:#iter-row-running"))
         ;; auto-show live output in the detail panel immediately
         (h/html
           [:div {:id "wg-detail" :hx-swap-oob "outerHTML:#wg-detail"}
            (ui/iteration-live-panel (:iteration event))])
         (h/html
           [:div {:id "log-tail" :hx-swap-oob "beforeend"}
            (ui/log-line :info (str "→ iteration " (:iteration event) " started"))]))

    :iteration-complete
    (let [ev (:evidence event)]
      (str ;; afterend FIRST — inserts sibling while #iter-row-running is still in DOM
           ;; outerHTML SECOND — replaces it; afterend runs against the live element, not the detached one
           (h/html
             (update (ui/iteration-row run-id ev) 1
                     assoc :hx-swap-oob "afterend:#iter-row-running"))
           (h/html
             [:div {:id "iter-row-running" :hx-swap-oob "outerHTML:#iter-row-running"}])
           ;; update detail panel to show latest
           (h/html
             [:div {:hx-swap-oob "innerHTML:#wg-detail"}
              (ui/iteration-detail ev)])
           ;; update iter count in topbar
           (h/html
             [:span {:id "wg-iter-count" :hx-swap-oob "outerHTML:#wg-iter-count"
                     :class "wg-iter"}
              (str "iter " (:iteration ev))])
           ;; log line
           (h/html
             [:div {:id "log-tail" :hx-swap-oob "beforeend"}
              (ui/log-line (if (zero? (or (:exit-code ev) 0)) :ok :warn)
                           (str "✓ iter " (:iteration ev)
                                " +" (count (:files-written ev))
                                " ~" (count (:files-edited ev))
                                (when-let [v (:verification ev)]
                                  (if (zero? (:exit v)) " ✓" " ✗"))))])))

    :run-paused
    (let [run          (store/get-run run-store run-id)
          latest-ev    (when run (last @(:iterations run)))
          milestone?   (:milestone? event)
          review       (:review event)
          review-pause? (:review-pause? event)]
      (str (h/html
             [:div#review-panel.review-panel
              {:hx-swap-oob "outerHTML:#review-panel"}
              [:div.review-header
               [:span.review-title
                (cond
                  review-pause? (str "⚠ Judge review · iteration " (:iteration event))
                  milestone?    (str "⭐ Milestone · iteration " (:iteration event))
                  :else         (str "⏸ Paused · iteration " (:iteration event)))]
               (when latest-ev
                 (let [ev      latest-ev
                       ver-ok? (or (nil? (:verification ev))
                                   (zero? (:exit (:verification ev))))
                       secs    (int (/ (or (:duration-ms ev) 0) 1000))]
                   [:div.review-summary
                    [:span {:class (str "exit-badge" (when (pos? (or (:exit-code ev) 0)) " exit-nonzero"))}
                     (str "exit " (:exit-code ev))]
                    [:span (str secs "s")]
                    (when (seq (:files-written ev))
                      [:span.files-new (str "+" (count (:files-written ev)) " new")])
                    (when (seq (:files-edited ev))
                      [:span.files-edit (str "~" (count (:files-edited ev)) " edited")])
                    [:span {:class (str "ver-inline " (if ver-ok? "ver-ok" "ver-fail"))}
                     (if ver-ok? "✓ verified" "✗ verify failed")]]))]
              (when review
                (ui/judge-review-block review))
              [:form.review-form
               {:hx-post   (str "/runs/" run-id "/resume")
                :hx-target "#review-panel"
                :hx-swap   "outerHTML"}
               [:textarea.review-feedback
                {:id          "review-feedback-text"
                 :name        "feedback"
                 :rows        3
                 :hx-preserve "true"
                 :placeholder "Steer the next iteration… e.g. \"Focus on the auth layer\" or leave blank to let the supervisor decide"}]
               [:div.review-actions
                [:button {:type "submit" :name "action" :value "abort"   :class "btn-danger"}  "Abort"]
                (when review
                  [:button {:type "submit" :name "action" :value "restore" :class "btn-warn"
                            :title "Discard this phase's changes and re-enter the phase"}
                   "Restore ⎌"])
                [:button {:type "submit" :name "action" :value "retry"   :class "btn"}         "Retry ↺"]
                [:button {:type "submit" :name "action" :value "step"    :class "btn-primary"}  "Continue →"]]]])
           (h/html
             [:div {:id "log-tail" :hx-swap-oob "beforeend"}
              (ui/log-line (if review-pause? :warn (if milestone? :info :warn))
                           (str (cond review-pause? "⚠ judge paused"
                                      milestone?    "⭐ milestone"
                                      :else         "⏸ paused")
                                " after iteration " (:iteration event)))])))

    :output-line
    (str (h/html
           [:span {:id "live-output" :hx-swap-oob "beforeend"}
            (str (:line event) "\n")
            [:br]]))

    :provider-fallback
    (str (h/html
           [:div {:id "log-tail" :hx-swap-oob "beforeend"}
            (ui/log-line :warn
                         (str "⚠ rate limit — falling back to " (:fallback event)
                              " (delay " (:delay-ms event) "ms)"))]))

    :steer-queued
    (let [text (:text event "")]
      (str (h/html
             [:div {:id "log-tail" :hx-swap-oob "beforeend"}
              (ui/log-line :info
                           (str "↳ steer queued: "
                                (if (> (count text) 60)
                                  (str (subs text 0 60) "…")
                                  text)))])))

    :control-changed
    (str (h/html
           [:div {:hx-swap-oob "outerHTML:#step-toggle"}
            (ui/step-toggle run-id (:step-once? event))]))

    (:run-complete :run-aborted)
    (let [done? (= :run-complete (:type event))]
      (str (h/html
             [:span {:id          "run-status"
                     :hx-swap-oob "outerHTML:#run-status"
                     :class       (str "status-pill status-" (if done? "done" "aborted"))}
              (if done? "done" "aborted")])
           ;; hide the review panel when run finishes
           (h/html
             [:div#review-panel {:hx-swap-oob "outerHTML:#review-panel" :style "display:none"}])
           (h/html
             [:div {:id "log-tail" :hx-swap-oob "beforeend"}
              (ui/log-line (if done? :ok :err)
                           (if done?
                             (str "✅ done · " (name (or (:reason event) :done))
                                  (when-let [i (:iteration event)] (str " · " i " iters")))
                             "✗ aborted"))])))

    nil))

;; ──────────────────────────────────────────
;; SSE — unified stream handler
;; ──────────────────────────────────────────

(defn stream-handler [run-store]
  (fn [{:keys [path-params] :as req}]
    (let [run-id (parse-long (:id path-params))
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (with-channel req ch
          ;; Send SSE handshake headers immediately so the browser accepts the stream
          (send! ch {:status  200
                     :headers {"Content-Type"  "text/event-stream"
                               "Cache-Control" "no-cache"
                               "Connection"    "keep-alive"}}
                 false)
          (let [tap-ch   (async/chan 64)
                rtype    (store/run-type run)
                evt-name (if (= :wiggum rtype) "wiggum-update" "node-update")
                mult     (:event-mult run)]
            ;; Tap before replaying so we don't miss events between replay and live
            (async/tap mult tap-ch)
            (on-close ch (fn [_]
                           (log/debug "SSE client disconnected" run-id)
                           (async/untap mult tap-ch)
                           (async/close! tap-ch)))
            ;; Replay past events for DAG runs — nodes may complete before the browser connects
            (when (= :dag rtype)
              (doseq [event @(:event-log run)]
                (try
                  (let [fragment (dag-fragment run run-id event)]
                    (when fragment
                      (send! ch (sse-event evt-name fragment))))
                  (catch Exception e
                    (log/error e "SSE replay error" {:event-type (:type event)})))))
            ;; For wiggum runs, the page already renders past iterations + the
            ;; control packet at page-load time. The one piece that's missing
            ;; on a refresh-while-paused is the review dialog. Replay just the
            ;; most recent :run-paused event so the dialog reappears.
            (when (and (= :wiggum rtype) (= :paused @(:state run)))
              (when-let [pause-ev (->> @(:event-log run)
                                       (filter #(= :run-paused (:type %)))
                                       last)]
                (try
                  (when-let [fragment (wiggum-fragment run-id pause-ev run-store)]
                    (send! ch (sse-event evt-name fragment)))
                  (catch Exception e
                    (log/error e "SSE wiggum pause replay error")))))
            (go-loop []
              (when-let [event (<! tap-ch)]
                (try
                  (let [fragment (if (= :wiggum rtype)
                                   (wiggum-fragment run-id event run-store)
                                   (dag-fragment run run-id event))]
                    (when fragment
                      (send! ch (sse-event evt-name fragment))))
                  (catch Exception e
                    (log/error e "SSE fragment error" {:event-type (:type event)})))
                (recur)))))))))

;; ──────────────────────────────────────────
;; DAG — checkpoint action handler
;; ──────────────────────────────────────────

(defn checkpoint-handler [run-store]
  (fn [{:keys [path-params form-params]}]
    (let [run-id   (parse-long (:id path-params))
          node-id  (keyword (:node-id path-params))
          action   (keyword (get form-params "action"))
          feedback (get form-params "feedback" "")
          run      (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (do
          (engine/resume! run {:action   action
                               :node-id  node-id
                               :feedback feedback})
          (html-resp
            (case action
              :approve (ui/cleared-panel)
              :revise  (ui/revising-panel node-id)
              :abort   [:div#checkpoint-panel.checkpoint-panel
                        [:div.cp-label "Run aborted."]])))))))

;; ──────────────────────────────────────────
;; DAG — node detail handler
;; ──────────────────────────────────────────

(defn node-detail-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id  (parse-long (:id path-params))
          node-id (keyword (:node-id path-params))
          run     (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (let [nodes   (-> @(:graph-atom run) :graph/nodes)
              node    (first (filter #(= (:id %) node-id) nodes))
              state   (get @(:state run) node-id :pending)
              output  (or (get @(:context run) (:output-key node))
                          (when (= :running state)
                            (get @(:output-buffers run) node-id)))]
          (html-resp (ui/node-detail node state output)))))))

;; ──────────────────────────────────────────
;; DAG — start run handler
;; ──────────────────────────────────────────

(defn start-run-handler [run-store]
  (fn [{:keys [body-params]}]
    (let [graph  (:graph body-params)
          ctx    (:context body-params {})
          run-id (System/currentTimeMillis)
          run    (engine/execute! run-id graph ctx)]
      (store/add-run! run-store run)
      {:status  201
       :headers {"Location" (str "/runs/" run-id)}
       :body    (str run-id)})))

;; ──────────────────────────────────────────
;; Wiggum — start handler
;; ──────────────────────────────────────────

(defn start-wiggum-handler [run-store]
  (fn [{:keys [body-params]}]
    (let [run-id (System/currentTimeMillis)
          _      (when-let [pd (:project-dir body-params)]
                   (store/load-ui-state! run-store pd))
          run    (wiggum/execute! run-id body-params)]
      (store/add-run! run-store run)
      {:status  201
       :headers {"Location" (str "/runs/" run-id)}
       :body    (str run-id)})))

;; ──────────────────────────────────────────
;; Wiggum — iteration control handlers
;; ──────────────────────────────────────────

(defn wiggum-resume-handler [run-store]
  (fn [{:keys [path-params form-params]}]
    (let [run-id   (parse-long (:id path-params))
          run      (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (let [action    (keyword (get form-params "action" "step"))
              feedback  (let [fb (str/trim (get form-params "feedback" ""))]
                          (when (seq fb) fb))
              overrides (when-let [ov (get form-params "overrides")]
                          (clojure.edn/read-string ov))]
          (wiggum/resume! run {:action    action
                               :feedback  feedback
                               :overrides overrides})
          ;; hide the review panel immediately on submit
          (html-resp [:div#review-panel {:style "display:none"}]))))))

(defn steer-handler [run-store]
  (fn [{:keys [path-params form-params]}]
    (let [run-id (parse-long (:id path-params))
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (let [text (str/trim (get form-params "steer" ""))]
          (when (seq text)
            (case (store/run-type run)
              :wiggum (wiggum/steer! run text)
              :dag    (engine/steer! run text)))
          (html-resp (ui/steer-widget run-id)))))))

(defn abort-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id (parse-long (:id path-params))
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (do (case (store/run-type run)
              :wiggum (wiggum/abort! run)
              :dag    (engine/abort! run))
            (html-resp (ui/abort-button run-id :disabled)))))))

(defn wiggum-step-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id (parse-long (:id path-params))
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (do (wiggum/step! run)
            (html-resp (ui/step-toggle run-id true)))))))

(defn wiggum-auto-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id (parse-long (:id path-params))
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (do (wiggum/auto! run)
            (html-resp (ui/step-toggle run-id false)))))))

;; ──────────────────────────────────────────
;; Run page — dispatches on run type
;; ──────────────────────────────────────────

(defn run-page-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id (parse-long (:id path-params))
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (case (store/run-type run)
          :wiggum (page-resp (ui/wiggum-shell-page run-id (store/run-summary run)))
          :dag    (page-resp (ui/shell-page run-id (store/run-summary run))))))))

(defn iteration-detail-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id      (parse-long (:id path-params))
          n           (parse-long (:n path-params))
          run         (store/get-run run-store run-id)
          ev          (when run (nth @(:iterations run) (dec n) nil))
          live-output (when (and run (nil? ev) (= :running @(:state run)))
                        (let [buf @(:live-output run)]
                          (if (str/blank? buf)
                            "Claude Code is working…"
                            buf)))]
      (cond
        ev          (html-resp (ui/iteration-detail ev))
        live-output (html-resp [:pre#live-output.iter-output.iter-output-live live-output])
        :else       {:status 404 :body "Iteration not found"}))))

;; ──────────────────────────────────────────
;; Router
;; ──────────────────────────────────────────

(defn css-handler [_]
  (if-let [r (io/resource "public/style.css")]
    {:status  200
     :headers {"Content-Type" "text/css; charset=utf-8"}
     :body    (slurp r)}
    {:status 404 :body "style.css not found"}))

(defn create-router [run-store]
  (ring/ring-handler
    (ring/router
      [["/style.css"   {:get css-handler}]
       ["/favicon.ico" {:get (fn [_] {:status 204 :body ""})}]
       ["/runs"
        ["" {:post (start-run-handler run-store)}]
        ["/:id"
         [""                     {:get  (run-page-handler run-store)}]
         ["/stream"              {:get  (stream-handler run-store)}]
         ["/step"                {:post (wiggum-step-handler run-store)}]
         ["/auto"                {:post (wiggum-auto-handler run-store)}]
         ["/abort"               {:post (abort-handler run-store)}]
         ["/resume"              {:post (wiggum-resume-handler run-store)}]
         ["/steer"               {:post (steer-handler run-store)}]
         ["/checkpoint/:node-id" {:post (checkpoint-handler run-store)}]
         ["/nodes/:node-id"      {:get  (node-detail-handler run-store)}]
         ["/iterations/:n"       {:get  (iteration-detail-handler run-store)}]]]
       ["/wiggum"
        ["" {:post (start-wiggum-handler run-store)}]]])
    (ring/create-default-handler)
    {:middleware [params/wrap-params]}))
