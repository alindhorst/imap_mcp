package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.MessagingException;
import java.util.List;
import java.util.Objects;

/**
 * The user's consent to one {@code purge_messages} call (SPEC 4 v3a, "Confirmation"). Consent comes only from here,
 * never from a tool argument or configuration; the core ships only {@link #UNAVAILABLE}, so nothing can be deleted
 * until a transport supplies an implementation that really asks the user. {@link #available} is checked at step 3
 * with no I/O; {@link #confirm} runs at step 9, on the call's lease, after the scope check and before
 * {@code tryBeginMutation()}.
 */
public interface PurgeConfirmation {

    /** What the user is asked about: the resolved Trash folder and the exact items of the call. */
    record Request(ResolvedAccount account, ImapLease lease, String trashName, List<WritePreflight.Item> items) {
        public Request {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(trashName, "trashName");
            items = List.copyOf(items);
        }
    }

    sealed interface Decision {
        /** @param confirmation {@link BulkResult#ELICITED} or {@link BulkResult#SESSION_APPROVAL} */
        record Confirmed(String confirmation) implements Decision {
            public Confirmed {
                if (!BulkResult.ELICITED.equals(confirmation) && !BulkResult.SESSION_APPROVAL.equals(confirmation)) {
                    throw new IllegalArgumentException("confirmation");
                }
            }
        }

        /**
         * Too little time (less than the minimum wait window, or the worker was interrupted by the deadline or a
         * cancel): no dialog, or none that counts. The tool answers with the plain v1 {@code TIMEOUT}, nothing changed.
         */
        record Expired() implements Decision {
        }

        /** Anything but an accepted answer; becomes {@code PERMISSION_DENIED} / {@code not_confirmed}. */
        record Refused(ToolError.ElicitationReason reason) implements Decision {
            public Refused {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /** Whether this session can ask the user at all (step 3, no I/O). */
    boolean available(ToolCallContext ctx);

    /** Asks the user, or applies a session approval (step 9). May throw the call's connection failures. */
    Decision confirm(ToolCallContext ctx, Request request) throws MessagingException;

    /** Cannot confirm anything: every purge call ends in {@code confirmation_unavailable}. */
    PurgeConfirmation UNAVAILABLE = new PurgeConfirmation() {
        @Override
        public boolean available(ToolCallContext ctx) {
            return false;
        }

        @Override
        public Decision confirm(ToolCallContext ctx, Request request) {
            return new Decision.Refused(ToolError.ElicitationReason.ERROR);
        }
    };
}
