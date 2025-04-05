(ns modex.mcp.client-test
  (:require [clojure.test :refer :all]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [clojure.core :as cc]
            [modex.mcp.client :as client]
            [modex.mcp.protocols :as mcp]
            [modex.mcp.schema :as schema]
            [modex.mcp.server :as server]
            [modex.mcp.tools :as tools]
            [taoensso.timbre :as log])
  (:import [java.io PipedInputStream PipedOutputStream]))

(def tool-fixtures
  (tools/tools
    (foo "Greets a person by name."
         [^{:type :text :doc "A person's name."} name]
         [(str "Hello, " name "!")])
    (slow "A slow tool to test async."
      []
      [(do (Thread/sleep 500) :done)])
    (inc "A simple tool that returns a greeting"
         [^{:type :number :doc "A number to increment."} x]
         [(cc/inc x)])))

(defprotocol ATransport ; probably this exists elsewhere
  (write! [this method params])
  (read [this]))

(defn make-request [id method params]
  (let [request (cond-> {:jsonrpc "2.0"
                         :id      id
                         :method  method}
                  params (assoc :params params))]
    (str (json/write-value-as-string request json/keyword-keys-object-mapper) "\n")))

(comment
  (make-request 1 "tools/call" {:name "foo" :arguments {:name "AI"}})

  (make-request 1 "initialize" {:protocolVersion schema/latest-protocol-version
                                :capabilities    {:sampling {}}
                                :clientInfo      {:name "Test Client" :version "1.0.0"}}))

