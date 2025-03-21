(ns modex.mcp.core
  (:require [modex.mcp.protocols :as mcp]
            [modex.mcp.server :as server]
            [modex.mcp.tools :as tools]
            [clojure.core :as cc]
            [taoensso.timbre :as log])
  (:gen-class))

(def !server-ready? (atom false))

(def my-tools
  "Define your tools here."
  (tools/tools
    (greet
      "Greets a person by name."
      [^{:type :string :doc "A person's first name." :required true} first-name
       ^{:type :string :doc "A person's last name (optional)." :required false} last-name]
      [(str "Hello from Modex, " (if last-name ; args can optional
                                   (str first-name " " last-name)
                                   first-name) "!")])

    (inc
      "Increments a number."
      [^{:type :number :doc "x is a number to increment."} x]
      [(cc/inc x)])

    (range
      "Calls (range n) in Clojure and returns a seq."       ; demonstrates seq returns
      [^{:type :number :doc "(range n)"} n]
      (cc/range n))

    (add "Adds two numbers."
         [^{:type :number, :doc "1st Number"} a
          ^{:type :number, :doc "2nd number"} b]
         [(+ a b)])))

(comment
  ((fnil :required true) {:required nil})
  (tools/invoke-tool (get my-tools :range) {:n 5})
  (tools/invoke-tool (get my-tools :greet) {:first-name "Petrus"})
  (tools/invoke-tool (get my-tools :add) {:a 10 :b 6}))

(defn init-server
  "Blocking init function for long-running I/O like connecting to remote databases.
  Modex will notify the MCP client when ready.

  Here we simulate a DB connect call that takes 1 second.

  With no delay, notification/initialized can be sent before the init request, but it still works. Will fix."
  []
  (Thread/sleep 1000)
  (reset! !server-ready? true))

(def my-mcp-server
  "Here we create a reified instance of AServer. Only tools are presently supported."
  (server/->server
    {:name       "Modex MCP Server"
     :version    "0.0.1"
     :initialize init-server
     :tools      my-tools
     :prompts    nil
     :resources  nil}))

(comment
  "You can init server (this will call init-server):"
  (mcp/initialize my-mcp-server)

  "You can list tools:"
  (mcp/list-tools my-mcp-server)

  "You can invoke a tool with:"                                      ; will probably rename to invoke.
  (mcp/call-tool my-mcp-server :greet {:name "Petrus"}))

(defn -main
  "Starts an MCP server that talks JSON-RPC over stdio/stdout."
  [& args]
  (log/debug "Server starting via -main")
  (try
    (server/start-server! my-mcp-server)
    (catch Throwable t
      (log/debug "Fatal error in -main:" (.getMessage t))
      (.printStackTrace t (java.io.PrintWriter. *err*)))))
