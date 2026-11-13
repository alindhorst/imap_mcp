package de.alexanderlindhorst.imapmcp.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TokenAcquisitionExceptionTest {

    private static ToolError map(RefreshResult r) {
        return TokenAcquisitionException.of(r).toolError("https://link", "rid");
    }

    @Test
    void invalidGrantIsRelinkRequired() {
        ToolError e = map(new RefreshResult.InvalidGrant());
        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(e.retryable()).isFalse();
        assertThat(e.details()).containsEntry("reason", "relink_required").containsEntry("link_url", "https://link");
    }

    @Test
    void clientRejectedIsUpstreamError() {
        ToolError e = map(new RefreshResult.ClientRejected());
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(e.details()).containsEntry("reason", "oauth_client_rejected");
    }

    @Test
    void unavailableIsRetryableWithCappedRetryAfter() {
        ToolError e = map(new RefreshResult.Unavailable(Duration.ofMinutes(10)));
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(e.retryable()).isTrue();
        assertThat(e.retryAfterMs()).isEqualTo(60_000);
        assertThat(e.details()).containsEntry("reason", "token_refresh_unavailable");
        assertThat(map(new RefreshResult.Unavailable(null)).retryAfterMs()).isEqualTo(ToolError.DEFAULT_RETRY_AFTER_MS);
    }

    @Test
    void lockTimeoutIsServerBusy() {
        ToolError e = map(new RefreshResult.LockTimeout());
        assertThat(e.code()).isEqualTo(ErrorCode.SERVER_BUSY);
        assertThat(e.retryable()).isTrue();
    }

    @Test
    void providerUnconfiguredIsUpstreamError() {
        ToolError e = map(new RefreshResult.ProviderUnconfigured());
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(e.details()).containsEntry("reason", "provider_unconfigured");
    }

    @Test
    void waitTimeoutIsTimeoutAndSaturationIsServerBusy() {
        assertThat(TokenAcquisitionException.waitTimeout().toolError(null, "r").code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(TokenAcquisitionException.busy().toolError(null, "r").code()).isEqualTo(ErrorCode.SERVER_BUSY);
        assertThat(TokenAcquisitionException.internal().toolError(null, "r").code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
