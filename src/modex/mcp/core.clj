(ns modex.mcp.core
  (:require [modex.mcp.protocols :as mcp]
            [modex.mcp.server :as server]
            [modex.mcp.tools :as tools]
            [clojure.core :as cc]
            [taoensso.timbre :as log])
  (:gen-class))

(def my-tools
  "Define your tools here."
  (tools/tools
    (greet
      "Greets the user. Takes name"
      [^{:type :text :doc "A person's name."} name]
      (str "Hello from Modex, " name "!"))

    (inc
      "Increments a number."
      [^{:type :number :doc "x is a number to increment."} x]
      (cc/inc x))

    (add "Adds two numbers."
         [^{:type :number, :doc "1st Number"} a
          ^{:type :number, :doc "2nd number"} b]
         (+ a b))))

(comment
  (tools/invoke-tool (get my-tools :greet) {:name "Petrus"})
  (tools/invoke-tool (get my-tools :add) {:a 10 :b 6}))

(def my-mcp-server
  "Here we create a reified instance of AServer. Only tools are presently supported."
  (server/->server
    {:name      "Modex MCP Server"
     :version   "0.0.1"
     :tools     my-tools
     :prompts   nil
     :resources nil}))

(comment
  "You can list tools:"
  (mcp/list-tools my-mcp-server)

  "You can call-tool:" ; will probably rename to invoke.
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