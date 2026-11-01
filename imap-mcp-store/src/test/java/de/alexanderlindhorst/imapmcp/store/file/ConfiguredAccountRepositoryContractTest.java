package de.alexanderlindhorst.imapmcp.store.file;

import de.alexanderlindhorst.imapmcp.core.account.AccountHandles;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepositoryContract;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.List;
import java.util.Map;

/**
 * Runs the core contract. The contract loads accounts for two tenants, so one single-tenant repository per
 * tenant is composed behind a delegating facade.
 */
class ConfiguredAccountRepositoryContractTest extends AccountRepositoryContract {

    static final byte[] SALT = new byte[16];

    @Override
    protected AccountRepository createRepository(Map<TenantId, List<MailAccount>> accountsByTenant) {
        Map<TenantId, ConfiguredAccountRepository> perTenant = new java.util.HashMap<>();
        accountsByTenant.forEach((t, a) -> perTenant.put(t, new ConfiguredAccountRepository(t, a, SALT)));
        return new AccountRepository() {
            @Override
            public List<de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount> findByTenant(TenantId tenant) {
                return perTenant.values().stream().flatMap(r -> r.findByTenant(tenant).stream()).toList();
            }

            @Override
            public java.util.Optional<de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount>
                    findByTenantAndHandle(TenantId tenant, String handle) {
                return perTenant.values().stream().map(r -> r.findByTenantAndHandle(tenant, handle))
                        .flatMap(java.util.Optional::stream).findFirst();
            }
        };
    }

    @Override
    protected String handleFor(AccountRepository repository, TenantId tenant, AccountId account) {
        return AccountHandles.derive(SALT, tenant, account);
    }
}
