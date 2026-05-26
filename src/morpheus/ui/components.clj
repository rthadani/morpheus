(ns morpheus.ui.components
  "Hiccup UI components. Pure functions — no I/O. State lives in the executor."
  (:require [hiccup2.core :as h]))

(def ^:private theme-init-js
  "Runs synchronously before paint so the saved theme is applied before any
   CSS is interpreted. Without this, the page would briefly render in the
   default (OS-preferred) theme before the saved override kicked in."
  "(function(){
  var saved = null;
  try { saved = localStorage.getItem('morpheus-theme'); } catch (e) {}
  if (saved === 'light' || saved === 'dark') {
    document.documentElement.setAttribute('data-theme', saved);
  }
  window.morpheusToggleTheme = function() {
    var d = document.documentElement;
    var prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    var current = d.getAttribute('data-theme') || (prefersDark ? 'dark' : 'light');
    var next = current === 'dark' ? 'light' : 'dark';
    d.setAttribute('data-theme', next);
    try { localStorage.setItem('morpheus-theme', next); } catch (e) {}
  };
})();")

(def ^:private tailwind-config-js
  "tailwind.config = {
  theme: {
    extend: {
      colors: {
        bg:     'var(--bg)',     bg2:     'var(--bg2)',     bg3:     'var(--bg3)',
        border: 'var(--border)', border2: 'var(--border2)',
        ink:    'var(--text)',   ink2:    'var(--text2)',   ink3:    'var(--text3)',
        green:  'var(--green)',  'green-bg': 'var(--green-bg)',
        blue:   'var(--blue)',   'blue-bg':  'var(--blue-bg)',
        amber:  'var(--amber)',  'amber-bg': 'var(--amber-bg)',
        red:    'var(--red)',    'red-bg':   'var(--red-bg)'
      },
      borderRadius: { card: 'var(--radius)' },
      animation: {
        'pulse-badge': 'pulse-badge 1s ease-in-out infinite',
        'pulse-node':  'pulse 1.2s ease-in-out infinite'
      }
    }
  }
};")

