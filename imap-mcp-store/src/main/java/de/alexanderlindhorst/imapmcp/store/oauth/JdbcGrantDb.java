package de.alexanderlindhorst.imapmcp.store.oauth;

import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@link GrantDb} on JDBC (SPEC 5.5, 5.7). One connection and transaction per refresh; {@code SET LOCAL
 * lock_timeout} applies to the grant row lock. Every statement carries the tenant. This is a separate reader and
 * writer for {@code kind = 'oauth_refresh'}: the password reader keeps {@code kind = 'password'}.
 */
final class JdbcGrantDb implements GrantDb {

    private static final String LOCK_TIMEOUT = "SET LOCAL lock_timeout = '10s'";

    private final DataSource dataSource;

    JdbcGrantDb(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> T inTransaction(TxBody<T> body) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.execute(LOCK_TIMEOUT);
                }
                T result = body.run(new JdbcTx(c));
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }
    }

    private record JdbcTx(Connection c) implements Tx {

        @Override
        public Optional<Grant> lockGrant(UUID tenant, String account) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("SELECT provider_id, refresh_version FROM oauth_grant"
                    + " WHERE tenant_id = ? AND account_id = ? FOR UPDATE")) {
                ps.setObject(1, tenant);
                ps.setString(2, account);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new Grant(rs.getString(1), rs.getInt(2))) : Optional.empty();
                }
            }
        }

        @Override
        public Optional<CredentialCipher.Sealed> readRefresh(UUID tenant, String account, int version)
                throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("SELECT kek_id, wrapped_dek, nonce, ciphertext"
                    + " FROM credential WHERE tenant_id = ? AND account_id = ? AND version = ?"
                    + " AND kind = 'oauth_refresh'")) {
                ps.setObject(1, tenant);
                ps.setString(2, account);
                ps.setInt(3, version);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new CredentialCipher.Sealed(rs.getString(1), rs.getBytes(2),
                            rs.getBytes(3), rs.getBytes(4))) : Optional.empty();
                }
            }
        }

        @Override
        public void insertRefresh(UUID tenant, String account, int version, CredentialCipher.Sealed sealed)
                throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO credential (tenant_id, account_id, version,"
                    + " kind, kek_id, wrapped_dek, nonce, ciphertext) VALUES (?, ?, ?, 'oauth_refresh', ?, ?, ?, ?)")) {
                ps.setObject(1, tenant);
                ps.setString(2, account);
                ps.setInt(3, version);
                ps.setString(4, sealed.kekId());
                ps.setBytes(5, sealed.wrappedDek());
                ps.setBytes(6, sealed.nonce());
                ps.setBytes(7, sealed.ciphertext());
                ps.executeUpdate();
            }
        }

        @Override
        public void pointTo(UUID tenant, String account, int version) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("UPDATE oauth_grant SET refresh_version = ?,"
                    + " refreshed_at = now() WHERE tenant_id = ? AND account_id = ?")) {
                ps.setInt(1, version);
                ps.setObject(2, tenant);
                ps.setString(3, account);
                ps.executeUpdate();
            }
        }

        @Override
        public void deleteRefresh(UUID tenant, String account, int version) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM credential WHERE tenant_id = ?"
                    + " AND account_id = ? AND version = ? AND kind = 'oauth_refresh'")) {
                ps.setObject(1, tenant);
                ps.setString(2, account);
                ps.setInt(3, version);
                ps.executeUpdate();
            }
        }

        @Override
        public void markNeedsRelink(UUID tenant, String account) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("UPDATE mailbox_account SET status = 'needs_relink',"
                    + " revision = revision + 1, updated_at = now() WHERE tenant_id = ? AND id = ?")) {
                ps.setObject(1, tenant);
                ps.setString(2, account);
                ps.executeUpdate();
            }
        }

        @Override
        public void audit(EventInfo event) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO audit_event (tenant_id, account_id,"
                    + " request_id, event_type, result, completed_at) VALUES (?, ?, ?, ?, ?, now())")) {
                ps.setObject(1, UUID.fromString(event.tenant().value()));
                ps.setString(2, event.account() == null ? null : event.account().value());
                ps.setString(3, event.requestId());
                ps.setString(4, event.type());
                ps.setString(5, event.result());
                ps.executeUpdate();
            }
        }
    }
}
