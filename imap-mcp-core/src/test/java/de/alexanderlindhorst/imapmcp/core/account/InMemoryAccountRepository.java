package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Trivial reference implementation (test scope); handles use the real derivation with a fixed salt. */
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<TenantId, List<MailAccount>> accounts;

    public InMemoryAccountRepository(Map<TenantId, List<MailAccount>> accounts) {
        this.accounts = Map.copyOf(accounts);
    }

    private static final byte[] SALT = new byte[AccountHandles.SALT_LENGTH];

    public static String handle(TenantId tenant, AccountId account) {
        return AccountHandles.derive(SALT, tenant, account);
    }

    @Override
    public List<ResolvedAccount> findByTenant(TenantId tenant) {
        return accounts.getOrDefault(tenant, List.of()).stream()
                .map(a -> new ResolvedAccount(tenant, a, handle(tenant, a.id()))).toList();
    }

    @Override
    public Optional<ResolvedAccount> findByTenantAndHandle(TenantId tenant, String handle) {
        if (!AccountHandles.isValid(handle)) {
            return Optional.empty();
        }
        return findByTenant(tenant).stream().filter(r -> r.handle().equals(handle)).findFirst();
    }

    @Override
    public Optional<ResolvedAccount> findByTenantAndId(TenantId tenant, AccountId id) {
        return findByTenant(tenant).stream().filter(r -> r.account().id().equals(id)).findFirst();
    }
}
