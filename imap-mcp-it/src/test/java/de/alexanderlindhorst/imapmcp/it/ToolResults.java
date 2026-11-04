package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.Map;

/**
 * The payload of a tool result as the ITs read it (SPEC 4). A success result carries it as
 * {@code structuredContent}; an error result carries {@code {"error":{...}}} only in its single text block and
 * has no {@code structuredContent}, which is asserted here.
 */
final class ToolResults {

    private ToolResults() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> payload(McpSchema.CallToolResult r) {
        if (!Boolean.TRUE.equals(r.isError())) {
            return (Map<String, Object>) r.structuredContent();
        }
        assertThat(r.structuredContent()).as("error result has no structuredContent").isNull();
        assertThat(r.content()).hasSize(1);
        assertThat(r.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
        try {
            Map<String, Object> payload = McpJsonDefaults.getMapper().readValue(((McpSchema.TextContent) r.content().get(0)).text(), Map.class);
            assertThat(payload).containsOnlyKeys("error");
            return payload;
        } catch (IOException e) {
            throw new AssertionError("error text block is not JSON", e);
        }
    }
}
