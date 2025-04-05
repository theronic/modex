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
         (throw (ex-info "Tool throws intentionally" {:cause "broken-tool"})))))

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
                                   :on-init    #(do (log/debug 'init-called) true) ; sample delay
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
      (is (= {:success true, :results ["Hello, Petrus!"]} (mcp/call-tool server :foo {:name "Petrus"})))
      (let [ex (try
                 (mcp/call-tool server :foo {:bad-arg "Petrus"})
                 (catch Exception ex
                   ;(prn 'ex ex)
                   ex))
            ex-data (ex-data ex)
            ex-msg (ex-message ex)]
        (is (= "Missing tool parameters: :name" ex-msg))
        (is (= :tool.exception/missing-parameters (:cause ex-data)))))

    (testing "handle-tools-call throws protocol-level error for unknown tools"
      ; this test is at wrong level
      (let [missing-tool-request {:id     4
                                  :method "tools/call"
                                  :params {:name "unknown-tool"
                                           :arguments {:missing-arg "abc"}}}
            mcp-server           (server/->server {:tools fixture-basic-tools})]
        (log/warn (server/handle-request mcp-server missing-tool-request))
        ; todo missing tool should return error code invalid params
        ; this is broken. should be a protocol level error.
        (is (= {:jsonrpc schema/json-rpc-version
                :id 4
                :error {:code schema/error-invalid-params
                        :message (str "Unknown tool: unknown-tool")}}
               (server/handle-request mcp-server missing-tool-request)))))

    (testing "broken tools return error in result (not via protocol-level errors)"
      (let [bad-tool-request {:id     4
                              :method "tools/call"
                              :params {:name "broken-tool", :arguments {:x ""}}}
            mcp-server       (server/->server {:tools fixture-basic-tools})]
        ; todo missing tool should return error code invalid params
        ; {:error {:code 123
        ;                        :message "Tool throws intentionally"}
        ;                :jsonrpc schema/json-rpc-version
        ;                :id      4}
        (is (= {:jsonrpc schema/json-rpc-version
                :id      4
                :result   {:isError true
                           :content [{:type "text", :text "Tool throws intentionally"}]}}
               (server/handle-request mcp-server bad-tool-request)))))

    (testing "missing tool arguments are considered protocol-level errors"
      (let [missing-args-req {:id     4
                              :method "tools/call"
                              :params {:name "broken-tool"}}
            mcp-server       (server/->server {:tools fixture-basic-tools})]
        ;(log/warn (server/handle-request mcp-server missing-args-req))
        ; todo missing tool should return error code invalid params
        (is (= {:jsonrpc schema/json-rpc-version
                :id      4
                :error {:code schema/error-invalid-params
                        :message (str "Missing tool parameters: :x")}}
               (server/handle-request mcp-server missing-args-req)))))))

(deftest test-handle-initialize
  (testing "initialize returns capabilities and sends init notification."
    (let [init-request         {:id     1
                                :method "initialize"
                                :params {:protocolVersion schema/latest-protocol-version
                                         :capabilities    {}
                                         :clientInfo      {:name "Test Client" :version "1.0.0"}}}
          !notifications       (atom [])
          notification-handler (fn [msg] (comment "until we have an async bus, we use :on-send-notification for testing."))
          mcp-server           (server/->server {:initialize           (fn [] (log/warn "mcp-server initialize called"))
                                                 :enqueue-notification (fn [msg] (swap! !notifications conj msg))
                                                 :tools                fixture-basic-tools})
          init-response        (server/handle-request mcp-server init-request notification-handler)]
      (is (= {:jsonrpc "2.0"
              :id      1
              :result  {:protocolVersion "2024-11-05"
                        :capabilities    {:tools     {:listChanged true}
                                          :resources {:listChanged false}
                                          :prompts   {:listChanged false}}
                        :serverInfo      {:name nil, :version nil}}}
             init-response))

      (is (= [{:jsonrpc "2.0"
               :method  "notifications/initialized"}] @!notifications)))))
