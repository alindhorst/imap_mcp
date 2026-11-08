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

    private static String err(int code, String message, Object data) throws IOException {
        return SafeMcpJson.mapper().writeValueAsString(
                new io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError(code, message, data));
    }

    @Test
    void aJsonRpcErrorObjectOfAResponseNeverCarriesInternals() throws IOException {
        assertThat(err(-32603, "secret", new RuntimeException("x"))).isEqualTo(
                "{\"code\":-32603,\"message\":\"Internal error\"}");
        var response = io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError.class;
        String whole = SafeMcpJson.mapper().writeValueAsString(new io.modelcontextprotocol.spec.McpSchema
                .JSONRPCResponse("2.0", 1, null, new io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse
                        .JSONRPCError(-32603, "secret", new RuntimeException("x"))));
        assertThat(whole).contains("\"message\":\"Internal error\"").doesNotContain("secret", "RuntimeException",
                "stackTrace");
        assertThat(response).isNotNull();
    }

    @Test
    void messagesAreKeptOnlyWhenEveryPartIsAFixedSdkString() throws IOException {
        assertThat(err(-32600, "Invalid message format", null)).contains("\"message\":\"Invalid message format\"");
        assertThat(err(-32600, "Invalid message format: tools.jackson.MismatchedInputException: McpSchema$X", null))
                .contains("\"message\":\"Invalid message format\"").doesNotContain("jackson", "McpSchema");
        assertThat(err(-32600, "Unsupported Media Type: Content-Type must be application/json", null))
                .contains("Unsupported Media Type: Content-Type must be application/json");
        assertThat(err(-32600, "application/json required in Accept header; Session ID required in mcp-session-id header",
                null)).contains("application/json required in Accept header; Session ID required");
        // one unknown part drops the whole message for the fixed text of the code
        assertThat(err(-32600, "Invalid message format; Cannot deserialize JSONRPCMessage: {\"canary\":1}", null))
                .isEqualTo("{\"code\":-32600,\"message\":\"Invalid request\"}");
        assertThat(err(-32700, "whatever", null)).contains("\"message\":\"Parse error\"");
        assertThat(err(-32601, "x", null)).contains("\"message\":\"Method not found\"");
        assertThat(err(-32602, "x", null)).contains("\"message\":\"Invalid params\"");
        assertThat(err(-32001, "Session not found at /srv/x", null)).contains("\"message\":\"Error\"");
    }

    @Test
    void dataIsKeptOnlyAsAScalarAndNeverFor32603() throws IOException {
        assertThat(err(-32602, "x", "detail")).isEqualTo(
                "{\"code\":-32602,\"message\":\"Invalid params\",\"data\":\"detail\"}");
        assertThat(err(-32602, "x", java.util.Map.of("a", 1))).doesNotContain("data");
        assertThat(err(-32602, "x", new RuntimeException("boom"))).doesNotContain("data", "boom");
        assertThat(err(-32603, "x", "detail")).doesNotContain("data");
    }

    @Test
    void anyThrowableIsWrittenAsAFixedInternalError() throws IOException {
        assertThat(SafeMcpJson.mapper().writeValueAsString(new IllegalStateException("secret")))
                .isEqualTo("{\"message\":\"Internal error\"}");
    }
}