(def ^:private tailwind-components-css
  "@layer components {
  /* ── Shared atoms ── */
  .status-pill { @apply text-[11px] font-semibold py-[2px] px-[10px] rounded-full tracking-[0.02em]; }
  .status-pending { @apply bg-bg3 text-ink2; }
  .status-running { @apply bg-blue-bg text-blue; }
  .status-paused  { @apply bg-amber-bg text-amber; }
  .status-done    { @apply bg-green-bg text-green; }
  .status-aborted { @apply bg-red-bg text-red; }

  .exit-badge { @apply text-[10px] font-mono py-px px-[6px] rounded-[4px] bg-green-bg text-green; }
  .exit-badge.exit-nonzero { @apply bg-red-bg text-red; }

  .btn, .btn-primary, .btn-danger, .btn-active {
    @apply text-[12px] py-[5px] px-[12px] rounded-card border-[0.5px] border-border2 bg-bg text-ink cursor-pointer;
  }
  .btn-primary { @apply bg-blue-bg  text-blue  border-transparent font-medium; }
  .btn-danger  { @apply bg-red-bg   text-red   border-transparent; }
  .btn-active  { @apply bg-amber-bg text-amber border-transparent font-medium; }
  .theme-toggle { @apply text-[14px] leading-none py-[3px] px-[8px] min-w-[28px] text-center; }

  .log-line { @apply text-[11px] font-mono text-ink2 py-px leading-[1.6]; }
  .log-line.ok   { @apply text-green; }
  .log-line.warn { @apply text-amber; }
  .log-line.err  { @apply text-red; }
  .log-line.info { @apply text-blue; }

  /* ── Wiggum mail split-view ── */
  .wg-shell {
    @apply grid grid-cols-[180px_1fr_420px] grid-rows-[44px_auto_1fr_auto] h-screen bg-bg;
  }
  .wg-topbar {
    @apply col-span-full flex items-center gap-[10px] px-4 border-b border-border bg-bg2;
  }
  .wg-run     { @apply text-[12px] font-semibold text-ink2; }
  .wg-obj     { @apply text-[12px] text-ink2 flex-1 overflow-hidden whitespace-nowrap text-ellipsis; }
  .wg-iter    { @apply text-[11px] text-ink3; }
  .wg-workdir { @apply text-[10px] font-mono text-ink3 max-w-[260px] overflow-hidden whitespace-nowrap text-ellipsis; }

  #paused-banner, .paused-banner {
    @apply col-span-full flex items-center gap-3 px-4 py-2 bg-amber-bg border-b border-amber;
  }
  .paused-banner-text { @apply text-[12px] font-medium text-amber flex-1; }

  .wg-list {
    @apply row-start-3 border-r border-border overflow-y-auto bg-bg2 flex flex-col;
  }
  .wg-pane-header {
    @apply px-3 py-2 text-[10px] font-semibold tracking-[0.06em] uppercase text-ink3 border-b border-border bg-bg2 sticky top-0 z-[1];
  }

  .wg-detail-col {
    @apply row-start-3 border-r border-border flex flex-col overflow-hidden bg-bg;
  }
  .wg-detail-body { @apply flex-1 min-h-0 overflow-y-auto px-5 py-4 flex flex-col; }

  .wg-packet-col {
    @apply row-start-3 flex flex-col overflow-hidden bg-bg2;
  }
  #control-packet-panel { @apply flex-1 min-h-0 overflow-y-auto px-4 py-[14px] flex flex-col gap-[10px]; }

  .wg-footer {
    @apply col-span-full border-t border-border bg-bg2 px-4 py-[6px] min-h-[32px] max-h-24 overflow-y-auto;
  }
  .log-tail { @apply text-[11px] font-mono text-ink2 flex flex-col-reverse; }
  .log-tail .log-line { @apply whitespace-nowrap overflow-hidden text-ellipsis; }

  /* ── Iteration list rows ── */
  .iter-row { @apply px-3 py-[10px] border-b border-border cursor-pointer transition-colors duration-100; }
  .iter-row:hover { @apply bg-bg3; }
  .iter-row-running, .iter-row-running:hover { @apply bg-blue-bg cursor-default; }
  .iter-row-top  { @apply flex items-center gap-[6px] mb-1; }
  .iter-row-num  { @apply text-[12px] font-semibold; }
  .iter-row-dur  { @apply text-[11px] text-ink3 ml-auto; }
  .iter-badge    { @apply text-[10px] font-bold py-px px-[5px] rounded-[4px]; }
  .badge-ok      { @apply bg-green-bg text-green; }
  .badge-fail    { @apply bg-red-bg text-red; }
  .badge-warn    { @apply bg-amber-bg text-amber; }
  .badge-next    { @apply bg-blue-bg text-blue; }
  .badge-running { @apply bg-blue-bg text-blue animate-pulse-badge; }
  .iter-running-label { @apply text-[10px] text-blue; }

  .iter-row-files  { @apply flex gap-[6px]; }
  .iter-row-tokens { @apply flex gap-1 mt-[2px]; }
  .tok-in, .tok-out { @apply text-[10px] text-ink3; }
  .files-new  { @apply text-[10px] text-green; }
  .files-edit { @apply text-[10px] text-blue; }

  /* ── Iteration detail ── */
  .iter-detail { @apply flex-shrink-0; }
  .iter-detail-placeholder { @apply text-ink3 text-[12px] flex items-center justify-center h-full pt-[60px]; }
  .iter-detail-header { @apply flex items-center gap-2 mb-4 pb-3 border-b border-border; }
  .iter-detail-title  { @apply text-[14px] font-semibold; }
  .iter-detail-dur    { @apply text-[11px] text-ink3; }
  .iter-detail-tokens { @apply text-[10px] text-ink3 font-mono; }
  .iter-detail-model  { @apply text-[10px] text-ink3 font-mono ml-auto; }

  .iter-section { @apply mb-[14px]; }
  .iter-section-label { @apply text-[10px] font-semibold uppercase tracking-[0.05em] text-ink3 mb-[5px]; }
  .iter-file-list { @apply pl-[14px] text-[11px] font-mono leading-[1.8]; }
  .iter-file-list.files-new  li { @apply text-green; }
  .iter-file-list.files-edit li { @apply text-blue; }
  .iter-file-list.files-del  li { @apply text-red line-through; }

  .ver-block { @apply text-[12px] font-medium py-[6px] px-[10px] rounded-card; }
  .ver-ok    { @apply bg-green-bg text-green; }
  .ver-fail  { @apply bg-red-bg text-red; }

  .slop-row  { @apply flex gap-[6px] flex-wrap; }
  .slop-pill { @apply text-[10px] py-[2px] px-[7px] rounded-[4px] bg-bg2 text-ink2; }
  .slop-pill.slop-warn { @apply bg-amber-bg text-amber font-semibold; }

  .iter-output {
    @apply text-[11px] font-mono bg-bg2 rounded-card p-[10px] whitespace-pre-wrap break-words max-h-80 overflow-y-auto leading-[1.6] border border-border;
  }
  .iter-output-live { @apply flex-1 max-h-none min-h-0 overflow-y-scroll; }

  /* ── Steer widget ── */
  .steer-widget { @apply flex-shrink-0 px-3 py-2 border-t border-border bg-bg; }
  .steer-form   { @apply flex gap-2 items-end; }
  .steer-input  {
    @apply flex-1 text-[12px] font-sans leading-normal resize-y min-h-[32px] max-h-24 py-[6px] px-[10px] border border-border2 rounded-card bg-bg2 text-ink;
  }
  .steer-input:focus { @apply outline-none border-blue bg-bg; }
  .steer-btn         { @apply flex-shrink-0; }
  .btn-disabled      { @apply opacity-40 cursor-not-allowed pointer-events-none; }

  /* ── Control packet ── */
  .cp-objective { @apply text-[13px] font-medium leading-normal; }
  .cp-section   { @apply flex flex-col gap-1; }
  .cp-label     { @apply text-[10px] font-semibold uppercase tracking-[0.05em] text-ink3; }
  .cp-list      { @apply pl-[14px] text-[12px] text-ink2; }
  .cp-list li   { @apply mb-[2px]; }
  .cp-check     { @apply text-[11px] font-mono bg-bg3 rounded-card py-1 px-2 block; }
  .cp-brief     { @apply text-[12px] text-ink2 leading-normal bg-bg3 rounded-card py-2 px-[10px]; }

  /* ── Review / intervention panel ── */
  #review-panel { @apply col-span-full row-start-2; }
  .review-panel { @apply flex flex-col bg-amber-bg border-b border-amber max-h-[55vh] overflow-y-auto; }
  .btn-restore, .btn-ignore { @apply hidden; }

  .review-exhausted {
    @apply flex flex-col gap-1 px-4 py-2 bg-red-bg text-red text-[12px];
    border-top: 1px solid rgba(220, 38, 38, 0.3);
    border-bottom: 1px solid rgba(220, 38, 38, 0.3);
  }
  .review-exhausted-msg  { @apply font-semibold; }
  .review-exhausted-hint { @apply text-ink2 font-normal; }
  .review-header  { @apply flex items-center gap-3 px-4 py-2 flex-wrap; }
  .review-title   { @apply text-[12px] font-semibold text-amber flex-1; }
  .review-summary { @apply flex items-center gap-2 flex-wrap; }
  .ver-inline     { @apply text-[10px] font-semibold py-px px-[6px] rounded-[4px]; }
  .ver-inline.ver-ok   { @apply bg-green-bg text-green; }
  .ver-inline.ver-fail { @apply bg-red-bg text-red; }

  .review-form {
    @apply flex flex-col gap-2 px-4 pt-2 pb-[10px] sticky bottom-0 bg-amber-bg;
    border-top: 1px solid rgba(186,117,23,0.2);
  }
  .review-feedback { @apply w-full text-[12px] resize-y border border-border2 rounded-card py-[7px] px-[10px] bg-bg text-ink font-sans min-h-[52px]; }
  .review-actions  { @apply flex gap-[6px] justify-end; }

  .review-judge {
    @apply flex flex-col gap-[6px] px-4 pt-[6px] pb-2;
    border-top: 1px solid rgba(186,117,23,0.2);
  }
  .review-judge-top { @apply flex items-center gap-[10px] flex-wrap; }
  .review-score     { @apply text-[11px] font-semibold py-[2px] px-2 rounded-[4px] bg-bg text-amber border border-amber; }
  .review-rec       { @apply text-[10px] font-semibold uppercase py-px px-[6px] rounded-[4px] tracking-[0.5px]; }
  .review-rec-continue     { @apply bg-green-bg text-green; }
  .review-rec-restore      { @apply bg-red-bg text-red; }
  .review-rec-needs-review { @apply bg-amber-bg text-amber; }
  .review-summary-text { @apply text-[12px] text-ink2 flex-1; }
  .review-violations   { @apply m-0 p-0 list-none flex flex-col gap-[3px] max-h-[180px] overflow-y-auto; }
  .review-violation    { @apply flex gap-2 items-baseline text-[11px] font-mono py-[3px] px-[6px] bg-bg rounded-[3px]; }
  .sev          { @apply text-[9px] font-semibold uppercase py-px px-[5px] rounded-[3px] min-w-[48px] text-center; }
  .sev-low      { @apply bg-bg3 text-ink2; }
  .sev-medium   { @apply bg-amber-bg text-amber; }
  .sev-high     { @apply bg-red-bg text-red; }
  .review-v-file   { @apply text-blue; }
  .review-v-type   { @apply text-ink2 italic; }
  .review-v-reason { @apply flex-1 text-ink; }

  .btn-warn { @apply bg-amber-bg text-amber border border-amber text-[12px] py-[6px] px-[10px] rounded-card cursor-pointer font-sans; }

  /* ── DAG shell ── */
  .shell {
    @apply grid grid-cols-[1fr_300px] grid-rows-[44px_1fr] h-screen bg-bg;
  }
  .topbar {
    @apply col-span-full flex items-center gap-3 px-4 border-b border-border bg-bg2;
  }
  .topbar-title { @apply text-[13px] font-medium; }
  .topbar-sub   { @apply text-[12px] text-ink2; }
  .graph-pane   { @apply p-6 overflow-auto bg-bg3; }
  .side-pane    { @apply border-l border-border flex flex-col bg-bg; }
  .side-header  { @apply px-[14px] py-2 border-b border-border text-[10px] font-semibold tracking-[0.06em] uppercase text-ink3; }
  .side-body    { @apply flex-1 overflow-y-auto px-[14px] py-3; }

  /* ── DAG checkpoint ── */
  .checkpoint-panel { @apply border-t border-border px-[14px] py-3 bg-amber-bg hidden flex-col gap-2; }
  .checkpoint-panel.visible { @apply flex; }
  .cp-summary  { @apply text-[12px] bg-bg border border-border2 rounded-card py-2 px-[10px] max-h-20 overflow-y-auto; }
  .cp-feedback { @apply w-full text-[12px] resize-none border border-border2 rounded-card py-2 px-[10px] bg-bg text-ink font-sans; }
  .cp-actions  { @apply flex gap-[6px]; }
  .cp-actions button { @apply flex-1; }

  /* ── DAG node detail ── */
  .detail-header { @apply flex items-center gap-2 mb-[10px]; }
  .detail-id     { @apply text-[13px] font-medium; }
  .detail-type   { @apply text-[11px] text-ink2; }
  .detail-state  { @apply text-[11px] py-[2px] px-[7px] rounded-full bg-bg2 ml-auto; }
  .detail-label  { @apply flex items-center justify-between text-[10px] font-semibold uppercase tracking-[0.05em] text-ink3 mb-[5px] mt-[10px]; }
  .detail-pre    { @apply text-[11px] font-mono bg-bg2 border border-border rounded-card py-2 px-[10px] whitespace-pre-wrap break-words max-h-[280px] overflow-y-auto; }
  .detail-file-list { @apply pl-[14px] text-[11px] font-mono leading-[1.7]; }

  .node-output { @apply mb-[10px] border border-border rounded-card overflow-hidden; }
  .node-output .detail-label { @apply px-[10px] py-[5px] bg-bg2 border-b border-border m-0; }
  .node-output .detail-pre   { @apply border-0 rounded-none m-0 max-h-[220px]; }
}")

