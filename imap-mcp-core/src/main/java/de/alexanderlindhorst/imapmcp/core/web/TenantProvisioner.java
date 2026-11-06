package de.alexanderlindhorst.imapmcp.core.web;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Optional;

/**
 * Tenant rows by IdP identity (issuer and subject claim), as the linking surface needs them (SPEC 5.3, 5.7). Only
 * the login path may call {@link #create}; MCP requests only look up.
 */
public interface TenantProvisioner {

    /** A tenant row: its id and whether it is disabled. */
    record TenantRef(TenantId tenantId, boolean disabled) {
    }

    Optional<TenantRef> find(String issuer, String subject);

    /**
     * Creates the row if absent (idempotent, safe under concurrency) and returns it. When it creates the row it
     * writes the {@code tenant_created} audit row in the same transaction: a failed audit write
     * ({@link de.alexanderlindhorst.imapmcp.core.audit.AuditWriteException}) leaves no tenant behind (fail-closed, D5).
     */
    TenantRef create(String issuer, String subject, String requestId);
}
