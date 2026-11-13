package de.alexanderlindhorst.imapmcp.core.oauth;

import java.time.Duration;
import java.util.Objects;

/** Outcome of a {@link RefreshGrant#refresh}: a new token or a typed failure (SPEC 5.5, "Mapping"). */
public sealed interface RefreshResult {

    record Refreshed(AccessToken token) implements RefreshResult {
        public Refreshed {
            Objects.requireNonNull(token, "token");
        }
    }

    /** The provider answered {@code invalid_grant} (or {@code invalid_scope}): the account needs a re-link. */
    record InvalidGrant() implements RefreshResult {
    }

    /** {@code invalid_client} or {@code unauthorized_client}: a provider registration problem, never flips accounts. */
    record ClientRejected() implements RefreshResult {
    }

    /** 5xx, timeout or 429; {@code retryAfter} is the provider's hint, or null. */
    record Unavailable(Duration retryAfter) implements RefreshResult {
    }

    /** The grant row lock was not obtained in time (SQLState 55P03). */
    record LockTimeout() implements RefreshResult {
    }

    /** The account's provider id is no longer configured. */
    record ProviderUnconfigured() implements RefreshResult {
    }
}
