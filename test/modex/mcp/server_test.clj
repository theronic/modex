(ns modex.mcp.server-test
  (:require [clojure.test :refer :all]
            [modex.mcp.server :as server]))

(deftest test-handle-initialize
  (testing "handle-initialize returns two messages: capabilities + init notifs."
    (let [init-request {:id     1
                        :method "initialize"
                        :params {:protocolVersion "2024-11-05"
                                 :capabilities    {}
                                 :clientInfo      {:name "Test Client" :version "1.0.0"}}}
          init-responses (server/handle-request init-request)]
      (is (= [{:jsonrpc "2.0",
               :id      1,
               :result  {:protocolVersion "2024-11-05",
                         :capabilities    {:tools     {:listChanged true}
                                           :resources {:listChanged false}
                                           :prompts   {:listChanged false}},
                         :serverInfo      {:name "MCP Hello World Server", :version "1.0.0"}}}
              {:jsonrpc "2.0", :method "notifications/initialized"}]
             init-responses)))))

(deftest test-handle-tools-list
  (testing "handle-tools-list returns correct response"
    (let [req-tool-list {:id     2
                         :method "tools/list"}
          tool-list-response (server/handle-request req-tool-list)]
      (is (= {:jsonrpc "2.0",
              :id      2,
              :result  {:tools [{:name        "foo",
                                 :description "A simple tool that returns a greeting",
                                 :inputSchema {:type "object", :properties {}}}]}}
             tool-list-response)))))

(deftest test-handle-tools-call
  (testing "handle-tools-call returns correct response for foo tool"
    (let [tool-call-request {:id     3
                             :method "tools/call"
                             :params {:name "foo"}}
          tool-response     (server/handle-request tool-call-request)]
      (is (= {:jsonrpc "2.0"
              :id      3
              :result  {:isError false
                        :content [{:type "text"
                                   :text "Hello, AI!"}]}} tool-response))))

  (testing "handle-tools-call returns error for unknown tool"
    (let [tool-call-request     {:id     4
                                 :method "tools/call"
                                 :params {:name "unknown-tool"}}
          missing-tool-response (server/handle-request tool-call-request)]
      (is (= {:jsonrpc "2.0"
              :id      4
              :error   {:code    server/error-code-invalid-params
                        :message "Unknown tool: unknown-tool"}}) missing-tool-response))))