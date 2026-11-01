package de.alexanderlindhorst.imapmcp.transport.stdio;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;

public final class StdioServer {

    private StdioServer() {
    }

    public static StdioServer start(ProtocolStreams streams, McpJsonMapper mapper,
            List<McpServerFeatures.SyncToolSpecification> tools, McpSchema.Implementation serverInfo) {
        throw new UnsupportedOperationException();
    }

    public void awaitTermination() throws InterruptedException {
        throw new UnsupportedOperationException();
    }
}
