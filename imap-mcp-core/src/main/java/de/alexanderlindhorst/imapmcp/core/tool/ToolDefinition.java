package de.alexanderlindhorst.imapmcp.core.tool;

import java.util.Map;
import java.util.Objects;

/** Transport- and SDK-independent description of a tool. Input and output JSON schemas are plain maps. */
public record ToolDefinition(String name, String title, String description, Map<String, Object> inputSchema,
        Map<String, Object> outputSchema, ToolHints hints) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(hints, "hints");
    }
}
