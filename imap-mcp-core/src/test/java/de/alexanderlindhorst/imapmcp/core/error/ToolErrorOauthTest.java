package de.alexanderlindhorst.imapmcp.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolErrorOauthTest {

    @Test
    void clientRejectedIsUpstreamErrorNotRetryable() {
        ToolError e = ToolError.oauthClientRejected("r1");
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(e.retryable()).isFalse();
        assertThat(e.details()).containsEntry("reason", "oauth_client_rejected");
        assertThat(e.hint()).contains("operator").contains("provider registration");
    }

    @Test
    void tokenRefreshUnavailableIsRetryableWithRetryAfterCappedAt60Seconds() {
        ToolError e = ToolError.tokenRefreshUnavailable(5_000, "r2");
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(e.retryable()).isTrue();
        assertThat(e.retryAfterMs()).isEqualTo(5_000);
        assertThat(e.details()).containsEntry("reason", "token_refresh_unavailable");
        assertThat(ToolError.tokenRefreshUnavailable(600_000, "r").retryAfterMs()).isEqualTo(60_000);
        assertThat(ToolError.tokenRefreshUnavailable(-1, "r").retryAfterMs()).isEqualTo(0);
    }

    @Test
    void providerUnconfiguredIsUpstreamErrorNotRetryable() {
        ToolError e = ToolError.providerUnconfigured("r3");
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(e.retryable()).isFalse();
        assertThat(e.details()).containsEntry("reason", "provider_unconfigured");
    }
}
