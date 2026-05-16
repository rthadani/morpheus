(ns build
  "tools.build script for morpheus.

   Usage:
     clj -T:build uber              ; build target/morpheus.jar
     clj -T:build clean             ; remove target/

   Run the resulting jar with:
     java -jar target/morpheus.jar <graph.edn> [--project-dir <path>] [--step]"
  (:require
   [clojure.tools.build.api :as b]))

(def lib       'morpheus/morpheus)
(def version   "0.1.0")
(def class-dir "target/classes")
(def basis     (delay (b/create-basis {:project "deps.edn"})))
(def uber-file "target/morpheus.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis     @basis
                  :src-dirs  ["src"]
                  :class-dir class-dir
                  :ns-compile '[morpheus.main]})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'morpheus.main})
  (println "Built" uber-file))
