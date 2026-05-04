(ns morpheus.graph.context
  "Pure context store. {output-key → value}; inputs are get-in paths.")

(defn resolve-input [ctx path]
  (if (vector? path)
    (get-in ctx path)
    (get ctx path)))

(defn resolve-inputs [inputs ctx]
  (reduce-kv
    (fn [m slot path]
      (assoc m slot (resolve-input ctx path)))
    {}
    inputs))

(defn render-prompt
  "Replaces {{slot}} placeholders with resolved values. Missing slots stay as-is."
  [prompt resolved-inputs]
  (reduce-kv
    (fn [s slot value]
      (clojure.string/replace
        s
        (re-pattern (str "\\{\\{" (name slot) "\\}\\}"))
        (str (or value ""))))
    prompt
    resolved-inputs))

(defn store-output [ctx output-key value]
  (assoc ctx output-key value))

(defn summarise-context
  "Trimmed view of context for debug — long strings truncated."
  [ctx]
  (reduce-kv
    (fn [m k v]
      (assoc m k (if (and (string? v) (> (count v) 120))
                   (str (subs v 0 120) "…")
                   v)))
    {}
    ctx))
