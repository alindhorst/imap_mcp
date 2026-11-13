package de.alexanderlindhorst.imapmcp.store.oauth;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Typed outcome of a token-endpoint call (SPEC 5.3 "Upstream token egress", 5.5). Never carries response text. */
public sealed interface TokenResponse {

    /**
     * A token grant. {@code rotatedRefreshToken} is null when the provider did not return a refresh token; the
     * secrets belong to the caller, who wipes them.
     */
    record Granted(Secret accessToken, Instant expiresAt, Secret rotatedRefreshToken, String scope)
            implements TokenResponse {
        public Granted {
            Objects.requireNonNull(accessToken, "accessToken");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override
        public String toString() {
            return "Granted[***, expiresAt=" + expiresAt + "]";
        }
    }

    /** {@code invalid_grant} or {@code invalid_scope}. */
    record InvalidGrant() implements TokenResponse {
    }

    record InvalidClient() implements TokenResponse {
    }

    record UnauthorizedClient() implements TokenResponse {
    }

    /** 5xx, 429, timeout or a transport failure; {@code retryAfter} is the provider's hint, or null. */
    record Unavailable(Duration retryAfter) implements TokenResponse {
    }

    /** Not a usable answer: redirect, wrong content type, oversized, unparsable, unknown error code. */
    record Malformed() implements TokenResponse {
    }

    /** The host or one of its addresses is refused by the SSRF policy; nothing was connected. */
    record Refused() implements TokenResponse {
    }
}
