package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.List;
import java.util.Map;

/** Runs the contract against the in-memory reference implementation (test scope only). */
class InMemoryAccountRepositoryTest extends AccountRepositoryContract {

    @Override
    protected AccountRepository createRepository(Map<TenantId, List<MailAccount>> accountsByTenant) {
        return new InMemoryAccountRepository(accountsByTenant);
    }

    @Override
    protected String handleFor(AccountRepository repository, TenantId tenant, AccountId account) {
        return InMemoryAccountRepository.handle(tenant, account);
    }
}
