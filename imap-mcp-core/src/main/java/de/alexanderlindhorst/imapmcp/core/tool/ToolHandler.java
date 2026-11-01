package de.alexanderlindhorst.imapmcp.core.tool;

import java.util.Map;

/** Tool logic. Knows neither the transport nor the MCP SDK; identity comes only from the context. */
@FunctionalInterface
public interface ToolHandler {

    ToolOutcome handle(ToolCallContext context, Map<String, Object> args);
}
