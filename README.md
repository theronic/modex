# Modex: Model Context Protocol Server & Client in Clojure

Modex is a skeleton project to build [Model Context Protocol](https://modelcontextprotocol.io/) Servers & Clients in native
Clojure without using Anthropic's [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk).

Supports stdio, so no need to proxy SSE <=> stdio using a tool like
[mcp-proxy](https://github.com/sparfenyuk/mcp-proxy).

# What does it do?

This Modex skeleton exposes a single tool named `foo` that answers your LLM (an MCP Client), with:

```clojure
{:content [{:type "text"
            :text "Hello, AI!" 
            :isError false}]}
```

Your MCP client (e.g. Claude Desktop) can connect to this server and use exposed tools to provide
additional context to your AI models. 

## Project Status

- Tests in test/modex/mcp/server_test.clj are passing,
- but there is a bug in test/modex/mcp/client_test.clj after I made some changes. Will fix.

Anyway, the server works on _my_ machine.

## Rationale

Existing library [mcp-clj](https://github.com/hugoduncan/mcp-clj) uses SSE, so it requires mcp-proxy to proxy from
SSE <=> stdio. I was annoyed with that, so I made this.

## Why Modex?

MOdel + ContEXt = Modex.

## Goal

The goal for Modex is to eventually become a convenient library to add MCP Servers & Clients to existing Clojure 
projects, in order to expose tools & resources to MCP Clients like Claude Desktop.

In future, something like `(mcp.server/defntool tool-name [arg1 arg 2] {:data "tool response here"})` would be nice.

## Run a Modex MCP Server Locally:

```
clojure -M -m modex.mcp.server
```

## Build an Uberjar

    ./build.sh

or,
```
    clojure -T:build uber
```

## Configure Claude Desktop to use your Modex MCP Server:

Edit your Claude Configuration file, which should be at the following location on MacOS:

    ~/Library/Application\ Support/Claude/claude_desktop_config.json

Add an element under `mcpServers` so it looks like this (to run your MCP Server uberjar):

```json
{
  "mcpServers": {
    "modex-mcp-hello-world": {
      "command": "java",
      "args": ["-jar", "/Users/your-username/code/modex/target/modex-mcp-server-0.1.0.jar"]
    }
  },
  "globalShortcut": ""
}
```

Restart Claude Desktop, and you should be able to ask the LLM "what does the hello world tol say?" and it will run 
the `foo` tool.

## License

In summary:
- **Free Use**: Use it, modify it, share it under [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html)—no cost, just 
  keep it open source.
- **Proprietary Use**: Want to keep your changes private? Pay $20 once-off for a perpetual commercial license. This 
  covers the cost of my AI tokens to keep building this in public.

This tool is licensed under the [GNU General Public License v3.0 (GPLv3)](https://www.gnu.org/licenses/gpl-3.0.html). You are free to use, modify, and distribute it, provided that any derivative works are also licensed under the GPLv3 and made open source. This ensures the tool remains freely available to the community while requiring transparency for any changes.

If you wish to use or modify this tool in a proprietary project—without releasing your changes under the GPLv3—you 
may purchase a commercial license. This allows you to keep your modifications private for personal or commercial use.
To obtain a commercial license, please contact me at [modex@petrus.co.za](mailto:modex@petrus.co.za).

## Author(s)

- [Petrus Theron](http://petrustheron.com)
