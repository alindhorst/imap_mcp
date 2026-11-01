package de.alexanderlindhorst.imapmcp.core.mcp;

import io.modelcontextprotocol.archfixture.FakeSdkType;

/** Allowed use: SDK types are confined to {@code ..core.mcp..}. Guards against over-matching. */
class SdkInsideMcpFixture {
    FakeSdkType server;
}
