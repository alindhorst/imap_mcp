package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import java.util.Map;

/** Stub. */
public final class ListAttachmentsTool implements ToolHandler {

    public static final String NAME = "list_attachments";
    public static final ToolDefinition DEFINITION = null;

    public ListAttachmentsTool(AccountArgument accounts, MessageLocator locator, StoreLeases leases) {
    }

    @Override
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        throw new UnsupportedOperationException();
    }
}
