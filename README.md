# Modex: Model Context Protocol Server & Client Library in Clojure

Modex (MOdel + ContEXt) is a Clojure library that lets you augment your AI with new tools, resources and prompts.

Modex implements (most of) the [Model Context Protocol](https://modelcontextprotocol.io/) to build MCP Servers & Clients in 'native' Clojure.

Because it's native Clojure, you don't need to deal with Anthropic's [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk).

Modex implements the `stdio` transport in the 2024-11-05 MCP spec, so no need for a proxy like
[mcp-proxy](https://github.com/sparfenyuk/mcp-proxy) to translate between SSE <=> stdio or vice versa.

## Screenshot of Modex in Action

Claude Desktop can talk to a Modex MCP Server via its MCP client:

<img width="650" alt="image" src="https://github.com/user-attachments/assets/ef83b8da-1816-48c0-aba6-47b4b5623f7a" />

## Table of Contents

1. [Quickstart](#quickstart)
2. [What is MCP?](#what-is-mcp)
3. [What can Modex do?](#what-can-modex-do)
4. [Detailed Step-by-Step Instructions](#detailed-step-by-step-instructions)
5. [Implementation](#implementation)
6. [Project Status](#project-status)
7. [Rationale](#rationale)
8. [FAQ](#faq)
9. [Licence](#licence)

## Example Tools

- [Datomic MCP](https://github.com/theronic/datomic-mcp) uses Modex to expose Datomic tools so your models can query DB schema and data in dev or prod.

## Quickstart

1. `git clone git@github.com:theronic/modex.git`
2. `cd modex`
3. `./build.sh` builds an uberjar at `target/modex-mcp-0.1.0.jar`.
4. Open your Claude Desktop Config at `~/Library/Application\ Support/Claude/claude_desktop_config.json`
5. Configure a new MCP Server that will run the uberjar at its _full path_:

```json
{
  "mcpServers": {
    "modex-mcp-hello-world": {
      "command": "java",
      "args": ["-jar", "/Users/your-username/code/modex/target/modex-mcp-0.1.0.jar"]
    }
  },
  "globalShortcut": ""
}
```

6. Restart Claude Desktop to activate your new MCP Server + tools :)
7. Tell Claude to "run the inc tool with 123", authorize the tool and you should see an output of 124.

## What is MCP?

MCP lets you augment your AI models with Tools, Resources & Prompts:

- **Tools** are things it can do, like query a database (e.g. Datomic).
- **Resources** are files and data it can read, like PDF bank statements.
- **Prompts** are templated messages and workflows.

## Use Cases

Modex is used by [datomic-mcp](https://github.com/theronic/datomic-mcp), which exposes our production Datomic databases to an MCP client like Claude Desktop. The AI model intelligently diagnoses support queries in production by reading our database schema and running queries that checks server state & IPs, so it can try to reach it and compare the desired state of VMs against the actual state in our clusters.

Over time, I hope to automate the bulk of our recurring support queries using Modex + other MCP tools.

## What can Modex do?

### Full Example

There is an MCP server example in [src/modex/mcp/core.clj](src/modex/mcp/core.clj) that defines an MCP server with some basic tools.

Your MCP client (e.g. Claude Desktop) can connect to this server and use exposed tools to provide additional context to your AI models.

## Data Structures

### Tools

Internally, a tool is just `Tool` record with several `Parameter` arguments:

- `(defrecord Tool [name doc args handler])`
- `(defrecord Parameter [name doc type required default])`

However, it is more convenient to define tools using the `tool` & `tools` macros below.

### Describe a tool with the `tool` macro:

The `tool` macro acts like `defrecord`, where the handler definition takes a map of arguments ala `{:keys [arg1 arg2 ...]}` but with additional (optional) maps for `:type`, `:or` & `:doc`. This metadata is used to describe the tool to the MCP Client.

- The MCP spec currently only supports `:string` & `:number` tool parameter types.
- Presence in the `:or` map implies optionality.
- Missing parameter docstrings default to `(str parameter-name)`. 

```clojure
(require '[modex.mcp.tools :as tools])

(def add-tool
  (tools/tool
    ; feels like defrecord.
    (add [{:keys [x y]
           :type {x :number
                  y :number}
           :or   {y 0} ; y is optional.
           :doc  {x "First number"
                  y "Second number"}}]
         ; tools should return collections
         ; this will soon change to {:keys [success results ?error]}.
         [(+ x y)])))
```

### Invoke a Tool with `invoke-tool`:

Invocation uses a map of arguments like an MCP client would for a `tools/call` request:

```clojure
(tools/invoke-tool add-tool {:x 5 :y 6}) ; Modex will map these arguments and call the handler.
=> [11 nil]
```

Tool invocation returns a vector of `[results ?error]`.

Note: this will soon change to a map of `{:keys [success results ?error]}`. This may be a breaking change.

### Define a Toolset with `tools` macro

The `tools` macro just calls the `tool` macro for each tool definition and returns a map of tools keyed on tool name (keyword):

```clojure
(def my-tools
  "Define your tools here."
  (tools/tools
    (greet
      "Greets a person by name." ; tools can have a docstring
      [{:keys [first-name last-name]
        :doc {first-name "A person's first name."
              last-name  "A person's last name (optional)."}
        :type {first-name :string
               last-name  :string}
        :or {last-name nil}}] ; last-name is optional, implied by presence in `:or` map.
      ; tools should return collection, but will change to map of {:keys [success results ?error]}:
      [(str "Hello from Modex, "
            (if last-name ; args can be optional
              (str first-name " " last-name)
              first-name) "!")])
    
    (add
      "Adds two numbers."
      ; Tool handler args also support deprecated vector arg-style,
      ; but this is superseded by the newer map-destructuring style:
      [^{:type :number :doc "First number to add."} a
       ^{:type :number :doc "Second number to add."} b]
      [(+ a b)])

    (subtract
      "Subtracts two numbers (- a b)"
      [^{:type :number :doc "First number."} a
       ^{:type :number :doc "Second number."} b]
      [(- a b)])))
```

### Create a Modex MCP Server + tools:

```clojure
(require '[modex.mcp.server :as server])
(def my-mcp-server
  "Here we create a reified instance of AServer. Only tools are presently supported."
  (server/->server
    {:name       "Modex MCP Server"
     :version    "0.0.1"
     :initialize (fn [] "Do long-running setup & blocking I/O here, like connecting to prod database.")
     :tools      my-tools
     :prompts    nil    ; Prompts are WIP.
     :resources  nil})) ; Resources are WIP.
```

### Start your MCP Server

```clojure
(server/start-server! my-mcp-server)
```

Or put that in your `-main` function.

### Protocols

Modex exposes an `AServer` protocol and a DSL to define tools protocols that describe MCP servers, which expose tools, resources & prompts.

AServer Protocol:
```clojure
(defprotocol AServer
  (protocol-version [this])
  (server-name [this])
  (version [this])

  (capabilities [this])
  (initialize [this])

  (list-tools [this])
  (call-tool [this tool-name arg-map])

  (list-resources [this])
  (list-prompts [this]))
```

## Detailed Step-by-Step Instructions

### Step 1: Build the Uberjar

Before you can run it, you have to build it first. The build outputs an uberjar, which is like a Java executable.

```bash
clojure -T:build uber
```

or run the helper which does that:
```bash
./build.sh
```
(you might need to run `chmod +x build.sh`)

### Step 2: Open Claude Desktop Config

Open your Claude Desktop Configuration file, `claude_desktop_config.json`, which on MacOS should be at:

    ~/Library/Application\ Support/Claude/claude_desktop_config.json

### Step 3: Configure your MCP Server

Add an element under `mcpServers` so it looks like this:

```json
{
  "mcpServers": {
    "modex": {
      "command": "java",
      "args": ["-jar", "/Users/your-username/code/modex/target/modex-mcp-0.1.0.jar"]
    }
  },
  "globalShortcut": ""
}
```

This tells Claude Desktop there is a tool named `modex` and it can connect to by running `java -jar /path/to/your/uber.jar`.

The way this works is that your local MCP Client (i.e. Claude Desktop), starts your MCP server process and communicates with it via stdin/stdout pipes.

### Step 4: Restart Claude Desktop

You should now be able to ask Claude "run foo", or "what does foo say?" and it will run
the `foo` tool and reply with the response, "Hello, AI!".

## Implementation

Modex implements an MCP client & server in Clojure that is _mostly_ compliant with the [2024-11-05 MCP Spec](https://spec.modelcontextprotocol.io/specification/2024-11-05/).

Messages are encoded using the JSON-RPC 2.0 wire format. 

There are 3 message types:
- Requests have `{:keys [id method ?params]}`
- Responses have `{:keys [id result ?error]}`
- Notifications have `{:keys [method ?params}`

MCP supports two transport types:
- [x] stdio/stdout – implemented in Modex.
- [ ] Server-Sent Events (SSE) – not implemented yet. Useful for restricted networks

## Project Status

- [x] Passing tests
- [x] Ergonomics (AServer / AClient protocol?)
- [x] Tools
- [ ] nREPL for live changes to running process
- [ ] Resources
- [ ] Prompts
- [ ] SSE support
- [ ] Streaming HTTP Support (2025-03-26 MCP spec)

## Rationale

There is an existing library [mcp-clj](https://github.com/hugoduncan/mcp-clj) that uses SSE, so it requires mcp-proxy to proxy from SSE <=> stdio. I was annoyed by this, so I made Modex.

## FAQ

### Can I modify the server while an MCP Client (like Claude Desktop) is connected?

Not yet, but I'll add an nREPL soon so you can eval changes while Claude Desktop is connected to the process without rebuilding the uberjar.

Btw. I tried to get it to run `clojure -M -m modex.mcp.server`, but you can't set Claude Desktop's working directory.

So currently, I rebuild the uberjar and restart Claude Desktop. Will fix.

## Thank You To Paid Modex Customers:

- [Nextdoc](https://nextdoc.io/) – Document Streaming for Salesforce
- [Huppi](https://www.huppi.io/) — Small Business Accounting Software

## License 

In summary:
- **Free for non-commercial use**: Use it, modify it, share it under [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) at no cost, just keep it open source.
- **Commercial use**: Want to keep your changes private? Pay $20 once-off for a perpetual commercial license. This covers the cost of my AI tokens to keep building this in public.

This tool is licensed under the [GNU General Public License v3.0 (GPLv3)](https://www.gnu.org/licenses/gpl-3.0.html). You are free to use, modify, and distribute it, provided that any derivative works are also licensed under the GPLv3 and made open source. This ensures the tool remains freely available to the community while requiring transparency for any changes.

If you wish to use or modify this tool in a proprietary project—without releasing your changes under the GPLv3—you 
may purchase a commercial license. This allows you to keep your modifications private for personal or commercial use.
To obtain a commercial license, please contact me at [modex@petrus.co.za](mailto:modex@petrus.co.za).

## Author(s)

- [Petrus Theron](http://petrustheron.com)
