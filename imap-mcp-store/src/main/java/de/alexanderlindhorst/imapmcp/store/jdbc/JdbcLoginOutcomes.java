package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.account.LoginOutcomes;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Objects;

/**
 * The pool's login outcomes (SPEC 5.3 "Re-link") on the account row: the revision-guarded atomic SQL updates of
 * {@link JdbcAccountRepository#recordRefusedLogin} and {@link JdbcAccountRepository#recordSuccessfulLogin}. Link
 * probes of the linking surface do not go through the pool and so never reach this.
 */
public final class JdbcLoginOutcomes implements LoginOutcomes {

    private final JdbcAccountRepository accounts;

    public JdbcLoginOutcomes(JdbcAccountRepository accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override
    public void refused(TenantId tenant, AccountId account, long revision) {
        accounts.recordRefusedLogin(tenant, account, revision);
    }

    @Override
    public void succeeded(TenantId tenant, AccountId account, long revision) {
        accounts.recordSuccessfulLogin(tenant, account, revision);
    }
}
