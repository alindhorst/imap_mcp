package de.alexanderlindhorst.imapmcp.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ToolErrorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RID = "req-1";

    private static String json(ToolError e) {
        return JSON.writeValueAsString(e.toMap());
    }

    // AC1: golden JSON, exact shape and key order

    @Test
    void goldenAccountNotFound() {
        assertThat(json(ToolError.accountNotFound(RID))).isEqualTo(
                "{\"error\":{\"code\":\"ACCOUNT_NOT_FOUND\","
                        + "\"message\":\"Unknown account handle. Call list_accounts for valid handles.\","
                        + "\"retryable\":false,\"hint\":\"Call list_accounts for valid handles.\","
                        + "\"request_id\":\"req-1\"}}");
    }

    @Test
    void goldenAuthFailed() {
        assertThat(json(ToolError.authFailed(RID))).isEqualTo(
                "{\"error\":{\"code\":\"AUTH_FAILED\","
                        + "\"message\":\"Authentication to the IMAP server failed. Ask the operator to check the account "
                        + "credentials.\",\"retryable\":false,"
                        + "\"hint\":\"Update credentials / app password in server config. Do not retry; the credentials "
                        + "must be fixed by the operator.\","
                        + "\"request_id\":\"req-1\"}}");
    }

    @Test
    void goldenInvalidHandle() {
        assertThat(json(ToolError.invalidHandle(RID))).isEqualTo(
                "{\"error\":{\"code\":\"INVALID_HANDLE\","
                        + "\"message\":\"Invalid message handle. Obtain a handle via search_messages.\","
                        + "\"retryable\":false,\"hint\":\"Handle is invalid or not from this account; "
                        + "obtain handles via search_messages for this account.\","
                        + "\"request_id\":\"req-1\"}}");
    }

    @Test
    void goldenInvalidCursorWithoutDetails() {
        assertThat(json(ToolError.invalidCursor(RID))).isEqualTo(
                "{\"error\":{\"code\":\"INVALID_CURSOR\","
                        + "\"message\":\"Invalid cursor. Re-run search_messages without a cursor.\","
                        + "\"retryable\":false,\"hint\":\"Re-run search_messages without cursor.\","
                        + "\"request_id\":\"req-1\"}}");
    }

    @Test
    void goldenInvalidCursorCriteriaMismatch() {
        assertThat(json(ToolError.invalidCursorCriteriaMismatch(RID))).isEqualTo(
                "{\"error\":{\"code\":\"INVALID_CURSOR\","
                        + "\"message\":\"Cursor does not belong to these search criteria. "
                        + "Re-run search_messages without a cursor.\","
                        + "\"retryable\":false,\"hint\":\"Re-run search_messages without cursor.\","
                        + "\"details\":{\"reason\":\"criteria_mismatch\"},\"request_id\":\"req-1\"}}");
    }

    @Test
    void goldenStaleReference() {
        assertThat(json(ToolError.staleReference(ToolError.StaleReason.UIDVALIDITY_CHANGED, RID))).isEqualTo(
                "{\"error\":{\"code\":\"STALE_REFERENCE\","
                        + "\"message\":\"The folder changed since this reference was issued. "
                        + "Re-run search_messages without a cursor.\","
                        + "\"retryable\":false,\"hint\":\"re-run search_messages without cursor\","
                        + "\"details\":{\"reason\":\"uidvalidity_changed\"},\"request_id\":\"req-1\"}}");
        assertThat(json(ToolError.staleReference(ToolError.StaleReason.FOLDER_MISSING, RID)))
                .contains("\"details\":{\"reason\":\"folder_missing\"}");
    }

    @Test
    void goldenMessageNotFound() {
        assertThat(json(ToolError.messageNotFound(RID))).isEqualTo(
                "{\"error\":{\"code\":\"MESSAGE_NOT_FOUND\","
                        + "\"message\":\"Message not found. Re-run search_messages.\","
                        + "\"retryable\":false,"
                        + "\"hint\":\"Message was deleted or moved; re-run search_messages.\","
                        + "\"request_id\":\"req-1\"}}");
    }

    @Test
    void goldenFolderNotFoundByNameAndRole() {
        assertThat(json(ToolError.folderNotFound("Archive/2020", RID))).isEqualTo(
                "{\"error\":{\"code\":\"FOLDER_NOT_FOUND\","
                        + "\"message\":\"Folder not found. Call list_folders to see available roles.\","
                        + "\"retryable\":false,\"hint\":\"Call list_folders to see available roles.\","
                        + "\"details\":{\"folder\":\"Archive/2020\"},\"request_id\":\"req-1\"}}");
        assertThat(json(ToolError.folderRoleNotFound("trash", RID)))
                .contains("\"details\":{\"folder_role\":\"trash\"}");
    }

    @Test
    void goldenInvalidArgumentWithAndWithoutMax() {
        assertThat(json(ToolError.invalidArgument("limit", "too_large", 100, RID))).isEqualTo(
                "{\"error\":{\"code\":\"INVALID_ARGUMENT\","
                        + "\"message\":\"Invalid argument. Fix the field named in details and retry.\","
                        + "\"retryable\":false,\"hint\":\"Fix the field named in details and retry.\","
                        + "\"details\":{\"field\":\"limit\",\"reason\":\"too_large\",\"max\":100},"
                        + "\"request_id\":\"req-1\"}}");
        assertThat(json(ToolError.invalidArgument("query", "missing", null, RID)))
                .contains("\"details\":{\"field\":\"query\",\"reason\":\"missing\"}");
    }

    @Test
    void goldenInvalidArgumentWithOwnHint() {
        assertThat(json(ToolError.invalidArgumentHint("since", "too_many_candidates", "Narrow `since`.", RID)))
                .isEqualTo("{\"error\":{\"code\":\"INVALID_ARGUMENT\","
                        + "\"message\":\"Invalid argument. Narrow `since`.\","
                        + "\"retryable\":false,\"hint\":\"Narrow `since`.\","
                        + "\"details\":{\"field\":\"since\",\"reason\":\"too_many_candidates\"},"
                        + "\"request_id\":\"req-1\"}}");
    }

    @Test
    void goldenUnsupported() {
        assertThat(json(ToolError.unsupported("SORT", RID))).isEqualTo(
                "{\"error\":{\"code\":\"UNSUPPORTED\","
                        + "\"message\":\"The IMAP server does not support a required capability. "
                        + "Use a different tool or arguments.\","
                        + "\"retryable\":false,\"hint\":\"Check details.capability and avoid features that need it.\","
                        + "\"details\":{\"capability\":\"SORT\"},\"request_id\":\"req-1\"}}");
    }

    @Test
    void goldenInternalErrorHasGenericMessageAndRequestIdOnly() {
        assertThat(json(ToolError.internalError(RID))).isEqualTo(
                "{\"error\":{\"code\":\"INTERNAL_ERROR\","
                        + "\"message\":\"Internal error. Report the request_id to the operator.\","
                        + "\"retryable\":false,\"hint\":\"Report the request_id to the operator.\","
                        + "\"request_id\":\"req-1\"}}");
    }

    @Test
    void goldenUpstreamErrorSanitizesAndCaps() {
        String text = "NO [AUTHORIZATIONFAILED]\r\n\tbad\u0000thing " + "x".repeat(400);
        Map<?, ?> err = (Map<?, ?>) ToolError.upstreamError(text, RID).toMap().get("error");
        String msg = (String) ((Map<?, ?>) err.get("details")).get("server_text");
        assertThat(msg).hasSizeLessThanOrEqualTo(200).doesNotContain("\n", "\r", "\t", "\u0000");
        assertThat(msg).startsWith("NO [AUTHORIZATIONFAILED]").contains("bad thing");
        assertThat(json(ToolError.upstreamError("NO nope", RID))).isEqualTo(
                "{\"error\":{\"code\":\"UPSTREAM_ERROR\","
                        + "\"message\":\"The IMAP server rejected the request. Adjust the request or try later.\","
                        + "\"retryable\":false,\"hint\":\"See details.server_text; the text comes from the server "
                        + "and is not trusted.\","
                        + "\"details\":{\"server_text\":\"NO nope\"},\"request_id\":\"req-1\"}}");
    }

    @Test
    void upstreamErrorStripsBidiAndDoesNotSplitSurrogatePairAtCap() {
        String text = "NO bad\r\n\u202Ereversed " + "x".repeat(185) + "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00";
        Map<?, ?> err = (Map<?, ?>) ToolError.upstreamError(text, RID).toMap().get("error");
        String out = (String) ((Map<?, ?>) err.get("details")).get("server_text");
        assertThat(out).doesNotContain("\n", "\r", "\u202E");
        assertThat(out.codePointCount(0, out.length())).isLessThanOrEqualTo(200);
        assertThat(out).startsWith("NO bad").doesNotContain("\uFFFD");
        assertThat(Character.isHighSurrogate(out.charAt(out.length() - 1))).isFalse();
        Map<String, Object> nullErr = (Map<String, Object>) ToolError.upstreamError(null, RID).toMap().get("error");
        assertThat(nullErr.containsKey("details")).isFalse();
    }

    @Test
    void goldenRetryableCodes() {
        assertThat(json(ToolError.serverBusy(RID))).isEqualTo(
                "{\"error\":{\"code\":\"SERVER_BUSY\","
                        + "\"message\":\"Server busy. Retry after retry_after_ms.\","
                        + "\"retryable\":true,\"hint\":\"Retry the same call after retry_after_ms.\","
                        + "\"retry_after_ms\":2000,\"request_id\":\"req-1\"}}");
        assertThat(json(ToolError.serverBusy(500, RID))).contains("\"retry_after_ms\":500");
    }

    // AC2: not-found and invalid-handle payloads independent of inputs

    @Test
    void accountNotFoundAndInvalidHandleAreByteIdenticalExceptRequestId() {
        assertThat(json(ToolError.accountNotFound("a")).replace("\"a\"", "\"X\""))
                .isEqualTo(json(ToolError.accountNotFound("b")).replace("\"b\"", "\"X\""));
        assertThat(json(ToolError.invalidHandle("a")).replace("\"a\"", "\"X\""))
                .isEqualTo(json(ToolError.invalidHandle("b")).replace("\"b\"", "\"X\""));
    }

    // AC3: no factory takes a handle/cursor; value-carrying parameters are enumerated

    @Test
    void handleAndCursorFactoriesTakeOnlyRequestId() throws Exception {
        for (String name : new String[] {"accountNotFound", "invalidHandle", "invalidCursor",
                "invalidCursorCriteriaMismatch", "messageNotFound", "internalError"}) {
            Method m = ToolError.class.getMethod(name, String.class);
            assertThat(m.getParameterCount()).as(name).isEqualTo(1);
        }
        // every other public factory may only take the documented non-handle values
        Set<Class<?>> allowed = Set.of(String.class, int.class, Integer.class, ToolError.StaleReason.class,
                ToolError.PermissionReason.class, ToolError.ElicitationReason.class);
        for (Method m : ToolError.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers())
                    && m.getReturnType() == ToolError.class) {
                assertThat(m.getParameterTypes()).as(m.getName()).allMatch(allowed::contains);
            }
        }
    }

    // AC4: retryable table and retry_after_ms only for SERVER_BUSY

    @Test
    void retryableTable() {
        Map<ErrorCode, Boolean> expected = new EnumMap<>(ErrorCode.class);
        for (ErrorCode c : ErrorCode.values()) {
            expected.put(c, c == ErrorCode.SERVER_BUSY || c == ErrorCode.TIMEOUT
                    || c == ErrorCode.UPSTREAM_UNAVAILABLE);
        }
        expected.forEach((c, r) -> assertThat(c.retryable()).as(c.name()).isEqualTo(r));
        assertThat(ErrorCode.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "INVALID_ARGUMENT", "ACCOUNT_NOT_FOUND", "INVALID_HANDLE", "INVALID_CURSOR", "STALE_REFERENCE",
                "MESSAGE_NOT_FOUND", "FOLDER_NOT_FOUND", "AUTH_FAILED", "SERVER_BUSY", "TIMEOUT",
                "UPSTREAM_UNAVAILABLE", "UPSTREAM_ERROR", "UNSUPPORTED", "INTERNAL_ERROR",
                "PERMISSION_DENIED", "QUOTA_EXCEEDED", "OUTCOME_UNKNOWN");
    }

    @Test
    void retryAfterOnlyForServerBusy() {
        for (ToolError e : new ToolError[] {ToolError.authFailed(RID), ToolError.timeout(RID),
                ToolError.upstreamUnavailable(RID), ToolError.internalError(RID), ToolError.messageNotFound(RID)}) {
            assertThat(e.retryAfterMs()).isNull();
            assertThat(json(e)).doesNotContain("retry_after_ms");
        }
        assertThat(ToolError.serverBusy(RID).retryAfterMs()).isEqualTo(2000);
        assertThat(ToolError.timeout(RID).retryable()).isTrue();
        assertThat(ToolError.upstreamUnavailable(RID).retryable()).isTrue();
        assertThat(ToolError.authFailed(RID).retryable()).isFalse();
    }

    // AC5: no stack traces, host names or exception class names

    @Test
    void payloadsNeverLeakExceptionDetails() {
        // the marker travels through the real mapper, whose result must not carry it
        ToolError mapped = ImapErrorMapper.map(new java.net.UnknownHostException("marker-host.example.invalid"), RID)
                .error();
        // the API cannot accept a Throwable
        for (Method m : ToolError.class.getDeclaredMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                assertThat(Throwable.class.isAssignableFrom(p)).as(m.getName()).isFalse();
            }
        }
        for (ToolError e : new ToolError[] {ToolError.internalError(RID), ToolError.upstreamUnavailable(RID),
                ToolError.timeout(RID), ToolError.authFailed(RID), ToolError.serverBusy(RID), mapped}) {
            assertThat(json(e)).doesNotContain("marker-host", "UnknownHostException", "java.", "\\tat ", "Exception");
        }
    }

    // v2 write error codes

    @Test
    void v2CodesAreNotRetryable() {
        assertThat(ErrorCode.PERMISSION_DENIED.retryable()).isFalse();
        assertThat(ErrorCode.QUOTA_EXCEEDED.retryable()).isFalse();
        assertThat(ErrorCode.OUTCOME_UNKNOWN.retryable()).isFalse();
    }

    @Test
    void goldenPermissionDenied() {
        assertThat(json(ToolError.permissionDenied(ToolError.PermissionReason.ACCOUNT_READ_ONLY, RID))).isEqualTo(
                "{\"error\":{\"code\":\"PERMISSION_DENIED\","
                        + "\"message\":\"The change is not permitted. Do not retry.\","
                        + "\"retryable\":false,"
                        + "\"hint\":\"The change was refused by configuration or by the server; do not retry. "
                        + "See details.reason.\","
                        + "\"details\":{\"reason\":\"account_read_only\"},\"request_id\":\"req-1\"}}");
        assertThat(json(ToolError.permissionDenied(ToolError.PermissionReason.FOLDER_READ_ONLY, RID)))
                .contains("\"details\":{\"reason\":\"folder_read_only\"}");
        assertThat(json(ToolError.permissionDenied(ToolError.PermissionReason.SERVER_DENIED, RID)))
                .contains("\"details\":{\"reason\":\"server_denied\"}");
        assertThat(ToolError.PermissionReason.values()).hasSize(7);
    }

    @Test
    void goldenPurgePermissionDenied() {
        String disabled = json(ToolError.purgeDisabled(RID));
        assertThat(disabled).contains("\"code\":\"PERMISSION_DENIED\"").contains("\"retryable\":false")
                .contains("\"details\":{\"reason\":\"purge_disabled\"}").contains("accounts[].allowPurge");
        String unavailable = json(ToolError.confirmationUnavailable(RID));
        assertThat(unavailable).contains("\"details\":{\"reason\":\"confirmation_unavailable\"}")
                .contains("Purging needs a client that supports MCP elicitation; nothing was deleted.");
        String scope = json(ToolError.purgeScope("handles[2]", RID));
        assertThat(scope).contains("\"details\":{\"reason\":\"purge_scope\",\"field\":\"handles[2]\"}")
                .contains("Permanent deletion is only possible from Trash; move the messages there with "
                        + "delete_messages first.");
        for (ToolError.ElicitationReason r : ToolError.ElicitationReason.values()) {
            String nc = json(ToolError.notConfirmed(r, RID));
            assertThat(nc).contains("\"details\":{\"reason\":\"not_confirmed\",\"elicitation\":\""
                    + r.name().toLowerCase() + "\"}")
                    .contains("The user did not confirm; nothing was deleted. Call again only if the user asks for it.")
                    .contains("\"retryable\":false");
        }
        assertThat(ToolError.ElicitationReason.values()).hasSize(6);
    }

    @Test
    void goldenQuotaExceeded() {
        assertThat(json(ToolError.quotaExceeded(RID))).isEqualTo(
                "{\"error\":{\"code\":\"QUOTA_EXCEEDED\","
                        + "\"message\":\"The mailbox quota is exceeded. Ask the user to free space; do not retry.\","
                        + "\"retryable\":false,"
                        + "\"hint\":\"Ask the user to free space in the mailbox; do not retry.\","
                        + "\"request_id\":\"req-1\"}}");
    }

    @Test
    void goldenOutcomeUnknown() {
        assertThat(json(ToolError.outcomeUnknown(RID))).isEqualTo(
                "{\"error\":{\"code\":\"OUTCOME_UNKNOWN\","
                        + "\"message\":\"The outcome of the copy is unknown. Search the target folder before repeating.\","
                        + "\"retryable\":false,"
                        + "\"hint\":\"Search the target folder before repeating the call; otherwise the copies are "
                        + "duplicated.\","
                        + "\"request_id\":\"req-1\"}}");
    }

    @Test
    void invalidHandleWithFieldAddsOnlyDetailsField() {
        String v1 = json(ToolError.invalidHandle(RID));
        String v2 = json(ToolError.invalidHandle("handles[3]", RID));
        assertThat(v2).isEqualTo(v1.replace("\"request_id\"",
                "\"details\":{\"field\":\"handles[3]\"},\"request_id\""));
        ToolError e = ToolError.invalidHandle("handles[3]", RID);
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_HANDLE);
        assertThat(e.message()).isEqualTo(ToolError.invalidHandle(RID).message());
        assertThat(e.hint()).isEqualTo(ToolError.invalidHandle(RID).hint());
    }

    @Test
    void withOutcomeUnknownAppendsDetailOutcome() {
        assertThat(json(ToolError.timeout(RID).withOutcomeUnknown()))
                .contains("\"details\":{\"outcome\":\"unknown\"},\"request_id\":\"req-1\"");
        assertThat(ToolError.upstreamUnavailable(RID).withOutcomeUnknown().details())
                .containsEntry("outcome", "unknown");
        assertThat(ToolError.internalError(RID).withOutcomeUnknown().details()).containsEntry("outcome", "unknown");
        // existing details are kept, outcome appended last
        ToolError withDetails = new ToolError(ErrorCode.TIMEOUT, "m", "h", null,
                Map.of("reason", "x"), RID).withOutcomeUnknown();
        assertThat(withDetails.details().keySet()).containsExactly("reason", "outcome");
        // the original is unchanged
        ToolError plain = ToolError.timeout(RID);
        plain.withOutcomeUnknown();
        assertThat(plain.details()).isNull();
    }

    @Test
    void withOutcomeUnknownRejectsOtherCodes() {
        for (ToolError e : new ToolError[] {ToolError.authFailed(RID), ToolError.serverBusy(RID),
                ToolError.quotaExceeded(RID), ToolError.outcomeUnknown(RID), ToolError.messageNotFound(RID),
                ToolError.upstreamError("NO", RID)}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(e::withOutcomeUnknown)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void toItemMapIsErrorObjectWithoutRequestId() {
        assertThat(JSON.writeValueAsString(ToolError.quotaExceeded(RID).toItemMap())).isEqualTo(
                "{\"code\":\"QUOTA_EXCEEDED\","
                        + "\"message\":\"The mailbox quota is exceeded. Ask the user to free space; do not retry.\","
                        + "\"retryable\":false,"
                        + "\"hint\":\"Ask the user to free space in the mailbox; do not retry.\"}");
        assertThat(JSON.writeValueAsString(
                ToolError.permissionDenied(ToolError.PermissionReason.SERVER_DENIED, RID).toItemMap()))
                .contains("\"details\":{\"reason\":\"server_denied\"}").doesNotContain("request_id");
        Map<String, Object> inner = (Map<String, Object>) ToolError.serverBusy(RID).toMap().get("error");
        Map<String, Object> item = new java.util.LinkedHashMap<>(inner);
        item.remove("request_id");
        assertThat(ToolError.serverBusy(RID).toItemMap()).isEqualTo(item);
        assertThat(ToolError.serverBusy(RID).toItemMap().keySet()).containsExactlyElementsOf(item.keySet());
    }
}
