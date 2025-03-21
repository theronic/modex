(ns modex.mcp.server-test
  (:require [clojure.test :refer :all]
            [clojure.core :as cc]
            [jsonista.core :as json]
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

(defn make-request [id method params]
  (let [request (cond-> {:jsonrpc "2.0"
                         :id      id
                         :method  method}
                  params (assoc :params params))]
    (str (json/write-value-as-string request json/keyword-keys-object-mapper) "\n")))

;(def fixture-initialize
;  (make-request 1 "initialize"))

(deftest mcp-server-delayed-init-tests
  ; probably does not belong here.
  (testing "server sends notification/initialized when on-init callback is called"
    (let [!rx    (atom [])
          !tx    (atom [])
          !inited (atom false)
          server (server/->server {:name       "Test Server"
                                   :version    "1.0"
                                   :on-receive (fn [msg] (swap! !rx conj msg))
                                   :on-send    (fn [msg] (swap! !tx conj msg))
                                   :on-init    #(do (prn 'init-called) true) ; sample delay
                                   :tools      fixture-basic-tools})]
      ;(mcp/initialize server)
      (server/handle-message server
                             {:id 1
                              :method "initialize"
                              :params {}}
                             (fn [msg]
                               ; (prn 'send-msg msg) ; todo: switch to Timbre.
                               ; todo check contents
                               (reset! !inited true)))
      ; ok now to handle message
      (Thread/sleep 100) ; todo better concurrency on send

      (is (true? @!inited)))))

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
                             :properties {:name {:type :text
                                                 :doc "A person's name."
                                                 :required true}}}}
              {:name        :inc,
               :description "A simple tool that returns a greeting",
               :inputSchema {:type       "object"
                             :required   [:x]
                             :properties {:x {:type :number
                                              :doc "A number to increment."
                                              :required true}}}}
              {:name        :broken-tool,
               :description "A tool that throws for error tests.",
               :inputSchema {:type       "object"
                             :required   [:x]
                             :properties {:x {:type :text
                                              :doc "Anything"
                                              :required true}}}}]
             (mcp/list-tools server))))

    (testing "we can invoke tools via server. call-tool returns [?result ?error]."
      (is (= [["Hello, Petrus!"] nil] (mcp/call-tool server :foo {:name "Petrus"})))
      (is (= [nil [{:missing-tool-parameters '(:name)}]] (mcp/call-tool server :foo {:bad-arg "Petrus"}))))

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
    (let [init-request         {:id     1
                                :method "initialize"
                                :params {:protocolVersion schema/latest-protocol-version
                                         :capabilities    {}
                                         :clientInfo      {:name "Test Client" :version "1.0.0"}}}
          !notifications       (atom [])
          notification-handler (fn [msg] (swap! !notifications conj msg))
          test-tools           (mcp/create-toolset ; why are we testing this?
                                 {:inc {:name :inc
                                        :fn   (fn [x] (cc/inc x))
                                        :doc  "Increments a number"
                                        :args [{:name "x" :doc "x" :type :number}]}
                                  :foo {:name :foo
                                        :fn   (fn [] (str "Hello, World!"))
                                        :doc  "Returns a greeting"
                                        :args []}})
          mcp-server           (server/->server {:tools test-tools})
          init-response        (server/handle-request mcp-server init-request notification-handler)]
      (prn 'init-response init-response)
      (is (= {:jsonrpc "2.0"
              :id      1
              :result  {:protocolVersion "2024-11-05"
                        :capabilities    {:tools     {:listChanged true}
                                          :resources {:listChanged false}
                                          :prompts   {:listChanged false}}
                        :serverInfo      {:name nil, :version nil}}}
             init-response))

      ;"notifications/initialized"
      ;(Thread/sleep 100) ; do we need to sleep here because future?
      (is (= [{:jsonrpc "2.0"
               :method  "notifications/initialized"}] @!notifications)))))

(comment
  (ex-data
    (ex-info (str "Unknown tool: ")
             ; todo: we have error codes for this (method not found)
             {:tool-name "hi"
              :available #{:a :b}})))
