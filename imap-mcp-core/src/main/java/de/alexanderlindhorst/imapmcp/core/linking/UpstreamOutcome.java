package de.alexanderlindhorst.imapmcp.core.linking;

/** The result of an upstream linking step; the pages turn each into a fixed message (SPEC 5.3). */
public enum UpstreamOutcome {
    OK,
    INVALID_INPUT,
    /** Unknown provider, or no such account for this tenant (unknown, malformed, foreign or not an upstream account). */
    NOT_FOUND,
    /** The tenant has the maximum number of accounts. */
    ACCOUNT_LIMIT,
    /** No probe left in the tenant's hourly budget. */
    RATE_LIMITED,
    /** More than 20 starts in the last hour for the tenant on this replica. */
    START_LIMIT,
    /** The tenant has 3 unfinished flows already. */
    PENDING_LIMIT,
    /** State unknown, expired or replayed, or cookie or path mismatch. */
    EXPIRED,
    /** {@code error=access_denied}. */
    DENIED,
    /** Any other {@code error}, or no code. */
    PROVIDER_ERROR,
    /** The exchange timed out or was answered 5xx or 429. */
    PROVIDER_UNAVAILABLE,
    /** {@code invalid_grant}, no refresh token or a required scope missing. */
    INCOMPLETE,
    /** SSRF check, probe budget or the XOAUTH2 probe failed. */
    PROBE_FAILED,
    /** The tenant has this provider and address already. */
    DUPLICATE,
    /** The account limit, rechecked in the transaction. */
    LIMIT,
    /** The database or the key service could not do it; nothing was stored. */
    UNAVAILABLE
}
