package de.alexanderlindhorst.imapmcp.store.oauth;

import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * The database side of a refresh (SPEC 5.5, "Lock") as one transaction, so that the refresh logic can be tested
 * without a database. Every operation is scoped by tenant and account. {@link #inTransaction} commits when the body
 * returns and rolls back when it throws; a commit failure is an {@link SQLException} from it.
 */
interface GrantDb {

    <T> T inTransaction(TxBody<T> body) throws SQLException;

    @FunctionalInterface
    interface TxBody<T> {
        T run(Tx tx) throws SQLException;
    }

    record Grant(String providerId, int refreshVersion) {
    }

    interface Tx {

        /** {@code SELECT ... FROM oauth_grant ... FOR UPDATE} under {@code lock_timeout} 10 s. */
        Optional<Grant> lockGrant(UUID tenant, String account) throws SQLException;

        Optional<CredentialCipher.Sealed> readRefresh(UUID tenant, String account, int version) throws SQLException;

        void insertRefresh(UUID tenant, String account, int version, CredentialCipher.Sealed sealed)
                throws SQLException;

        /** Sets {@code refresh_version} and {@code refreshed_at}; never touches {@code mailbox_account}. */
        void pointTo(UUID tenant, String account, int version) throws SQLException;

        void deleteRefresh(UUID tenant, String account, int version) throws SQLException;

        /** Sets {@code status = needs_relink} and bumps {@code revision} of the account. */
        void markNeedsRelink(UUID tenant, String account) throws SQLException;

        void audit(EventInfo event) throws SQLException;
    }
}
