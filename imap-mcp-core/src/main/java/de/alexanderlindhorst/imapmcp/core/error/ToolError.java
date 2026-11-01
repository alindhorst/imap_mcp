package de.alexanderlindhorst.imapmcp.core.error;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured tool error. Factories never accept handles, cursors, exceptions or other client/host supplied
 * values except the explicitly documented detail fields, so payloads cannot echo them.
 */
public record ToolError(ErrorCode code, String message, String hint, Integer retryAfterMs,
        Map<String, Object> details, String requestId) {

    public static final int DEFAULT_RETRY_AFTER_MS = 2000;

    public enum StaleReason {
        UIDVALIDITY_CHANGED("uidvalidity_changed"),
        FOLDER_MISSING("folder_missing");

        private final String wire;

        StaleReason(String wire) {
            this.wire = wire;
        }
    }

    public ToolError {
        details = details == null || details.isEmpty() ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public boolean retryable() {
        return code.retryable();
    }

    /** Wire shape: {"error":{code,message,retryable,hint,retry_after_ms?,details?,request_id}}, in that order. */
    public Map<String, Object> toMap() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code.name());
        e.put("message", message);
        e.put("retryable", retryable());
        e.put("hint", hint);
        if (retryAfterMs != null) {
            e.put("retry_after_ms", retryAfterMs);
        }
        if (details != null) {
            e.put("details", details);
        }
        e.put("request_id", requestId);
        return Map.of("error", e);
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    private static ToolError of(ErrorCode c, String message, String hint, Map<String, Object> details, String rid) {
        return new ToolError(c, message, hint, null, details, rid);
    }

    public static ToolError invalidArgument(String field, String reason, Integer max, String requestId) {
        String fix = "Fix the field named in details and retry.";
        return of(ErrorCode.INVALID_ARGUMENT, "Invalid argument. " + fix, fix,
                detail("field", field, "reason", reason, "max", max), requestId);
    }

    /** Like {@link #invalidArgument} without max, but with a hint specific to the reason (a fixed literal). */
    public static ToolError invalidArgumentHint(String field, String reason, String hint, String requestId) {
        return of(ErrorCode.INVALID_ARGUMENT, "Invalid argument. " + hint, hint,
                detail("field", field, "reason", reason), requestId);
    }

    public static ToolError accountNotFound(String requestId) {
        return of(ErrorCode.ACCOUNT_NOT_FOUND, "Unknown account handle. Call list_accounts for valid handles.",
                "Call list_accounts for valid handles.", null, requestId);
    }

    public static ToolError invalidHandle(String requestId) {
        return of(ErrorCode.INVALID_HANDLE, "Invalid message handle. Obtain a handle via search_messages.",
                "Handle is invalid or not from this account; obtain handles via search_messages for this account.",
                null, requestId);
    }

    public static ToolError invalidCursor(String requestId) {
        return of(ErrorCode.INVALID_CURSOR, "Invalid cursor. Re-run search_messages without a cursor.",
                "Re-run search_messages without cursor.", null, requestId);
    }

    /** Only to be used after the cursor MAC verified (see cursor codec). */
    public static ToolError invalidCursorCriteriaMismatch(String requestId) {
        return of(ErrorCode.INVALID_CURSOR,
                "Cursor does not belong to these search criteria. Re-run search_messages without a cursor.",
                "Re-run search_messages without cursor.", detail("reason", "criteria_mismatch"), requestId);
    }

    public static ToolError staleReference(StaleReason reason, String requestId) {
        return of(ErrorCode.STALE_REFERENCE,
                "The folder changed since this reference was issued. Re-run search_messages without a cursor.",
                "re-run search_messages without cursor", detail("reason", reason.wire), requestId);
    }

    public static ToolError messageNotFound(String requestId) {
        return of(ErrorCode.MESSAGE_NOT_FOUND, "Message not found. Re-run search_messages.",
                "Message was deleted or moved; re-run search_messages.", null, requestId);
    }

    public static ToolError folderNotFound(String folder, String requestId) {
        return folderNotFound(detail("folder", folder), requestId);
    }

    public static ToolError folderRoleNotFound(String folderRole, String requestId) {
        return folderNotFound(detail("folder_role", folderRole), requestId);
    }

    private static ToolError folderNotFound(Map<String, Object> details, String requestId) {
        return of(ErrorCode.FOLDER_NOT_FOUND, "Folder not found. Call list_folders to see available roles.",
                "Call list_folders to see available roles.", details, requestId);
    }

    public static ToolError authFailed(String requestId) {
        return of(ErrorCode.AUTH_FAILED,
                "Authentication to the IMAP server failed. Ask the operator to check the account credentials.",
                "Do not retry; the credentials must be fixed by the operator.", null, requestId);
    }

    public static ToolError serverBusy(String requestId) {
        return serverBusy(DEFAULT_RETRY_AFTER_MS, requestId);
    }

    public static ToolError serverBusy(int retryAfterMs, String requestId) {
        return new ToolError(ErrorCode.SERVER_BUSY, "Server busy. Retry after retry_after_ms.",
                "Retry the same call after retry_after_ms.", retryAfterMs, null, requestId);
    }

    public static ToolError timeout(String requestId) {
        return of(ErrorCode.TIMEOUT, "The IMAP operation timed out. Retry, or narrow the request.",
                "Retry the same call, or use a smaller limit.", null, requestId);
    }

    public static ToolError upstreamUnavailable(String requestId) {
        return of(ErrorCode.UPSTREAM_UNAVAILABLE, "The IMAP server is unreachable. Retry later.",
                "Retry the same call later.", null, requestId);
    }

    /** serverText is untrusted: control characters are stripped, collapsed to a single line and capped (UntrustedText). */
    public static ToolError upstreamError(String serverText, String requestId) {
        return of(ErrorCode.UPSTREAM_ERROR,
                "The IMAP server rejected the request. Adjust the request or try later.",
                "See details.server_text; the text comes from the server and is not trusted.",
                detail("server_text",
                        serverText == null ? null : UntrustedText.singleLine(serverText, UntrustedText.UPSTREAM_ERROR_MAX)), requestId);
    }

    public static ToolError unsupported(String capability, String requestId) {
        return of(ErrorCode.UNSUPPORTED,
                "The IMAP server does not support a required capability. Use a different tool or arguments.",
                "Check details.capability and avoid features that need it.", detail("capability", capability),
                requestId);
    }

    /** A role-resolved folder whose full name exceeds the 1000 byte handle limit; the name is not echoed. */
    public static ToolError folderNameTooLong(String requestId) {
        return of(ErrorCode.UNSUPPORTED, "The folder's full name exceeds 1000 UTF-8 bytes; this server cannot address it.",
                "Check details.capability and avoid features that need it.", detail("capability", "folder_name_length"),
                requestId);
    }

    public static ToolError internalError(String requestId) {
        return of(ErrorCode.INTERNAL_ERROR, "Internal error. Report the request_id to the operator.",
                "Report the request_id to the operator.", null, requestId);
    }
}
