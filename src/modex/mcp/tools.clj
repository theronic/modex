(ns modex.mcp.tools
  (:require [modex.mcp.schema :as schema]
            [clojure.string :as string]
            [modex.mcp.json-rpc :as json-rpc]
            [taoensso.timbre :as log]))

(defrecord Parameter [name doc type required])

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
    [(map->Parameter {:name   :name
                      :doc      "Person's name"
                      :type     :text
                      :required true})
     (map->Parameter {:name   :x
                      :doc      "Person's Age (optional)"
                      :type     :number
                      :required false})]))

(defrecord Tool [name doc args handler]
  ITool
  (required-args [this] (->> (filter :required args)
                             (mapv :name)))
  (input-schema [^Tool this]
    {:type       "object" ; object = map.
     :required   (required-args this) ; strings?
     :properties (tool-args->input-schema args)}))

(comment
  (let [tool (->Tool :foo "test"
                     [(map->Parameter {:name   :name
                                       :doc      "Person's name"
                                       :type     :text
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
                     [(map->Parameter {:name   :name
                                       :doc      "Person's name"
                                       :type     :text
                                       :required true})]
                     (fn [name] (str "Hi there, " name "!")))]
    (invoke-tool tool {:name "Petrus"})))

(defmacro tool [[tool-name & tool-body]]
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
                                 :doc      (or (:doc m#) (str arg#))
                                 :type     (or (:type m#) :text)
                                 :required ((fnil :required true) m#)}))))]
       (->Tool ~tool-key# ~docstring# arg-info# (fn ~args-vec# ~@fn-body#)))))

(defmacro tools
  "Returns a map of tool name => Tool.

  Syntax like defrecord, but supports metadata with {:keys [type doc]} for each argument:

  (tools
    (add \"Adds two numbers, a & b.\"
      [^{:type :number, :doc \"First Number\"} a b] (+ a b))
    (subtract [x y] (+ x z)))"
  [& tool-defs]
  `(let [tools# (vector ~@(map (fn [tool-def] `(tool ~tool-def)) tool-defs)) ; there is better way
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