(defn page-head
  "Common HTML <head> shared by wiggum-page and shell-page. Wires HTMX,
   Tailwind Play CDN with theme config, the @apply components layer, and
   the base style.css for selectors/keyframes Tailwind utilities can't express.

   The theme-init script runs first, synchronously, so the saved theme
   choice is on the <html> element before CSS is parsed — no FOUC."
  [title]
  [:head
   [:meta {:charset "utf-8"}]
   [:title title]
   [:link {:rel "icon" :type "image/svg+xml" :href "/favicon.svg"}]
   [:script (h/raw theme-init-js)]
   [:script {:src "https://unpkg.com/htmx.org@1.9.12"}]
   [:script {:src "https://unpkg.com/htmx.org@1.9.12/dist/ext/sse.js"}]
   [:script {:src "https://cdn.tailwindcss.com"}]
   [:script (h/raw tailwind-config-js)]
   [:style {:type "text/tailwindcss"} (h/raw tailwind-components-css)]
   [:link {:rel "stylesheet" :href "/style.css"}]])

(defn theme-toggle
  "Light/dark theme toggle. The button glyph and CSS-var swap are handled
   by data-theme on <html> + the theme-init script defined in page-head.
   ml-auto pushes it right in topbars without a flex-1 stretcher; harmless
   when one is already present (wg-topbar)."
  []
  [:button.btn.theme-toggle.ml-auto
   {:onclick    "morpheusToggleTheme()"
    :title      "Toggle light/dark theme"
    :aria-label "Toggle theme"
    :type       "button"}])

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

