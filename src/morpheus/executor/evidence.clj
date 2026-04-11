(ns morpheus.executor.evidence
  "Pure functions for building and analysing iteration evidence.

   Evidence is the deterministic record of what happened in one Wiggum iteration:
   which files were created vs edited, what commands ran, whether verification
   passed, model metadata, and slop signals.

   No I/O here. File snapshots are captured in claude-code/run! and passed in."
  (:require [clojure.set    :as set]
            [clojure.string :as str]))

;; ──────────────────────────────────────────
;; File change classification
;; ──────────────────────────────────────────

(defn classify-changes
  "Given before and after snapshots ({relative-path -> last-modified-ms}),
   returns:
     :new     — files that did not exist before the run
     :edited  — files that existed and whose mtime changed
     :deleted — files that existed but are gone after the run"
  [before after]
  (let [before-paths (set (keys before))
        after-paths  (set (keys after))
        new-paths    (set/difference after-paths before-paths)
        deleted      (set/difference before-paths after-paths)
        existing     (set/intersection before-paths after-paths)
        edited       (filterv #(not= (get before %) (get after %)) existing)]
    {:new     (vec (sort new-paths))
     :edited  (vec (sort edited))
     :deleted (vec (sort deleted))}))

;; ──────────────────────────────────────────
;; Slop signal detection
;; ──────────────────────────────────────────

(def ^:private slop-name-patterns
  "Filename substrings that suggest low-value abstraction work."
  #{"util" "helper" "wrapper" "adapter" "facade" "abstract" "base" "common" "shared"})

(defn- slop-name? [path]
  (let [lower (str/lower-case path)]
    (boolean (some #(str/includes? lower %) slop-name-patterns))))

(defn slop-signals
  "Detects anti-patterns from file change data.
   Returns a map of signal-keyword -> boolean or number."
  [{:keys [new edited]}]
  (let [total     (+ (count new) (count edited))
        new-ratio (if (pos? total) (double (/ (count new) total)) 0.0)]
    {:new-file-ratio   (double (Math/round (* new-ratio 100.0)) )
     :helpers-added?   (boolean (some slop-name? new))
     :only-new-files?  (and (pos? (count new)) (zero? (count edited)))}))

;; ──────────────────────────────────────────
;; Evidence builder
;; ──────────────────────────────────────────

(defn build
  "Constructs a complete evidence map for one iteration.

   cc-result is the enriched map from claude-code/run! and must include:
     :stdout :stderr :exit
     :started-at   — epoch ms when the run started
     :duration-ms  — elapsed ms
     :before-snapshot — {path -> mtime} captured before CC ran
     :after-snapshot  — {path -> mtime} captured after CC finished
     :model    — model id string (may be nil)
     :provider — provider string, defaults to \"anthropic\"

   verification is optional: {:exit <n> :output <s>} from running the
   success-check command after the CC process exits."
  [iteration cc-result verification]
  (let [before  (:before-snapshot cc-result {})
        after   (:after-snapshot  cc-result {})
        changes (classify-changes before after)]
    {:iteration     iteration
     :started-at    (:started-at  cc-result)
     :duration-ms   (:duration-ms cc-result)
     :files-written (:new changes)
     :files-edited  (:edited changes)
     :files-deleted (:deleted changes)
     :exit-code     (:exit cc-result)
     :output        (:stdout cc-result)
     :stderr        (:stderr cc-result)
     :verification  verification
     :model         (or (:model cc-result) "unknown")
     :provider      (or (:provider cc-result) "anthropic")
     :slop-signals  (slop-signals changes)}))

;; ──────────────────────────────────────────
;; Human-readable summary (for supervisor prompt)
;; ──────────────────────────────────────────

(defn summarise
  "Returns a compact multi-line string describing the evidence.
   Used as part of the supervisor's input prompt."
  [{:keys [iteration duration-ms files-written files-edited
           exit-code verification slop-signals]}]
  (let [secs     (int (/ (or duration-ms 0) 1000))
        ver-str  (if verification
                   (str "exit=" (:exit verification)
                        (when (pos? (:exit verification 0))
                          (str " — " (some-> (:output verification)
                                             (subs 0 (min 120 (count (:output verification))))))))
                   "not run")]
    (str/join "\n"
              [(str "Iteration " iteration " (" secs "s, exit=" exit-code ")")
               (str "  Files written : " (count files-written)
                    (when (seq files-written)
                      (str " — " (str/join ", " (take 6 files-written)))))
               (str "  Files edited  : " (count files-edited)
                    (when (seq files-edited)
                      (str " — " (str/join ", " (take 6 files-edited)))))
               (str "  Verification  : " ver-str)
               (str "  Slop signals  : "
                    "new-ratio=" (:new-file-ratio slop-signals) "% "
                    "helpers=" (:helpers-added? slop-signals) " "
                    "only-new=" (:only-new-files? slop-signals))])))
