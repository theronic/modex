(ns modex.mcp.server-test
  (:require [clojure.test :refer :all]
            [modex.mcp.server :as server]))

(deftest test-handle-initialize
  (testing "handle-initialize returns correct response"
    (let [request  {:id     1
                    :method "initialize"
                    :params {:protocolVersion "2024-11-05"
                             :capabilities    {}
                             :clientInfo      {:name "Test Client" :version "1.0.0"}}}
          response (server/handle-request request)]
      (is (= "2.0" (:jsonrpc response)))
      (is (= 1 (:id response)))
      (is (map? (:result response)))
      (is (= "2024-11-05" (get-in response [:result :protocolVersion])))
      (is (map? (get-in response [:result :capabilities])))
      (is (map? (get-in response [:result :serverInfo]))))))

(deftest test-handle-tools-list
  (testing "handle-tools-list returns correct response"
    (let [request  {:id     2
                    :method "tools/list"}
          response (server/handle-request request)]
      (is (= "2.0" (:jsonrpc response)))
      (is (= 2 (:id response)))
      (is (vector? (get-in response [:result :tools])))
      (is (= 1 (count (get-in response [:result :tools]))))
      (is (= "foo" (get-in response [:result :tools 0 :name]))))))

(deftest test-handle-tools-call
  (testing "handle-tools-call returns correct response for foo tool"
    (let [request  {:id     3
                    :method "tools/call"
                    :params {:name "foo"}}
          response (server/handle-request request)]
      (is (= "2.0" (:jsonrpc response)))
      (is (= 3 (:id response)))
      (is (vector? (get-in response [:result :content])))
      (is (= 1 (count (get-in response [:result :content]))))
      (is (= "Hello, AI!" (get-in response [:result :content 0 :text])))))

  (testing "handle-tools-call returns error for unknown tool"
    (let [request  {:id     4
                    :method "tools/call"
                    :params {:name "unknown-tool"}}
          response (server/handle-request request)]
      (is (= "2.0" (:jsonrpc response)))
      (is (= 4 (:id response)))
      (is (map? (:error response)))
      (is (= -32602 (get-in response [:error :code])))
      (is (= "Unknown tool: unknown-tool" (get-in response [:error :message]))))))

