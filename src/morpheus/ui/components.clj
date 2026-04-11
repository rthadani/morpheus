(ns morpheus.ui.components
  "Hiccup UI components. Pure functions — no I/O.
   All mutable state lives in the executor; these just render it.")

;; ──────────────────────────────────────────
;; Node state → visual
;; ──────────────────────────────────────────

(def state-classes
  {:done    "node-done"
   :running "node-running"
   :paused  "node-paused"
   :pending "node-pending"
   :error   "node-error"})

(def state-labels
  {:done    "complete"
   :running "running"
   :paused  "awaiting input"
   :pending "pending"
   :error   "error"})

(defn node-badge
  [{:keys [id type]} node-state duration]
  (let [cls (get state-classes node-state "node-pending")]
    [:g {:id            (str "node-badge-" (name id))
         :class         (str "dag-node " cls)
         :hx-get        (str "/nodes/" (name id))
         :hx-target     "#side-body"
         :hx-swap       "innerHTML"
         :style         "cursor:pointer"}
     [:circle {:r 24 :class "node-circle"}]
     [:text   {:class "node-label" :text-anchor "middle" :y -4} (name id)]
     [:text   {:class "node-sublabel" :text-anchor "middle" :y 10}
      (if duration duration (get state-labels node-state "pending"))]]))

;; ──────────────────────────────────────────
;; DAG canvas
;; ──────────────────────────────────────────

(defn dag-canvas [nodes state-map]
  [:div#dag-canvas
   [:svg {:width "100%" :viewBox "0 0 520 500"
          :xmlns "http://www.w3.org/2000/svg"}
    [:defs
     [:marker {:id "arrow" :viewBox "0 0 10 10" :refX "8" :refY "5"
               :markerWidth "6" :markerHeight "6" :orient "auto-start-reverse"}
      [:path {:d "M2 1L8 5L2 9" :fill "none" :stroke "#888" :stroke-width "1.5"}]]]
    (for [[idx node] (map-indexed vector nodes)]
      (let [x 260 y (+ 50 (* idx 80))]
        [:g {:transform (str "translate(" x "," y ")")}
         (node-badge node (get state-map (:id node) :pending) nil)]))]])

;; ──────────────────────────────────────────
;; DAG — log + checkpoint
;; ──────────────────────────────────────────

(defn log-line [level text]
  [:div {:class (str "log-line " (name level))} text])

(defn checkpoint-panel [run-id node-id summary]
  [:div#checkpoint-panel.checkpoint-panel.visible
   [:div.cp-label (str "⏸ Checkpoint · " (name node-id))]
   [:div.cp-summary summary]
   [:textarea#cp-feedback.cp-feedback
    {:name "feedback" :rows 3
     :placeholder "Optional feedback or revisions…"}]
   [:div.cp-actions
    [:button {:hx-post    (str "/runs/" run-id "/checkpoint/" (name node-id))
              :hx-vals    "{\"action\":\"abort\"}"
              :hx-include "#cp-feedback"
              :hx-target  "#checkpoint-panel"
              :hx-swap    "outerHTML"
              :class      "btn-danger"}
     "Abort"]
    [:button {:hx-post    (str "/runs/" run-id "/checkpoint/" (name node-id))
              :hx-vals    "{\"action\":\"revise\"}"
              :hx-include "#cp-feedback"
              :hx-target  "#checkpoint-panel"
              :hx-swap    "outerHTML"
              :class      "btn"}
     "Revise ↺"]
    [:button {:hx-post    (str "/runs/" run-id "/checkpoint/" (name node-id))
              :hx-vals    "{\"action\":\"approve\"}"
              :hx-include "#cp-feedback"
              :hx-target  "#checkpoint-panel"
              :hx-swap    "outerHTML"
              :class      "btn-primary"}
     "Approve ↗"]]])

(defn cleared-panel []  [:div#checkpoint-panel.checkpoint-panel])
(defn revising-panel [node-id]
  [:div#checkpoint-panel.checkpoint-panel.visible
   [:div.cp-label (str "Re-running " (name node-id) "…")]
   [:div.cp-summary "Feedback injected. Waiting for new output."]])

;; ──────────────────────────────────────────
;; DAG — node detail
;; ──────────────────────────────────────────

(defn node-output-panel [node-id stdout exit]
  [:div#node-output.node-output
   [:div.detail-label
    [:span (str (name node-id) " output")]
    (when exit
      [:span {:class (str "exit-badge" (when (pos? exit) " exit-nonzero"))}
       (str "exit " exit)])]
   [:pre.detail-pre (or stdout "")]])

(defn node-detail [node state output]
  (let [stdout    (if (map? output) (:output output) output)
        files     (when (map? output) (:files-written output))
        exit-code (when (map? output) (:exit output))]
    [:div
     [:div.detail-header
      [:span.detail-id   (name (:id node))]
      [:span.detail-type (name (:type node))]
      (when exit-code
        [:span {:class (str "exit-badge" (when (pos? exit-code) " exit-nonzero"))}
         (str "exit " exit-code)])
      [:span {:class (str "detail-state " (name (get state-classes state "pending")))}
       (get state-labels state "pending")]]
     (when (seq files)
       [:div.detail-files
        [:div.detail-label "Files written"]
        [:ul.detail-file-list (for [f files] [:li f])]])
     (when stdout
       [:div.detail-output
        [:div.detail-label "Output"]
        [:pre.detail-pre stdout]])]))

;; ──────────────────────────────────────────
;; Wiggum — iteration list row (left panel)
;; ──────────────────────────────────────────

(defn iteration-row
  [run-id {:keys [iteration duration-ms files-written files-edited
                  exit-code verification slop-signals]}]
  (let [secs    (int (/ (or duration-ms 0) 1000))
        ver-ok? (or (nil? verification) (zero? (:exit verification)))
        slop?   (let [s (or slop-signals {})]
                  (or (:helpers-added? s)
                      (:only-new-files? s)
                      (> (or (:new-file-ratio s) 0) 70)))]
    [:div {:id       (str "iter-row-" iteration)
           :class    "iter-row"
           :hx-get   (str "/runs/" run-id "/iterations/" iteration)
           :hx-target "#wg-detail"
           :hx-swap  "innerHTML"}
     [:div.iter-row-top
      [:span.iter-row-num (str "#" iteration)]
      [:span {:class (str "iter-badge " (if ver-ok? "badge-ok" "badge-fail"))}
       (if ver-ok? "✓" "✗")]
      (when slop? [:span.iter-badge.badge-warn "⚠"])
      [:span.iter-row-dur (str secs "s")]]
     [:div.iter-row-files
      (when (seq files-written) [:span.files-new (str "+" (count files-written) " new")])
      (when (seq files-edited)  [:span.files-edit (str "~" (count files-edited) " edited")])]]))

(defn iteration-running-row [iteration]
  [:div {:id "iter-row-running" :class "iter-row iter-row-running"}
   [:div.iter-row-top
    [:span.iter-row-num (str "#" iteration)]
    [:span.iter-badge.badge-running "…"]
    [:span.iter-row-dur "running"]]
   [:div.iter-row-files [:span.iter-running-label "Claude Code working…"]]])

(defn iteration-list [run-id evidence-list]
  [:div#iteration-list
   (for [ev (reverse evidence-list)]
     (iteration-row run-id ev))])

;; ──────────────────────────────────────────
;; Wiggum — iteration detail (centre panel)
;; ──────────────────────────────────────────

(defn iteration-detail
  [{:keys [iteration duration-ms files-written files-edited files-deleted
           exit-code output verification slop-signals model]}]
  (let [secs    (int (/ (or duration-ms 0) 1000))
        ver-ok? (or (nil? verification) (zero? (:exit verification)))]
    [:div.iter-detail
     [:div.iter-detail-header
      [:span.iter-detail-title (str "Iteration #" iteration)]
      [:span {:class (str "exit-badge" (when (pos? (or exit-code 0)) " exit-nonzero"))}
       (str "exit " exit-code)]
      [:span.iter-detail-dur (str secs "s")]
      (when model [:span.iter-detail-model model])]

     (when (seq files-written)
       [:div.iter-section
        [:div.iter-section-label (str "New files (" (count files-written) ")")]
        [:ul.iter-file-list.files-new (for [f files-written] [:li f])]])
     (when (seq files-edited)
       [:div.iter-section
        [:div.iter-section-label (str "Edited (" (count files-edited) ")")]
        [:ul.iter-file-list.files-edit (for [f files-edited] [:li f])]])
     (when (seq files-deleted)
       [:div.iter-section
        [:div.iter-section-label (str "Deleted (" (count files-deleted) ")")]
        [:ul.iter-file-list.files-del (for [f files-deleted] [:li f])]])

     (when verification
       [:div.iter-section
        [:div.iter-section-label "Verification"]
        [:div {:class (str "ver-block " (if ver-ok? "ver-ok" "ver-fail"))}
         (if ver-ok? "✓ passed" (str "✗ exit " (:exit verification)))]])

     (when slop-signals
       (let [{:keys [new-file-ratio helpers-added? only-new-files?]} slop-signals]
         [:div.iter-section
          [:div.iter-section-label "Slop signals"]
          [:div.slop-row
           [:span {:class (str "slop-pill" (when (> (or new-file-ratio 0) 70) " slop-warn"))}
            (str "new " new-file-ratio "%")]
           [:span {:class (str "slop-pill" (when helpers-added? " slop-warn"))}
            (str "helpers " helpers-added?)]
           [:span {:class (str "slop-pill" (when only-new-files? " slop-warn"))}
            (str "only-new " only-new-files?)]]]))

     (when (seq output)
       [:div.iter-section
        [:div.iter-section-label "Output"]
        [:pre.iter-output output]])]))

(defn iteration-detail-placeholder []
  [:div.iter-detail-placeholder "← Select an iteration"])

;; ──────────────────────────────────────────
;; Wiggum — control packet (right panel)
;; ──────────────────────────────────────────

(defn control-packet-panel [packet]
  (let [{:keys [objective constraints success-check anti-goals brief]} (or packet {})]
    [:div#control-packet-panel
     (when (seq objective) [:div.cp-objective objective])
     (when (seq constraints)
       [:div.cp-section
        [:div.cp-label "Constraints"]
        [:ul.cp-list (for [c constraints] [:li c])]])
     (when success-check
       [:div.cp-section
        [:div.cp-label "Done when"]
        [:code.cp-check success-check]])
     (when (seq anti-goals)
       [:div.cp-section
        [:div.cp-label "Do not"]
        [:ul.cp-list (for [g anti-goals] [:li g])]])
     (when (seq brief)
       [:div.cp-brief brief])]))

;; ──────────────────────────────────────────
;; Wiggum — controls + step toggle
;; ──────────────────────────────────────────

(defn iteration-controls [run-id paused?]
  [:div {:id    "iteration-controls"
         :class (str "iter-controls" (when paused? " iter-controls-visible"))}
   [:button {:hx-post   (str "/runs/" run-id "/resume")
             :hx-vals   "{\"action\":\"step\"}"
             :hx-target "#iteration-controls"
             :hx-swap   "outerHTML"
             :class     "btn-primary"}
    "Step ↓"]
   [:button {:hx-post   (str "/runs/" run-id "/resume")
             :hx-vals   "{\"action\":\"retry\"}"
             :hx-target "#iteration-controls"
             :hx-swap   "outerHTML"
             :class     "btn"}
    "Retry ↺"]
   [:button {:hx-post   (str "/runs/" run-id "/resume")
             :hx-vals   "{\"action\":\"abort\"}"
             :hx-target "#iteration-controls"
             :hx-swap   "outerHTML"
             :class     "btn-danger"}
    "Abort"]])

(defn step-toggle [run-id step-once?]
  [:button {:id        "step-toggle"
            :hx-post   (str "/runs/" run-id (if step-once? "/auto" "/step"))
            :hx-target "#step-toggle"
            :hx-swap   "outerHTML"
            :class     (str "btn" (when step-once? " btn-active"))}
   (if step-once? "Step ON" "Auto")])

;; ──────────────────────────────────────────
;; Wiggum — human review panel (shown on pause)
;; ──────────────────────────────────────────

(defn review-panel
  "Full review panel shown when the run pauses. Includes evidence summary,
   feedback textarea, and action buttons."
  [run-id iteration evidence milestone?]
  (let [ev      (or evidence {})
        ver-ok? (or (nil? (:verification ev))
                    (zero? (:exit (:verification ev))))
        secs    (int (/ (or (:duration-ms ev) 0) 1000))]
    [:div#review-panel.review-panel
     [:div.review-header
      [:span.review-title
       (if milestone?
         (str "⭐ Milestone reached · iteration " iteration)
         (str "⏸ Paused · iteration " iteration))]
      [:div.review-summary
       [:span {:class (str "exit-badge" (when (pos? (or (:exit-code ev) 0)) " exit-nonzero"))}
        (str "exit " (:exit-code ev))]
       [:span (str secs "s")]
       (when (seq (:files-written ev))
         [:span.files-new (str "+" (count (:files-written ev)) " new")])
       (when (seq (:files-edited ev))
         [:span.files-edit (str "~" (count (:files-edited ev)) " edited")])
       [:span {:class (str "ver-inline " (if ver-ok? "ver-ok" "ver-fail"))}
        (if ver-ok? "✓ verified" "✗ verify failed")]]]

     [:form.review-form
      {:hx-post    (str "/runs/" run-id "/resume")
       :hx-target  "#review-panel"
       :hx-swap    "outerHTML"}
      [:textarea.review-feedback
       {:name        "feedback"
        :rows        3
        :placeholder "Steer the next iteration… e.g. \"Focus on the auth layer\" or \"The tests are slow, optimise them first\""}]
      [:div.review-actions
       [:button {:type "submit" :name "action" :value "abort" :class "btn-danger"}
        "Abort"]
       [:button {:type "submit" :name "action" :value "retry" :class "btn"}
        "Retry iteration ↺"]
       [:button {:type "submit" :name "action" :value "step" :class "btn-primary"}
        "Continue →"]]]]))


;; ──────────────────────────────────────────
;; Wiggum — full shell page (mail split-view)
;; ──────────────────────────────────────────

(defn wiggum-shell-page [run-id summary]
  (let [state         (:state summary :pending)
        objective     (:objective summary "…")
        iteration     (:iteration summary 0)
        packet        (:control-packet summary)
        evidence-list (:evidence-list summary [])
        step-once?    (get-in summary [:control :step-once?] false)
        latest        (last evidence-list)]
    [:html
     [:head
      [:meta {:charset "utf-8"}]
      [:title (str "Morpheus · " run-id)]
      [:script {:src "https://unpkg.com/htmx.org@1.9.12"}]
      [:script {:src "https://unpkg.com/htmx.org@1.9.12/dist/ext/sse.js"}]
      [:link {:rel "stylesheet" :href "/style.css"}]]
     [:body
      [:div.wg-shell
       {:hx-ext "sse" :sse-connect (str "/runs/" run-id "/stream")}
       [:div {:id "sse-sink" :sse-swap "wiggum-update" :style "display:none"}]

       ;; ── Topbar ─────────────────────────────────────────
       [:div.wg-topbar
        [:span.wg-run (str "#" run-id)]
        [:span.wg-obj objective]
        [:span#run-status {:class (str "status-pill status-" (name state))} (name state)]
        [:span#wg-iter-count.wg-iter (str "iter " iteration)]
        (when-let [wd (:work-dir summary)]
          [:span.wg-workdir {:title wd} (str "→ " wd)])
        (step-toggle run-id step-once?)]

       ;; ── Review panel (hidden until :run-paused) ────────
       [:div#review-panel {:style "display:none"}]

       ;; ── Left: iteration list ────────────────────────────
       [:div.wg-list
        [:div.wg-pane-header "Iterations"]
        (iteration-list run-id evidence-list)]

       ;; ── Centre: iteration detail ────────────────────────
       [:div.wg-detail-col
        [:div.wg-pane-header "Detail"]
        [:div#wg-detail.wg-detail-body
         (if latest (iteration-detail latest) (iteration-detail-placeholder))]]

       ;; ── Right: control packet ───────────────────────────
       [:div.wg-packet-col
        [:div.wg-pane-header "Control packet"]
        (control-packet-panel packet)]

       ;; ── Footer: log ─────────────────────────────────────
       [:div.wg-footer
        [:div#log-tail.log-tail]]]]]))

;; ──────────────────────────────────────────
;; DAG full shell page
;; ──────────────────────────────────────────

(defn shell-page [run-id graph-id]
  [:html
   [:head
    [:meta {:charset "utf-8"}]
    [:title (str "Morpheus · " graph-id)]
    [:script {:src "https://unpkg.com/htmx.org@1.9.12"}]
    [:script {:src "https://unpkg.com/htmx.org@1.9.12/dist/ext/sse.js"}]
    [:link {:rel "stylesheet" :href "/style.css"}]]
   [:body
    [:div.shell
     [:div.topbar
      [:span.topbar-title (str graph-id)]
      [:span.topbar-sub   (str "run #" run-id)]
      [:span#run-status.status-pill.status-running "Running"]]

     [:div.graph-pane
      {:hx-ext "sse" :sse-connect (str "/runs/" run-id "/stream")}
      [:div {:id "sse-sink" :sse-swap "node-update" :style "display:none"}]
      [:div#dag-placeholder "Loading graph…"]]

     [:div.side-pane
      [:div.side-header "Execution log"]
      [:div#side-body.side-body
       [:div#node-output.node-output]
       [:div#log-lines]]
      [:div#checkpoint-panel.checkpoint-panel]]]]])
