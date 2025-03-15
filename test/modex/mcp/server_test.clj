(ns modex.mcp.server-test
  (:require [clojure.test :refer :all]
            [clojure.core :as cc]
            [modex.mcp.protocols :as mcp]
            [modex.mcp.schema :as schema]
            [modex.mcp.server :as server]
            [modex.mcp.tools :as tools]
            [taoensso.timbre :as log]))

(def fixture-basic-tools
  (tools/tools
    (foo "Greets a person by name."
         [^{:type :text :doc "A person's name."} name]
         [(str "Hello, " name "!")])
    (inc "A simple tool that returns a greeting"
         [^{:type :number :doc "A number to increment."} x]
         [(cc/inc x)])
    (broken-tool "A tool that throws for error tests."
         [^{:type :text :doc "Anything"} x]
         (throw (ex-info "Tool throws intentionally" [{:error "Throws intentionally"}])))))

(deftest mcp-server-protocol-tests
  (let [server (server/->server {:name "Test Server"
                                 :version "1.0"
                                 :tools fixture-basic-tools})]

    ; order may be non-deterministic. will need to cache tools in AServer.

    (testing "server has name & version"
      (= "Test Server" (mcp/server-name server))
      (= "1.0" (mcp/version server)))

    (testing "we can list tools"
      (is (= [{:name        :foo,
               :description "Greets a person by name.",
               :inputSchema {:type       "object"
                             :required   [:name]
                             :properties {:name {:type :text, :doc "A person's name."}}}}
              {:name        :inc,
               :description "A simple tool that returns a greeting",
               :inputSchema {:type       "object"
                             :required   [:x]
                             :properties {:x {:type :number, :doc "A number to increment."}}}}
              {:name        :broken-tool,
               :description "A tool that throws for error tests.",
               :inputSchema {:type       "object"
                             :required   [:x]
                             :properties {:x {:type :text, :doc "Anything"}}}}]
             (mcp/list-tools server))))

    (testing "we can invoke tools via server"
      (is (= [["Hello, Petrus!"] nil] (mcp/call-tool server :foo {:name "Petrus"}))))

    (testing "handle-tools-call throws protocol-level error for unknown tools"
      ; this test is at wrong level
      (let [missing-tool-request {:id     4
                                  :method "tools/call"
                                  :params {:name "unknown-tool"}}
            mcp-server           (server/->server {:tools fixture-basic-tools})]
        (log/warn (server/handle-request mcp-server missing-tool-request))
        ; todo missing tool should return error code invalid params
        ; ; ugh this is broken. needs to be protocol level error.
        (is (= {:jsonrpc schema/json-rpc-version
                :id 4
                :error {:content [{:type "text", :text (pr-str
                                                         {:error :missing-tool
                                                          :tool-name "unknown-tool"
                                                          :available-tools '(:foo :inc :broken-tool)})}]
                        :isError true}}
               (server/handle-request mcp-server missing-tool-request)))))

    (testing "broken tools return error in result, not via protocol-level errors"
      ; this test is at wrong level
      (let [bad-tool-request {:id     4
                              :method "tools/call"
                              :params {:name "broken-tool", :arguments {:x ""}}}
            mcp-server       (server/->server {:tools fixture-basic-tools})]
        ; todo missing tool should return error code invalid params
        (is (= {:jsonrpc schema/json-rpc-version
                :id      4
                :result   {:content [{:type "text", :text "{:error \"Throws intentionally\"}"}]
                           :isError true}}
               (server/handle-request mcp-server bad-tool-request)))))

    (testing "missing arguments should return error in result, not via protocol-level errors"
      ; this test is at wrong level
      (let [missing-args-req {:id     4
                              :method "tools/call"
                              :params {:name "broken-tool"}}
            mcp-server       (server/->server {:tools fixture-basic-tools})]
        ;(log/warn (server/handle-request mcp-server missing-args-req))
        ; todo missing tool should return error code invalid params
        (is (= {:jsonrpc schema/json-rpc-version
                :id      4
                :result   {:isError true
                           :content [{:type "text", :text "{:missing-tool-parameters (:x)}"}]}}
               (server/handle-request mcp-server missing-args-req)))))))

(deftest test-handle-initialize
  (testing "handle-initialize returns two messages: capabilities + init notifs."
    (let [init-request   {:id     1
                          :method "initialize"
                          :params {:protocolVersion schema/latest-protocol-version
                                   :capabilities    {}
                                   :clientInfo      {:name "Test Client" :version "1.0.0"}}}
          test-tools     (mcp/create-toolset ; why are we testing this?
                           {:inc {:name :inc
                                  :fn   (fn [x] (cc/inc x))
                                  :doc  "Increments a number"
                                  :args [{:name "x" :doc "x" :type :number}]}
                            :foo {:name :foo
                                  :fn   (fn [] (str "Hello, World!"))
                                  :doc  "Returns a greeting"
                                  :args []}})
          mcp-server     (server/->server {:tools test-tools})
          init-responses (server/handle-request mcp-server init-request)]
      (is (= 2 (count init-responses)))
      (is (= schema/latest-protocol-version (get-in (first init-responses) [:result :protocolVersion])))
      (is (= "notifications/initialized" (:method (second init-responses)))))))

;(deftest test-handle-tools-list
;  (testing "handle-tools-list returns correct response"
;    (let [req-tool-list      {:id     2
;                              :method "tools/list"}
;          test-tools         (mcp/create-toolset
;                               {:inc {:name :inc
;                                      :fn   (fn [x] (cc/inc x))
;                                      :doc  "Increments a number"
;                                      :args [{:name "x" :doc "x" :type :number}]}
;                                :foo {:name :foo
;                                      :fn   (fn [] (str "Hello, World!"))
;                                      :doc  "Returns a greeting"
;                                      :args []}})
;          mcp-server         (server/->server {:tools test-tools})
;          tool-list-response (server/handle-request mcp-server req-tool-list)]
;      (is (= {:jsonrpc "2.0"
;              :id 2
;              :result {:tools [{:name "inc", :description "Increments a number", :inputSchema {:type "object"}, :properties {:x {:type "number"}}, :required ["x"]}
;                               {:name "foo", :description "Returns a greeting", :inputSchema {:type "object"}, :properties {}, :required ()}]}}
;             tool-list-response)))))

(comment
  (ex-data
    (ex-info (str "Unknown tool: ")
             ; todo: we have error codes for this (method not found)
             {:tool-name "hi"
              :available #{:a :b}})))