package de.alexanderlindhorst.imapmcp.core.oauth;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.time.Duration;

/**
 * An access token could not be obtained. Carries no cause or message text from the provider, so it can never hold
 * a token; {@link #toolError} is the mapping to the tool error contract (SPEC 4 and 5.5).
 */
public final class TokenAcquisitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private enum Kind {
        INVALID_GRANT, CLIENT_REJECTED, UNAVAILABLE, LOCK_TIMEOUT, PROVIDER_UNCONFIGURED, WAIT_TIMEOUT, BUSY, INTERNAL
    }

    private final Kind kind;
    private final long retryAfterMs;

    private TokenAcquisitionException(Kind kind, long retryAfterMs) {
        super("token acquisition failed: " + kind, null, false, false);
        this.kind = kind;
        this.retryAfterMs = retryAfterMs;
    }

    /** The failure of a non-successful {@link RefreshResult}. */
    public static TokenAcquisitionException of(RefreshResult failure) {
        return switch (failure) {
            case RefreshResult.InvalidGrant f -> new TokenAcquisitionException(Kind.INVALID_GRANT, 0);
            case RefreshResult.ClientRejected f -> new TokenAcquisitionException(Kind.CLIENT_REJECTED, 0);
            case RefreshResult.Unavailable f -> new TokenAcquisitionException(Kind.UNAVAILABLE,
                    f.retryAfter() == null ? ToolError.DEFAULT_RETRY_AFTER_MS : f.retryAfter().toMillis());
            case RefreshResult.LockTimeout f -> new TokenAcquisitionException(Kind.LOCK_TIMEOUT, 0);
            case RefreshResult.ProviderUnconfigured f -> new TokenAcquisitionException(Kind.PROVIDER_UNCONFIGURED, 0);
            case RefreshResult.Refreshed f -> throw new IllegalArgumentException("not a failure");
        };
    }

    /** The caller's deadline passed while waiting; the refresh itself continues. */
    public static TokenAcquisitionException waitTimeout() {
        return new TokenAcquisitionException(Kind.WAIT_TIMEOUT, 0);
    }

    /** The refresh executor is saturated. */
    public static TokenAcquisitionException busy() {
        return new TokenAcquisitionException(Kind.BUSY, 0);
    }

    public static TokenAcquisitionException internal() {
        return new TokenAcquisitionException(Kind.INTERNAL, 0);
    }

    /** True for a failure of the token endpoint, where a still valid cached token may be used instead. */
    boolean endpointUnavailable() {
        return kind == Kind.UNAVAILABLE;
    }

    public Duration retryAfter() {
        return Duration.ofMillis(retryAfterMs);
    }

    /** {@code linkUrl} (may be null) is only used for the re-link error. */
    public ToolError toolError(String linkUrl, String requestId) {
        return switch (kind) {
            case INVALID_GRANT -> ToolError.relinkRequired(linkUrl, requestId);
            case CLIENT_REJECTED -> ToolError.oauthClientRejected(requestId);
            case UNAVAILABLE -> ToolError.tokenRefreshUnavailable(retryAfterMs, requestId);
            case LOCK_TIMEOUT, BUSY -> ToolError.serverBusy(requestId);
            case PROVIDER_UNCONFIGURED -> ToolError.providerUnconfigured(requestId);
            case WAIT_TIMEOUT -> ToolError.timeout(requestId);
            case INTERNAL -> ToolError.internalError(requestId);
        };
    }
}
