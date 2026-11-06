package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;

/**
 * Persistence of the linking actions (SPEC 5.3, 5.7). Each method is one transaction that also writes the
 * action's audit row, so a failed audit write undoes the change (fail-closed, D5). The password is encrypted
 * before it is stored and is not retained; the caller wipes it.
 */
public interface LinkedAccountStore {

    /** The tenant already has {@code maxAccounts} accounts. */
    class LimitReachedException extends RuntimeException {

        public LimitReachedException() {
            super("account limit reached", null, false, false);
        }
    }

    /** The key service or the database could not complete the action; nothing was stored. */
    class UnavailableException extends RuntimeException {

        public UnavailableException() {
            super("linking store unavailable", null, false, false);
        }
    }

    /**
     * Creates the account (random id and handle, credential version 1) unless the tenant has {@code maxAccounts}
     * already; the count and the insert are serialized per tenant.
     */
    ResolvedAccount create(TenantId tenant, LinkRequest request, char[] password, int maxAccounts, String requestId);

    /**
     * New credential version, current from now on; clears {@code needs_relink}, resets the failed-login counter and
     * raises the revision; older credential versions are removed. False when the tenant has no such account.
     */
    boolean relink(TenantId tenant, AccountId id, char[] password, String requestId);

    /** False when the tenant has no such account. */
    boolean updateSettings(TenantId tenant, AccountId id, String label, boolean readOnly, boolean allowPurge,
            String requestId);

    /** Deletes the account and its credentials. False when the tenant has no such account. */
    boolean unlink(TenantId tenant, AccountId id, String requestId);
}
