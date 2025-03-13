(ns modex.mcp.schema
  (:require [malli.core :as m]))

;;; Constants
(def latest-protocol-version "2024-11-05")
(def json-rpc-version "2.0")

;; Standard JSON-RPC error codes
(def error-parse -32700)
(def error-invalid-request -32600)
(def error-method-not-found -32601)
(def error-invalid-params -32602)
(def error-internal -32603)

;;; Basic Types
;; A progress token used to associate progress notifications with the original request
(def progress-token [:or string? number?])

;; An opaque token used to represent a cursor for pagination
(def cursor string?)

;; A uniquely identifying ID for a request in JSON-RPC
(def request-id [:or string? number?])

;; Role for messages and data in a conversation
(def role [:enum "user" "assistant"])

;; Logging levels
(def logging-level
  [:enum "debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"])

;;; JSON-RPC Message Types
(def request
  [:map
   [:method string?]
   [:params {:optional true}
    [:map
     [:_meta {:optional true}
      [:map
       [:progressToken {:optional true} progress-token]
       [:* [:map-of string? any?]]]]
     [:* [:map-of string? any?]]]]])

(def notification
  [:map
   [:method string?]
   [:params {:optional true}
    [:map
     [:_meta {:optional true} [:map-of string? any?]]
     [:* [:map-of string? any?]]]]])

(def result
  [:map
   [:_meta {:optional true} [:map-of string? any?]]
   [:* [:map-of string? any?]]])

(def jsonrpc-request
  [:map
   [:jsonrpc [:= json-rpc-version]]
   [:id request-id]
   [:method string?]
   [:params {:optional true} [:map]]])

(def jsonrpc-notification
  [:map
   [:jsonrpc [:= json-rpc-version]]
   [:method string?]
   [:params {:optional true} [:map]]])

(def jsonrpc-response
  [:map
   [:jsonrpc [:= json-rpc-version]]
   [:id request-id]
   [:result result]])

(def jsonrpc-error
  [:map
   [:jsonrpc [:= json-rpc-version]]
   [:id request-id]
   [:error
    [:map
     [:code number?]
     [:message string?]
     [:data {:optional true} any?]]]])

(def jsonrpc-message
  [:or
   jsonrpc-request
   jsonrpc-notification
   jsonrpc-response
   jsonrpc-error])

;;; Empty Result
(def empty-result result)

;;; Cancellation
(def cancelled-notification
  [:map
   [:method [:= "notifications/cancelled"]]
   [:params
    [:map
     [:requestId request-id]
     [:reason {:optional true} string?]]]])

;;; Implementation Info
(def implementation
  [:map
   [:name string?]
   [:version string?]])

;;; Initialization
(def client-capabilities
  [:map
   [:experimental {:optional true} [:map-of string? map?]]
   [:roots {:optional true}
    [:map
     [:listChanged {:optional true} boolean?]]]
   [:sampling {:optional true} map?]])

(def server-capabilities
  [:map
   [:experimental {:optional true} [:map-of string? map?]]
   [:logging {:optional true} map?]
   [:prompts {:optional true}
    [:map
     [:listChanged {:optional true} boolean?]]]
   [:resources {:optional true}
    [:map
     [:subscribe {:optional true} boolean?]
     [:listChanged {:optional true} boolean?]]]
   [:tools {:optional true}
    [:map
     [:listChanged {:optional true} boolean?]]]])

(def initialize-request
  [:map
   [:method [:= "initialize"]]
   [:params
    [:map
     [:protocolVersion string?]
     [:capabilities client-capabilities]
     [:clientInfo implementation]]]])

(def initialize-result
  [:map
   [:protocolVersion string?]
   [:capabilities server-capabilities]
   [:serverInfo implementation]
   [:instructions {:optional true} string?]])

(def initialized-notification
  [:map
   [:method [:= "notifications/initialized"]]])

;;; Ping
(def ping-request
  [:map
   [:method [:= "ping"]]])

;;; Progress Notifications
(def progress-notification
  [:map
   [:method [:= "notifications/progress"]]
   [:params
    [:map
     [:progressToken progress-token]
     [:progress number?]
     [:total {:optional true} number?]]]])

;;; Pagination
(def paginated-request
  [:map
   [:method string?]
   [:params {:optional true}
    [:map
     [:cursor {:optional true} cursor]]]])

