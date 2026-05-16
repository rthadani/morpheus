(ns morpheus.slug-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [morpheus.slug :as slug]))

(deftest slugify-handles-common-cases
  (testing "lower-cases, collapses non-alphanumeric runs into single underscore"
    (is (= "options_trader" (slug/slugify "Options-Trader")))
    (is (= "kanban_react"   (slug/slugify "kanban-react")))
    (is (= "my_cool_app"    (slug/slugify "My Cool App")))
    (is (= "foo_bar_baz"    (slug/slugify "foo bar  baz")))
    (is (= "a_b"            (slug/slugify "a---b"))
        "consecutive non-alphanumerics collapse to a single underscore"))

  (testing "trims leading and trailing underscores"
    (is (= "foo"            (slug/slugify "-foo-")))
    (is (= "foo"            (slug/slugify "---foo---")))
    (is (= "foo"            (slug/slugify "!!!foo!!!"))))

  (testing "alphanumerics survive unchanged"
    (is (= "abc123"         (slug/slugify "abc123")))
    (is (= "v2_release"     (slug/slugify "v2-release"))))

  (testing "empty / all-non-alphanumeric / nil inputs return nil"
    (is (nil? (slug/slugify "")))
    (is (nil? (slug/slugify "!!!")))
    (is (nil? (slug/slugify "---")))
    (is (nil? (slug/slugify nil)))))

(deftest project-slug-derives-from-canonical-basename
  (testing "uses basename of project-dir, lowercased and snake-cased"
    (let [tmp (System/getProperty "java.io.tmpdir")
          dir (str tmp "/morpheus-slug-test-" (System/currentTimeMillis))
          _   (.mkdirs (java.io.File. dir))]
      (try
        (is (re-find #"^morpheus_slug_test_\d+$" (slug/project-slug dir))
            "basename lowercased and hyphens become underscores")
        (finally
          (.delete (java.io.File. dir))))))

  (testing "nil project-dir returns nil"
    (is (nil? (slug/project-slug nil)))))

(deftest graph-slug-strips-edn-extension
  (testing "strips .edn extension and slugifies the rest"
    (is (= "wifi_qoe_service" (slug/graph-slug "graphs/wifi-qoe-service.edn")))
    (is (= "foo"              (slug/graph-slug "foo.edn")))
    (is (= "options_trader"   (slug/graph-slug "/abs/path/Options-Trader.edn")))
    (is (= "spec_generator"   (slug/graph-slug "graphs/spec-generator.edn"))))

  (testing "non-.edn paths still slugify (extension preserved by slugify)"
    (is (= "graph_yaml"       (slug/graph-slug "graph.yaml"))
        "if not .edn, the trailing extension is treated as another segment"))

  (testing "nil returns nil"
    (is (nil? (slug/graph-slug nil)))))
