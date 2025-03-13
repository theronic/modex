# Modex: Model Context Protocol Server & Client in Clojure

Modex (MOdel + ContEXt) is a native Clojure implementation of the [Model Context Protocol](https://modelcontextprotocol.io/) that lets you augment your AI with new tools, resources and prompts.

Because it's native Clojure, you don't need to deal with Anthropic's [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk).

Modex implements the `stdio` transport, so no need for a proxy like
[mcp-proxy](https://github.com/sparfenyuk/mcp-proxy) to translate between SSE <=> stdio or vice versa.

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

## What is MCP?

MCP lets you augment your AI models with Tools, Resources & Prompts:

- **Tools** are things it can do, like query a database (e.g. Datomic).
- **Resources** are files and data it can read, like PDF bank statements.
- **Prompts** are templated messages and workflows.

For example, you could make a tool that fetches purchases from your bank's API and let the AI categorize your expenses, and then use another tool to write those expense categories to your accounting system, or to a database. Pretty cool.

## What can Modex do?

The Modex skeleton exposes a single tool named `foo` that answers an MCP Client (like your LLM), with:

```clojure
{:content [{:type "text"
            :text "Hello, AI!" 
            :isError false}]}
```

Your MCP client (e.g. Claude Desktop) can connect to this server and use exposed tools to provide additional context to your AI models.

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
- [ ] Ergonomics (AServer / AClient protocol?)
- [ ] nREPL for live changes to running process
- [ ] SSE support

## Rationale

There is an existing library [mcp-clj](https://github.com/hugoduncan/mcp-clj) that uses SSE, so it requires mcp-proxy to proxy from SSE <=> stdio. I was annoyed by this, so I made Modex.

## FAQ

### Can I modify the server while an MCP Client (like Claude Desktop) is connected?

Not yet, but I'll add an nREPL soon so you can eval changes while Claude Desktop is connected to the process without rebuilding the uberjar.

Btw. I tried to get it to run `clojure -M -m modex.mcp.server`, but you can't set Claude Desktop's working directory.

So currently, I rebuild the uberjar and restart Claude Desktop. Will fix.

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
