package de.alexanderlindhorst.imapmcp.core.tool;

/** Behaviour hints every tool must declare (SPEC 4). Mapped to MCP tool annotations by the adapter. */
public record ToolHints(boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {

    /** v1 tools: read-only IMAP access, talks to an external server. */
    public static final ToolHints READ_ONLY_OPEN_WORLD = new ToolHints(true, false, true, true);

    /** v2 set_flags, move_messages, delete_messages: change state, retry-safe (SPEC 4 v2 write contract). */
    public static final ToolHints WRITE_DESTRUCTIVE_IDEMPOTENT = new ToolHints(false, true, true, true);

    /** v2 copy_messages: additive only, a retry creates another copy. */
    public static final ToolHints WRITE_ADDITIVE = new ToolHints(false, false, false, true);

    /** v2 create_folder: additive only, repeating it changes nothing further. */
    public static final ToolHints WRITE_ADDITIVE_IDEMPOTENT = new ToolHints(false, false, true, true);
}