(def paginated-result
  [:map
   [:nextCursor {:optional true} cursor]])

;;; Annotated Base
(def annotated
  [:map
   [:annotations {:optional true}
    [:map
     [:audience {:optional true} [:vector role]]
     [:priority {:optional true} [:and number? [:>= 0] [:<= 1]]]]]])

;;; Content Types
(def text-content
  [:merge
   annotated
   [:map
    [:type [:= "text"]]
    [:text string?]]])

(def image-content
  [:merge
   annotated
   [:map
    [:type [:= "image"]]
    [:data string?]
    [:mimeType string?]]])

;;; Resources
(def resource
  [:merge
   annotated
   [:map
    [:uri string?]
    [:name string?]
    [:description {:optional true} string?]
    [:mimeType {:optional true} string?]
    [:size {:optional true} number?]]])

(def resource-template
  [:merge
   annotated
   [:map
    [:uriTemplate string?]
    [:name string?]
    [:description {:optional true} string?]
    [:mimeType {:optional true} string?]]])

(def list-resources-request
  [:map
   [:method [:= "resources/list"]]
   [:params {:optional true}
    [:map
     [:cursor {:optional true} cursor]]]])

(def list-resources-result
  [:merge
   paginated-result
   [:map
    [:resources [:vector resource]]]])

(def list-resource-templates-request
  [:map
   [:method [:= "resources/templates/list"]]
   [:params {:optional true}
    [:map
     [:cursor {:optional true} cursor]]]])

(def list-resource-templates-result
  [:merge
   paginated-result
   [:map
    [:resourceTemplates [:vector resource-template]]]])

(def resource-contents
  [:map
   [:uri string?]
   [:mimeType {:optional true} string?]])

(def text-resource-contents
  [:merge
   resource-contents
   [:map
    [:text string?]]])

(def blob-resource-contents
  [:merge
   resource-contents
   [:map
    [:blob string?]]])

(def embedded-resource
  [:merge
   annotated
   [:map
    [:type [:= "resource"]]
    [:resource [:or text-resource-contents blob-resource-contents]]]])

(def read-resource-request
  [:map
   [:method [:= "resources/read"]]
   [:params
    [:map
     [:uri string?]]]])

(def read-resource-result
  [:map
   [:contents [:vector [:or text-resource-contents blob-resource-contents]]]])

(def resource-list-changed-notification
  [:map
   [:method [:= "notifications/resources/list_changed"]]])

(def subscribe-request
  [:map
   [:method [:= "resources/subscribe"]]
   [:params
    [:map
     [:uri string?]]]])

(def unsubscribe-request
  [:map
   [:method [:= "resources/unsubscribe"]]
   [:params
    [:map
     [:uri string?]]]])

(def resource-updated-notification
  [:map
   [:method [:= "notifications/resources/updated"]]
   [:params
    [:map
     [:uri string?]]]])

;;; Prompts
(def prompt-message
  [:map
   [:role role]
   [:content [:or text-content image-content embedded-resource]]])

(def prompt-argument
  [:map
   [:name string?]
   [:description {:optional true} string?]
   [:required {:optional true} boolean?]])

(def prompt
  [:map
   [:name string?]
   [:description {:optional true} string?]
   [:arguments {:optional true} [:vector prompt-argument]]])

(def list-prompts-request
  [:map
   [:method [:= "prompts/list"]]
   [:params {:optional true}
    [:map
     [:cursor {:optional true} cursor]]]])

(def list-prompts-result
  [:merge
   paginated-result
   [:map
    [:prompts [:vector prompt]]]])

(def get-prompt-request
  [:map
   [:method [:= "prompts/get"]]
   [:params
    [:map
     [:name string?]
     [:arguments {:optional true} [:map-of string? string?]]]]])

(def get-prompt-result
  [:map
   [:description {:optional true} string?]
   [:messages [:vector prompt-message]]])

(def prompt-list-changed-notification
  [:map
   [:method [:= "notifications/prompts/list_changed"]]])

;;; Tools
(def tool
  [:map
   [:name string?]
   [:description {:optional true} string?]
   [:inputSchema
    [:map
     [:type [:= "object"]]
     [:properties {:optional true} [:map-of string? map?]] ; need more detail here.
     [:required {:optional true} [:vector string?]]]]])

