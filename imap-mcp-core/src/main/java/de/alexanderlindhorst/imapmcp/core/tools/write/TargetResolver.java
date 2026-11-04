package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.folder.FolderRole;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Target of the v2 write tools (SPEC 4 "v2 write contract", 5.4): {@code target_folder} (verbatim full name) or
 * {@code target_folder_role} (SPECIAL-USE only), and the {@code \Trash} role for {@code delete_messages}. The target
 * is resolved by LIST only (store-level, selectable) and never selected; there is no INBOX default.
 */
public final class TargetResolver {

    static final String TARGET_FOLDER = "target_folder";
    static final String TARGET_FOLDER_ROLE = "target_folder_role";

    private static final String TRASH_HINT = "No folder with the \\Trash role exists. "
            + "Use move_messages with an explicit target_folder.";

    private TargetResolver() {
    }

    /**
     * Argument shape of move/copy (check order step 1, before any I/O): exactly one target, the verbatim name at most
     * 1000 UTF-8 bytes. {@code null} when valid.
     */
    public static ToolError checkShape(String targetFolder, FolderRole role, String requestId) {
        if (targetFolder == null && role == null) {
            return ToolError.invalidArgument(TARGET_FOLDER, "required", null, requestId);
        }
        if (targetFolder != null && role != null) {
            return ToolError.invalidArgument(TARGET_FOLDER_ROLE, "mutually_exclusive", null, requestId);
        }
        if (targetFolder != null && (targetFolder.isEmpty()
                || targetFolder.getBytes(StandardCharsets.UTF_8).length > FolderResolver.MAX_FOLDER_NAME_BYTES)) {
            return ToolError.invalidArgument(TARGET_FOLDER, "out_of_range", FolderResolver.MAX_FOLDER_NAME_BYTES,
                    requestId);
        }
        return null;
    }

    /** Check order step 8: resolves the target of move/copy by LIST. The shape is re-checked, so no I/O on a bad one. */
    public static FolderResolver.Resolution resolve(ImapLease lease, String targetFolder, FolderRole role,
            String requestId) throws MessagingException {
        ToolError shape = checkShape(targetFolder, role, requestId);
        if (shape != null) {
            return new FolderResolver.Resolution(null, shape);
        }
        return FolderResolver.resolve(lease, targetFolder, role, requestId, TARGET_FOLDER_ROLE);
    }

    /** The Trash of {@code delete_messages}: role {@code trash} by SPECIAL-USE; missing is FOLDER_NOT_FOUND with a hint. */
    public static FolderResolver.Resolution resolveTrash(ImapLease lease, String requestId)
            throws MessagingException {
        FolderResolver.Resolution r = FolderResolver.resolve(lease, null, FolderRole.TRASH, requestId);
        if (r.error() != null && r.error().code() == ErrorCode.FOLDER_NOT_FOUND) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("folder_role", FolderRole.TRASH.wire());
            return new FolderResolver.Resolution(null, new ToolError(ErrorCode.FOLDER_NOT_FOUND,
                    "Folder not found. " + TRASH_HINT, TRASH_HINT, null, details, requestId));
        }
        return r;
    }

    /** Step 8, after resolution: a role target of copy_messages that is a source folder. {@code null} when fine. */
    public static ToolError sameFolderForRole(String resolvedTarget, Collection<String> sourceFolders,
            String requestId) {
        if (sourceFolders.contains(resolvedTarget)) {
            return ToolError.invalidArgument(TARGET_FOLDER_ROLE, "same_folder", null, requestId);
        }
        return null;
    }
}