(defn write-request!
  "Returns ID."
  [writer id method & [params]]
  ; can this use our concurrency-safe writer?
  (let [msg (make-request id method params)]
    (log/debug 'client-test-send msg)
    (locking writer                                         ; can we use existing fns for this?
      (.write writer msg)
      (.flush writer))
    id))


(defn read-response [reader]
  (let [line (.readLine reader)]
    (when line
      (log/debug 'parsing-line line)
      (let [parsed (json/read-value line json/keyword-keys-object-mapper)]
        (log/debug 'parsed parsed)
        parsed))))

(defrecord MockTransport [!request-id writer reader]
  ATransport
  (write! [this method params]
    (write-request! writer (swap! !request-id inc) method params))
  (read [this] (read-response reader)))

(defn make-piped-streams []
  (let [client-to-server (PipedOutputStream.)
        server-in        (PipedInputStream. client-to-server)

        server-to-client (PipedOutputStream.)
        client-in        (PipedInputStream. server-to-client)]

    {:server-reader (io/reader server-in)
     :server-writer (io/writer server-to-client)
     :client-reader (io/reader client-in)
     :client-writer (io/writer client-to-server)}))

(defn close-pipes! [{:as pipes
                     :keys [server-writer server-reader
                            client-writer client-reader]}
                    stdio-server]
  (testing "order matters here"
    ;; First close client-writer to signal end-of-stream to the server
    (.close client-writer)

    ;; Give the server a brief period to process the disconnection
    (Thread/sleep 100) ; todo figure out a way to wait for this. probably server notification?

    ;; Then cancel the server future
    (future-cancel stdio-server)

    ;; Clean up remaining resources
    (.close client-reader)
    (.close server-writer)
    (.close server-reader)))

(deftest test-server-client-integration
  (testing "Server responds correctly to requests over piped streams"
    (let [{:as pipes
           :keys [server-writer server-reader
                  client-writer client-reader]} (make-piped-streams)

          mcp-server         (server/->server {:name       "Test MCP Server"
                                               :version    "1.0.0"
                                               :initialize (fn [init-params] (Thread/sleep 50))
                                               :tools      tool-fixtures})
          ;; Start the server in a separate thread
          stdio-server       (future (server/start-server! mcp-server server-reader server-writer))

          ;; Create a mini client for testing
          !request-id        (atom 0)
          client             (->MockTransport !request-id client-writer client-reader)] ; client transport?
      (try
       ;; Test 1: Initialize
       (let [init-id       (write! client "initialize"
                                              {:protocolVersion schema/latest-protocol-version
                                               :capabilities    {:sampling {}}
                                               :clientInfo      {:name "Test Client" :version "1.0.0"}})
             init-response (read client)
             _             (log/debug 'init-response init-response)]

         (is (= {:id      init-id
                 :jsonrpc "2.0"
                 :result  {:capabilities    {:prompts   {:listChanged false}
                                             :resources {:listChanged false}
                                             :tools     {:listChanged true}}
                           :protocolVersion "2024-11-05"
                           :serverInfo      {:name    "Test MCP Server"
                                             :version "1.0.0"}}} init-response))

         (testing "initialize response should be followed by notifications/initalized")
         (let [init-notification (read client)]
           ;(prn 'init-notif init-notification) ; todo: switch to Timbre.
           (is (= "notifications/initialized" (:method init-notification)))))

       ;; Test 2: List tools
       (let [list-tools-id       (write! client "tools/list" {})
             list-tools-response (read client)]
         ;(prn list-tools-response)
         (is (= {:id      list-tools-id
                 :jsonrpc "2.0"
                 :result  {:tools [{:name        "foo"
                                    :description "Greets a person by name."
                                    :inputSchema {:properties {:name {:doc  "A person's name."
                                                                      :required true
                                                                      :type "text"}}
                                                  :type       "object"
                                                  :required   ["name"]}}
                                   {:name        "slow"
                                    :description "A slow tool to test async."
                                    :inputSchema {:properties {}
                                                  :type       "object"
                                                  :required   []}}
                                   {:name        "inc"
                                    :description "A simple tool that returns a greeting"
                                    :inputSchema {:properties {:x {:doc  "A number to increment."
                                                                   :required true
                                                                   :type "number"}}
                                                  :type       "object"
                                                  :required   ["x"]}}]}}
                list-tools-response)))

       ;; Test 3: Call the foo tool
       ;; ; actually arrives from client:
       ;; {:jsonrpc "2.0", :method "tools/call", :params {:arguments {:x 5}, :name "inc"}, :id 6}

       (let [call-id       (write! client "tools/call" {:name "foo" :arguments {:name "AI"}})
             call-response (read client)]
         (log/debug call-response)
         (is (= {:jsonrpc schema/json-rpc-version
                 :id      call-id
                 :result  {:content [{:type "text", :text "Hello, AI!"}]
                           :isError false}}
                call-response)))

       (let [call-id       (write! client "tools/call" {:name "inc" :arguments {:x 5}})
             call-response (read client)]
         (log/debug call-response)
         (is (= {:jsonrpc schema/json-rpc-version
                 :id      call-id
                 :result  {:content [{:type "text", :text "6"}]
                           :isError false}}
                call-response)))

       (testing "missing arguments are -32602 protocol-level errors"
         (let [call-id       (write! client "tools/call" {:name "inc" :arguments {:y 5}})
               call-response (read client)]
           (log/debug call-response)
           (is (= {:jsonrpc schema/json-rpc-version
                   :id      call-id
                   :error {:code schema/error-invalid-params
                           :message "Missing tool parameters: :x"}}
                  call-response))))

       (finally
         (close-pipes! pipes stdio-server))))))

(deftest async-tests
  (testing "slow tools do not block execution of fast tools"
    (let [{:as   pipes
           :keys [server-writer server-reader
                  client-writer client-reader]} (make-piped-streams)

          mcp-server   (server/->server {:tools tool-fixtures})
          stdio-server (future (server/start-server! mcp-server server-reader server-writer))

          !request-id  (atom 0)
          client       (->MockTransport !request-id client-writer client-reader)]

      (try
        (write! client "initialize" {:protocolVersion schema/latest-protocol-version
                                     :capabilities    {:sampling {}}
                                     :clientInfo      {:name "Test Client" :version "1.0.0"}})
        (is (= "notifications/initialized" (:method (read client))))
        (write! client "tools/call" {:name "slow" :arguments {}})
        (write! client "tools/call" {:name "inc" :arguments {:x 100}})
        (is (= 1 (:id (read client)))) ; init - don't care.
        (is (= 3 (:id (read client)))) ; fast cool comes back first.
        (is (= 2 (:id (read client)))) ; slow call come back later.
        (finally
          (testing "order matters here"
            (close-pipes! pipes stdio-server)))))))