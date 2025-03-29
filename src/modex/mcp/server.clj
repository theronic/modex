(ns modex.mcp.server
  "Handles JSON RPC interface and dispatches to an MCP server "
  (:require [taoensso.timbre :as log]
            [jsonista.core :as json]
            [modex.mcp.protocols :as mcp :refer [AServer]]
            [modex.mcp.schema :as schema]
            [modex.mcp.json-rpc :as json-rpc]
            [modex.mcp.tools :as tools])
  (:gen-class))                                             ; gen-class should move to core with main.

;; Configure Timbre to output to stderr so it shows up in MCP Server
(log/set-config!
  {:level :debug                                            ;info  ;; Set minimum logging level
   :appenders
   {:println
    {:enabled?  true
     :output-fn :inherit                                    ;; Use default output formatting
     :fn        (fn [data]                                  ;; Custom appender function to use stderr
                  (let [{:keys [output-fn]} data
                        formatted-output (output-fn data)]
                    (binding [*out* *err*]                  ;; Redirect to stderr
                      (println formatted-output))))}}})

(defn format-tool-results
  "Format a tool result into the expected JSON-RPC text response format.
  Everything is text right now, but could be (TextContent | ImageContent | EmbeddedResource)."
  [results]
  (let [content-type "text"]                                ; supported content types are under schemacall-tool-result
    {:content (vec (for [result results]
                     {:type content-type                    ; todo result types. just text or number basically.
                      :text (str result)}))
     :isError false}))

(defn format-tool-errors
  [errors]
  (-> (format-tool-results errors)
      (assoc :isError true)))

