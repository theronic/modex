(ns modex.mcp.protocols
  (:require [modex.mcp.schema :as schema]
            [modex.mcp.tools :as tools]))

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

(defn arrange-arguments [{:as tool :keys [args]} arg-map]
  (let [args arg-map] ; todo: arrange args from arg-map based on tool argument definitions so that they can be applied to tool.
    args))

(defn check-arg-types
  "TODO: this should validate input argument types using Malli."
  [tool args]
  (throw (Exception. "not implemented.")))

(defn ->server
  [{:keys [protocol-version
           tools resources prompts]
    :or {protocol-version schema/latest-protocol-version}}]

  ; todo: put tools in a map for easy data lookup by tool name.
  ; todo: maintain a mapping between argument map and argument order so call a tool.
  ; todo: check tool arg types in call-tool.

  (reify AServer
    (protocol-version [this] protocol-version)

    (capabilities [this]
      {:tools     {:listChanged (boolean (seq tools))}
       :resources {:listChanged (boolean (seq resources))}
       :prompts   {:listChanged (boolean (seq prompts))}})

    (list-tools [this] (tools/list-tools tools))

    (call-tool [this name argument-map]
      (let [tool nil ; todo: (lookup-tool-by-name name)
            args (arrange-arguments tool argument-map)])
      ; here we have to unpack arguments for passing to tool
      ; ; todo bug
      (tools/call-tool tools name nil))

    ; todo: implement Resources & Prompts.
    (list-resources [this])
    (list-prompts [this])))