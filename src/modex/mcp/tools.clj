(ns modex.mcp.tools
  (:require [modex.mcp.schema :as schema]
            [clojure.string :as string]
            [modex.mcp.json-rpc :as json-rpc]
            [taoensso.timbre :as log]))

(defrecord Parameter [name doc type required default])

; todo: move to protocols
(defprotocol ITool
  (required-args [this])
  (input-schema [this]))

(defn tool-arg->property
  [^Parameter tool-arg]
  (select-keys tool-arg [:type :doc :required]))

(defn tool-args->input-schema [args]
  (into {}
        (for [tool-arg args]
          [(:name tool-arg) (tool-arg->property tool-arg)])))

(comment
  (tool-args->input-schema
    [(map->Parameter {:name     :name
                      :doc      "Person's name"
                      :type     :string
                      :required true})
     (map->Parameter {:name     :x
                      :doc      "Person's Age (optional)"
                      :type     :number
                      :required false})]))

(defrecord Tool [name doc args handler]
  ITool
  (required-args [this] (->> (filter :required args)
                             (mapv :name)))
  (input-schema [^Tool this]
    {:type       "object"                                   ; object = map.
     :required   (required-args this)                       ; strings?
     :properties (tool-args->input-schema args)}))

(comment
  (let [tool (->Tool :foo "test"
                     [(map->Parameter {:name     :name
                                       :doc      "Person's name"
                                       :type     :string
                                       :required true})]
                     (fn [name] (str "Hi there, " name "!")))]
    [(required-args tool)
     (input-schema tool)]))

(defn missing-elements [required passed]
  (remove (set passed) (set required)))

