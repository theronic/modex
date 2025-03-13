(ns modex.mcp.server
  "Handles JSON RPC interface and dispatches to an MCP server "
  (:require [jsonista.core :as json]
            [modex.mcp.schema :as schema]
            [taoensso.timbre :as log]
            [clojure.core :as cc]
            [modex.mcp.json-rpc :as json-rpc]
            [modex.mcp.tools :as tools]
            [modex.mcp.protocols :as mcp :refer [AServer]])
  (:gen-class))                                             ; gen-class should move to core with main.

(def json-rpc-version "2.0")

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

; MCP Standard Error Codes:
; (SDKs and applications can define their own error codes above -32000)
; Todo: move to schema.

(defn format-tool-result
  "Format a tool result into the expected JSON-RPC response format"
  [result]
  (let [content-type "text"] ; supported content types are under schemacall-tool-result
        ;(cond
        ;  (number? result) "number"            ; keyword?
        ;  :else "text")]
    {:content [{:type content-type
                :text (str result)}]
     :isError false}))

(defn format-tool-error
  [result]
  (-> (format-tool-result result)
      (assoc :isError true)))

(defn ->server
  "Given tools, resources and prompt, returns a reified instance of AServer, which describes an MCP Server.
  JSON-RPC is handled by caller. Good idea?"
  [{:keys [protocol-version
           name version
           tools resources prompts]
    :or   {protocol-version schema/latest-protocol-version}}]

  (reify AServer
    (protocol-version [_this] protocol-version)

    (server-name [_this] name)
    (version [_this] version)

    (capabilities [_this]
      {:tools     {:listChanged (boolean (seq tools))}
       :resources {:listChanged (boolean (seq resources))}
       :prompts   {:listChanged (boolean (seq prompts))}})


    (initialize [this]
      ; I don't like the self-calling here. Feels dangerous.
      {:protocolVersion protocol-version
       :capabilities    (mcp/capabilities this)             ; calls above.
       :serverInfo      {:name    (mcp/server-name this)
                         :version (mcp/version this)}})

    (list-tools [_this]
      (->> (vals tools) ; tools is a map.
           (mapv tools/tool->json-schema)))

    (list-resources [this] [])                              ; not impl.
    (list-prompts [_this] [])                               ; not impl.

    (call-tool
      ;"Maps arguments to tool handler arity, validates optional schema and invokes fool fn with args."
      [_this tool-name arg-map]
      (log/debug "call-tool:" tool-name arg-map)

      (let [tool-key (keyword tool-name)                    ;; hoist?
            tool     (get tools tool-key)]

        (if-not tool
          ; is this exception handled properly by caller?
          ; todo better exceptions here to handle missing stuff in router.
          (throw (ex-info (str "Unknown tool: " tool-name)
                          ; todo: we have error codes for this (method not found)
                          {:tool-name tool-name
                           :available (keys tools)}))
          (try
            (tools/invoke-tool tool arg-map)
            (catch Exception ex
              ; todo better exception handling.
              (prn 'call-tool-ex (pr-str ex))
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
(defn write-json-rpc-message [writer message]
  (try
    (let [json-str (json/write-value-as-string message json/keyword-keys-object-mapper)]
      (log/debug "Server Sending message:" json-str)
      (.write writer (str json-str "\n"))
      (.flush writer))
    (catch Exception e
      (log/debug "Error writing message:" (.getMessage e))
      (.printStackTrace e (java.io.PrintWriter. *err*)))))

;; Request handlers
;(defn handle-initialize [server {:keys [id params]}]
;  (log/debug "Handling initialize request with id:" id)
;  [(json-rpc/result id {:protocolVersion (:protocolVersion params)
;                        :capabilities    server-capabilities
;                        :serverInfo      server-info})
;   (json-rpc/method "notifications/initialized")])


;(defn handle-list-tools [server {:keys [id]}]
;  (log/debug "Handling tools/list request with id:" id)
;  (let [tools (:tools server)]
;    ; move to server namespace?
;    (json-rpc/result id {:tools (map tools/tool->json-schema tools)})))

(comment)

;(defn handle-list-prompts [server {:keys [id]}]
;  (log/debug "Handling prompts/list request with id:" id)
;  (json-rpc/result id {:prompts (mcp/list-prompts server)}))

;(defn handle-list-resources [server {:keys [id]}]
;  (log/debug "Handling resources/list request with id:" id)
;  (json-rpc/result id {:resources (mcp/list-resources server)}))

;(defn handle-call-tool [server {:as request, :keys [id params]}]
;  (log/debug "Handling tools/call request with id:" id "for tool:" (pr-str (:name params)))
;  (let [{tool-name :name arguments :arguments parameters :parameters} params
;        ;; MCP spec has 'arguments' but our test is using 'parameters' - handle both
;        arg-map (or arguments parameters {})]
;    (try
;      (let [result (mcp/call-tool server tool-name arg-map)]
;        (json-rpc/result id result))
;      (catch Exception e
;        (log/error "Error calling tool" tool-name ":" (.getMessage e))
;        (json-rpc/error id {:code    schema/error-invalid-params
;                            :message (.getMessage e)})))))

;(defn handle-ping [_mcp-server {:keys [id]}]
;  (log/debug "Handling ping request with id:" id)
;  (json-rpc/result id {}))

(defn handle-tool-call-request
  "Handles tools/call request and invokes tool. Returns JSON-RPC result or error.
  Errors need work."
  [server {:as _request :keys [id params]}]
  ;(log/debug "Handling tools/call request (ID " id ") with params:" (pr-str params))
  (let [{tool-name :name
         arg-map   :arguments} params]
    (try
      (->> (mcp/call-tool server tool-name arg-map)
           (format-tool-result)
           (json-rpc/result id))
      (catch Exception ex
        (json-rpc/error id (format-tool-error (ex-data ex)))))))

;; Main request dispatcher
(defn handle-request
  "Just a router for AServer.
  Each dispatch may return aa map (single message), or a collection of sequential messages."
  [mcp-server, {:as request :keys [id method params]}]
  ; todo move out JSON RPC stuff to wire format.
  ;(log/debug "Dispatching request method:" method)
  (try
    (case method
      "ping" (do
               ; we deal with ping here not in server. todo: liveness checks.
               (log/debug "Handling ping request with id:" id)
               (json-rpc/result id {}))

      "tools/call" (handle-tool-call-request mcp-server request) ; todo: run invoke in future / thread.

      "initialize" [(json-rpc/result id (mcp/initialize mcp-server))
                    ; not a fan of this method business.
                    (json-rpc/method "notifications/initialized")]

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

;; Check if a message is a notification (has method but no id)
(defn notification? [{:as _message :keys [method id]}]
  (and method (not id)))

(defn handle-notification
  "We don't need to do anything special for notifications right now.
  Just log them and continue."
  [notification]
  (let [method (:method notification)]
    (log/debug "Received notification:" method)
    nil))

(defn handle-message
  "Returns a JSON-RPC message."
  [server message]
  (try
    (log/debug "Handling message: " (pr-str message))
    (cond
      ; Log errors:
      (:error message)
      (log/debug "Error message: " message)

      ;; Handle requests (have method & id)
      (and (:method message) (:id message))
      (handle-request server message)

      ; Notification (has method, no id)
      (notification? message)
      (do
        (log/debug "Handling notification:" (:method message))
        (handle-notification message))

      ;; Unknown message type (we just log it)
      :else
      (do (log/debug "Unknown message type:" message)
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
     (loop []
       (log/debug "Waiting for request...")
       (let [message (read-json-rpc-message reader)]
         (if-not message
           (do ; we exit here.
             (log/debug "Reader returned nil, client probably disconnected"))

           (let [?responses (handle-message server message)]
             (when ?responses
               (log/debug "Responding with messages: " (pr-str ?responses)))
             (if (map? ?responses)                          ; map = single message
               (write-json-rpc-message writer ?responses)
               (if (seq ?responses)                         ; if seq/coll, send multiple messages sequentially. Todo: make async.
                 (doseq [response ?responses]
                   (write-json-rpc-message writer response))))
             (recur)))))
     (log/debug "Exiting.")
     (catch Exception e
       (log/debug "Critical error in server:" (.getMessage e))
       (.printStackTrace e (java.io.PrintWriter. *err*))))))