package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import jakarta.mail.MessagingException;
import java.time.Clock;
import java.util.Map;

/** Skeleton for the red test commit. */
public final class DeleteMessagesTool implements ToolHandler {

    public static final String NAME = "delete_messages";

    @FunctionalInterface
    interface TrashResolver {
        FolderResolver.Resolution resolve(ImapLease lease, String requestId) throws MessagingException;
    }

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "Delete messages", "Skeleton",
            Map.of(), Map.of(), ToolHints.WRITE_DESTRUCTIVE_IDEMPOTENT);

    public DeleteMessagesTool(WritePreflight preflight, StoreLeases leases, MessageHandles handles, Clock clock) {
    }

    DeleteMessagesTool(WritePreflight preflight, StoreLeases leases, MessageHandles handles, Clock clock,
            TrashResolver trash, MoveStep.Mover mover) {
    }

    @Override
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        throw new UnsupportedOperationException();
    }
}
