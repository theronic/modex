(ns modex.mcp.tools
  (:require [modex.mcp.schema :as schema]
            [modex.mcp.json-rpc :as json-rpc]
            [taoensso.timbre :as log]))

(defrecord ToolBelt [tools])

(defprotocol IToolBelt
  (list-tools [this] "Returns a collection of supported tool names and metadata.")
  (call-tool [this tool-name args] "Invokes the named tool with args."))

(extend-protocol IToolBelt
  ToolBelt
  (call-tool [this tool-name args]
    (if-let [tool-info (get (:tools this) tool-name)]
      (apply (:fn tool-info) args)
      (throw (ex-info (str "Unknown tool: " tool-name) {:tool-name tool-name
                                                        :available (keys (:tools this))}))))

  (list-tools [this]
    (map (fn [[tool-name info]]
           {:name tool-name
            :doc  (:doc info)
            :args (:args info)})
         (:tools this))))

(defprotocol ATool
  (doc-string [this])
  (return-type [this]) ; e.g. :text, :number
  (arguments [this])
  (call [this arguments]))

(defmacro tools [& tool-defs]
  (let [tools# (map (fn [[t-name# & body#]]
                      (let [t-key# (keyword t-name#)

                            ;; Handle optional docstring
                            [docstring# rest-body#] (if (string? (first body#))
                                                      [(first body#) (rest body#)]
                                                      [(str t-name#) body#])

                            ;; Get args vector and function body
                            args-vec# (first rest-body#)
                            fn-body# (rest rest-body#)

                            ;; Process args for metadata
                            arg-info# (vec
                                        (for [arg# args-vec#]
                                          (let [m# (meta arg#)]
                                            {:name (str (symbol arg#))
                                             :doc (or (:doc m#) (str (symbol arg#)))
                                             :type (or (:type m#) :text)})))]

                        ; this should construct a Tool record that has details about name, doc, return type and argument types.
                        [t-key# {:fn   `(fn ~args-vec# ~@fn-body#)
                                 :doc  docstring#
                                 :args arg-info#}]))
                    tool-defs)
        tool-map# (into {} tools#)]
    tool-map#))

(comment (macroexpand '(tools
                         (add [a b] (+ a b)))))

(defmacro deftools [name & tool-defs]
  `(def ~name (tools ~@tool-defs)))

(comment
  (macroexpand '(deftools my-tools
                          (add [a b] (+ a b)))))

;; old stuff:

(defn handle-inc-tool [{:as _request, :keys [id params]}]
  (let [{args :arguments} params
        {x :x} args]
    (if (number? x)
      (json-rpc/result id {:content          [{:type "text"          ; not sure if 'number' type is supported.
                                               :text (str (inc x))}]
                           :isError false})
      (json-rpc/error id {:code schema/error-parse :message "Pass a number as argument x to inc."}))))

(defn handle-tools-call [{:as request, :keys [id params]}]
  (log/debug "Handling tools/call request with id:" id "for tool:" (:name params))
  (let [{tool-name :name} params]
    (case tool-name
      "inc" (handle-inc-tool request)
      "foo" (json-rpc/result id {:content          [{:type "text"
                                                     :text "Hello, AI!"}]
                                 :isError false})
      (json-rpc/error id {:code             schema/error-invalid-params ; todo handle throw.
                          :message (str "Unknown tool: " tool-name)}))))

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

(defrecord IncTool []
  ATool
  (call [this {:as _args :keys [x]}] (inc x)))