package de.alexanderlindhorst.imapmcp.core.tenancy;

import java.util.Objects;

/** Identifies a tenant. Fixed single value in stdio / http-local. */
public record TenantId(String value) {

    /**
     * The tenant of a validated principal without a tenant row (http-public): it owns nothing. The nil UUID, which
     * no row can have (tenant ids are random UUIDs).
     */
    public static final TenantId NONE = new TenantId("00000000-0000-0000-0000-000000000000");

    public TenantId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("tenant id must not be blank");
        }
    }
}
