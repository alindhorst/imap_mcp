package de.alexanderlindhorst.imapmcp.core.folder;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * Resolves the folder of a tool call to a full name (SPEC 5.4): an explicit {@code folder} is addressed by its full
 * name, a {@code folder_role} only by its SPECIAL-USE attribute, never by name heuristics (Gmail localizes names).
 * The only name rule is RFC 3501 INBOX. Runs as store-level commands (LIST) on a lease without an open folder and
 * never opens a folder itself.
 *
 * <p>Angus 2.0.5 issues a plain {@code LIST "" "*"} (no {@code (SPECIAL-USE)} selection, no RETURN options) and
 * exposes every attribute of the response through {@link IMAPFolder#getAttributes()}. Roles therefore resolve only
 * when the server includes SPECIAL-USE attributes in a plain LIST, which Dovecot does when it advertises
 * SPECIAL-USE; a server without it (e.g. GreenMail) yields FOLDER_NOT_FOUND for every role but {@code inbox}.
 * If several folders carry the same attribute the first one in Unicode code point order of the full name wins
 * (deterministic).
 */
public final class FolderResolver {

    /** Same limit as the message handle (#7). */
    static final int MAX_FOLDER_NAME_BYTES = 1000;

    private static final String INBOX = "INBOX";

    private static final Comparator<String> CODE_POINT_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    /** Either the resolved full name or the error to return; exactly one is non-null. */
    public record Resolution(String fullName, ToolError error) {
    }

    private FolderResolver() {
    }

    public static Resolution resolve(ImapLease lease, String folder, FolderRole role, String requestId)
            throws MessagingException {
        if (folder != null && role != null) {
            return fail(ToolError.invalidArgument("folder_role", "mutually_exclusive", null, requestId));
        }
        if (folder != null) {
            return resolveName(lease.store(), folder, requestId);
        }
        if (role == null || role == FolderRole.INBOX) {
            return new Resolution(INBOX, null);
        }
        return resolveRole(lease.store(), role, requestId);
    }

    private static Resolution resolveName(IMAPStore store, String name, String rid) throws MessagingException {
        IMAPFolder f = (IMAPFolder) store.getFolder(name);
        // exists() is a LIST for exactly this name and fills in the attributes; nothing is selected.
        if (!f.exists() || !selectable(f.getAttributes())) {
            return fail(ToolError.folderNotFound(name, rid));
        }
        return new Resolution(INBOX.equalsIgnoreCase(name) ? INBOX : name, null);
    }

    private static Resolution resolveRole(IMAPStore store, FolderRole role, String rid) throws MessagingException {
        String best = null;
        for (Folder f : store.getDefaultFolder().list("*")) {
            IMAPFolder imap = (IMAPFolder) f;
            String[] attrs = imap.getAttributes();
            if (!has(attrs, role.attribute()) || !selectable(attrs)) {
                continue;
            }
            String name = imap.getFullName();
            if (best == null || CODE_POINT_ORDER.compare(name, best) < 0) {
                best = name;
            }
        }
        if (best == null) {
            return fail(ToolError.folderRoleNotFound(role.wire(), rid));
        }
        if (best.getBytes(StandardCharsets.UTF_8).length > MAX_FOLDER_NAME_BYTES) {
            // No handle or cursor may be minted for it, and the name is not echoed.
            return fail(ToolError.folderNameTooLong(rid));
        }
        return new Resolution(best, null);
    }

    private static boolean selectable(String[] attrs) {
        return !has(attrs, "\\Noselect") && !has(attrs, "\\NonExistent");
    }

    private static boolean has(String[] attrs, String attr) {
        if (attrs != null) {
            for (String a : attrs) {
                if (attr.equalsIgnoreCase(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Resolution fail(ToolError e) {
        return new Resolution(null, e);
    }
}