(defn iteration-row
  [run-id {:keys [iteration duration-ms files-written files-edited
                  exit-code verification slop-signals approx-tokens]}]
  (let [secs    (int (/ (or duration-ms 0) 1000))
        ver-ok? (or (nil? verification) (zero? (:exit verification)))
        slop?   (let [s (or slop-signals {})]
                  (or (:helpers-added? s)
                      (:only-new-files? s)
                      (> (or (:new-file-ratio s) 0) 70)))
        tok-in  (get approx-tokens :in 0)
        tok-out (get approx-tokens :out 0)]
    [:div {:id       (str "iter-row-" iteration)
           :class    "iter-row"
           :hx-get   (str "/runs/" run-id "/iterations/" iteration)
           :hx-target "#wg-detail"
           :hx-swap  "innerHTML"}
     [:div.iter-row-top
      [:span.iter-row-num (str "#" iteration)]
      [:span {:class (str "iter-badge " (if ver-ok? "badge-ok" "badge-next"))}
       (if ver-ok? "verified" "next iter")]
      (when slop? [:span.iter-badge.badge-warn "slop"])
      [:span.iter-row-dur (str secs "s")]]
     [:div.iter-row-files
      (when (seq files-written) [:span.files-new (str "+" (count files-written) " new")])
      (when (seq files-edited)  [:span.files-edit (str "~" (count files-edited) " edited")])]
     (when (pos? (+ tok-in tok-out))
       [:div.iter-row-tokens
        [:span.tok-in  (str "↑" tok-in "t")]
        [:span.tok-out (str "↓" tok-out "t")]])]))