(def list-tools-request
  [:map
   [:method [:= "tools/list"]]
   [:params {:optional true}
    [:map
     [:cursor {:optional true} cursor]]]])

(def list-tools-result
  [:merge
   paginated-result
   [:map
    [:tools [:vector tool]]]])

(def call-tool-request
  [:map
   [:method [:= "tools/call"]]
   [:params
    [:map
     [:name string?]
     [:arguments {:optional true} [:map-of string? any?]]]]])

(def call-tool-result
  [:map
   [:content [:vector [:or text-content image-content embedded-resource]]]
   [:isError {:optional true} boolean?]])

(def tool-list-changed-notification
  [:map
   [:method [:= "notifications/tools/list_changed"]]])

;;; Logging
(def set-level-request
  [:map
   [:method [:= "logging/setLevel"]]
   [:params
    [:map
     [:level logging-level]]]])

(def logging-message-notification
  [:map
   [:method [:= "notifications/message"]]
   [:params
    [:map
     [:level logging-level]
     [:logger {:optional true} string?]
     [:data any?]]]])

;;; Sampling
(def sampling-message
  [:map
   [:role role]
   [:content [:or text-content image-content]]])

(def model-hint
  [:map
   [:name {:optional true} string?]])

(def model-preferences
  [:map
   [:hints {:optional true} [:vector model-hint]]
   [:costPriority {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:speedPriority {:optional true} [:and number? [:>= 0] [:<= 1]]]
   [:intelligencePriority {:optional true} [:and number? [:>= 0] [:<= 1]]]])

(def create-message-request
  [:map
   [:method [:= "sampling/createMessage"]]
   [:params
    [:map
     [:messages [:vector sampling-message]]
     [:modelPreferences {:optional true} model-preferences]
     [:systemPrompt {:optional true} string?]
     [:includeContext {:optional true} [:enum "none" "thisServer" "allServers"]]
     [:temperature {:optional true} number?]
     [:maxTokens number?]
     [:stopSequences {:optional true} [:vector string?]]
     [:metadata {:optional true} map?]]]])

(def create-message-result
  [:merge
   sampling-message
   [:map
    [:model string?]
    [:stopReason {:optional true} [:or [:enum "endTurn" "stopSequence" "maxTokens"] string?]]]])

;;; Autocomplete
(def prompt-reference
  [:map
   [:type [:= "ref/prompt"]]
   [:name string?]])

(def resource-reference
  [:map
   [:type [:= "ref/resource"]]
   [:uri string?]])

(def complete-request
  [:map
   [:method [:= "completion/complete"]]
   [:params
    [:map
     [:ref [:or prompt-reference resource-reference]]
     [:argument
      [:map
       [:name string?]
       [:value string?]]]]]])

(def complete-result
  [:map
   [:completion
    [:map
     [:values [:vector string?]]
     [:total {:optional true} number?]
     [:hasMore {:optional true} boolean?]]]])

;;; Roots
(def root
  [:map
   [:uri string?]
   [:name {:optional true} string?]])

(def list-roots-request
  [:map
   [:method [:= "roots/list"]]])

(def list-roots-result
  [:map
   [:roots [:vector root]]])

(def roots-list-changed-notification
  [:map
   [:method [:= "notifications/roots/list_changed"]]])

;;; Client Messages
(def client-request
  [:or
   ping-request
   initialize-request
   complete-request
   set-level-request
   get-prompt-request
   list-prompts-request
   list-resources-request
   list-resource-templates-request
   read-resource-request
   subscribe-request
   unsubscribe-request
   call-tool-request
   list-tools-request])

(def client-notification
  [:or
   cancelled-notification
   progress-notification
   initialized-notification
   roots-list-changed-notification])

(def client-result
  [:or
   empty-result
   create-message-result
   list-roots-result])

;;; Server Messages
(def server-request
  [:or
   ping-request
   create-message-request
   list-roots-request])

(def server-notification
  [:or
   cancelled-notification
   progress-notification
   logging-message-notification
   resource-updated-notification
   resource-list-changed-notification
   tool-list-changed-notification
   prompt-list-changed-notification])

(def server-result
  [:or
   empty-result
   initialize-result
   complete-result
   get-prompt-result
   list-prompts-result
   list-resources-result
   list-resource-templates-result
   read-resource-result
   call-tool-result
   list-tools-result])