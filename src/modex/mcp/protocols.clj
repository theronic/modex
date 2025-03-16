(ns modex.mcp.protocols
  (:require [modex.mcp.schema :as schema]
            [modex.mcp.tools :as mcp.tools]))

(defprotocol AResource)

(defprotocol APrompt)

; todo: move JSON specific stuff out to a JSON-RPCWireFormat thing.
(defprotocol AServer
  (protocol-version [this])
  (server-name [this])
  (version [this])

  (on-receive [this msg] "For testing receive.")
  (on-send [this msg] "For testing notifications.")

  (send-notification [this notification])

  (capabilities [this])
  (initialize [this])

  (list-tools [this])
  (call-tool [this tool-name arg-map])

  (list-resources [this])
  (list-prompts [this]))

;(defn check-arg-types
;  "Validates input argument types using the tool's argument metadata.
;   Returns nil if valid, otherwise returns an error message."
;  [tool args]
;  (try
;    (doseq [arg-spec (:args tool)]
;      (let [arg-name  (keyword (:name arg-spec))
;            arg-value (get args arg-name)
;            arg-type  (:type arg-spec)]
;        (when (nil? arg-value)
;          (throw (ex-info (str "Missing required argument: " arg-name)
;                          {:arg-name arg-name
;                           :arg-spec arg-spec})))
;
;        ;; Basic type checking - can be expanded with Malli in the future
;        (when-not (case arg-type
;                    :number (number? arg-value)
;                    :text (or (string? arg-value) (nil? arg-value))
;                    true)                                   ;; Default to true for unknown types
;          (throw (ex-info (str "Type mismatch for argument " arg-name ": expected " arg-type)
;                          {:arg-name      arg-name
;                           :arg-value     arg-value
;                           :expected-type arg-type})))))
;    nil
;    (catch Exception e
;      {:error (ex-message e)
;       :data  (ex-data e)})))

; this is outdated
(defn create-toolset
  "Create a toolset from tool definitions. This version of the function allows
   for direct invocation from code (not just the macro)."
  [& tool-defs]
  (if (and (= 1 (count tool-defs)) (map? (first tool-defs)))
    ;; Handle case where a map is passed directly (from macro expansion)
    (first tool-defs)
    ;; Otherwise, process the tool definitions
    (into {} (apply concat tool-defs))))

;; Stub implementations for resources and prompts

(comment
  (let [server (->server {:tools (mcp.tools/tools
                                   (add [a b] (+ a b))
                                   (subtract [a b] (- a b)))})]
    ;(list-tools server)
    (call-tool server :add {:a 10 :b 5})))