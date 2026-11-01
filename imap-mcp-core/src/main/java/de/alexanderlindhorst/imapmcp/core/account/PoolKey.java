package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Objects;

/**
 * Internal pool key {@code (tenant, account)}. Only obtainable from the caller's {@link AccountContext} and an
 * account resolved through a tenant-scoped lookup, never from client-supplied strings.
 */
public final class PoolKey {

    private final TenantId tenantId;
    private final AccountId accountId;

    private PoolKey(TenantId tenantId, AccountId accountId) {
        this.tenantId = tenantId;
        this.accountId = accountId;
    }

    public static PoolKey of(AccountContext context, ResolvedAccount resolved) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(resolved, "resolved");
        if (!context.tenantId().equals(resolved.tenantId())) {
            throw new IllegalArgumentException("account does not belong to the context tenant");
        }
        return new PoolKey(resolved.tenantId(), resolved.account().id());
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public AccountId accountId() {
        return accountId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PoolKey k && tenantId.equals(k.tenantId) && accountId.equals(k.accountId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, accountId);
    }

    @Override
    public String toString() {
        return "PoolKey[" + tenantId.value() + "/" + accountId.value() + "]";
    }
}
