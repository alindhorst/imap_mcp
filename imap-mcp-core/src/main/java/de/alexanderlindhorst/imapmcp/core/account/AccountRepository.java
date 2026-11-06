package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.List;
import java.util.Optional;

/**
 * Tenant-scoped account lookup. There is deliberately no method that resolves an account without a tenant.
 * Implementations must pass {@code AccountRepositoryContract} (core test-jar).
 */
public interface AccountRepository {

    List<ResolvedAccount> findByTenant(TenantId tenant);

    /** Empty for unknown, foreign or malformed handles; never throws on client input. */
    Optional<ResolvedAccount> findByTenantAndHandle(TenantId tenant, String handle);

    /**
     * Internal lookup by account id, used by the connection pool for the account of a pool key. Tenant-scoped like
     * the other methods; the id is never client-supplied. Empty for an unknown or foreign id.
     */
    Optional<ResolvedAccount> findByTenantAndId(TenantId tenant, AccountId id);
}
