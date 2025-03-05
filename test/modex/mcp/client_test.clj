(ns modex.mcp.client-test
  (:require [clojure.test :refer :all]
            [modex.mcp.client :as client]
            [modex.mcp.server :as server]
            [clojure.java.io :as io])
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
          server-future      (future (server/start-server! server-reader server-writer))

          ;; Create a mini client for testing
          request-id         (atom 0)
          send-test-request  (fn [method & [params]]
                               (let [id      (swap! request-id inc)
                                     request {:jsonrpc "2.0" :id id :method method}
                                     request (if params (assoc request :params params) request)]
                                 (.write client-writer (str (cheshire.core/generate-string request) "\n"))
                                 (.flush client-writer)
                                 id))

          read-test-response (fn []
                               (let [line (.readLine client-reader)]
                                 (when line
                                   (cheshire.core/parse-string line true))))]

      (try
        ;; Test 1: Initialize
        (let [init-id           (send-test-request "initialize"
                                                   {:protocolVersion "2024-11-05"
                                                    :capabilities    {:sampling {}}
                                                    :clientInfo      {:name "Test Client" :version "1.0.0"}})
              init-response     (read-test-response)
              init-notification (read-test-response)]       ;; Capture the initialized notification

          (is (= init-id (:id init-response)))
          (is (= "2024-11-05" (get-in init-response [:result :protocolVersion])))
          (is (map? (get-in init-response [:result :capabilities])))
          (is (map? (get-in init-response [:result :serverInfo])))

          (is (= "notifications/initialized" (:method init-notification))))

        ;; Test 2: List tools
        (let [list-id       (send-test-request "tools/list")
              list-response (read-test-response)]
          (is (= list-id (:id list-response)))
          (is (vector? (get-in list-response [:result :tools])))
          (is (= 1 (count (get-in list-response [:result :tools]))))
          (is (= "foo" (get-in list-response [:result :tools 0 :name]))))

        ;; Test 3: Call the foo tool
        (let [call-id       (send-test-request "tools/call" {:name "foo"})
              call-response (read-test-response)]
          (is (= call-id (:id call-response)))
          (is (vector? (get-in call-response [:result :content])))
          (is (= 1 (count (get-in call-response [:result :content]))))
          (is (= "Hello, AI!" (get-in call-response [:result :content 0 :text]))))

        (finally
          ;; Clean up
          (future-cancel server-future)
          (.close client-writer)
          (.close client-reader)
          (.close server-writer)
          (.close server-reader))))))