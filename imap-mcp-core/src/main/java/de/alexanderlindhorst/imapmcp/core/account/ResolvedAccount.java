package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Objects;

/** An account found by a tenant-scoped lookup, together with the tenant it was found for. */
public record ResolvedAccount(TenantId tenantId, MailAccount account) {

    public ResolvedAccount {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(account, "account");
    }
}
