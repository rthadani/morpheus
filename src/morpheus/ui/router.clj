(ns morpheus.ui.router
  "Reitit ring router. Routes return hiccup rendered to HTML, except the SSE
   stream which returns text/event-stream."
  (:require
   [reitit.ring            :as ring]
   [ring.middleware.params :as params]
   [clojure.edn            :as edn]
   [clojure.java.io        :as io]
   [clojure.string         :as str]
   [hiccup2.core           :as h]
   [clojure.core.async     :as async :refer [go-loop <!]]
   [taoensso.timbre        :as log]
   [org.httpkit.server     :refer [send! with-channel on-close]]
   [morpheus.executor.engine    :as engine]
   [morpheus.executor.wiggum    :as wiggum]
   [morpheus.executor.store     :as store]
   [morpheus.executor.dispatch  :as dispatch]
   [morpheus.slug               :as slug]
   [morpheus.ui.components      :as ui]))

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
  "Multi-line SSE event — each line of data-html gets its own data: prefix."
  [event-name data-html]
  (let [data-lines (->> (str/split data-html #"\n")
                        (map #(str "data: " %))
                        (str/join "\n"))]
    (str "event: " event-name "\n"
         data-lines "\n\n")))

(declare with-attr)

(defn- dag-fragment [run run-id event]
  (case (:type event)
    :state-change
    (let [nodes     (-> @(:graph-atom run) :graph/nodes)
          state-map @(:state run)
          node      (first (filter #(= (:id %) (:node-id event)) nodes))]
      (when node
        (str ;; Replace the whole canvas div — individual <g> OOB swaps fail because
             ;; HTMX parses them as HTML unknowns inside #sse-sink (a div), not as SVG.
             (h/html (with-attr (ui/dag-canvas nodes state-map)
                                :hx-swap-oob "outerHTML:#dag-canvas"))
             (when (= :running (:state event))
               (h/html [:div#node-output.node-output
                        {:hx-swap-oob "outerHTML:#node-output"}
                        [:div.detail-label [:span (str (name (:id node)) " running…")]]
                        [:pre#live-output.detail-pre
                         (str (ui/agent-label (dispatch/resolve-agent node)) " working…")]])))))

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
            (:line event)]))

    :wiggum-started
    (let [node-id    (name (:node-id event))
          sub-run-id (:sub-run-id event)
          href       (str "/runs/" sub-run-id)]
      (str (h/html
             [:div#node-output.node-output
              {:hx-swap-oob "outerHTML:#node-output"}
              [:div.detail-label [:span (str node-id " running")]]
              [:div.subrun-link-box
               [:a.subrun-link {:href href :target "_blank" :rel "noopener"}
                "Open sub-run"]]])
           (h/html
             [:div {:id "log-lines" :hx-swap-oob "beforeend"}
              (ui/log-line :info (str node-id " started " sub-run-id))])))

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

(defn- with-attr
  "Adds an attribute to a hiccup vector whether or not it has an explicit attr
   map at index 1 — so we can tag a component with hx-swap-oob without forcing
   every component to declare an empty {} attr map."
  [[tag & body :as h] k v]
  (if (map? (first body))
    (apply vector tag (assoc (first body) k v) (rest body))
    (apply vector tag {k v} body)))

(defn- wiggum-fragment [run-id event run-store]
  (case (:type event)
    :state-change
    (str (h/html
           [:span {:id          "run-status"
                   :hx-swap-oob "outerHTML:#run-status"
                   :class       (str "status-pill status-" (name (:state event)))}
            (name (:state event))]))

    :iteration-started
    (let [agent (store/run-agent (store/get-run run-store run-id))]
     (str (h/html
           ;; hx-swap-oob applied directly to the panel's root so outerHTML
           ;; doesn't create a wrapper-and-nested duplicate id.
           (with-attr (ui/control-packet-panel (:control-packet event))
                      :hx-swap-oob "outerHTML"))
         (h/html
           (with-attr (ui/iteration-running-row run-id (:iteration event) agent)
                      :hx-swap-oob "outerHTML"))
         (h/html
           (with-attr (ui/iteration-live-panel (:iteration event) agent)
                      :hx-swap-oob "outerHTML"))
         (h/html
           [:div {:id "log-tail" :hx-swap-oob "beforeend"}
            (ui/log-line :info (str "→ iteration " (:iteration event) " started"))])))

    :iteration-complete
    (let [ev        (:evidence event)
          run       (store/get-run run-store run-id)
          all-iters (when run @(:iterations run))
          tok-str   (ui/total-cost-str (if (some #(= (:iteration %) (:iteration ev)) all-iters)
                                         all-iters
                                         (conj (vec all-iters) ev)))]
      (str ;; afterend FIRST inserts the sibling while #iter-row-running is in DOM;
           ;; outerHTML SECOND replaces it. Order matters — afterend runs against
           ;; the live element, not the detached one.
           (h/html
             (update (ui/iteration-row run-id ev) 1
                     assoc :hx-swap-oob "afterend:#iter-row-running"))
           (h/html
             [:div {:id "iter-row-running" :hx-swap-oob "outerHTML:#iter-row-running"}])
           (h/html
             [:div {:hx-swap-oob "innerHTML:#wg-detail"}
              (ui/iteration-detail ev)])
           (h/html
             [:span {:id "wg-iter-count" :hx-swap-oob "outerHTML:#wg-iter-count"
                     :class "wg-iter"}
              (str "iter " (:iteration ev))])
           (when tok-str
             (h/html
               [:span {:id "wg-tokens" :hx-swap-oob "outerHTML:#wg-tokens"
                       :class "wg-iter"}
                tok-str]))
           (h/html
             [:div {:id "log-tail" :hx-swap-oob "beforeend"}
              (ui/log-line (if (zero? (or (:exit-code ev) 0)) :ok :warn)
                           (str "✓ iter " (:iteration ev)
                                " +" (count (:files-written ev))
                                " ~" (count (:files-edited ev))
                                (when-let [v (:verification ev)]
                                  (if (zero? (:exit v)) " ✓" " ✗"))))])))

    :run-paused
    (let [run           (store/get-run run-store run-id)
          latest-ev     (when run (last @(:iterations run)))
          milestone?    (:milestone? event)
          review-pause? (:review-pause? event)
          exhausted?    (:exhausted? event)]
      ;; Swap only #review-meta — the form (and textarea) live in
      ;; #review-panel and are never re-rendered, so typing is not disrupted.
      (str (h/html
             [:div {:id "review-meta" :hx-swap-oob "innerHTML:#review-meta"}
              (ui/review-meta event latest-ev)])
           (h/html
             [:div {:id "log-tail" :hx-swap-oob "beforeend"}
              (ui/log-line (cond exhausted? :err
                                 review-pause? :warn
                                 milestone? :info
                                 :else :warn)
                           (str (cond exhausted?    "⛔ tokens exhausted"
                                      review-pause? "⚠ judge paused"
                                      milestone?    "⭐ milestone"
                                      :else         "⏸ paused")
                                " after iteration " (:iteration event)
                                (when-let [m (:quota-message event)]
                                  (str " — " m))))])))

    :output-line
    ;; Append raw — the agent supplies its own newlines.
    (str (h/html
           [:span {:id "live-output" :hx-swap-oob "beforeend"}
            (:line event)]))

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

(defn stream-handler [run-store]
  (fn [{:keys [path-params] :as req}]
    (let [run-id (:id path-params)
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (with-channel req ch
          (send! ch {:status  200
                     :headers {"Content-Type"  "text/event-stream"
                               "Cache-Control" "no-cache"
                               "Connection"    "keep-alive"}}
                 false)
          (let [tap-ch   (async/chan 64)
                rtype    (store/run-type run)
                evt-name (if (= :wiggum rtype) "wiggum-update" "node-update")
                mult     (:event-mult run)]
            ;; Tap before replaying so we don't miss events between replay and live.
            (async/tap mult tap-ch)
            (on-close ch (fn [_]
                           (log/debug "SSE client disconnected" run-id)
                           (async/untap mult tap-ch)
                           (async/close! tap-ch)))
            ;; DAG runs: replay past events — nodes may have completed before
            ;; the browser connected.
            (when (= :dag rtype)
              (doseq [event @(:event-log run)]
                (try
                  (let [fragment (dag-fragment run run-id event)]
                    (when fragment
                      (send! ch (sse-event evt-name fragment) false)))
                  (catch Exception e
                    (log/error e "SSE replay error" {:event-type (:type event)})))))
            ;; On (re)connect, resync the packet/status/iter-count — an
            ;; :iteration-started event that fired during a reconnect gap
            ;; would otherwise leave the panel stuck on the page-load packet.
            (when (= :wiggum rtype)
              (try
                (let [iters    @(:iterations run)
                      tok-str  (ui/total-cost-str iters)]
                  (send! ch (sse-event evt-name
                              (str (h/html (with-attr (ui/control-packet-panel @(:control-packet run))
                                                      :hx-swap-oob "outerHTML"))
                                   (h/html [:span {:id          "run-status"
                                                   :hx-swap-oob "outerHTML:#run-status"
                                                   :class       (str "status-pill status-" (name @(:state run)))}
                                            (name @(:state run))])
                                   (h/html [:span {:id          "wg-iter-count"
                                                   :hx-swap-oob "outerHTML:#wg-iter-count"
                                                   :class       "wg-iter"}
                                            (str "iter " (count iters))])
                                   (when tok-str
                                     (h/html [:span {:id          "wg-tokens"
                                                     :hx-swap-oob "outerHTML:#wg-tokens"
                                                     :class       "wg-iter"}
                                              tok-str]))))
                            false))
                (catch Exception e
                  (log/error e "SSE wiggum state resync error"))))
            ;; Replay the latest :run-paused if the run is paused — same
            ;; reconnect gap as above. The OOB swap is idempotent against the
            ;; inline panel from wiggum-shell-page, and the textarea has
            ;; hx-preserve so typing survives.
            (when (and (= :wiggum rtype) (= :paused @(:state run)))
              (when-let [pause-ev (->> @(:event-log run)
                                       (filter #(= :run-paused (:type %)))
                                       last)]
                (try
                  (when-let [fragment (wiggum-fragment run-id pause-ev run-store)]
                    (send! ch (sse-event evt-name fragment) false))
                  (catch Exception e
                    (log/error e "SSE wiggum pause replay error")))))
            (go-loop []
              (when-let [event (<! tap-ch)]
                (try
                  (let [fragment (if (= :wiggum rtype)
                                   (wiggum-fragment run-id event run-store)
                                   (dag-fragment run run-id event))]
                    (when fragment
                      (send! ch (sse-event evt-name fragment) false)))
                  (catch Exception e
                    (log/error e "SSE fragment error" {:event-type (:type event)})))
                (recur)))))))))

(defn checkpoint-handler [run-store]
  (fn [{:keys [path-params form-params]}]
    (let [run-id   (:id path-params)
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

(defn node-detail-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id  (:id path-params)
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

(defn start-run-handler [run-store]
  (fn [{:keys [body-params]}]
    (let [graph  (:graph body-params)
          ctx    (assoc (:context body-params {}) :morpheus.executor/run-store run-store)
          run-id (or (slug/slugify (:graph-name body-params))
                     (str "dag_" (System/currentTimeMillis)))
          run    (engine/execute! run-id graph ctx)]
      (store/add-run! run-store run)
      {:status  201
       :headers {"Location" (str "/runs/" run-id)}
       :body    run-id})))

(defn start-wiggum-handler [run-store]
  (fn [{:keys [body-params]}]
    (let [pd     (:project-dir body-params)
          run-id (or (slug/project-slug pd)
                     (str "wiggum_" (System/currentTimeMillis)))
          _      (when pd (store/load-ui-state! run-store pd))
          run    (wiggum/execute! run-id body-params)]
      (store/add-run! run-store run)
      {:status  201
       :headers {"Location" (str "/runs/" run-id)}
       :body    run-id})))

(defn wiggum-resume-handler [run-store]
  (fn [{:keys [path-params form-params]}]
    (let [run-id   (:id path-params)
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
          ;; Form targets #review-meta with innerHTML; empty body hides the
          ;; panel via CSS (#review-panel:not(:has(#review-meta > *)) → none).
          {:status  200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body    ""})))))

(defn steer-handler [run-store]
  (fn [{:keys [path-params form-params]}]
    (let [run-id (:id path-params)
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
    (let [run-id (:id path-params)
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (do (case (store/run-type run)
              :wiggum (wiggum/abort! run)
              :dag    (engine/abort! run))
            (html-resp (ui/abort-button run-id :disabled)))))))

(defn wiggum-step-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id (:id path-params)
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (do (wiggum/step! run)
            (html-resp (ui/step-toggle run-id true)))))))

(defn wiggum-auto-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id (:id path-params)
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (do (wiggum/auto! run)
            (html-resp (ui/step-toggle run-id false)))))))

(defn run-page-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id (:id path-params)
          run    (store/get-run run-store run-id)]
      (if-not run
        {:status 404 :body "Run not found"}
        (case (store/run-type run)
          :wiggum (page-resp (ui/wiggum-shell-page run-id (store/run-summary run)))
          :dag    (page-resp (ui/shell-page run-id (store/run-summary run))))))))

(defn iteration-detail-handler [run-store]
  (fn [{:keys [path-params]}]
    (let [run-id      (:id path-params)
          n           (parse-long (:n path-params))
          run         (store/get-run run-store run-id)
          ev          (when run (nth @(:iterations run) (dec n) nil))
          live-output (when (and run (nil? ev) (= :running @(:state run)))
                        (let [buf @(:live-output run)]
                          (if (str/blank? buf)
                            (str (ui/agent-label (store/run-agent run)) " is working…")
                            buf)))]
      (cond
        ev          (html-resp (ui/iteration-detail ev))
        live-output (html-resp [:pre#live-output.iter-output.iter-output-live live-output])
        :else       {:status 404 :body "Iteration not found"}))))

(defn- static-handler [path content-type]
  (fn [_]
    (if-let [r (io/resource (str "public/" path))]
      {:status  200
       :headers {"Content-Type" content-type}
       :body    (slurp r)}
      {:status 404 :body (str path " not found")})))

(def css-handler     (static-handler "style.css"        "text/css; charset=utf-8"))
(def favicon-handler (static-handler "favicon.svg"      "image/svg+xml; charset=utf-8"))
(def theme-js-handler      (static-handler "theme-init.js"    "text/javascript; charset=utf-8"))
(def tailwind-cfg-handler  (static-handler "tailwind-config.js" "text/javascript; charset=utf-8"))

(defn- cfg-type [cfg]
  (cond
    (contains? cfg :graph/nodes) :dag
    (contains? cfg :objective)   :wiggum))

(defn launch-handler [run-store]
  (fn [request]
    (let [params      (:params request)
          edn-file    (get params "edn-file")
          project-dir (not-empty (get params "project-dir"))
          step?       (= "true" (get params "step"))
          max-iter    (some-> (get params "max-iterations") not-empty parse-long)
          description (not-empty (get params "description"))]
      (if-not (and edn-file (.exists (io/file edn-file)))
        {:status 400 :body (str "file not found: " edn-file)}
        (try
          (let [raw    (edn/read-string (slurp edn-file))
                rtype  (cfg-type raw)
                cfg    (cond-> raw
                         project-dir (assoc :project-dir project-dir)
                         step?       (assoc :step-once? true)
                         max-iter    (assoc :max-iterations max-iter)
                         description (assoc :description description))
                run-id (or (case rtype
                             :wiggum (slug/project-slug project-dir)
                             :dag    (slug/graph-slug edn-file))
                           (str "run_" (System/currentTimeMillis)))
                run    (case rtype
                         :wiggum
                         (wiggum/execute! run-id cfg)
                         :dag
                         (engine/execute! run-id cfg
                           (cond-> {:morpheus.executor/run-store run-store}
                             project-dir
                             (assoc :graph/params
                                    (assoc (:graph/params cfg {}) :project-dir project-dir))
                             description
                             (assoc :describe/output description))
                           (if description {:describe :done} {})))]
            (store/add-run! run-store run)
            {:status 200 :headers {"Content-Type" "text/plain"} :body run-id})
          (catch Exception e
            {:status 500 :body (str "launch failed: " (ex-message e))}))))))

(defn create-router [run-store]
  (ring/ring-handler
    (ring/router
      [["/style.css"          {:get css-handler}]
       ["/favicon.svg"        {:get favicon-handler}]
       ["/theme-init.js"      {:get theme-js-handler}]
       ["/tailwind-config.js" {:get tailwind-cfg-handler}]
       ;; .ico left as 204 — modern browsers follow the <link rel="icon"
       ;; type="image/svg+xml"> in <head> and never hit /favicon.ico.
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
        ["" {:post (start-wiggum-handler run-store)}]]
       ["/internal/launch" {:get (launch-handler run-store)}]])
    (ring/create-default-handler)
    {:middleware [params/wrap-params]}))