(defn ->server
  "Returns a reified instance of AServer (an MCP Server),
  given tools, resources and prompts. Only tools are supported at this time."
  [{:keys [protocol-version
           name version
           tools resources prompts
           initialize
           on-receive
           on-send
           enqueue-notification]
    :or   {protocol-version schema/latest-protocol-version
           initialize (fn [])}}]
  (reify AServer
    ; todo: add handle-message or handle-request
    (protocol-version [_this] protocol-version)

    (server-name [_this] name)
    (version [_this] version)

    (capabilities [_this]
      {:tools     {:listChanged (boolean (seq tools))}
       :resources {:listChanged (boolean (seq resources))}
       :prompts   {:listChanged (boolean (seq prompts))}})

    ; For debugging:
    (on-receive [_this msg]
      (when on-receive (on-receive msg)))

    (on-send [_this msg]
      (when on-send (on-send msg)))

    (enqueue-notification [_this msg]
      (when enqueue-notification
        (enqueue-notification msg)))

    ; this is triggered by MCP client asking for init.
    (initialize [_this]
      (initialize)) ; this can block.

    (list-tools [_this]
      (->> (vals tools)                                     ; tools is a map.
           (mapv tools/tool->json-schema)))

    (list-resources [_this] [])                             ; not impl.
    (list-prompts [_this] [])                               ; not impl.

    (call-tool
      ; returns [?result ?error]. Considering switching to maps, even for tool responses.
      ; This maps the argument map to the tool handler's expected arity, should validate MAlli schema and invokes the tool.
      [_this tool-name arg-map]
      (log/debug "call-tool:" tool-name arg-map)

      (let [tool-key (keyword tool-name)                    ;; hoist to caller?
            tool     (get tools tool-key)]

        (if-not tool
          ; is this exception handled properly by caller?
          ; todo better exceptions here to handle missing stuff in router.
          [nil [{:error           :missing-tool
                 :tool-name       tool-name
                 :available-tools (keys tools)}]]
          ;(throw (ex-info (str "Unknown tool: " tool-name)
          ;                ; todo: we have error codes for this (method not found)
          ;                [{:tool-name tool-name
          ;                  :available (keys tools)}]))
          (try
            (tools/invoke-tool tool arg-map)
            (catch Exception ex
              ; todo better exception handling.
              ;(prn 'call-tool-ex (pr-str ex))
              (throw ex))))))))

(defn read-json-rpc-message
  "Reads a JSON value from reader and coerces map string keys to keywords.
  Returns parse error on JSON parse exception."
  [reader]
  (try
    (when-let [line (.readLine reader)]
      (log/debug "Received message:" line)
      (json/read-value line json/keyword-keys-object-mapper))
    (catch java.io.IOException e
      (log/debug "IO error reading message:" (.getMessage e))
      nil)                                                  ;; Return nil to exit the loop for any IO error
    (catch Exception e
      (log/debug "Error parsing message:" (.getMessage e))
      (.printStackTrace e (java.io.PrintWriter. *err*))
      {:error {:code    schema/error-parse
               :message "Parse error"}})))

; can move to JSON-RPC-specific namespace.
(defn write-json-rpc-message
  "Concurrency-safe JSON writes. Locks writer during .write & .flush."
  [writer message]
  (try
    (let [json-str (json/write-value-as-string message json/keyword-keys-object-mapper)]
      (log/debug "Server Sending message:" json-str)
      (locking writer
        (.write writer (str json-str "\n"))
        (.flush writer)))
    (catch Exception e
      (log/debug "Error writing message:" (.getMessage e))
      (.printStackTrace e (java.io.PrintWriter. *err*)))))

(defn handle-tool-call-request
  "Handles tools/call request and invokes tool. Returns JSON-RPC result or error.
  Errors need work."
  [server {:as _request :keys [id params]}]
  ;(log/debug "Handling tools/call request (ID " id ") with params:" (pr-str params))
  (let [{tool-name :name
         arg-map   :arguments} params]
    (try
      (let [[results ?errors] (mcp/call-tool server tool-name arg-map)]
        (if ?errors
          (do
            (log/debug "Tool Error:" ?errors)
            (->> (format-tool-errors ?errors)               ; note that tool errors are reported via a normal JSON-RPC result, but with isError = true.
                 (json-rpc/result id)))
          (->> (format-tool-results results)
               (json-rpc/result id))))
      (catch Exception ex                                   ; this is unexpected now.
        ; note that tool errors are reported via a normal JSON-RPC result, but with isError = true.
        (json-rpc/result id (format-tool-errors (ex-data ex)))))))

;; Main request dispatcher
(defn handle-request
  "Just a router for AServer.
  Each dispatch may return aa map (single message), or a collection of sequential messages."
  [mcp-server, {:as request :keys [id method params]} & [send-notification]]
  ; todo: move out JSON-RPC-specific results to a format handler.
  ;(log/debug "Dispatching request method:" method)
  (try
    (case method
      "ping" (do
               ; we deal with ping here not in server. todo: liveness checks.
               (log/debug "Handling ping request with id:" id)
               (json-rpc/result id {}))

      "tools/call" (handle-tool-call-request mcp-server request) ; todo: run invoke in future / thread.

      "initialize" (let [init-response {:protocolVersion (mcp/protocol-version mcp-server)
                                        :capabilities    (mcp/capabilities mcp-server) ; calls above.
                                        :serverInfo      {:name    (mcp/server-name mcp-server)
                                                          :version (mcp/version mcp-server)}}]
                     (let [inited-notification (json-rpc/method "notifications/initialized")]
                       (mcp/enqueue-notification mcp-server inited-notification) ; for testing w/o bus.
                       (future
                         (log/warn 'initialize)
                         (try
                           (mcp/initialize mcp-server)
                           ; todo: consider only init notifs if initialize returned true.
                           ; this will move to an async bus.
                           ; note that if initialize is fast, this can arrive before the init result.
                           (send-notification inited-notification)
                           (catch Exception ex
                             (log/error "MCP Server initialize failed: " (ex-message ex)))))) ; too coupled.

                     (json-rpc/result id init-response))

      ;; Enumeration methods:
      "tools/list" (json-rpc/result id {:tools (mcp/list-tools mcp-server)})
      "prompts/list" (do
                       (log/debug "Handling prompts/list request with id:" id)
                       (json-rpc/result id {:prompts (mcp/list-prompts mcp-server)}))
      "resources/list" (do
                         (log/debug "Handling resources/list request with id:" id)
                         (json-rpc/result id {:resources (mcp/list-resources mcp-server)}))
      (do
        (log/debug "Unknown method:" method)
        (json-rpc/error id {:code    schema/error-method-not-found
                            :message (str "Method not found: " method)})))
    (catch Exception e
      (log/error "Error handling request:" (.getMessage e))
      (json-rpc/error id {:code    schema/error-internal
                          :message (str "Internal error: " (.getMessage e))}))))

(defn notification?
  "Notifications have method, but no id."
  [{:as _message :keys [method id]}]
  (and method (not id)))

(defn handle-notification
  "We don't need to do anything special for notifications right now.
  Just log them and continue."
  [{:as _notification :keys [method]}]
  (log/debug "Received notification:" method)
  nil)

(defn handle-message
  "Returns a JSON-RPC message."
  [server message send-notification]                        ; not loving send-message here.
  (try
    (log/debug "Handling message: " message)
    (cond
      ; Log errors:
      (:error message)
      (log/debug "Error message: " message)

      ;; Handle requests (have method & id)
      (and (:method message) (:id message))
      (handle-request server message send-notification)

      ; Notification (has method, no id)
      (notification? message)
      (do
        (log/debug "Handling notification:" (:method message))
        (handle-notification message))

      ;; Unknown message type (we just log it)
      :else
      (do (log/warn "Unknown message type:" message)
          nil))
    (catch Exception e
      ; this needs to be handled by router.
      (log/error "Critical error handling message:" (.getMessage e))
      (.printStackTrace e (java.io.PrintWriter. *err*))
      (if-let [id (:id message)]
        (json-rpc/error id {:code    schema/error-internal
                            :message (str "Internal error: " (.getMessage e))})
        ;; No ID, can't send an error response
        nil))))

(defn start-server!
  "Main server loop – supports *in* & *out* bindings."
  ([server] (start-server! server *in* *out*))
  ([server reader writer]
   (log/debug "Starting Modex MCP server...")
   (try
     (let [send-notification-handler (fn [message] (write-json-rpc-message writer message))]
       (loop []
         (log/debug "Waiting for request...")
         (let [message (read-json-rpc-message reader)]
           (mcp/on-receive server message)                  ; notify caller on rx (mainly for testing)

           (if-not message
             (do                                            ; we exit here.
               (log/debug "Reader returned nil, client probably disconnected"))

             (let [?response (handle-message server message send-notification-handler)]
               (when ?response
                 (log/debug "Responding with messages: " (pr-str ?response))
                 (mcp/on-send server ?response)             ; tracking for send events.
                 (write-json-rpc-message writer ?response))
               (recur))))))
     (log/debug "Exiting.")
     (catch Exception e
       (log/debug "Critical error in server:" (.getMessage e))
       (.printStackTrace e (java.io.PrintWriter. *err*))))))