package de.alexanderlindhorst.imapmcp.core.linking;

/** The result of a linking action; the pages turn each into a fixed message. */
public enum LinkOutcome {
    OK,
    INVALID_INPUT,
    /** The SSRF policy refused host, port or TLS rule. */
    HOST_REFUSED,
    /** More than 10 probes for the tenant in the last hour. */
    RATE_LIMITED,
    PROBE_AUTH_FAILED,
    PROBE_UNREACHABLE,
    /** The tenant has the maximum number of accounts. */
    ACCOUNT_LIMIT,
    /** No such account for this tenant (unknown, malformed or foreign handle alike). */
    NOT_FOUND,
    /** Turning on {@code allow_purge} needs a login from the last 5 minutes. */
    REAUTH_REQUIRED,
    /** {@code allow_purge} is not offered by this deployment ({@code purge.enabled}). */
    PURGE_DISABLED,
    /** The key service or the database could not do it; nothing was stored. */
    UNAVAILABLE
}
