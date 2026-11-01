package de.alexanderlindhorst.imapmcp.core.tenancy;

import java.util.Objects;

/** Identifies a tenant. Fixed single value in stdio / http-local. */
public record TenantId(String value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("tenant id must not be blank");
        }
    }
}
