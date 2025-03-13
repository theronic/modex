(ns modex.mcp.rpc-test
  ""
  (:require [clojure.test :as t :refer [deftest testing is]]
            [modex.mcp.json-rpc :as json-rpc]
            [modex.mcp.server :as server]
            [modex.mcp.tools :as tools]
            [modex.mcp.server :as mcp-server]))

(def fixture-basic-tools
  ; todo: move to fixtures namespace
  (tools/tools
    (foo "Greets a person by name."
         [^{:type :text :doc "A person's name."} name]
         (str "Hello, " name "!"))
    (inc "A simple tool that returns a greeting"
         [^{:type :number :doc "A number to increment."} x]
         (clojure.core/inc x))))

(deftest modex-rcp-tests
  (testing "JSON RPC requests are routed to MCP server"
    (let [server (mcp-server/->server {:tools fixture-basic-tools})]
      (testing "tools/list"
        (let [request-id 1
              expected   (json-rpc/result
                           request-id
                           [{:name        :foo,
                             :description "Greets a person by name.",
                             :inputSchema {:type "object", :required [:name], :properties {:name {:type :text, :doc "A person's name."}}}}
                            {:name        :inc,
                             :description "A simple tool that returns a greeting",
                             :inputSchema {:type       "object",
                                           :required   [:x],
                                           :properties {:x {:type :number, :doc "A number to increment."}}}}])]
          (is (= expected
                 (server/handle-request server {:id request-id :method "tools/list"})))))

      (testing "tools/call foo"
        (let [request-id 2]
          (is (= {:jsonrpc "2.0"
                  :id      request-id
                  :result  {:content [{:type "text", :text "Hello, AI!"}]
                            :isError false}}
                 (server/handle-request server {:id     request-id
                                                :method "tools/call"
                                                :params {:name "foo" :parameters {:name "AI"}}})))))

      (testing "tools/call inc"
        (let [request-id 2]
          (is (= {:jsonrpc "2.0"
                  :id      request-id
                  :result  {:content [{:type "number", :text "101"}]
                            :isError false}}
                 (server/handle-request server {:id     request-id
                                                :method "tools/call"
                                                :params {:name "inc" :parameters {:x 100}}}))))))))