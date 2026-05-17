(ns morpheus.slug
  "URL-safe, lower-case, kebab-case slugs derived from project-dir paths or
   graph-file paths. Used so that run-ids (in URLs and store keys) agree
   with on-disk paths (snapshot + UI-state under ~/.morpheus/runs/{slug}/)."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]))

(defn slugify
  [s]
  (when (seq s)
    (let [out (-> s
                  str/lower-case
                  (str/replace #"[^a-z0-9]+" "-")
                  (str/replace #"^-+|-+$" ""))]
      (when (seq out) out))))

(defn project-slug
  [project-dir]
  (some-> project-dir io/file .getCanonicalFile .getName slugify))

(defn graph-slug
  [graph-path]
  (some-> graph-path io/file .getName (str/replace #"\.edn$" "") slugify))
