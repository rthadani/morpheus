(ns morpheus.executor.evidence
  "Pure functions for building and analysing iteration evidence — the
   deterministic record of what happened in one Wiggum iteration. File
   snapshots are captured in claude-code-agent/run! and passed in."
  (:require [clojure.set    :as set]
            [clojure.string :as str]))

(defn classify-changes
  "Given before/after {path -> mtime} snapshots, returns {:new :edited :deleted}."
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

(def ^:private slop-name-patterns
  #{"util" "helper" "wrapper" "adapter" "facade" "abstract" "base" "common" "shared"})

(defn- slop-name? [path]
  (let [lower (str/lower-case path)]
    (boolean (some #(str/includes? lower %) slop-name-patterns))))

(defn slop-signals [{:keys [new edited]}]
  (let [total     (+ (count new) (count edited))
        new-ratio (if (pos? total) (double (/ (count new) total)) 0.0)]
    {:new-file-ratio   (double (Math/round (* new-ratio 100.0)))
     :helpers-added?   (boolean (some slop-name? new))
     :only-new-files?  (and (pos? (count new)) (zero? (count edited)))}))

(defn build
  "Constructs the evidence map for one iteration. cc-result is the enriched
   map from claude-code-agent/run! (snapshots, exit, model, etc.)."
  [iteration cc-result verification & [top-level dir-tree expected-check]]
  (let [before  (:before-snapshot cc-result {})
        after   (:after-snapshot  cc-result {})
        changes (classify-changes before after)
        in-chars  (or (:prompt-chars cc-result) 0)
        out-chars (count (or (:stdout cc-result) ""))]
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
     :slop-signals  (slop-signals changes)
     :approx-tokens {:in  (int (/ in-chars 4))
                     :out (int (/ out-chars 4))}
     :cost-usd       (:cost-usd cc-result)
     :top-level      top-level
     :dir-tree       dir-tree
     :expected-check expected-check}))

(defn- format-review [{:keys [recommendation score summary violations]}]
  (let [grouped  (group-by :severity violations)
        bullet   (fn [v] (str "      - [" (name (:type v :other)) "]"
                              (when-let [f (not-empty (:file v))] (str " " f))
                              " — " (:reason v)))
        sev-block (fn [label sev]
                    (when-let [vs (seq (get grouped sev))]
                      (str "    " label " (" (count vs) "):\n"
                           (str/join "\n" (map bullet vs)))))]
    (str "  Judge review  : recommendation=" (name (or recommendation :continue))
         (when score (str " score=" score)) "\n"
         (when (seq summary)    (str "    summary: " summary "\n"))
         (when-let [b (sev-block "HIGH"   :high)]   (str b "\n"))
         (when-let [b (sev-block "medium" :medium)] (str b "\n"))
         (when-let [b (sev-block "low"    :low)]    (str b "\n"))
         "    NOTE: address violations in the next control packet — "
         "tighten constraints, rescope expected_files, or add anti-goals "
         "so the executor doesn't repeat them.")))

(defn summarise
  "Compact multi-line evidence string for the supervisor's prompt."
  [{:keys [iteration duration-ms files-written files-edited
           exit-code verification slop-signals top-level dir-tree
           expected-check review]}]
  (let [secs     (int (/ (or duration-ms 0) 1000))
        ver-str  (if verification
                   (str "exit=" (:exit verification)
                        (when (pos? (:exit verification 0))
                          (str " — " (some-> (:output verification)
                                             (subs 0 (min 600 (count (:output verification))))))))
                   "not run")]
    (str/join "\n"
              [(str "Iteration " iteration " (" secs "s, exit=" exit-code ")")
               (when (seq top-level)
                 (str "  Top-level     : " top-level))
               (str "  Files written : " (count files-written))
               (str "  Files edited  : " (count files-edited))
               (str "  Verification  : " ver-str)
               (str "  Slop signals  : "
                    "new-ratio=" (:new-file-ratio slop-signals) "% "
                    "helpers=" (:helpers-added? slop-signals) " "
                    "only-new=" (:only-new-files? slop-signals))
               (when expected-check
                 (str "  Declared targets (supervisor's own expected_files from prior packet):"
                      (when (seq (:present expected-check))
                        (str "\n    ✓ " (str/join "\n    ✓ " (:present expected-check))))
                      (when (seq (:missing expected-check))
                        (str "\n    ✗ " (str/join "\n    ✗ " (:missing expected-check))))))
               (when review (format-review review))
               (when (seq dir-tree)
                 (str "  Directory tree :\n"
                      (str/join "\n" (map #(str "    " %)
                                         (str/split-lines dir-tree)))))])))
