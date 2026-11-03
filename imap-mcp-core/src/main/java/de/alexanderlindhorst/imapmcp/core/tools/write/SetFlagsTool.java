package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import java.util.List;
import java.util.Map;

/** Skeleton, replaced by the implementation. */
public final class SetFlagsTool {

    public static final ToolDefinition DEFINITION = new ToolDefinition("set_flags", "Set flags", "Set flags.",
            Map.of("type", "object"), Map.of("type", "object"), ToolHints.READ_ONLY_OPEN_WORLD);

    public record Parsed(List<String> handles, List<String> add, List<String> remove, ToolError error) {
        public boolean valid() {
            return error == null;
        }
    }

    private SetFlagsTool() {
    }

    public static Parsed parse(Map<String, Object> args, String requestId) {
        return new Parsed(List.of(), List.of(), List.of(), null);
    }
}
