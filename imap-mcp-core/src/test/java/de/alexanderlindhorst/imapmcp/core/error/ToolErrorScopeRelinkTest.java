package de.alexanderlindhorst.imapmcp.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolErrorScopeRelinkTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(ToolError e) {
        return (Map<String, Object>) e.toMap().get("error");
    }

    @Test
    void insufficientScopeIsPermissionDeniedWithReasonAndScopeAndNotRetryable() {
        ToolError e = ToolError.insufficientScope("imap-mcp:write", "r1");
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(error(e)).containsEntry("retryable", false).containsEntry("request_id", "r1");
        assertThat(e.details()).containsEntry("reason", "insufficient_scope").containsEntry("scope", "imap-mcp:write");
        assertThat((String) error(e).get("hint")).contains("imap-mcp:write").contains("authorize");
    }

    @Test
    void relinkRequiredIsAuthFailedWithReasonAndLinkUrlAndNotRetryable() {
        ToolError e = ToolError.relinkRequired("https://mail.example.com/accounts", "r2");
        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(error(e)).containsEntry("retryable", false);
        assertThat(e.details()).containsEntry("reason", "relink_required")
                .containsEntry("link_url", "https://mail.example.com/accounts");
        assertThat((String) error(e).get("hint")).containsIgnoringCase("re-link");
    }

    @Test
    void relinkRequiredWithoutALinkUrlOmitsIt() {
        ToolError e = ToolError.relinkRequired(null, "r3");
        assertThat(e.details()).containsEntry("reason", "relink_required").doesNotContainKey("link_url");
    }

    @Test
    void neitherPayloadLeaksAnythingButClosedValues() {
        String json = ToolError.relinkRequired("https://x/accounts", "r").toMap().toString()
                + ToolError.insufficientScope("imap-mcp:write", "r").toMap();
        assertThat(json).doesNotContain("password").doesNotContain("host");
    }
}
