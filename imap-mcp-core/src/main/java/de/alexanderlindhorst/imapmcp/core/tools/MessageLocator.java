package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.error.ToolError.StaleReason;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import java.util.Objects;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;

/**
 * Shared by the single-message tools (SPEC 5.4). Two steps so that a bad handle never costs a connection:
 * {@link #check} verifies the signed handle against the caller's tenant and the resolved account with zero
 * I/O; {@link #locate} then checks, in this order, that the folder exists, opens it READ_ONLY, compares
 * UIDVALIDITY and finally looks the message up by UID. The returned folder and message belong to the lease and
 * must not outlive it; the folder is closed by the lease (or earlier on failure).
 */
public final class MessageLocator {

    /** Outcome of {@link #check}; every failure is the same INVALID_HANDLE error. */
    public sealed interface Checked {
        record Ok(MessageRef ref) implements Checked {
        }

        record Invalid(ToolError error) implements Checked {
        }
    }

    /** Outcome of {@link #locate}. */
    public sealed interface Located {
        record Found(IMAPFolder folder, IMAPMessage message, MessageRef ref) implements Located {
        }

        record Failed(ToolError error) implements Located {
        }
    }

    private final MessageHandles handles;

    public MessageLocator(MessageHandles handles) {
        this.handles = Objects.requireNonNull(handles, "handles");
    }

    /** Decode and MAC check; tenant from the context, account from the tenant-scoped resolution. */
    public Checked check(ToolCallContext ctx, ResolvedAccount account, String handle) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(account, "account");
        return switch (handles.decode(ctx.accountContext().tenantId(), account.account().id(), handle)) {
            case MessageHandles.Decoded.Ok ok -> new Checked.Ok(ok.ref());
            case MessageHandles.Decoded.Invalid i -> new Checked.Invalid(ToolError.invalidHandle(ctx.requestId()));
        };
    }

    public Located locate(ImapLease lease, ToolCallContext ctx, MessageRef ref) throws MessagingException {
        Objects.requireNonNull(lease, "lease");
        if (!lease.store().getFolder(ref.folder()).exists()) {
            return new Located.Failed(ToolError.staleReference(StaleReason.FOLDER_MISSING, ctx.requestId()));
        }
        IMAPFolder folder = lease.openReadOnly(ref.folder());
        if (folder.getUIDValidity() != ref.uidValidity()) {
            lease.closeFolder();
            return new Located.Failed(ToolError.staleReference(StaleReason.UIDVALIDITY_CHANGED, ctx.requestId()));
        }
        Message message = folder.getMessageByUID(ref.uid());
        if (message == null || message.isExpunged()) {
            lease.closeFolder();
            return new Located.Failed(ToolError.messageNotFound(ctx.requestId()));
        }
        return new Located.Found(folder, (IMAPMessage) message, ref);
    }
}
