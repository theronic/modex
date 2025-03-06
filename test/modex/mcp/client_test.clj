(ns modex.mcp.client-test
  (:require [clojure.test :refer :all]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [modex.mcp.client :as client]
            [modex.mcp.protocols :as p]
            [modex.mcp.schema :as schema]
            [modex.mcp.server :as server]
            [modex.mcp.tools :as tools]
            [taoensso.timbre :as log])
  (:import [java.io PipedInputStream PipedOutputStream]))

(deftest test-server-client-integration
  (testing "Server responds correctly to requests over piped streams"
    (let [client-to-server   (PipedOutputStream.)
          server-in          (PipedInputStream. client-to-server)
          server-to-client   (PipedOutputStream.)
          client-in          (PipedInputStream. server-to-client)

          server-reader      (io/reader server-in)
          server-writer      (io/writer server-to-client)
          client-reader      (io/reader client-in)
          client-writer      (io/writer client-to-server)

          ;; Start the server in a separate thread
          mcp-handler        (p/->TestServer [tools/foo-tool tools/inc-tool])
          server-future      (future (server/start-server! mcp-handler server-reader server-writer))

          ;; Create a mini client for testing
          request-id         (atom 0)
          send-test-request  (fn [method & [params]]
                               (let [id      (swap! request-id inc)
                                     request {:jsonrpc "2.0" :id id :method method}
                                     request (if params (assoc request :params params) request)
                                     msg     (str (json/write-value-as-string request json/keyword-keys-object-mapper) "\n")]
                                 (log/debug 'client-test-send msg)
                                 (.write client-writer msg)
                                 (.flush client-writer)
                                 id))

          read-test-response (fn []
                               (let [line (.readLine client-reader)]
                                 (when line
                                   (log/debug 'parsing-line line)
                                   (let [parsed (json/read-value line json/keyword-keys-object-mapper)]
                                     (log/debug 'parsed parsed)
                                     parsed))))]

      (try
        ;; Test 1: Initialize
        (let [init-id           (send-test-request "initialize"
                                                   {:protocolVersion schema/latest-protocol-version
                                                    :capabilities    {:sampling {}}
                                                    :clientInfo      {:name "Test Client" :version "1.0.0"}})
              init-response     (read-test-response)
              _                 (log/debug 'init-response init-response)
              init-notification (read-test-response)
              _                 (log/debug 'init-notification init-notification)] ;; Capture the initialized notification

          (is (= init-id (:id init-response)))
          (is (= "2024-11-05" (get-in init-response [:result :protocolVersion])))
          (is (map? (get-in init-response [:result :capabilities])))
          (is (map? (get-in init-response [:result :serverInfo])))

          (is (= "notifications/initialized" (:method init-notification))))

        ;; Test 2: List tools
        (let [list-id       (send-test-request "tools/list")
              list-response (read-test-response)]
          (is (= list-id (:id list-response)))
          (is (= (get-in list-response [:result :tools])
                 [server/foo-tool server/inc-tool])))

        ;; Test 3: Call the foo tool
        (let [call-id       (send-test-request "tools/call" {:name "foo"})
              call-response (read-test-response)]
          (log/debug call-response)
          (is (= {:jsonrpc schema/json-rpc-version
                  :id      3
                  :result  {:content [{:type "text", :text "Hello, AI!"}]
                            :isError false}}
                 call-response)))

        (finally
          (testing "order matters here"
            ;; First close client-writer to signal end-of-stream to the server
            (.close client-writer)

            ;; Give the server a brief period to process the disconnection
            (Thread/sleep 100)

            ;; Then cancel the server future
            (future-cancel server-future)

            ;; Clean up remaining resources
            (.close client-reader)
            (.close server-writer)
            (.close server-reader)))))))
