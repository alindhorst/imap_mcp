package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.util.Optional;
import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * The write-relevant capabilities of ONE leased connection (SPEC 5.4), read from {@code IMAPStore.hasCapability},
 * which answers from the greeting/LOGIN capability list and issues no IMAP command. Never taken from the
 * {@code CapabilityCache}: a stale cache must not select the COPY + {@code \Deleted} path on a server without
 * UID EXPUNGE, because without UIDPLUS the only expunge left would be the plain EXPUNGE, which is forbidden.
 * <p>
 * {@code set_flags} and {@code create_folder} need no gate: STORE and CREATE are base IMAP4rev1.
 */
public record WriteCapabilities(boolean move, boolean uidplus) {

    public static WriteCapabilities from(IMAPStore store) throws MessagingException {
        return new WriteCapabilities(store.hasCapability("MOVE"), store.hasCapability("UIDPLUS"));
    }

    /**
     * Gate for copy, move and delete: empty when allowed, else UNSUPPORTED with {@code details.capability}
     * {@code UIDPLUS}. Pure; call it before any SELECT.
     */
    public Optional<ToolError> requireUidplus(String requestId) {
        return uidplus ? Optional.empty() : Optional.of(ToolError.unsupported("UIDPLUS", requestId));
    }
}
