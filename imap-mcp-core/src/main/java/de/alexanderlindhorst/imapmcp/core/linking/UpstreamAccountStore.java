package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;

/**
 * Persistence of the upstream OAuth link and re-link (SPEC 5.3, 5.7). Each method is one transaction that also
 * writes the audit row, so a failed audit write undoes the change (fail-closed, D5). The refresh token is encrypted
 * before it is stored and not retained; the caller wipes it.
 */
public interface UpstreamAccountStore {

    /** The tenant already has an account of this provider with this address (compared case-insensitively). */
    class DuplicateException extends RuntimeException {

        public DuplicateException() {
            super("account already linked", null, false, false);
        }
    }

    /** The values of a new upstream account; {@code address} is also the IMAP user name. */
    record NewUpstreamAccount(String label, String address, String providerId, String host, int port,
            MailAccount.Security security, MailAccount.Flavor flavor, boolean readOnly, String scope) {

        @Override
        public String toString() {
            return "NewUpstreamAccount[providerId=" + providerId + ", port=" + port + ", readOnly=" + readOnly + "]";
        }
    }

    /**
     * Creates the account, its {@code oauth_grant} and the {@code oauth_refresh} credential version 1 and the audit
     * row {@code ok} in one transaction. Throws {@link DuplicateException}, {@link LinkedAccountStore.LimitReachedException}
     * (the tenant has {@code maxAccounts} already, counted in the transaction) or
     * {@link LinkedAccountStore.UnavailableException}; nothing is stored then.
     */
    ResolvedAccount create(TenantId tenant, NewUpstreamAccount account, char[] refreshToken, int maxAccounts,
            String requestId);

    /**
     * New refresh-token version, current from now on; clears {@code needs_relink} and the failed-login counter and
     * raises the revision; older versions are removed. False when the tenant has no such upstream account.
     */
    boolean relink(TenantId tenant, AccountId id, char[] refreshToken, String scope, String requestId);
}
