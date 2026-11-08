package de.alexanderlindhorst.imapmcp.core.tenancy;

import java.util.Objects;
import java.util.Set;

/**
 * The only source of identity for tools. Built by the transport from the validated principal (fixed in
 * stdio / http-local), never from request parameters.
 *
 * <p>{@code scopes} are the OAuth scopes of the validated token in {@code http-public} (SPEC 5.3, D3), checked per
 * call by the tools that need one ({@link #hasScope}). Null means the context is not scope-limited (the single-tenant
 * modes, which have no tokens with scopes); an empty set grants nothing.
 *
 * <p>{@code principalLogId} is set only by {@code http-public} for a principal without a tenant row
 * ({@link TenantId#NONE}): the hashed principal (SPEC 5.3, D11), opaque, for one WARN. It is never a key, a
 * lookup or a selector, and never the raw issuer or subject; null everywhere else.
 */
public record AccountContext(TenantId tenantId, Set<String> scopes, String principalLogId) {

    public AccountContext(TenantId tenantId, Set<String> scopes) {
        this(tenantId, scopes, null);
    }

    public AccountContext {
        Objects.requireNonNull(tenantId, "tenantId");
        scopes = scopes == null ? null : Set.copyOf(scopes);
    }

    /** Not scope-limited: stdio and http-local, which have no token scopes. */
    public static AccountContext unrestricted(TenantId tenantId) {
        return new AccountContext(tenantId, null);
    }

    /** Same as {@link #unrestricted}. */
    public AccountContext(TenantId tenantId) {
        this(tenantId, null);
    }

    public boolean scopeLimited() {
        return scopes != null;
    }

    /** True when the context is not scope-limited, or the token carried exactly this scope. */
    public boolean hasScope(String scope) {
        return scopes == null || scopes.contains(scope);
    }
}
