package de.alexanderlindhorst.imapmcp.transport.stdio;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Reads the error object from a raw JSON-RPC tools/call {@code result} (SPEC 4): {@code isError} true, no
 * {@code structuredContent}, exactly one text block carrying {@code {"error":{...}}}.
 */
final class ErrorResults {

    private ErrorResults() {
    }

    /** The inner {@code error} object of the text block; asserts the error-result shape on the way. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> error(Map<String, Object> result) {
        assertThat(result).containsEntry("isError", true);
        assertThat(result).doesNotContainKey("structuredContent");
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("type", "text");
        try {
            Map<String, Object> payload = McpJsonDefaults.getMapper().readValue((String) content.get(0).get("text"), Map.class);
            assertThat(payload).containsOnlyKeys("error");
            return (Map<String, Object>) payload.get("error");
        } catch (IOException e) {
            throw new AssertionError("error text block is not JSON", e);
        }
    }
}