(defn agent-label
  "Display name of the executor agent — 'Pi' for :pi, otherwise 'Claude Code'."
  [agent]
  (if (= :pi agent) "Pi" "Claude Code"))

(defn iteration-running-row [run-id iteration agent]
  [:div {:id       "iter-row-running"
         :class    "iter-row iter-row-running"
         :hx-get   (str "/runs/" run-id "/iterations/" iteration)
         :hx-target "#wg-detail"
         :hx-swap  "innerHTML"
         :hx-trigger "click"}
   [:div.iter-row-top
    [:span.iter-row-num (str "#" iteration)]
    [:span.iter-badge.badge-running "…"]
    [:span.iter-row-dur "running"]]
   [:div.iter-row-files [:span.iter-running-label (str (agent-label agent) " working…")]]])

(defn iteration-detail
  [{:keys [iteration duration-ms files-written files-edited files-deleted
           exit-code output verification slop-signals model approx-tokens cost-usd
           control-packet]}]
  (let [secs    (int (/ (or duration-ms 0) 1000))
        ver-ok? (or (nil? verification) (zero? (:exit verification)))
        tok-in  (get approx-tokens :in 0)
        tok-out (get approx-tokens :out 0)]
    [:div.iter-detail
     [:div.iter-detail-header
      [:span.iter-detail-title (str "Iteration #" iteration)]
      [:span {:class (str "exit-badge" (when (pos? (or exit-code 0)) " exit-nonzero"))}
       (str "exit " exit-code)]
      [:span.iter-detail-dur (str secs "s")]
      (when model [:span.iter-detail-model model])
      (if cost-usd
        [:span.iter-detail-tokens (format "$%.4f" (double cost-usd))]
        (when (pos? (+ tok-in tok-out))
          [:span.iter-detail-tokens (str "~" (+ tok-in tok-out) " tok")]))]

     ;; The packet that drove THIS iteration, first — so review reads "what was
     ;; asked" before "what was produced". The live column only has the latest.
     (when control-packet
       (let [{:keys [objective constraints anti-goals success-check expected-files]} control-packet]
         [:div.iter-section
          [:div.iter-section-label "Control packet"]
          [:div.cp-detail
           (when (seq objective) [:div.cp-objective objective])
           (when (seq constraints)
             [:div.cp-section [:div.cp-label "Constraints"]
              [:ul.cp-list (for [c constraints] [:li c])]])
           (when (seq anti-goals)
             [:div.cp-section [:div.cp-label "Do not"]
              [:ul.cp-list (for [g anti-goals] [:li g])]])
           (when (seq expected-files)
             [:div.cp-section [:div.cp-label "Expected files"]
              [:ul.cp-list (for [f expected-files] [:li f])]])
           (when success-check
             [:div.cp-section [:div.cp-label "Done when"]
              [:code.cp-check success-check]])]]))

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

(defn iteration-live-panel [iteration agent]
  [:div#wg-detail.wg-detail-body
   [:div.iter-detail-header
    [:span.iter-detail-title (str "Iteration #" iteration)]
    [:span.iter-badge.badge-running "running…"]]
   [:pre#live-output.iter-output.iter-output-live
    (str (agent-label agent) " is working…")]])

(defn iteration-detail-placeholder []
  [:div#wg-detail.iter-detail-placeholder
   "← Select an iteration"])

(defn judge-review-block
  "Renders the judge's score, recommendation, summary, and violations.
   Sits inside .review-panel between .review-header and .review-form."
  [review]
  (when review
    (let [{:keys [score recommendation summary violations]} review
          rec-name (name (or recommendation :continue))]
      [:div.review-judge
       [:div.review-judge-top
        [:span.review-score (str "score " (or score "?") "/10")]
        [:span {:class (str "review-rec review-rec-" rec-name)} rec-name]
        (when summary
          [:span.review-summary-text summary])]
       (when (seq violations)
         [:ul.review-violations
          (for [{:keys [file type severity reason]} violations]
            [:li.review-violation
             [:span {:class (str "sev sev-" (name (or severity :low)))}
              (name (or severity :low))]
             (when (seq file) [:span.review-v-file file])
             [:span.review-v-type (name (or type :other))]
             [:span.review-v-reason reason]])])])))

(defn review-meta
  "Header + judge block — the only piece that changes per pause event.
   Returns hiccup for the contents of #review-meta. When this is empty the
   parent #review-panel hides itself via CSS (:has selector)."
  [pause-event latest-ev]
  (let [{:keys [iteration milestone? review review-pause?
                exhausted? quota-message]} pause-event
        ver-ok? (or (nil? (:verification latest-ev))
                    (zero? (:exit (:verification latest-ev))))
        secs    (int (/ (or (:duration-ms latest-ev) 0) 1000))]
    (list
      [:div.review-header
       [:span.review-title
        (cond
          exhausted?    (str "⛔ Tokens exhausted · iteration " iteration)
          review-pause? (str "⚠ Judge review · iteration " iteration)
          milestone?    (str "⭐ Milestone · iteration " iteration)
          :else         (str "⏸ Paused · iteration " iteration))]
       (when latest-ev
         [:div.review-summary
          [:span {:class (str "exit-badge" (when (pos? (or (:exit-code latest-ev) 0)) " exit-nonzero"))}
           (str "exit " (:exit-code latest-ev))]
          [:span (str secs "s")]
          (when (seq (:files-written latest-ev))
            [:span.files-new (str "+" (count (:files-written latest-ev)) " new")])
          (when (seq (:files-edited latest-ev))
            [:span.files-edit (str "~" (count (:files-edited latest-ev)) " edited")])
          [:span {:class (str "ver-inline " (if ver-ok? "ver-ok" "ver-fail"))}
           (if ver-ok? "✓ verified" "✗ verify failed")]])]
      (when exhausted?
        [:div.review-exhausted
         [:div.review-exhausted-msg (or quota-message "Quota exhausted.")]
         [:div.review-exhausted-hint
          "Wait for tokens to reset, then click Continue to retry the same step."
          " Restore / Retry / Ignore are not meaningful here. Abort to stop the run."]])
      (when review
        (judge-review-block review)))))

(defn review-panel
  "Stable container rendered once on the page. The header/judge block
   (#review-meta) is the only piece swapped on :run-paused events — the form
   (with the textarea) is never re-rendered, so typing is never interrupted
   by SSE replays or reconnects.

   CSS hides the panel when #review-meta has no children (see style.css)."
  [run-id pause-event latest-ev]
  [:div#review-panel.review-panel
   [:div#review-meta.review-meta
    (when pause-event
      (review-meta pause-event latest-ev))]
   [:form.review-form
    {:id        "review-form"
     :hx-post   (str "/runs/" run-id "/resume")
     :hx-target "#review-meta"
     :hx-swap   "innerHTML"
     ;; Reset the form (clears the textarea) once the submission lands. The
     ;; meta is wiped server-side; this clears the still-present form fields.
     :hx-on:htmx:after-request "this.reset()"}
    [:textarea.review-feedback
     {:id          "review-feedback-text"
      :name        "feedback"
      :rows        3
      :placeholder "Steer the next iteration… e.g. \"Focus on the auth layer\" or leave blank to let the supervisor decide"}]
    [:div.review-actions
     [:button {:type "submit" :name "action" :value "abort"   :class "btn-danger"}  "Abort"]
     [:button {:type "submit" :name "action" :value "restore" :class "btn-warn btn-restore"
               :title "Discard this phase's changes and re-enter the phase"}
      "Restore ⎌"]
     [:button {:type "submit" :name "action" :value "ignore"  :class "btn btn-ignore"
               :title "Drop the judge's review and continue — use when the judge is stuck or wrongly flagging issues"}
      "Ignore judge ⤳"]
     [:button {:type "submit" :name "action" :value "retry"   :class "btn"}         "Retry ↺"]
     [:button {:type "submit" :name "action" :value "step"    :class "btn-primary"}  "Continue →"]]]])

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

(defn abort-button
  "Pass :disabled after the abort fires to grey it out."
  [run-id & [state]]
  (let [disabled? (= state :disabled)]
    [:button {:id       "abort-btn"
              :hx-post  (str "/runs/" run-id "/abort")
              :hx-target "#abort-btn"
              :hx-swap  "outerHTML"
              :class    (str "btn-danger" (when disabled? " btn-disabled"))
              :disabled disabled?}
     "Abort ✕"]))

(defn step-toggle [run-id step-once?]
  [:button {:id        "step-toggle"
            :hx-post   (str "/runs/" run-id (if step-once? "/auto" "/step"))
            :hx-target "#step-toggle"
            :hx-swap   "outerHTML"
            :class     (str "btn" (when step-once? " btn-active"))}
   (if step-once? "Step ON" "Auto")])

(defn steer-widget [run-id]
  [:div#steer-widget.steer-widget
   [:form.steer-form
    {:hx-post   (str "/runs/" run-id "/steer")
     :hx-target "#steer-widget"
     :hx-swap   "outerHTML"}
    [:textarea.steer-input
     {:name        "steer"
      :rows        1
      :placeholder "Guide next iteration… (sent to supervisor before it plans the next step)"}]
    [:button {:type "submit" :class "btn-primary steer-btn"} "Steer →"]]])

(defn wiggum-shell-page [run-id summary]
  (let [state         (:state summary :pending)
        objective     (:objective summary "…")
        iteration     (:iteration summary 0)
        packet        (:control-packet summary)
        evidence-list (:evidence-list summary [])
        step-once?    (get-in summary [:control :step-once?] false)
        latest        (last evidence-list)
        pause-event   (:last-pause-event summary)
        paused?       (= :paused state)]
    [:html
     (page-head (str "Morpheus · " run-id))
     [:body
      [:div.wg-shell
       {:hx-ext "sse" :sse-connect (str "/runs/" run-id "/stream")}
       [:div {:id "sse-sink" :sse-swap "wiggum-update" :style "display:none"}]

       [:div.wg-topbar
        [:span.wg-run (str "#" run-id)]
        [:span.wg-obj objective]
        [:span#run-status {:class (str "status-pill status-" (name state))} (name state)]
        [:span#wg-iter-count.wg-iter (str "iter " iteration)]
        (when-let [wd (:work-dir summary)]
          [:span.wg-workdir {:title wd} (str "→ " wd)])
        (step-toggle run-id step-once?)
        (abort-button run-id)
        (theme-toggle)]

       (review-panel run-id (when paused? pause-event) latest)

       [:div.wg-list
        [:div.wg-pane-header "Iterations"]
        [:div#iteration-list
         (if (= :running state)
           (iteration-running-row run-id (inc iteration) (:agent summary))
           [:div#iter-row-running])
         (for [ev (reverse evidence-list)]
           (iteration-row run-id ev))]]

       [:div.wg-detail-col
        [:div.wg-pane-header "Detail"]
        [:div#wg-detail.wg-detail-body
         (if latest (iteration-detail latest) (iteration-detail-placeholder))]
        (steer-widget run-id)]

       [:div.wg-packet-col
        [:div.wg-pane-header "Control packet"]
        (control-packet-panel packet)]

       [:div.wg-footer
        [:div#log-tail.log-tail]]]]]))

(defn shell-page [run-id summary]
  (let [nodes     (or (:nodes summary) [])
        state-map (or (:state-map summary) {})]
    [:html
     (page-head (str "Morpheus · run #" run-id))
     [:body
      [:div.shell
       [:div.topbar
        [:span.topbar-title (str "run #" run-id)]
        [:span#run-status.status-pill.status-running "running"]
        (abort-button run-id)
        (theme-toggle)]

       [:div.graph-pane
        {:hx-ext "sse" :sse-connect (str "/runs/" run-id "/stream")}
        [:div {:id "sse-sink" :sse-swap "node-update" :style "display:none"}]
        (dag-canvas nodes state-map)]

       [:div.side-pane
        [:div.side-header "Execution log"]
        [:div#side-body.side-body
         [:div#node-output.node-output]
         [:div#log-lines]]
        [:div#checkpoint-panel.checkpoint-panel]
        (steer-widget run-id)]]]]))
