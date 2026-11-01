package de.alexanderlindhorst.imapmcp.core.mcp;

import io.modelcontextprotocol.server.McpServer;

/** Allowed use: SDK types are confined to {@code ..core.mcp..}. Guards against over-matching. */
class SdkInsideMcpFixture {
    McpServer server;
}
