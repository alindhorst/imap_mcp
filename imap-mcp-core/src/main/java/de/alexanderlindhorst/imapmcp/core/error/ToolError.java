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

    /** Closed set of PERMISSION_DENIED reasons (details.reason). */
    public enum PermissionReason {
        ACCOUNT_READ_ONLY("account_read_only"),
        FOLDER_READ_ONLY("folder_read_only"),
        SERVER_DENIED("server_denied"),
        PURGE_DISABLED("purge_disabled"),
        PURGE_SCOPE("purge_scope"),
        CONFIRMATION_UNAVAILABLE("confirmation_unavailable"),
        NOT_CONFIRMED("not_confirmed");

        private final String wire;

        PermissionReason(String wire) {
            this.wire = wire;
        }
    }

    /** Closed set of {@code details.elicitation} values of a {@code not_confirmed} refusal (SPEC 4, purge_messages). */
    public enum ElicitationReason {
        DECLINED, CANCELLED, TIMEOUT, INVALID_ANSWER, ERROR, RECENTLY_DECLINED
    }

    /** Closed set of LOCAL_STORAGE_ERROR reasons (details.reason); never a path or exception text. */
    public enum StorageReason {
        DIRECTORY_UNAVAILABLE, NAME_EXHAUSTED, NO_SPACE, IO_ERROR
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

    /** The error object without the {"error":...} wrapper and without request_id (per-item error of a bulk result). */
    public Map<String, Object> toItemMap() {
        @SuppressWarnings("unchecked")
        Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) toMap().get("error"));
        e.remove("request_id");
        return e;
    }

    /**
     * Marks that a write may have been applied (details.outcome "unknown"), appended after the existing details.
     * Only valid for TIMEOUT, UPSTREAM_UNAVAILABLE and INTERNAL_ERROR.
     */
    public ToolError withOutcomeUnknown() {
        if (code != ErrorCode.TIMEOUT && code != ErrorCode.UPSTREAM_UNAVAILABLE && code != ErrorCode.INTERNAL_ERROR) {
            throw new IllegalStateException("details.outcome is not defined for " + code);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        if (details != null) {
            d.putAll(details);
        }
        d.put("outcome", "unknown");
        return new ToolError(code, message, hint, retryAfterMs, d, requestId);
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

    /** Multi-handle tools: names the first failing position (the client's own input, e.g. handles[3]). */
    public static ToolError invalidHandle(String field, String requestId) {
        ToolError v1 = invalidHandle(requestId);
        return of(v1.code, v1.message, v1.hint, detail("field", field), requestId);
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
                "Update credentials / app password in server config. Do not retry; the credentials must be fixed by the operator.",
                null, requestId);
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
        return upstreamError(serverText, null, requestId);
    }

    /** responseCode must be one of a closed set of RFC 5530 atoms chosen by the caller, never server text. */
    public static ToolError upstreamError(String serverText, String responseCode, String requestId) {
        return of(ErrorCode.UPSTREAM_ERROR,
                "The IMAP server rejected the request. Adjust the request or try later.",
                "See details.server_text; the text comes from the server and is not trusted.",
                detail("response_code", responseCode, "server_text",
                        serverText == null ? null : UntrustedText.singleLine(serverText, UntrustedText.UPSTREAM_ERROR_MAX)), requestId);
    }

    /**
     * The error of an item whose copy exists in the target but whose original was not removed (move without MOVE,
     * STORE or UID EXPUNGE refused). Never retryable: repeating the call copies again. A refusal that maps to a
     * retryable code becomes UPSTREAM_ERROR; {@code responseCode} (INUSE or UNAVAILABLE only) is then kept in
     * details.response_code.
     */
    public ToolError copiedNotRemoved(String responseCode) {
        ToolError mapped = this;
        Map<String, Object> d = new LinkedHashMap<>();
        if (mapped.details() != null) {
            d.putAll(mapped.details());
        }
        ErrorCode c = mapped.code();
        String message = mapped.message();
        if (c.retryable()) {
            c = ErrorCode.UPSTREAM_ERROR;
            message = "The IMAP server refused to remove the original messages. Check the source and target folders.";
            if ("INUSE".equals(responseCode) || "UNAVAILABLE".equals(responseCode)) {
                d.put("response_code", responseCode);
            }
        }
        return new ToolError(c, message, "The messages were copied to the target folder (new_handle) but not removed "
                + "from the source folder; repeating the call copies them again.", null, d, requestId());
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

    public static ToolError permissionDenied(PermissionReason reason, String requestId) {
        return of(ErrorCode.PERMISSION_DENIED, "The change is not permitted. Do not retry.",
                "The change was refused by configuration or by the server; do not retry. See details.reason.",
                detail("reason", reason.wire), requestId);
    }

    /** purge_messages on an account without {@code allowPurge} (SPEC 4 check order step 3). */
    public static ToolError purgeDisabled(String requestId) {
        String hint = "Permanent deletion is off for this account; the operator enables it with accounts[].allowPurge "
                + "in the configuration. Do not retry.";
        return of(ErrorCode.PERMISSION_DENIED, "Permanent deletion is not enabled for this account. Do not retry.",
                hint, detail("reason", PermissionReason.PURGE_DISABLED.wire), requestId);
    }

    /** purge_messages in a session that cannot ask the user (no form-mode elicitation). */
    public static ToolError confirmationUnavailable(String requestId) {
        String hint = "Purging needs a client that supports MCP elicitation; nothing was deleted.";
        return of(ErrorCode.PERMISSION_DENIED, "The user cannot be asked to confirm. " + hint, hint,
                detail("reason", PermissionReason.CONFIRMATION_UNAVAILABLE.wire), requestId);
    }

    /** A handle outside the Trash folder; {@code field} is the client's own position, e.g. handles[3]. */
    public static ToolError purgeScope(String field, String requestId) {
        String hint = "Permanent deletion is only possible from Trash; move the messages there with delete_messages "
                + "first.";
        return of(ErrorCode.PERMISSION_DENIED, "These messages are not in the Trash folder. " + hint, hint,
                detail("reason", PermissionReason.PURGE_SCOPE.wire, "field", field), requestId);
    }

    /** The user did not confirm a purge; nothing was deleted. */
    public static ToolError notConfirmed(ElicitationReason elicitation, String requestId) {
        String hint = "The user did not confirm; nothing was deleted. Call again only if the user asks for it.";
        return of(ErrorCode.PERMISSION_DENIED, "The permanent deletion was not confirmed. Do not retry.", hint,
                detail("reason", PermissionReason.NOT_CONFIRMED.wire, "elicitation",
                        elicitation.name().toLowerCase(java.util.Locale.ROOT)), requestId);
    }

    public static ToolError quotaExceeded(String requestId) {
        return of(ErrorCode.QUOTA_EXCEEDED, "The mailbox quota is exceeded. Ask the user to free space; do not retry.",
                "Ask the user to free space in the mailbox; do not retry.", null, requestId);
    }

    public static ToolError outcomeUnknown(String requestId) {
        return of(ErrorCode.OUTCOME_UNKNOWN,
                "The outcome of the copy is unknown. Search the target folder before repeating.",
                "Search the target folder before repeating the call; otherwise the copies are duplicated.", null,
                requestId);
    }

    /** The message of a tool's OUTCOME_UNKNOWN; downloads say that the file may exist (SPEC 5.5). */
    public static ToolError outcomeUnknown(String tool, String requestId) {
        if (!"download_attachment".equals(tool)) {
            return outcomeUnknown(requestId);
        }
        return of(ErrorCode.OUTCOME_UNKNOWN,
                "The outcome of the download is unknown: the file may have been saved. Check the download directory "
                        + "before repeating.",
                "Check the download directory before repeating the call; otherwise the file is saved twice.", null,
                requestId);
    }

    /** {@code size} is the estimated decoded size of the pre-fetch check; null when the streaming check fired. */
    public static ToolError tooLarge(Long size, long max, String requestId) {
        String hint = "The attachment is larger than the configured download limit; the operator can raise "
                + "tools.download.maxBytes. Do not retry.";
        return of(ErrorCode.TOO_LARGE, "The attachment exceeds the download size limit.", hint,
                detail("size", size, "max", max), requestId);
    }

    public static ToolError localStorage(StorageReason reason, String requestId) {
        return of(ErrorCode.LOCAL_STORAGE_ERROR, "The attachment could not be saved to the download directory.",
                "Ask the operator to check the download directory; see details.reason. Do not retry.",
                detail("reason", reason.name().toLowerCase(java.util.Locale.ROOT)), requestId);
    }

    /** {@code belowRfc822}: the path leads into a message/rfc822 part, which is not descended. */
    public static ToolError partNotFound(boolean belowRfc822, String requestId) {
        String hint = belowRfc822
                ? "Parts inside a message/rfc822 part cannot be addressed; download the message/rfc822 part itself."
                : "Call list_attachments for the part paths of this message.";
        return invalidArgumentHint("part", "part_not_found", hint, requestId);
    }

    public static ToolError notALeaf(String requestId) {
        return invalidArgumentHint("part", "not_a_leaf",
                "This part only contains other parts; choose one of them (see list_attachments).", requestId);
    }

    public static ToolError unsupportedTransferEncoding(String requestId) {
        return of(ErrorCode.UNSUPPORTED, "The attachment uses a transfer encoding this server cannot decode.",
                "Only base64, quoted-printable, 7bit, 8bit and binary parts can be downloaded.",
                detail("reason", "transfer_encoding"), requestId);
    }

    public static ToolError decodeFailed(String requestId) {
        return of(ErrorCode.UNSUPPORTED, "The attachment content is malformed and could not be decoded.",
                "The message part is damaged; it cannot be downloaded.", detail("reason", "decode_failed"), requestId);
    }
}
