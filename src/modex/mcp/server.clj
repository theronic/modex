(ns modex.mcp.server
  (:require [cheshire.core :as json])
  (:gen-class))

;; Logging function that writes to stderr
(defn log [& args]
  (binding [*out* *err*]
    (apply println args)
    (flush)))

;; Server capabilities and info
(def server-capabilities
  {:tools     {:listChanged true}
   :resources {:listChanged false}
   :prompts   {:listChanged false}})

(def server-info
  {:name    "MCP Hello World Server"
   :version "1.0.0"})

;; Define our "foo" tool
(def foo-tool
  {:name        "foo"
   :description "A simple tool that returns a greeting"
   :inputSchema {:type       "object"
                 :properties {}}})

;; JSON-RPC message handling
(defn read-json-rpc-message [reader]
  (try
    (when-let [line (.readLine reader)]
      (log "Received message:" line)
      (json/parse-string line true))
    (catch Exception e
      (log "Error parsing message:" (.getMessage e))
      (.printStackTrace e (java.io.PrintWriter. *err*))
      {:error {:code    -32700
               :message "Parse error"}})))

(defn write-json-rpc-message [writer message]
  (try
    (let [json-str (json/generate-string message)]
      (log "Sending message:" json-str)
      (.write writer (str json-str "\n"))
      (.flush writer))
    (catch Exception e
      (log "Error writing message:" (.getMessage e))
      (.printStackTrace e (java.io.PrintWriter. *err*)))))

;; Request handlers
(defn handle-initialize [{:keys [id params]}]
  (log "Handling initialize request with id:" id)
  {:jsonrpc "2.0"
   :id      id
   :result  {:protocolVersion (:protocolVersion params)
             :capabilities    server-capabilities
             :serverInfo      server-info}})

(defn handle-tools-list [{:keys [id]}]
  (log "Handling tools/list request with id:" id)
  {:jsonrpc "2.0"
   :id      id
   :result  {:tools [foo-tool]}})

(defn handle-prompts-list [{:keys [id]}]
  (log "Handling prompts/list request with id:" id)
  {:jsonrpc "2.0"
   :id      id
   :result  {:prompts []}})

(defn handle-resources-list [{:keys [id]}]
  (log "Handling resources/list request with id:" id)
  {:jsonrpc "2.0"
   :id      id
   :result  {:resources []}})

(defn handle-tools-call [{:keys [id params]}]
  (log "Handling tools/call request with id:" id "for tool:" (:name params))
  (if (= (:name params) "foo")
    {:jsonrpc "2.0"
     :id      id
     :result  {:content [{:type "text"
                          :text "Hello, AI!"}]
               :isError false}}
    {:jsonrpc "2.0"
     :id      id
     :error   {:code    -32602
               :message (str "Unknown tool: " (:name params))}}))

(defn handle-ping [{:keys [id]}]
  (log "Handling ping request with id:" id)
  {:jsonrpc "2.0"
   :id      id
   :result  {}})

;; Main request dispatcher
(defn handle-request [request]
  (log "Dispatching request method:" (:method request))
  (case (:method request)
    "initialize" (handle-initialize request)
    "tools/list" (handle-tools-list request)
    "tools/call" (handle-tools-call request)
    "prompts/list" (handle-prompts-list request)
    "resources/list" (handle-resources-list request)
    "ping" (handle-ping request)
    (do
      (log "Unknown method:" (:method request))
      {:jsonrpc "2.0"
       :id      (:id request)
       :error   {:code    -32601
                 :message (str "Method not found: " (:method request))}})))

;; Check if a message is a notification (has method but no id)
(defn notification? [message]
  (and (:method message) (nil? (:id message))))

;; Handle notifications (we don't need to respond to these)
(defn handle-notification [notification]
  (let [method (:method notification)]
    (log "Received notification:" method)))
;; We don't need to do anything special for notifications right now
;; Just log them and continue


;; Main server loop
(defn start-server
  ([] (start-server *in* *out*))
  ([reader writer]
   (log "Starting MCP server")
   (try
     (loop []
       (log "Waiting for request...")
       (let [message (read-json-rpc-message reader)]
         (if message
           (do
             (log "Processing message:" (pr-str message))
             (cond
               ;; If it's a notification (has method but no id)
               (notification? message)
               (do
                 (log "Handling notification:" (:method message))
                 (handle-notification message))

               ;; If it's a request (has both method and id)
               (and (:method message) (:id message))
               (let [response (handle-request message)]
                 (log "Sending response:" (pr-str response))
                 (write-json-rpc-message writer response)

                 ;; Send initialized notification after initialize request
                 (when (= (:method message) "initialize")
                   (log "Sending initialized notification")
                   (write-json-rpc-message
                     writer
                     {:jsonrpc "2.0"
                      :method  "notifications/initialized"})))

               ;; If it's an error
               (:error message)
               (do
                 (log "Handling error message")
                 (write-json-rpc-message writer message))

               ;; Unknown message type
               :else
               (log "Unknown message type:" message))
             (recur))
           (log "Reader returned nil, client probably disconnected"))))
     (catch Exception e
       (log "Critical error in server:" (.getMessage e))
       (.printStackTrace e (java.io.PrintWriter. *err*))))))

(defn -main [& args]
  (log "Server starting via -main")
  (try
    (start-server)
    (catch Throwable t
      (log "Fatal error in -main:" (.getMessage t))
      (.printStackTrace t (java.io.PrintWriter. *err*)))))