package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Objects;

/**
 * An account found by a tenant-scoped lookup, with the tenant it was found for and its client-facing {@code acc_}
 * handle (owned by the repository).
 */
public record ResolvedAccount(TenantId tenantId, MailAccount account, String handle) {

    public ResolvedAccount {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(handle, "handle");
    }
}
