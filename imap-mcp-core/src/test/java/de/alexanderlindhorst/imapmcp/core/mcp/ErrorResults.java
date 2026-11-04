package de.alexanderlindhorst.imapmcp.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.Map;

/**
 * Reads the error object of an error result (SPEC 4): {@code isError} true, no {@code structuredContent},
 * exactly one text block carrying {@code {"error":{...}}}.
 */
final class ErrorResults {

    private ErrorResults() {
    }

    /** The whole parsed text block, i.e. {@code {"error":{...}}}; asserts the error-result shape on the way. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> payload(McpSchema.CallToolResult r) {
        assertThat(r.isError()).isTrue();
        assertThat(r.structuredContent()).isNull();
        assertThat(r.content()).hasSize(1);
        assertThat(r.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
        try {
            return McpJsonDefaults.getMapper().readValue(((McpSchema.TextContent) r.content().get(0)).text(), Map.class);
        } catch (IOException e) {
            throw new AssertionError("error text block is not JSON", e);
        }
    }

    /** The inner {@code error} object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> error(McpSchema.CallToolResult r) {
        return (Map<String, Object>) payload(r).get("error");
    }
}
