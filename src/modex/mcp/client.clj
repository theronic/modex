(ns modex.mcp.client
  "MCP client implementation using the stdio transport mechanism."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io])
  (:gen-class))

;; State to store the subprocess and IO streams
(defonce ^:private state (atom nil))

;; Logging helper
(defn log [& args]
  (binding [*out* *err*]
    (apply println args)
    (flush)))

;; JSON-RPC helpers
(defn- send-request [writer request-id method & [params]]
  (let [request {:jsonrpc "2.0"
                 :id request-id
                 :method method}
        request (if params (assoc request :params params) request)
        json-str (json/generate-string request)]
    (log "Sending request:" json-str)
    (.write writer (str json-str "\n"))
    (.flush writer)))

(defn- send-notification [writer method & [params]]
  (let [notification {:jsonrpc "2.0"
                      :method method}
        notification (if params (assoc notification :params params) notification)
        json-str (json/generate-string notification)]
    (log "Sending notification:" json-str)
    (.write writer (str json-str "\n"))
    (.flush writer)))

(defn- read-response [reader request-id]
  (loop []
    (let [line (.readLine reader)]
      (if (nil? line)
        (do
          (log "Server closed connection")
          {:error "Server closed connection"})
        (let [response (json/parse-string line true)]
          (log "Received response:" line)
          (cond
            ;; If it's a notification, process it and continue
            (and (= (:jsonrpc response) "2.0") (:method response))
            (do
              (log "Received notification:" response)
              (recur))

            ;; If it's the response we're waiting for
            (= (:id response) request-id)
            response

            ;; Otherwise, continue reading
            :else
            (recur)))))))

;; Client API
(defn start-client
  "Launches the MCP server as a subprocess and establishes the stdio transport connection."
  [server-command]
  (log "Starting client with command:" server-command)
  (try
    (let [process (if (string? server-command)
                    (.exec (Runtime/getRuntime) server-command)
                    (.exec (Runtime/getRuntime)
                           (into-array String server-command)))
          in (io/reader (.getInputStream process))  ; Server's stdout -> Client's input
          out (io/writer (.getOutputStream process)) ; Client's output -> Server's stdin
          err (io/reader (.getErrorStream process))]

      (reset! state {:process process
                     :in in
                     :out out
                     :err err})

      ;; Start a thread to log server stderr output
      (future
        (try
          (loop []
            (when-let [line (.readLine err)]
              (println "Server stderr:" line)
              (recur)))
          (catch Exception e
            (println "Error reading server stderr:" (.getMessage e)))))

      ;; Return the client state
      @state)
    (catch Exception e
      (log "Error starting client:" (.getMessage e))
      (.printStackTrace e (java.io.PrintWriter. *err*))
      nil)))

(defn stop-client
  "Stops the client by closing all streams and terminating the server subprocess."
  []
  (log "Stopping client")
  (when-let [{:keys [process in out err]} @state]
    (try
      (.close out)  ; Close stdin to the server process
      (.close in)
      (.close err)

      ;; Wait for the process to exit naturally
      (let [exited (future (.waitFor process 2 java.util.concurrent.TimeUnit/SECONDS))]
        (if (deref exited 2000 false)
          ;; Process exited naturally
          (log "Server process exited naturally")
          ;; Process didn't exit, so destroy it
          (do
            (log "Server process did not exit, destroying it")
            (.destroy process))))

      (reset! state nil)
      (catch Exception e
        (log "Error stopping client:" (.getMessage e))
        (.printStackTrace e (java.io.PrintWriter. *err*))))))

(defn initialize [protocol-version]
  (log "Initializing client with protocol version:" protocol-version)
  (let [{:keys [in out]} @state
        request-id 1
        params {:protocolVersion protocol-version
                :capabilities {:sampling {}}
                :clientInfo {:name "MCP Hello World Client"
                             :version "1.0.0"}}]
    (send-request out request-id "initialize" params)
    (let [response (read-response in request-id)]
      ;; Send initialized notification
      (when-not (:error response)
        (send-notification out "notifications/initialized"))
      response)))

(defn list-tools []
  (log "Listing tools")
  (let [{:keys [in out]} @state
        request-id 2]
    (send-request out request-id "tools/list")
    (read-response in request-id)))

(defn call-tool [tool-name & [arguments]]
  (log "Calling tool:" tool-name)
  (let [{:keys [in out]} @state
        request-id 3
        params {:name tool-name}
        params (if arguments (assoc params :arguments arguments) params)]
    (send-request out request-id "tools/call" params)
    (read-response in request-id)))

;; Helper function to start the server and initialize the client
(defn connect-to-server [server-command protocol-version]
  (log "Connecting to server with command:" server-command)
  (start-client server-command)
  (let [init-result (initialize protocol-version)]
    (if (:error init-result)
      (do
        (log "Initialization failed:" (:error init-result))
        (stop-client)
        {:error (:error init-result)})
      init-result)))

(defn -main [& args]
  (log "Client starting via -main")
  (let [server-command (or (first args) "clojure -M:run-server")]
    (try
      (let [connection (connect-to-server server-command "2024-11-05")]
        (if (:error connection)
          (println "Failed to connect:" (:error connection))
          (do
            (println "Connected successfully. Server info:"
                     (get-in connection [:result :serverInfo]))
            (let [tools-result (list-tools)]
              (println "Available tools:" (get-in tools-result [:result :tools]))
              (let [call-result (call-tool "foo")]
                (println "Tool response:"
                         (get-in call-result [:result :content 0 :text])))))))
      (finally
        (stop-client)))))