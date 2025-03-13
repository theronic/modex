(ns modex.mcp.core
  (:require [modex.mcp.server :as server]
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
      (str "Hello, " name "!"))

    (ping [^{:type :text :doc "IPv4 Address"} ip-addr] (prn "todo ping"))
    (inc
      "Increments a number."
      [^{:type :number :doc "x is a number to increment."} x]
      (cc/inc x))))

;(tools/invoke-tool (get my-tools :greet) {:name "Andre"})

(def my-mcp-server
  "Here we create a reified instance of AServer. Only tools are presently supported."
  (server/->server
    {:name      "Modex MCP Server"
     :version   "0.0.1"
     :tools     my-tools
     :prompts   nil
     :resources nil}))

;(mcp/list-tools my-mcp-server)

(defn -main
  "Todo: handle args for starting client or server, and which servers to start."
  [& args]
  (log/debug "Server starting via -main")
  (try
    (server/start-server! my-mcp-server)
    (catch Throwable t
      (log/debug "Fatal error in -main:" (.getMessage t))
      (.printStackTrace t (java.io.PrintWriter. *err*)))))