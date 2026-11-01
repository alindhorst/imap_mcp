package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountHandle;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Trivial reference implementation; handle format is a stand-in, not the real derivation. */
class InMemoryAccountRepository implements AccountRepository {

    private final Map<TenantId, List<MailAccount>> accounts;

    InMemoryAccountRepository(Map<TenantId, List<MailAccount>> accounts) {
        this.accounts = Map.copyOf(accounts);
    }

    static String handle(TenantId tenant, AccountId account) {
        return "acc_" + tenant.value() + "_" + account.value();
    }

    @Override
    public List<MailAccount> findByTenant(TenantId tenant) {
        return accounts.getOrDefault(tenant, List.of());
    }

    @Override
    public Optional<ResolvedAccount> findByTenantAndHandle(TenantId tenant, String handle) {
        if (AccountHandle.parse(handle).isEmpty()) {
            return Optional.empty();
        }
        return findByTenant(tenant).stream().filter(a -> handle(tenant, a.id()).equals(handle)).findFirst()
                .map(a -> new ResolvedAccount(tenant, a));
    }
}
