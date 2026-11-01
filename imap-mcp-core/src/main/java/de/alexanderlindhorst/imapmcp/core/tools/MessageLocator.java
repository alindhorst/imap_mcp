package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPMessage;

/** Stub. */
public final class MessageLocator {

    public sealed interface Checked {
        record Ok(MessageRef ref) implements Checked {
        }

        record Invalid(ToolError error) implements Checked {
        }
    }

    public sealed interface Located {
        record Found(IMAPFolder folder, IMAPMessage message, MessageRef ref) implements Located {
        }

        record Failed(ToolError error) implements Located {
        }
    }

    public MessageLocator(MessageHandles handles) {
    }

    public Checked check(ToolCallContext ctx, ResolvedAccount account, String handle) {
        throw new UnsupportedOperationException();
    }

    public Located locate(ImapLease lease, ToolCallContext ctx, MessageRef ref) throws MessagingException {
        throw new UnsupportedOperationException();
    }
}
