package de.alexanderlindhorst.imapmcp.transport.http;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Optional;

/**
 * Read-only lookup of the tenant of a validated principal (SPEC 5.3): the row found by the issuer and the subject
 * claim. There is deliberately no way to create a tenant here: no MCP request creates rows (tenant creation is
 * the linking surface's first login). Implementations cache (5 minutes, bounded) and are backed by the store.
 */
@FunctionalInterface
public interface PrincipalTenants {

    /** A tenant row: its id and whether it is disabled. */
    record Tenant(TenantId id, boolean disabled) {
    }

    Optional<Tenant> find(String issuer, String subject);
}
