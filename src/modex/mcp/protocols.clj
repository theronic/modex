(ns modex.mcp.protocols)

(defprotocol AResource)

(defprotocol APrompt)

; todo: move JSON specific stuff out to a JSON-RPCWireFormat thing.
(defprotocol AServer
  (protocol-version [this])
  (server-name [this])
  (version [this])

  (on-receive [this msg] "For testing receive.")
  (on-send [this msg] "For testing sent messages.")
  (enqueue-notification [_this msg] "For testing notifications. Will collapse into an async bus lands.")

  (send-notification [this notification] "Called after a has been sent.")

  (capabilities [this])
  (initialize [this])

  (list-tools [this])
  (call-tool [this tool-name arg-map])

  (list-resources [this])
  (list-prompts [this]))