(comment
  (missing-elements #{:a :b} [:a]))

(defn invoke-tool
  "Given a Tool & a map of arguments, arranges arguments and applies to handler in same thread.
  Returns a vector with [data ?error].
  ; TODO: check missing required vs. optional args.
  ; TODO: Malli schema validation.
  "
  ; ok big todo is to return correct error for missing parameters.
  [^Tool {:as _tool :keys [handler args]}, arg-map]
  ;(log/debug "arg-map:" arg-map)
  (let [required-args    (filter #(true? (:required %)) args)
        required-key-set (set (map :name required-args))
        ;_ (log/debug "required keys:" required-key-set)
        missing-args     (missing-elements required-key-set (keys arg-map))
        _                (when (seq missing-args) (log/debug "missing:" missing-args))]
    ; todo: switch to Malli schemas for required.
    (if (seq missing-args)
      [nil [{:missing-tool-parameters missing-args}]]
      (let [arg-vec (->> (map :name args)
                         (map arg-map))
            results (apply handler arg-vec)]
        [results nil]))))
;(comment
;  _ (assert (empty? missing-args)
;            (str "Missing tool parameters: " (string/join "," missing-args)))))
;(log/debug "arg-vec:" arg-vec)
;(log/debug "handler" handler)


(comment
  (let [tool (->Tool :foo "test"
                     [(map->Parameter {:name     :name
                                       :doc      "Person's name"
                                       :type     :string
                                       :required true})]
                     (fn [name] (str "Hi there, " name "!")))]
    (invoke-tool tool {:name "Petrus"})))

(def mcp-meta-keys [:type :doc :required])

(defn extract-mcp-meta
  "Extracts MCP tool metadata from a {:keys [...]} map that has :type, :doc & :required keys.
  Called by tool handler macros."
  [m]
  {:pre [(map? m)]}
  (select-keys m mcp-meta-keys))

(defn argmap
  "Takes a {:keys [...]} argument map like fn but extracts :type, :doc & :required to metadata.
  Called by tool handler macros."
  [m]
  {:pre [(map? m)]}
  (let [mcp-meta (select-keys m mcp-meta-keys)]
    (with-meta (apply dissoc m mcp-meta-keys) mcp-meta)))

(defmacro handler
  "Like fn but returns a fn with :mcp metadata, extracted from a single map argument for MCP Tool construction.
  Call meta on fn to see :mcp keys extracted from the :keys destructuring.
  Refer to tool-macro-tests."
  ; todo use Malli schema for :type validation.
  [[map-arg] & body]
  {:pre [(map? map-arg)]}
  (let [mcp-meta   (extract-mcp-meta map-arg)
        clean-args [(apply dissoc map-arg mcp-meta-keys)]]
    `(do (assert (every? #{:string :number} (vals (:type '~mcp-meta))) ":type must be one of :number or :string.")
         (with-meta (fn ~clean-args ~@body)
                    {:mcp '~mcp-meta}))))

(defmacro tool-v1
  "Deprecated. Superseded by tool-v2-argmap, but still supported via tool."
  [[tool-name & tool-body]]
  (let [tool-key# (keyword tool-name)

        ;; Handle optional docstring
        [docstring# rest-body#] (if (string? (first tool-body))
                                  [(first tool-body) (rest tool-body)]
                                  [(str tool-name) tool-body])

        ;; Get args vector and function body
        args-vec# (first rest-body#)
        fn-body#  (rest rest-body#)]

    ;; Return a quasiquoted form that will be evaluated at runtime
    `(let [arg-info# (vec (for [arg# '~args-vec#]
                            (let [m# (meta arg#)]
                              (map->Parameter
                                {:name     (keyword arg#)
                                 :doc      (get m# :doc (str arg#))
                                 :type     (get m# :type :string)
                                 :required (get m# :required true)}))))]
       (->Tool ~tool-key# ~docstring# arg-info# (fn ~args-vec# ~@fn-body#)))))

(defmacro tool-v2-argmap
  "Supersedes tool-v1. Expects map of handler args.

  Usage:
  (tool-v2-argmap
    (greet [{:keys [name age]
             :doc  {name \"A person's name.\"}
             :type {name :string
                    age  :number}
             :or   {age 37}] ; presence in :or implies optional arg.
       (str \"Hi \" name \"! You are \" age \" years old :)\"))"
  [[tool-name & tool-body]]
  (let [tool-key#  (keyword tool-name)

        ;; Handle optional docstring
        [docstring# rest-body#] (if (string? (first tool-body))
                                  [(first tool-body) (rest tool-body)]
                                  [(str tool-name) tool-body])

        ;; Get args vector and function body
        args-vec#  (first rest-body#)
        fn-body#   (rest rest-body#)

        ;; Extract the map from the vector (assuming args-vec# is a vector with a single map)
        args-map#  (first args-vec#)

        ;; Extract the keys vector from the map
        keys-vec#  (get args-map# :keys [])

        ;; Extract metadata maps at compile time
        type-map#  (get args-map# :type {})
        doc-map#   (get args-map# :doc {})
        or-map#    (get args-map# :or {})
        or-keyset# (set (keys or-map#))]                     ; not required if key present in :or, even if nil.

    ;; Return a Tool instance with a handler function and parameter info
    `(let [arg-info#   (vec (for [k# '~keys-vec#]
                              (map->Parameter
                                (cond-> {:name     (keyword k#)
                                         :doc      (get '~doc-map# k# (str k#))
                                         :type     (get '~type-map# k# :string)
                                         :required (let [has-default?# (get '~or-keyset# k#)]
                                                     (boolean (not has-default?#)))
                                         :default  (get '~or-map# k#)}))))

           handler-fn# (handler ~args-vec# ~@fn-body#)]
       (->Tool ~tool-key# ~docstring# arg-info# handler-fn#))))

;; 'tool' macro dispatches to either tool1 or tool2
(defmacro tool [[tool-name & tool-body]]
  (let [;; Handle optional docstring
        [_docstring# rest-body#] (if (string? (first tool-body))
                                   [(first tool-body) (rest tool-body)]
                                   [(str tool-name) tool-body])
        args# (first rest-body#)]
    ;; v2 uses map. v1 parses vector w/metadata.
    (if (map? (first args#))
      `(tool-v2-argmap [~tool-name ~@tool-body])
      `(tool-v1 [~tool-name ~@tool-body]))))

(defmacro tools
  "Returns a map of tool name => Tool.

  Syntax like defrecord:
  ```
  (tools
    (add \"Adds two numbers, a & b.\"
         [{:keys [a b]
           :type {a :number
                  b :number}}
         (+ a b)])
    (subtract [x y] (- x y)))
  ```"
  [& tool-defs]
  `(let [tools#    (vector ~@(map (fn [tool-def] `(tool ~tool-def)) tool-defs)) ; there is better way
         tool-map# (into {} (for [tool# tools#]
                              [(:name tool#) tool#]))]
     tool-map#))

(comment
  (tools
    (add [a b] (+ a b)))

  (->Tool)
  (macroexpand '(tools
                  (add [a b] (+ a b)))))

(defmacro deftools
  "Just calls tools and binds to a symbol via def. Of dubious merit."
  [name & tool-defs]
  `(def ~name (tools ~@tool-defs)))

(defn tool->json-schema
  "Builds MCP-compatible {:keys [name description inputSchema]},
  where inputSchema has {:keys [type required properties]},
  where properties has each tool, and required is list of arg names."
  [^Tool
   {:as tool :keys [name doc]}]
  {:name        name
   :description doc
   :inputSchema (input-schema tool)})