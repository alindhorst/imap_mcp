package de.alexanderlindhorst.imapmcp.core.tenancy;

import java.util.Objects;

/**
 * The only source of identity for tools. Built by the transport from the validated principal (fixed in
 * stdio / http-local), never from request parameters.
 */
public record AccountContext(TenantId tenantId) {

    public AccountContext {
        Objects.requireNonNull(tenantId, "tenantId");
    }
}
