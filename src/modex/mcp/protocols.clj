(ns modex.mcp.protocols)

(defprotocol ATool
  (call [this arguments]))

(defprotocol AResource)

(defprotocol APrompt)

(defprotocol AServer
  (protocol-version [this])
  (capabilities [this])

  (list-tools [this])
  (call-tool [this name args])

  (list-resources [this])
  (list-prompts [this]))

(defrecord TestServer [tools]
  AServer
  (protocol-version [this])
  (capabilities [this])

  (list-tools [this] tools)
  (call-tool [this name args])

  (list-resources [this])
  (list-prompts [this]))