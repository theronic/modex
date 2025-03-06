(ns modex.mcp.server
  (:require [jsonista.core :as json]
            [taoensso.timbre :as log]
            [reitit.core :as r]
            [modex.mcp.json-rpc :as json-rpc]
            [modex.mcp.protocols :as p :refer [AServer]])
  (:gen-class))

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
(def error-code-parse-error -32700)
(def error-code-invalid-request -32600)
(def error-code-method-not-found -32601)
(def error-code-invalid-params -32602)
(def error-code-internal-error -32603)

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

(def inc-tool
  {:name        "inc"
   :description "A simple tool that increments a number."
   :inputSchema {:type       "object"
                 :properties {:x {:type "number"}}
                 ; require has to be a string, because arrays are not coerced to keywords.
                 :required   ["x"]}})

(defn read-json-rpc-message
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
      {:error {:code    error-code-parse-error
               :message "Parse error"}})))

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
(defn handle-initialize [server {:keys [id params]}]
  (log/debug "Handling initialize request with id:" id)
  [(json-rpc/result id {:protocolVersion (:protocolVersion params)
                        :capabilities    server-capabilities
                        :serverInfo      server-info})
   (json-rpc/method "notifications/initialized")])

(defn handle-tools-list [server {:keys [id]}]
  (log/debug "Handling tools/list request with id:" id)
  (json-rpc/result id {:tools [foo-tool inc-tool]}))

(defn handle-prompts-list [server {:keys [id]}]
  (log/debug "Handling prompts/list request with id:" id)
  (json-rpc/result id {:prompts []}))

(defn handle-resources-list [server {:keys [id]}]
  (log/debug "Handling resources/list request with id:" id)
  (json-rpc/result id {:resources []}))

(defn handle-inc-tool [server {:as _request, :keys [id params]}]
  (let [{args :arguments} params
        {x :x} args]
    (if (number? x)
      (json-rpc/result id {:content [{:type "text"          ; not sure if 'number' type is supported.
                                      :text (str (inc x))}]
                           :isError false})
      (json-rpc/error id {:code error-code-parse-error :message "Pass a number as argument x to inc."}))))

(defn handle-tools-call [server {:as request, :keys [id params]}]
  (log/debug "Handling tools/call request with id:" id "for tool:" (:name params))
  (let [{tool-name :name} params]
    (case tool-name
      "inc" (handle-inc-tool server request)
      "foo" (json-rpc/result id {:content [{:type "text"
                                            :text "Hello, AI!"}]
                                 :isError false})
      (json-rpc/error id {:code    error-code-invalid-params
                          :message (str "Unknown tool: " tool-name)}))))

(defn handle-ping [{:keys [id]}]
  (log/debug "Handling ping request with id:" id)
  (json-rpc/result id {}))

;; Main request dispatcher
(defn handle-request [mpc-server, {:as request :keys [id method]}]
  (log/debug "Dispatching request method:" method)
  (case method
    "initialize" (handle-initialize mpc-server request)
    "tools/list" (handle-tools-list mpc-server request)
    "tools/call" (handle-tools-call mpc-server request)
    "prompts/list" (handle-prompts-list mpc-server request)
    "resources/list" (handle-resources-list mpc-server request)
    "ping" (handle-ping #_mpc-server request)
    (do
      (log/debug "Unknown method:" method)
      (json-rpc/error id {:code    error-code-method-not-found
                          :message (str "Method not found: " method)}))))

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
  [server message]                                                 ; WIP: factoring out writer.
  (log/debug "Handling message: " (pr-str message))
  (cond
    ; Log errors:
    (:error message)
    (log/debug "Error message: " message)

    ;; Handle requests (have method & id)
    (and (:method message) (:id message))
    (handle-request server message)

    ; Notification (no method, only id)
    (notification? message)
    (do
      (log/debug "Handling notification:" (:method message))
      (handle-notification message))

    ;; Unknown message type (we just log it)
    :else
    (do (log/debug "Unknown message type:" message)
        nil)))

(defn start-server!
  "Main server loop – supports *in* & *out* bindings."
  ([mcp-handler] (start-server! mcp-handler *in* *out*))
  ([mcp-handler reader writer]
   (log/debug "Starting Modex MCP server...")
   (try
     (loop []
       (log/debug "Waiting for request...")
       (let [message (read-json-rpc-message reader)]
         (if-not message
           (do (log/debug "Reader returned nil, client probably disconnected"))

           (let [?responses (handle-message mcp-handler message)]
             (log/debug "Responding with messages: " (pr-str ?responses))
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

(defn -main [& args]
  (log/debug "Server starting via -main")
  (try
    (let [mcp-handler (p/->TestServer [foo-tool inc-tool])]
      (start-server! mcp-handler))
    (catch Throwable t
      (log/debug "Fatal error in -main:" (.getMessage t))
      (.printStackTrace t (java.io.PrintWriter. *err*)))))