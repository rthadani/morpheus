(ns morpheus.graph.topo-test
  (:require
   [clojure.test          :refer [deftest is testing]]
   [morpheus.graph.topo   :as topo]
   [morpheus.graph.context :as ctx]))

(def sample-nodes
  [{:id :a :depends-on []}
   {:id :b :depends-on [:a]}
   {:id :c :depends-on [:a]}
   {:id :d :depends-on [:b :c]}])

(deftest topo-sort-test
  (testing "returns all nodes"
    (is (= 4 (count (topo/topo-sort sample-nodes)))))

  (testing "a comes before b and c"
    (let [sorted (topo/topo-sort sample-nodes)
          ids    (mapv :id sorted)
          idx    (fn [id] (.indexOf ids id))]
      (is (< (idx :a) (idx :b)))
      (is (< (idx :a) (idx :c)))
      (is (< (idx :b) (idx :d)))
      (is (< (idx :c) (idx :d)))))

  (testing "detects cycles"
    (is (thrown? clojure.lang.ExceptionInfo
                 (topo/topo-sort
                   [{:id :x :depends-on [:y]}
                    {:id :y :depends-on [:x]}])))))

(deftest runnable-nodes-test
  (testing "only nodes with all deps done are runnable"
    (let [state {:a :done :b :done :c :pending :d :pending}
          runnable (topo/runnable-nodes sample-nodes state)]
      (is (= #{:c} (set (map :id runnable)))))))

(deftest context-test
  (testing "render-prompt replaces slots"
    (is (= "Hello world"
           (ctx/render-prompt "Hello {{name}}" {:name "world"}))))

  (testing "missing slot left as-is"
    (is (= "Hello {{missing}}"
           (ctx/render-prompt "Hello {{missing}}" {})))))
