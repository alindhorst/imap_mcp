package de.alexanderlindhorst.imapmcp.it;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Direct SQL seeding and reading for the XOAUTH2 ITs (#498), independent of the code under test. */
final class Xoauth2Db {

    static final String PROVIDER = "stub";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Xoauth2Db() {
    }

    record Seeded(TenantId tenant, AccountId account, String handle) {
    }

    /** A new handle in the repository's format. */
    static String newHandle() {
        StringBuilder b = new StringBuilder("acc_");
        for (int i = 0; i < 26; i++) {
            b.append(BASE32.charAt(RANDOM.nextInt(BASE32.length())));
        }
        return b.toString();
    }

    /**
     * An XOAUTH2 account of {@code tenant} with its {@code oauth_grant} row and {@code oauth_refresh} credential
     * version 1 holding {@code refreshToken}.
     */
    static Seeded insertXoauth2Account(DataSource ds, CredentialCipher cipher, TenantId tenant, String accountId,
            String host, int port, String username, String refreshToken) throws SQLException {
        String handle = newHandle();
        AccountSql.insertAccount(ds, tenant, new MailAccount(new AccountId(accountId), "xoauth2 " + accountId,
                username + "@example.com", host, port, MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC,
                username, new SecretRef("db:"), true, false, MailAccount.Auth.XOAUTH2, MailAccount.Status.ACTIVE, 1L,
                PROVIDER), handle);
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO oauth_grant (tenant_id, account_id,"
                    + " provider_id, scope, refresh_version) VALUES (?, ?, ?, 'mail', 1)")) {
                ps.setObject(1, UUID.fromString(tenant.value()));
                ps.setString(2, accountId);
                ps.setString(3, PROVIDER);
                ps.executeUpdate();
            }
            CredentialCipher.Sealed s = cipher.seal(tenant.value(), accountId, 1, refreshToken.toCharArray());
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO credential (tenant_id, account_id, version,"
                    + " kind, kek_id, wrapped_dek, nonce, ciphertext) VALUES (?, ?, 1, 'oauth_refresh', ?, ?, ?, ?)")) {
                ps.setObject(1, UUID.fromString(tenant.value()));
                ps.setString(2, accountId);
                ps.setString(3, s.kekId());
                ps.setBytes(4, s.wrappedDek());
                ps.setBytes(5, s.nonce());
                ps.setBytes(6, s.ciphertext());
                ps.executeUpdate();
            }
        }
        return new Seeded(tenant, new AccountId(accountId), handle);
    }

    static long scalar(DataSource ds, String sql, Object... args) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static String text(DataSource ds, String sql, Object... args) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    static UUID uuid(TenantId t) {
        return UUID.fromString(t.value());
    }

    static long revision(DataSource ds, Seeded s) throws SQLException {
        return scalar(ds, "SELECT revision FROM mailbox_account WHERE tenant_id = ? AND id = ?", uuid(s.tenant()),
                s.account().value());
    }

    static String status(DataSource ds, Seeded s) throws SQLException {
        return text(ds, "SELECT status FROM mailbox_account WHERE tenant_id = ? AND id = ?", uuid(s.tenant()),
                s.account().value());
    }

    static long refreshVersion(DataSource ds, Seeded s) throws SQLException {
        return scalar(ds, "SELECT refresh_version FROM oauth_grant WHERE tenant_id = ? AND account_id = ?",
                uuid(s.tenant()), s.account().value());
    }

    static long refreshCredentials(DataSource ds, Seeded s) throws SQLException {
        return scalar(ds, "SELECT count(*) FROM credential WHERE tenant_id = ? AND account_id = ?"
                + " AND kind = 'oauth_refresh'", uuid(s.tenant()), s.account().value());
    }

    static long auditCount(DataSource ds, Seeded s, String eventType) throws SQLException {
        return scalar(ds, "SELECT count(*) FROM audit_event WHERE tenant_id = ? AND account_id = ?"
                + " AND event_type = ?", uuid(s.tenant()), s.account().value(), eventType);
    }

    /** Every audit row as one text line per row (all columns), for the log scan. */
    static List<String> auditRows(DataSource ds) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT a::text FROM audit_event a");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }
}
