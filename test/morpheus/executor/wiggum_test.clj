(ns morpheus.executor.wiggum-test
  "Tests for the Wiggum executor's exhaustion-pause plumbing — the macro
   that turns a quota-exhausted LLM exception into a pause/resume cycle
   instead of crashing the run."
  (:require
   [clojure.core.async :as async :refer [chan put! go <!!]]
   [clojure.test       :refer [deftest is testing]]
   [morpheus.executor.wiggum :as w]))

(defn- fresh-run
  "Minimal run map shaped enough for retry-on-exhaustion to operate on:
   event-ch + event-log for emit!/emit-pause!, resume-ch for the macro to
   block on, state atom for state-change events."
  []
  {:event-ch  (chan 16)
   :event-log (atom [])
   :resume-ch (chan 1)
   :state     (atom :running)})

(defn- wait-for!
  "Block until pred is truthy or timeout-ms elapses. Polls every 5ms."
  ([pred] (wait-for! pred 1000))
  ([pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (pred)                                  true
         (> (System/currentTimeMillis) deadline) false
         :else (do (Thread/sleep 5) (recur)))))))

(defn- event-types [run]
  (mapv :type @(:event-log run)))

(deftest passthrough-when-no-exception
  (testing "macro returns body value when nothing throws"
    (let [run    (fresh-run)
          result (<!! (go (w/retry-on-exhaustion run 1 :ok)))]
      (is (= :ok result))
      (is (= [] (event-types run)) "no pause event when body succeeds"))))

(deftest non-exhausted-exception-propagates
  (testing "ex-info without :cause :exhausted bubbles up untouched"
    (let [run    (fresh-run)
          result (<!! (go (try
                            (w/retry-on-exhaustion run 1
                              (throw (ex-info "boom" {:cause :other})))
                            (catch clojure.lang.ExceptionInfo e
                              [::caught (ex-message e) (:cause (ex-data e))]))))]
      (is (= [::caught "boom" :other] result)
          "non-:exhausted error propagates through the macro")
      (is (= [] (event-types run))
          "no pause event when the exception isn't a quota error"))))

(deftest pauses-and-retries-on-resume
  (testing "macro pauses on :exhausted, awaits resume, retries body, returns its value"
    (let [run      (fresh-run)
          attempts (atom 0)
          done     (go (w/retry-on-exhaustion run 1
                         (swap! attempts inc)
                         (if (= 1 @attempts)
                           (throw (ex-info "quota"
                                           {:cause   :exhausted
                                            :message "limit reached"}))
                           :finished)))]
      (is (wait-for! #(= :paused @(:state run)))
          "state becomes :paused while the macro waits on resume-ch")
      (let [last-paused (->> @(:event-log run)
                             (filter #(= :run-paused (:type %)))
                             last)]
        (is (= true (:exhausted? last-paused)))
        (is (= "limit reached" (:quota-message last-paused))))

      ;; Any non-:abort action triggers a retry.
      (put! (:resume-ch run) {:action :step})
      (is (= :finished (<!! done)))
      (is (= 2 @attempts) "body runs again after the pause is released")
      (is (= :running @(:state run))
          "state flips back to :running before the retry runs"))))

(deftest claude-md-code-mode-includes-code-shape-block
  (testing ":code mode (default) ships the code-shape block and the no-comments rule"
    (let [render @#'w/control-packet->claude-md
          md     (render {:objective "Build X" :plan ["step 1"]} nil nil :code)]
      (is (re-find #"Code shape — keep it minimal" md))
      (is (re-find #"NO comments\. NO docstrings" md))
      (is (re-find #"protocols, defrecords, multimethods" md)
          "names the typical over-engineering anti-patterns")
      (is (re-find #"Avoid \*\*orphan\*\* global state" md)
          "names the orphan-state anti-pattern (lifecycle-based test, not blanket atom ban)")
      (is (re-find #"who owns this state's lifecycle" md)
          "frames the rule as a lifecycle question")
      (is (re-find #"component/system map" md)
          "names legitimate uses (component-held state) so the model doesn't refuse them")
      (is (re-find #"thin: parse \+ validate input" md)
          "fullstack routes must stay thin")
      (is (re-find #"Function size — Goldilocks" md)
          "function size guidance present")
      (is (re-find #"Do NOT extract one-line wrappers" md)
          "covers the too-small failure mode (trivial helpers)")
      (is (re-find #"exceeds one screen" md)
          "covers the too-large failure mode")
      (is (not (re-find #"research deliverable" md))
          "no prose-mode block leaks in"))))

(deftest claude-md-research-mode-includes-prose-block
  (testing ":research mode swaps in the prose block and drops code-only guidance"
    (let [render @#'w/control-packet->claude-md
          md     (render {:objective "Write Y" :plan ["step 1"]} nil nil :research)]
      (is (re-find #"research deliverable" md))
      (is (re-find #"Cite every load-bearing claim" md))
      (is (re-find #"counter-evidence" md))
      (is (not (re-find #"Code shape — keep it minimal" md))
          "code-shape block does not appear on prose runs")
      (is (not (re-find #"NO comments\. NO docstrings" md))
          "no-comments rule does not appear on prose runs")
      (is (not (re-find #"Avoid \*\*orphan\*\* global state" md))
          "code-only global-state rule does not leak into prose runs")
      (is (not (re-find #"Function size — Goldilocks" md))
          "code-only function-size rule does not leak into prose runs"))))

(deftest claude-md-defaults-to-code-mode-when-mode-missing
  (testing "nil or missing mode arg defaults to :code"
    (let [render @#'w/control-packet->claude-md
          md3    (render {:objective "X"} nil nil)
          md4nil (render {:objective "X"} nil nil nil)]
      (is (re-find #"Code shape — keep it minimal" md3))
      (is (re-find #"Code shape — keep it minimal" md4nil)))))

(deftest aborts-cleanly-on-abort-resume
  (testing "user :abort during pause sets :aborted, emits :run-aborted, throws :user-abort"
    (let [run    (fresh-run)
          done   (go (try
                       (w/retry-on-exhaustion run 1
                         (throw (ex-info "quota"
                                         {:cause   :exhausted
                                          :message "limit reached"})))
                       (catch clojure.lang.ExceptionInfo e
                         [::caught (:cause (ex-data e))])))]
      (is (wait-for! #(= :paused @(:state run))))
      (put! (:resume-ch run) {:action :abort})
      (is (= [::caught :user-abort] (<!! done))
          (str "macro throws ex-info {:cause :user-abort} so execute!'s "
               "top-level catch can distinguish a clean abort from a real crash"))
      (is (= :aborted @(:state run)))
      (is (some #{:run-aborted} (event-types run))
          "macro emits :run-aborted so observers see the transition"))))
