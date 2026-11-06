package de.alexanderlindhorst.imapmcp.core.web;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Duration;
import java.util.Optional;

/**
 * Server-side sessions of the linking surface (SPEC 5.3, 5.7). The session id is the cookie value and a bearer
 * secret: implementations store only its SHA-256. A session ends 30 minutes after its last use or 8 hours after
 * its creation, whichever comes first, and not at all for a disabled tenant.
 */
public interface WebSessionStore {

    Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    Duration ABSOLUTE_TIMEOUT = Duration.ofHours(8);

    /** A new session: the cookie value and the CSRF token for forms. Neither is retained by the store in clear. */
    record Created(String sessionId, String csrfToken) {

        @Override
        public String toString() {
            return "Created[***]";
        }
    }

    /** Creates a session for a tenant that logged in at {@code authTime}. */
    Created create(TenantId tenant, java.time.Instant authTime);

    /**
     * Looks the session up by its id and, if it is still valid, extends its idle deadline (never beyond the
     * absolute one). Empty for an unknown, malformed or expired session and for a disabled tenant.
     */
    Optional<WebSession> find(String sessionId);

    /** Logout: deletes the session. Unknown ids are ignored. */
    void delete(String sessionId);
}
