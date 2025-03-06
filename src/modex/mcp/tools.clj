(ns modex.mcp.tools
  (:require [modex.mcp.protocols :as p :refer [ATool]]
            [modex.mcp.schema :as schema]
            [modex.mcp.json-rpc :as json-rpc]
            [taoensso.timbre :as log]))

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