package de.alexanderlindhorst.imapmcp.core.tool;

/** Behaviour hints every tool must declare (SPEC 4). Mapped to MCP tool annotations by the adapter. */
public record ToolHints(boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {

    /** v1 tools: read-only IMAP access, talks to an external server. */
    public static final ToolHints READ_ONLY_OPEN_WORLD = new ToolHints(true, false, true, true);
}
