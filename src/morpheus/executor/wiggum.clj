(ns morpheus.executor.wiggum
  "Wiggum loop — a single-threaded, iteration-based executor.

   Instead of walking a DAG of nodes, the Wiggum loop:
     1. Runs one bounded Claude Code iteration against the active control packet.
     2. Captures deterministic evidence (file changes, verification, slop signals).
     3. Pauses (if step-once mode) or calls the supervisor.
     4. The supervisor reviews evidence and emits a tighter control packet.
     5. Repeat until: verification passes, max-iterations reached, or aborted.

   The work directory persists across iterations so changes accumulate.
   The project is copied in once at run start — not on every iteration.

   run-config keys:
     :objective      required  — product goal, stable across all iterations
     :project-dir    optional  — path copied into the work dir at start
     :success-check  optional  — shell command; exit 0 = done. Default: \"echo ok\"
     :constraints    optional  — initial constraint list for first control packet
     :anti-goals     optional  — initial anti-goals for first control packet
     :max-iterations optional  — hard cap (default 20)
     :step-once?     optional  — start in step-once mode (default false)
     :timeout-ms     optional  — per-iteration CC timeout (default 300000)
     :model-config   optional  — LLM overrides for the supervisor"
  (:require
   [clojure.core.async          :as async :refer [go chan put! <!]]
   [clojure.java.shell          :as shell]
   [clojure.string              :as str]
   [taoensso.timbre             :as log]
   [morpheus.executor.claude-code  :as cc]
   [morpheus.executor.evidence     :as evidence]
   [morpheus.executor.supervisor   :as supervisor]))

;; ──────────────────────────────────────────
;; Run record
;; ──────────────────────────────────────────

(defn create-run
  "Returns a fresh Wiggum run map. All mutable state in atoms."
  [run-id run-config]
  (let [event-ch (chan 64)]
    {:run-id         run-id
     :objective      (:objective run-config)
     :config         run-config
     :work-dir       (atom nil)          ; set once the temp dir is created
     :control-packet (atom nil)          ; current control packet
     :iterations     (atom [])           ; vec of evidence maps (one per iteration)
     :event-log      (atom [])           ; append-only log of all events
     :state          (atom :pending)     ; :pending :running :paused :done :aborted :error
     :control        (atom {:step-once? (boolean (:step-once? run-config false))})
     :event-ch       event-ch
     :event-mult     (async/mult event-ch) ; created once; SSE taps into this
     :resume-ch      (chan 1)
     :started-at     (System/currentTimeMillis)}))

;; ──────────────────────────────────────────
;; Internal helpers
;; ──────────────────────────────────────────

(defn- emit!
  [{:keys [event-ch event-log]} event]
  (let [stamped (assoc event :ts (System/currentTimeMillis))]
    (swap! event-log conj stamped)
    (put! event-ch stamped)))

(defn- set-state! [run new-state]
  (reset! (:state run) new-state)
  (emit! run {:type :state-change :state new-state}))

(defn- run-verification!
  "Runs the success-check shell command in work-dir.
   Returns {:exit n :output s}."
  [work-dir command]
  (log/info "Running verification" {:command command})
  (let [result (shell/sh "sh" "-c" command :dir work-dir)]
    {:exit   (:exit result)
     :output (str (:out result) (:err result))}))

(defn- verification-passed? [verification]
  (and (some? verification) (zero? (:exit verification))))

;; ──────────────────────────────────────────
;; Provider fallback
;; ──────────────────────────────────────────

