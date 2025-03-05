(ns modex.mcp.server
  (:require [cheshire.core :as json])
  (:gen-class))

(def json-rpc-version "2.0")

; MCP Standard Error Codes:
; (SDKs and applications can define their own error codes above -32000)
(def error-code-parse-error -32700)
(def error-code-invalid-request -32600)
(def error-code-method-not-found -32601)
(def error-code-invalid-params -32602)
(def error-code-internal-error -32603)

(defn log
  "Redirects writes to stderr so logs show up in ~/Library/Logs/Claude/mcp*.log"
  [& args]
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

(defn read-json-rpc-message
  [reader]
  (try
    (when-let [line (.readLine reader)]
      (log "Received message:" line)
      (json/parse-string line true))
    (catch java.io.IOException e
      (log "IO error reading message:" (.getMessage e))
      nil)  ;; Return nil to exit the loop for any IO error
    (catch Exception e
      (log "Error parsing message:" (.getMessage e))
      (.printStackTrace e (java.io.PrintWriter. *err*))
      {:error {:code    error-code-parse-error
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

(defn json-rpc-result [id result]
  {:jsonrpc json-rpc-version
   :id      id
   :result  result})

(defn json-rpc-error [id error]
  {:jsonrpc json-rpc-version
   :id      id
   :error   error})

(defn json-rpc-method [method]
  {:jsonrpc json-rpc-version
   :method  method})

;; Request handlers
(defn handle-initialize [{:keys [id params]}]
  (log "Handling initialize request with id:" id)
  [(json-rpc-result id {:protocolVersion (:protocolVersion params)
                        :capabilities    server-capabilities
                        :serverInfo      server-info})
   (json-rpc-method "notifications/initialized")])

(defn handle-tools-list [{:keys [id]}]
  (log "Handling tools/list request with id:" id)
  (json-rpc-result id {:tools [foo-tool]}))

(defn handle-prompts-list [{:keys [id]}]
  (log "Handling prompts/list request with id:" id)
  (json-rpc-result id {:prompts []}))

(defn handle-resources-list [{:keys [id]}]
  (log "Handling resources/list request with id:" id)
  (json-rpc-result id {:resources []}))

(defn handle-tools-call [{:keys [id params]}]
  (log "Handling tools/call request with id:" id "for tool:" (:name params))
  (if (= (:name params) "foo")
    (json-rpc-result id {:content [{:type "text"
                                    :text "Hello, AI!"}]
                         :isError false})
    (json-rpc-error id {:code    error-code-invalid-params
                        :message (str "Unknown tool: " (:name params))})))

(defn handle-ping [{:keys [id]}]
  (log "Handling ping request with id:" id)
  (json-rpc-result id {}))

;; Main request dispatcher
(defn handle-request [{:as request :keys [id method]}]
  (log "Dispatching request method:" method)
  (case method
    "initialize" (handle-initialize request)
    "tools/list" (handle-tools-list request)
    "tools/call" (handle-tools-call request)
    "prompts/list" (handle-prompts-list request)
    "resources/list" (handle-resources-list request)
    "ping" (handle-ping request)
    (do
      (log "Unknown method:" method)
      (json-rpc-error id {:code    error-code-method-not-found
                          :message (str "Method not found: " method)}))))

;; Check if a message is a notification (has method but no id)
(defn notification? [{:as _message :keys [method id]}]
  (and method (not id)))

(defn handle-notification
  "We don't need to do anything special for notifications right now.
  Just log them and continue."
  [notification]
  (let [method (:method notification)]
    (log "Received notification:" method)
    nil))

(defn handle-message
  "Returns a JSON-RPC message."
  [message]                                                 ; WIP: factoring out writer.
  (log "Handling message: " (pr-str message))
  (cond
    ; Log errors:
    (:error message)
    (log "Error message: " message)

    ;; Handle requests (have method & id)
    (and (:method message) (:id message))
    (handle-request message)

    ; Notification (no method, only id)
    (notification? message)
    (do
      (log "Handling notification:" (:method message))
      (handle-notification message))

    ;; Unknown message type (we just log it)
    :else
    (do (log "Unknown message type:" message)
        nil)))

(defn start-server!
  "Main server loop – supports *in* & *out* bindings."
  ([] (start-server! *in* *out*))
  ([reader writer]
   (log "Starting Modex MCP server...")
   (try
     (loop []
       (log "Waiting for request...")
       (let [message (read-json-rpc-message reader)]
         (if-not message
           (do (log "Reader returned nil, client probably disconnected"))

           (let [?responses (handle-message message)]
             (log "Responding with messages: " (pr-str ?responses))
             (if (map? ?responses)                          ; map = single message
               (write-json-rpc-message writer ?responses)
               (if (seq ?responses)                         ; if seq/coll, send multiple messages sequentially.
                 (doseq [response ?responses]
                   (write-json-rpc-message writer response))))
             (recur)))))
     (log "Exiting.")
     (catch Exception e
       (log "Critical error in server:" (.getMessage e))
       (.printStackTrace e (java.io.PrintWriter. *err*))))))

(defn -main [& args]
  (log "Server starting via -main")
  (try
    (start-server!)
    (catch Throwable t
      (log "Fatal error in -main:" (.getMessage t))
      (.printStackTrace t (java.io.PrintWriter. *err*)))))