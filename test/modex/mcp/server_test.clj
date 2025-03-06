(ns modex.mcp.server-test
  (:require [clojure.test :refer :all]
            [modex.mcp.protocols :as p]
            [modex.mcp.schema :as schema]
            [modex.mcp.server :as server]
            [modex.mcp.tools :as tools]))

(deftest test-handle-initialize
  (testing "handle-initialize returns two messages: capabilities + init notifs."
    (let [init-request   {:id     1
                          :method "initialize"
                          :params {:protocolVersion schema/latest-protocol-version
                                   :capabilities    {}
                                   :clientInfo      {:name "Test Client" :version "1.0.0"}}}
          mcp-server     (p/->TestServer [tools/inc-tool tools/foo-tool])
          init-responses (server/handle-request mcp-server init-request)]
      (is (= [{:jsonrpc "2.0",
               :id      1,
               :result  {:protocolVersion schema/latest-protocol-version
                         :capabilities    {:tools     {:listChanged true}
                                           :resources {:listChanged false}
                                           :prompts   {:listChanged false}}
                         :serverInfo      {:name "MCP Hello World Server", :version "1.0.0"}}}
              {:jsonrpc "2.0", :method "notifications/initialized"}]
             init-responses)))))

(deftest test-handle-tools-list
  (testing "handle-tools-list returns correct response"
    (let [req-tool-list      {:id     2
                              :method "tools/list"}
          mcp-server         (p/->TestServer [tools/inc-tool tools/foo-tool])
          tool-list-response (server/handle-request mcp-server req-tool-list)]
      (is (= {:jsonrpc "2.0",
              :id      2,
              :result  {:tools [server/foo-tool
                                server/inc-tool]}}
             tool-list-response)))))

(deftest test-handle-tools-call
  (testing "handle-tools-call returns correct response for foo tool"
    (let [tool-call-request {:id     3
                             :method "tools/call"
                             :params {:name "foo"}}
          mcp-server         (p/->TestServer [tools/inc-tool tools/foo-tool])
          tool-response     (server/handle-request mcp-server tool-call-request)]
      (is (= {:jsonrpc "2.0"
              :id      3
              :result  {:isError false
                        :content [{:type "text"
                                   :text "Hello, AI!"}]}} tool-response))))

  (testing "handle-tools-call returns error for unknown tool"
    (let [tool-call-request     {:id     4
                                 :method "tools/call"
                                 :params {:name "unknown-tool"}}
          mcp-server         (p/->TestServer [tools/inc-tool tools/foo-tool])
          missing-tool-response (server/handle-request mcp-server tool-call-request)]
      (is (= {:jsonrpc "2.0"
              :id      4
              :error   {:code    server/error-code-invalid-params
                        :message "Unknown tool: unknown-tool"}}) missing-tool-response)))

  (testing "call inc tool increments number"
    (let [tool-call-request {:id     5
                             :method "tools/call"
                             :params {:name "inc" :arguments {:x 123}}}
          mcp-server         (p/->TestServer [tools/inc-tool tools/foo-tool])
          tool-response     (server/handle-request mcp-server tool-call-request)]
      (is (= {:jsonrpc "2.0"
              :id      5
              :result  {:isError false
                        :content [{:type "text"
                                   :text "124"}]}} tool-response)))))