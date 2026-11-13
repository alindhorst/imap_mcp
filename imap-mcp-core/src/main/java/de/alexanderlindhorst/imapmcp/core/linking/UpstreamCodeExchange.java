package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.account.Secret;

/**
 * The authorization-code exchange at the provider's token endpoint (SPEC 5.3 "Upstream token egress"). Never throws
 * for a provider answer; the result carries no response text.
 */
public interface UpstreamCodeExchange {

    sealed interface Result {

        /** {@code refreshToken} is null when the provider returned none; {@code scope} null when it did not say. */
        record Granted(Secret accessToken, Secret refreshToken, String scope) implements Result {

            /** Wipes both tokens. */
            public void close() {
                accessToken.close();
                if (refreshToken != null) {
                    refreshToken.close();
                }
            }

            @Override
            public String toString() {
                return "Granted[***]";
            }
        }

        /** {@code invalid_grant} or {@code invalid_scope}. */
        record InvalidGrant() implements Result {
        }

        /** Timeout, 5xx, 429, a refused client or an unusable answer. */
        record Unavailable() implements Result {
        }
    }

    /** Inputs are not retained or wiped. */
    Result exchange(UpstreamProviderInfo provider, char[] code, char[] codeVerifier);
}