(def ^:private rate-limit-signals
  #{"rate_limit_error" "overloaded_error" "429" "too many requests" "rate limit"})

(defn- rate-limited? [{:keys [stdout stderr exit]}]
  (and (pos? (or exit 0))
       (let [out (str/lower-case (str stdout stderr))]
         (boolean (some #(str/includes? out %) rate-limit-signals)))))

(defn- run-with-fallback!
  "Runs CC with the given opts. If the result looks like a rate-limit error and
   run-config has a :fallback-model, waits :fallback-delay-ms (default 30s)
   then retries once with the fallback model.
   Emits :provider-fallback event if the retry fires."
  [run opts run-config]
  (let [result (cc/run! opts)]
    (if-not (rate-limited? result)
      result
      (if-let [fb-model (:fallback-model run-config)]
        (let [delay-ms (:fallback-delay-ms run-config 30000)]
          (log/warn "Rate limit detected — falling back" {:model fb-model :delay-ms delay-ms})
          (emit! run {:type     :provider-fallback
                      :reason   :rate-limit
                      :fallback fb-model
                      :delay-ms delay-ms})
          (Thread/sleep delay-ms)
          (cc/run! (assoc opts :model fb-model)))
        (do
          (log/warn "Rate limit detected but no :fallback-model configured")
          result)))))

(defn- control-packet->claude-md
  "Generates a CLAUDE.md for the executor from the current control packet."
  [packet]
  (let [constraints (:constraints packet)
        anti-goals  (:anti-goals  packet)]
    (str "# Objective\n\n"
         (:objective packet)
         (when (seq constraints)
           (str "\n\n## Constraints\n\n"
                (clojure.string/join "\n" (map #(str "- " %) constraints))))
         (when-let [sc (:success-check packet)]
           (str "\n\n## Done when\n\n"
                "Running `" sc "` exits 0."))
         (when (seq anti-goals)
           (str "\n\n## Do not\n\n"
                (clojure.string/join "\n" (map #(str "- " %) anti-goals))))
         "\n")))

(defn- recent-evidence
  "Returns the last N evidence maps for the supervisor."
  [iterations-atom n]
  (vec (take-last n @iterations-atom)))

;; ──────────────────────────────────────────
;; Core iteration
;; ──────────────────────────────────────────

(defn- run-iteration!
  "Runs one Claude Code iteration and returns evidence.
   Writes the control-packet CLAUDE.md, invokes CC (with fallback on rate limit),
   then runs the success-check if configured."
  [run work-dir iteration control-packet]
  (let [config        (:config run)
        timeout-ms    (:timeout-ms config 300000)
        primary-model (get-in config [:model-config :model-id])
        success-check (:success-check control-packet)]
    ;; write scoped CLAUDE.md for this iteration
    (cc/write-claude-md! work-dir (control-packet->claude-md control-packet))
    (emit! run {:type           :iteration-started
                :iteration      iteration
                :work-dir       work-dir
                :control-packet control-packet})
    ;; run Claude Code — no :project-dir (project copied at run start)
    (let [cc-result    (run-with-fallback!
                         run
                         {:work-dir   work-dir
                          :prompt     (:objective control-packet)
                          :timeout-ms timeout-ms
                          :model      primary-model}
                         config)
          verification (when success-check
                         (run-verification! work-dir success-check))
          ev           (evidence/build iteration cc-result verification)]
      (emit! run {:type      :iteration-complete
                  :iteration iteration
                  :evidence  ev})
      ev)))

;; ──────────────────────────────────────────
;; Main loop
;; ──────────────────────────────────────────

(defn execute!
  "Starts the Wiggum loop in a go-block. Returns the run map immediately.
   Sends events to (:event-ch run) as iterations proceed.
   Pauses after each iteration when step-once mode is enabled.

   run-config: see namespace docstring for keys."
  [run-id run-config]
  (let [run (create-run run-id run-config)]
    (go
      (set-state! run :running)
      (emit! run {:type :run-started :run-id run-id :objective (:objective run-config)})

      ;; create a persistent work dir for the whole run
      (let [work-dir (cc/make-work-dir! run-id "wiggum")]
        (reset! (:work-dir run) work-dir)

        ;; copy project into work dir once
        (when-let [pd (:project-dir run-config)]
          (log/info "Copying project into work dir" {:src pd :dst work-dir})
          (shell/sh "sh" "-c" (str "cp -r " pd "/* " work-dir "/") :dir work-dir))

        (loop [iteration      1
               control-packet (supervisor/bootstrap run-config)]
          (reset! (:control-packet run) control-packet)

          (let [max-iter (or (:max-iterations run-config) 20)]
            (cond
              ;; hard cap reached
              (> iteration max-iter)
              (do
                (set-state! run :done)
                (emit! run {:type :run-complete :reason :max-iterations :iterations (dec iteration)}))

              ;; run aborted externally
              (= :aborted @(:state run))
              (emit! run {:type :run-aborted :run-id run-id})

              :else
              (let [ev (run-iteration! run work-dir iteration control-packet)]
                (swap! (:iterations run) conj ev)

                (if (verification-passed? (:verification ev))
                  ;; verification passed — we're done
                  (do
                    (set-state! run :done)
                    (emit! run {:type :run-complete :reason :verified :iteration iteration}))

                  ;; not done yet — pause or auto-continue
                  (let [checkpoint-every (:checkpoint-every run-config)
                        milestone-hit?   (and checkpoint-every
                                              (zero? (mod iteration checkpoint-every)))]
                    (if (or (:step-once? @(:control run)) milestone-hit?)

                      ;; ── pause for human review ──
                      (do
                        (set-state! run :paused)
                        (emit! run {:type       :run-paused
                                    :iteration  iteration
                                    :milestone? milestone-hit?})
                        (let [action (<! (:resume-ch run))]
                          (set-state! run :running)
                          (case (:action action)
                            :abort
                            (do (set-state! run :aborted)
                                (emit! run {:type :run-aborted}))

                            :retry
                            (recur iteration control-packet)

                            :retry-with-overrides
                            (recur iteration (merge control-packet (:overrides action)))

                            ;; :step — advance; supervisor incorporates human feedback
                            (let [feedback    (when (seq (:feedback action)) (:feedback action))
                                  next-packet (supervisor/review
                                                (:objective run-config)
                                                control-packet
                                                (recent-evidence (:iterations run) 3)
                                                (:model-config run-config {})
                                                feedback)]
                              (recur (inc iteration) next-packet)))))

                      ;; ── auto-continue ──
                      (let [next-packet (supervisor/review
                                          (:objective run-config)
                                          control-packet
                                          (recent-evidence (:iterations run) 3)
                                          (:model-config run-config {}))]
                        (recur (inc iteration) next-packet)))))))))
        ))
    run))

;; ──────────────────────────────────────────
;; External control
;; ──────────────────────────────────────────

(defn step!
  "Enable step-once mode. The loop pauses after the current iteration finishes."
  [run]
  (swap! (:control run) assoc :step-once? true)
  (emit! run {:type :control-changed :step-once? true}))

(defn auto!
  "Disable step-once mode. The loop resumes auto-advancing."
  [run]
  (swap! (:control run) assoc :step-once? false)
  (emit! run {:type :control-changed :step-once? false}))

(defn resume!
  "Called by the HTTP handler when a human acts on a paused run.

   action-map keys:
     :action   — :step | :retry | :retry-with-overrides | :abort
     :overrides — map merged into control packet (for :retry-with-overrides)"
  [run action-map]
  (put! (:resume-ch run) action-map))

(defn abort!
  "Signals the loop to stop after the current iteration."
  [run]
  (reset! (:state run) :aborted))

;; ──────────────────────────────────────────
;; Introspection helpers
;; ──────────────────────────────────────────

(defn current-iteration
  "Returns the index of the most recently completed iteration."
  [run]
  (count @(:iterations run)))

(defn last-evidence
  "Returns the evidence map for the most recently completed iteration."
  [run]
  (last @(:iterations run)))

(defn run-summary
  "Returns a plain map suitable for UI rendering — no atoms."
  [run]
  {:run-id         (:run-id run)
   :objective      (:objective run)
   :state          @(:state run)
   :iteration      (current-iteration run)
   :control-packet @(:control-packet run)
   :work-dir       @(:work-dir run)
   :started-at     (:started-at run)})
