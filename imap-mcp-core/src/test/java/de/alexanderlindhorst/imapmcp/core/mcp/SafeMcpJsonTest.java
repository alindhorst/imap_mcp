package de.alexanderlindhorst.imapmcp.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpError;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** SPEC 4 / 5.3: an MCP error reaches the client as a bare JSON-RPC error, never as a serialized exception (E19). */
class SafeMcpJsonTest {

    private static String json(McpError e) throws IOException {
        return SafeMcpJson.mapper().writeValueAsString(e);
    }

    @Test
    void anMcpErrorIsWrittenAsABareJsonRpcErrorWithCodeAndMessage() throws IOException {
        McpError e = McpError.builder(-32600).message("Invalid message format").build();
        assertThat(json(e)).isEqualTo(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Invalid message format\"},\"id\":null}");
    }

    @Test
    void anInternalErrorCarriesTheFixedMessageNotTheOriginal() throws IOException {
        McpError e = McpError.builder(-32603).message("boom at /srv/app/Secret.java:42").build();
        assertThat(json(e)).isEqualTo(
                "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"},\"id\":null}");
    }

    @Test
    void noStackTraceClassNameOrJdkVersionIsWritten() throws IOException {
        String out = json(McpError.builder(-32600).message("x").build());
        assertThat(out).doesNotContain("stackTrace", "className", "fileName", "localizedMessage", "cause",
                System.getProperty("java.version"));
    }

    @Test
    void theMapperStillHandlesOrdinaryValuesLikeTheSdkDefault() throws IOException {
        assertThat(SafeMcpJson.mapper().writeValueAsString(java.util.Map.of("a", 1))).isEqualTo("{\"a\":1}");
        assertThat(SafeMcpJson.mapper().readValue("{\"a\":1}", java.util.Map.class)).containsEntry("a", 1);
    }
}
