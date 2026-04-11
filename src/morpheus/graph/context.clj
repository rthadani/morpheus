(ns morpheus.graph.context
  "Context store — a plain map of output-key → value.
   Inputs are resolved as get-in paths into this map.
   All functions are pure.")

(defn resolve-input
  "Resolves a single input path vector against the context map.
   Path [:planning.sections/arch-design] becomes
   (get-in ctx [:planning.sections/arch-design]).
   Nested paths like [:planning :sections :arch-design] also work."
  [ctx path]
  (if (vector? path)
    (get-in ctx path)
    (get ctx path)))

(defn resolve-inputs
  "Takes an inputs map of {slot-kw → path} and returns
   {slot-kw → resolved-value} from the context."
  [inputs ctx]
  (reduce-kv
    (fn [m slot path]
      (assoc m slot (resolve-input ctx path)))
    {}
    inputs))

(defn render-prompt
  "Replaces {{slot}} placeholders in a prompt string with
   resolved input values. Missing slots are left as-is."
  [prompt resolved-inputs]
  (reduce-kv
    (fn [s slot value]
      (clojure.string/replace
        s
        (re-pattern (str "\\{\\{" (name slot) "\\}\\}"))
        (str (or value ""))))
    prompt
    resolved-inputs))

(defn store-output
  "Write a node's output into the context under its output-key."
  [ctx output-key value]
  (assoc ctx output-key value))

(defn summarise-context
  "Returns a trimmed view of context for debugging — truncates long strings."
  [ctx]
  (reduce-kv
    (fn [m k v]
      (assoc m k (if (and (string? v) (> (count v) 120))
                   (str (subs v 0 120) "…")
                   v)))
    {}
    ctx))
