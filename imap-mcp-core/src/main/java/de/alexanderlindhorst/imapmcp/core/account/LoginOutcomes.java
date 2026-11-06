package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;

/**
 * Receives the outcome of every real LOGIN of a pooled connection (SPEC 5.3 "Re-link"): a refusal (LOGIN answered
 * {@code NO} without {@code [UNAVAILABLE]}) or a success. {@code revision} is the account revision the LOGIN used,
 * so an outcome for an older revision changes nothing. The store updates the counter atomically in SQL; link probes
 * must not call this. Implementations must not throw for ordinary failures; callers ignore a failure anyway.
 */
public interface LoginOutcomes {

    LoginOutcomes NONE = new LoginOutcomes() {
        @Override
        public void refused(TenantId tenant, AccountId account, long revision) {
        }

        @Override
        public void succeeded(TenantId tenant, AccountId account, long revision) {
        }
    };

    void refused(TenantId tenant, AccountId account, long revision);

    void succeeded(TenantId tenant, AccountId account, long revision);
}
