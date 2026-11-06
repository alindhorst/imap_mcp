package de.alexanderlindhorst.imapmcp.core.web;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Instant;
import java.util.Objects;

/** A valid session: its tenant (the only source of identity on the linking surface), CSRF token and login time. */
public record WebSession(TenantId tenantId, String csrfToken, Instant authTime, Instant createdAt) {

    public WebSession {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(csrfToken, "csrfToken");
        Objects.requireNonNull(authTime, "authTime");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public String toString() {
        return "WebSession[***]";
    }
}
