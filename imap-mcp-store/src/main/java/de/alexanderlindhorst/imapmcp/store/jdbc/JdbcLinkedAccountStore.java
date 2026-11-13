package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.linking.LinkRequest;
import de.alexanderlindhorst.imapmcp.core.linking.LinkedAccountStore;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamAccountStore;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.KeyServiceUnavailableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LinkedAccountStore} on Postgres (SPEC 5.3, 5.7). Each action is one transaction: the account, its
 * credential and the audit row commit together or not at all. The account cap is serialized per tenant by a row lock
 * on the tenant; re-link locks the account row, so concurrent re-links get distinct credential versions. Every
 * statement filters on {@code tenant_id}; the tenant comes from the web session, never from a form.
 */
public final class JdbcLinkedAccountStore implements LinkedAccountStore, UpstreamAccountStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcLinkedAccountStore.class);

    private final DataSource dataSource;
    private final JdbcAccountRepository accounts;
    private final JdbcCredentialStore credentials;

    public JdbcLinkedAccountStore(DataSource dataSource, JdbcAccountRepository accounts,
            JdbcCredentialStore credentials) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /** One transactional action; returns its result and commits, or rolls back on any failure. */
    @FunctionalInterface
    private interface Action<T> {
        T run(Connection c, UUID tenant) throws SQLException;
    }

    private <T> T inTransaction(TenantId tenant, String what, Action<T> action) {
        UUID t = JdbcAccountRepository.uuid(tenant);
        if (t == null) {
            throw new IllegalArgumentException("tenant id is not a UUID");
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            boolean committed = false;
            try {
                T result = action.run(c, t);
                if (result instanceof Boolean b && !b) { // nothing matched: nothing to keep
                    c.rollback();
                } else {
                    c.commit();
                }
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        // the connection is discarded by the pool when it is broken
                    }
                }
            }
        } catch (KeyServiceUnavailableException e) {
            throw new UnavailableException(); // the credential store already wrote the WARN line and metric
        } catch (SQLException e) {
            LOG.warn("linking action failed ({}, sqlstate {})", what, e.getSQLState());
            throw new UnavailableException();
        }
    }

    @Override
    public ResolvedAccount create(TenantId tenant, LinkRequest r, char[] password, int maxAccounts,
            String requestId) {
        String[] ids = inTransaction(tenant, "link", (c, t) -> {
            try (PreparedStatement lock = c.prepareStatement("SELECT 1 FROM tenant WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, t);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("no such tenant", "02000");
                    }
                }
            }
            try (PreparedStatement count = c.prepareStatement(
                    "SELECT count(*) FROM mailbox_account WHERE tenant_id = ?")) {
                count.setObject(1, t);
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= maxAccounts) {
                        throw new LimitReachedException();
                    }
                }
            }
            String[] created = accounts.insertAccount(c, t, new JdbcAccountRepository.NewAccount(r.label(), null,
                    r.host(), r.port(), r.security(), MailAccount.Flavor.GENERIC, r.username(), r.readOnly(),
                    r.allowPurge()));
            AccountId id = new AccountId(created[0]);
            credentials.insertWith(c, tenant, id, 1, password);
            JdbcAuditLog.insertEvent(c, new EventInfo("link", tenant, id, requestId, "ok"));
            return created;
        });
        return accounts.findByTenantAndId(tenant, new AccountId(ids[0])).orElseThrow(UnavailableException::new);
    }

    @Override
    public boolean relink(TenantId tenant, AccountId id, char[] password, String requestId) {
        return inTransaction(tenant, "relink", (c, t) -> {
            int version;
            try (PreparedStatement ps = c.prepareStatement("SELECT credential_version FROM mailbox_account"
                    + " WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
                ps.setObject(1, t);
                ps.setString(2, id.value());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    version = rs.getInt(1) + 1;
                }
            }
            credentials.insertWith(c, tenant, id, version, password);
            try (PreparedStatement ps = c.prepareStatement("UPDATE mailbox_account SET credential_version = ?,"
                    + " status = 'active', failed_logins = 0, revision = revision + 1, updated_at = now()"
                    + " WHERE tenant_id = ? AND id = ?")) {
                ps.setInt(1, version);
                ps.setObject(2, t);
                ps.setString(3, id.value());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM credential WHERE tenant_id = ? AND account_id = ? AND version < ?")) {
                ps.setObject(1, t);
                ps.setString(2, id.value());
                ps.setInt(3, version);
                ps.executeUpdate();
            }
            JdbcAuditLog.insertEvent(c, new EventInfo("relink", tenant, id, requestId, "ok"));
            return true;
        });
    }

    @Override
    public boolean updateSettings(TenantId tenant, AccountId id, String label, boolean readOnly,
            boolean allowPurge, String requestId) {
        return inTransaction(tenant, "settings", (c, t) -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE mailbox_account SET label = ?, read_only = ?,"
                    + " allow_purge = ?, updated_at = now() WHERE tenant_id = ? AND id = ?")) {
                ps.setString(1, label);
                ps.setBoolean(2, readOnly);
                ps.setBoolean(3, allowPurge);
                ps.setObject(4, t);
                ps.setString(5, id.value());
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            JdbcAuditLog.insertEvent(c, new EventInfo("settings", tenant, id, requestId, "ok"));
            return true;
        });
    }

    @Override
    public boolean unlink(TenantId tenant, AccountId id, String requestId) {
        return inTransaction(tenant, "unlink", (c, t) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM mailbox_account WHERE tenant_id = ? AND id = ?")) { // credentials cascade
                ps.setObject(1, t);
                ps.setString(2, id.value());
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            JdbcAuditLog.insertEvent(c, new EventInfo("unlink", tenant, id, requestId, "ok"));
            return true;
        });
    }

    // ---- upstream OAuth (SPEC 5.3, 5.7) ----

    @Override
    public ResolvedAccount create(TenantId tenant, NewUpstreamAccount a, char[] refreshToken, int maxAccounts,
            String requestId) {
        String[] ids = inTransaction(tenant, "link", (c, t) -> {
            lockTenant(c, t); // serializes the cap and the duplicate check per tenant
            requireActiveTenant(c, t);
            try (PreparedStatement dup = c.prepareStatement("SELECT 1 FROM mailbox_account WHERE tenant_id = ?"
                    + " AND auth = 'xoauth2' AND provider_id = ? AND lower(address) = lower(?)")) {
                dup.setObject(1, t);
                dup.setString(2, a.providerId());
                dup.setString(3, a.address());
                try (ResultSet rs = dup.executeQuery()) {
                    if (rs.next()) {
                        throw new DuplicateException();
                    }
                }
            }
            checkLimit(c, t, maxAccounts);
            String[] created = accounts.insertUpstreamAccount(c, t, new JdbcAccountRepository.NewAccount(a.label(),
                    a.address(), a.host(), a.port(), a.security(), a.flavor(), a.address(), a.readOnly(), false),
                    a.providerId());
            AccountId id = new AccountId(created[0]);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO oauth_grant (tenant_id, account_id,"
                    + " provider_id, scope, refresh_version) VALUES (?, ?, ?, ?, 1)")) {
                ps.setObject(1, t);
                ps.setString(2, id.value());
                ps.setString(3, a.providerId());
                ps.setString(4, a.scope());
                ps.executeUpdate();
            }
            credentials.insertRefreshWith(c, tenant, id, 1, refreshToken);
            JdbcAuditLog.insertEvent(c, new EventInfo("link", tenant, id, requestId, "ok"));
            return created;
        });
        return accounts.findByTenantAndId(tenant, new AccountId(ids[0])).orElseThrow(UnavailableException::new);
    }

    @Override
    public boolean relink(TenantId tenant, AccountId id, char[] refreshToken, String scope, String requestId) {
        return inTransaction(tenant, "relink", (c, t) -> {
            requireActiveTenant(c, t);
            try (Statement st = c.createStatement()) { // a refresh holds the grant lock across its HTTP call
                st.execute("SET LOCAL lock_timeout = '10s'");
            }
            int version;
            // the grant row is the lock a refresh takes too (SPEC 5.5); the join keeps password accounts out
            try (PreparedStatement ps = c.prepareStatement("SELECT g.refresh_version FROM oauth_grant g"
                    + " JOIN mailbox_account a ON a.tenant_id = g.tenant_id AND a.id = g.account_id"
                    + " WHERE g.tenant_id = ? AND g.account_id = ? AND a.auth = 'xoauth2' FOR UPDATE OF g")) {
                ps.setObject(1, t);
                ps.setString(2, id.value());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    version = rs.getInt(1) + 1;
                }
            }
            credentials.insertRefreshWith(c, tenant, id, version, refreshToken);
            try (PreparedStatement ps = c.prepareStatement("UPDATE oauth_grant SET refresh_version = ?, scope = ?,"
                    + " refreshed_at = now() WHERE tenant_id = ? AND account_id = ?")) {
                ps.setInt(1, version);
                ps.setString(2, scope);
                ps.setObject(3, t);
                ps.setString(4, id.value());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE mailbox_account SET credential_version = ?,"
                    + " status = 'active', failed_logins = 0, revision = revision + 1, updated_at = now()"
                    + " WHERE tenant_id = ? AND id = ?")) {
                ps.setInt(1, version);
                ps.setObject(2, t);
                ps.setString(3, id.value());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM credential WHERE tenant_id = ? AND account_id = ? AND version < ?")) {
                ps.setObject(1, t);
                ps.setString(2, id.value());
                ps.setInt(3, version);
                ps.executeUpdate();
            }
            JdbcAuditLog.insertEvent(c, new EventInfo("relink", tenant, id, requestId, "ok"));
            return true;
        });
    }

    /** A tenant disabled after the flow started gets nothing (SPEC 5.3, D13); read in the transaction. */
    private static void requireActiveTenant(Connection c, UUID t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM tenant WHERE id = ?")) {
            ps.setObject(1, t);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"active".equals(rs.getString(1))) {
                    throw new TenantDisabledException();
                }
            }
        }
    }

    private static void lockTenant(Connection c, UUID t) throws SQLException {
        try (PreparedStatement lock = c.prepareStatement("SELECT 1 FROM tenant WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, t);
            try (ResultSet rs = lock.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("no such tenant", "02000");
                }
            }
        }
    }

    private static void checkLimit(Connection c, UUID t, int maxAccounts) throws SQLException {
        try (PreparedStatement count = c.prepareStatement("SELECT count(*) FROM mailbox_account WHERE tenant_id = ?")) {
            count.setObject(1, t);
            try (ResultSet rs = count.executeQuery()) {
                rs.next();
                if (rs.getInt(1) >= maxAccounts) {
                    throw new LimitReachedException();
                }
            }
        }
    }
}
