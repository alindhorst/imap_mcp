package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.error.ToolError.PermissionReason;
import jakarta.mail.MessagingException;
import jakarta.mail.ReadOnlyFolderException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.iap.Response;

/**
 * Maps upstream response codes on write commands to tool errors (SPEC 5.4, "Upstream response codes on writes").
 * An empty result means "not handled here": either the response is no failure, the code is informational
 * ({@code EXPUNGEISSUED}), or the throwable is not a protocol failure; the caller then falls back to
 * {@code ImapErrorMapper}. A NO or {@code [READ-ONLY]} does not break the lease, so the mapping never asks for
 * a discard. Server text is untrusted and goes through {@code ToolError.upstreamError}; no exception message is
 * copied into a payload. Nothing is logged.
 */
public final class WriteResponses {

    private static final int MAX_CHAIN = 16;
    private static final Pattern CODE = Pattern.compile("\\A\\[([A-Za-z0-9-]{1,32})(?: [^\\]]*)?\\]");

    /** Known RFC 5530 response codes reported as details.response_code of an UPSTREAM_ERROR. */
    private static final Set<String> RFC5530 = Set.of("UNAVAILABLE", "AUTHENTICATIONFAILED", "AUTHORIZATIONFAILED",
            "EXPIRED", "PRIVACYREQUIRED", "CONTACTADMIN", "NOPERM", "INUSE", "EXPUNGEISSUED", "CORRUPTION",
            "SERVERBUG", "CLIENTBUG", "CANNOT", "LIMIT", "OVERQUOTA", "ALREADYEXISTS", "NONEXISTENT");

    private WriteResponses() {
    }

    /**
     * @param targetFolder the target folder's full name, reported for {@code [TRYCREATE]}
     */
    public static Optional<ToolError> map(Response r, String targetFolder, String requestId) {
        if (r == null) {
            return Optional.empty();
        }
        String rest = r.getRest() == null ? "" : r.getRest().stripLeading();
        if (r.isOK()) {
            return "READ-ONLY".equals(code(rest)) ? Optional.of(readOnly(requestId)) : Optional.empty();
        }
        if (!r.isNO() && !r.isBAD()) {
            return Optional.empty();
        }
        return mapFailure(rest, targetFolder, requestId);
    }

    /** Walks the cause / next-exception chain; the first ReadOnlyFolderException or protocol failure decides. */
    public static Optional<ToolError> map(Throwable t, String targetFolder, String requestId) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> queue = new ArrayDeque<>();
        queue.add(t);
        while (!queue.isEmpty() && seen.size() < MAX_CHAIN) {
            Throwable cur = queue.poll();
            if (!seen.add(cur)) {
                continue;
            }
            if (cur instanceof ReadOnlyFolderException) {
                return Optional.of(readOnly(requestId));
            }
            if (cur instanceof ProtocolException pe) {
                Response r = pe.getResponse();
                if (r != null) {
                    Optional<ToolError> m = map(r, targetFolder, requestId);
                    if (m.isPresent() || r.isNO() || r.isBAD()) {
                        return m;
                    }
                }
            }
            if (cur.getCause() != null) {
                queue.add(cur.getCause());
            }
            if (cur instanceof MessagingException me && me.getNextException() != null) {
                queue.add(me.getNextException());
            }
        }
        return Optional.empty();
    }

    private static Optional<ToolError> mapFailure(String rest, String targetFolder, String rid) {
        String code = code(rest);
        if (code == null) {
            return Optional.of(ToolError.upstreamError(rest, rid));
        }
        return switch (code) {
            case "TRYCREATE" -> Optional.of(ToolError.folderNotFound(targetFolder, rid));
            case "OVERQUOTA" -> Optional.of(ToolError.quotaExceeded(rid));
            case "READ-ONLY" -> Optional.of(readOnly(rid));
            case "NOPERM" -> Optional.of(ToolError.permissionDenied(PermissionReason.SERVER_DENIED, rid));
            case "INUSE", "UNAVAILABLE" -> Optional.of(ToolError.upstreamUnavailable(rid));
            case "EXPUNGEISSUED" -> Optional.empty();
            default -> Optional.of(ToolError.upstreamError(rest, RFC5530.contains(code) ? code : null, rid));
        };
    }

    private static ToolError readOnly(String rid) {
        return ToolError.permissionDenied(PermissionReason.FOLDER_READ_ONLY, rid);
    }

    /** The response code atom of a tagged response, upper-cased, or null. */
    static String responseCode(Response r) {
        return r == null || r.getRest() == null ? null : code(r.getRest().stripLeading());
    }

    /** The upper-cased response code atom at the start of the text, or null. */
    private static String code(String rest) {
        Matcher m = CODE.matcher(rest);
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null;
    }
}
