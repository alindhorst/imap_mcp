package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.ExpiredRowCleanup;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Flyway from an empty database and the shape of the v3b schema (SPEC 5.7), A4. */
class DatabaseMigrationIT {

    private static PostgresFixture postgres;
    private static Database db;

    @BeforeAll
    static void start() {
        postgres = new PostgresFixture();
        postgres.start();
        db = Database.open(postgres.config());
    }

    @AfterAll
    static void stop() {
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?"
                        + " ORDER BY ordinal_position")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static UUID insertTenant(Connection c, String subject) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tenant (id, issuer, subject) VALUES (?, 'https://idp.example', ?)")) {
            ps.setObject(1, id);
            ps.setString(2, subject);
            ps.executeUpdate();
        }
        return id;
    }

    private static void insertAccount(Connection c, UUID tenant, String id, String handle) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO mailbox_account (tenant_id, id, handle, label, address, host, port, security, flavor,"
                        + " username, auth) VALUES (?, ?, ?, 'l', 'a@example.org', 'imap.example.org', 993,"
                        + " 'IMPLICIT_TLS', 'GENERIC', 'u', 'password')")) {
            ps.setObject(1, tenant);
            ps.setString(2, id);
            ps.setString(3, handle);
            ps.executeUpdate();
        }
    }

    private static int count(Connection c, String table) throws SQLException {
        try (var st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void migratingAnEmptyDatabaseCreatesAllSixTables() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            assertThat(columns(c, "tenant")).containsExactly("id", "issuer", "subject", "status", "created_at");
            assertThat(columns(c, "mailbox_account")).contains("tenant_id", "id", "handle", "label", "address", "host",
                    "port", "security", "flavor", "username", "auth", "read_only", "allow_purge", "status",
                    "failed_logins", "credential_version", "revision", "created_at", "updated_at");
            assertThat(columns(c, "credential")).containsExactly("tenant_id", "account_id", "version", "kind",
                    "kek_id", "wrapped_dek", "nonce", "ciphertext", "created_at");
            assertThat(columns(c, "audit_event")).contains("id", "occurred_at", "tenant_id", "account_id",
                    "request_id", "event_type", "tool", "ref_hash", "ref_key_id", "item_status", "error_code",
                    "confirmation", "result");
            assertThat(columns(c, "web_session")).containsExactly("id_hash", "tenant_id", "csrf_secret", "auth_time",
                    "created_at", "expires_at");
            assertThat(columns(c, "oidc_pending")).containsExactly("state_hash", "nonce", "pkce_verifier",
                    "created_at", "expires_at");
        }
    }

    @Test
    void migratingAgainIsANoOp() {
        try (Database second = Database.open(postgres.config())) {
            assertThat(second.dataSource()).isNotNull();
        }
    }

    @Test
    void issuerAndSubjectAreUniquePerTenant() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            insertTenant(c, "dup-subject");
            assertThatThrownBy(() -> insertTenant(c, "dup-subject")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("tenant_issuer_subject");
        }
    }

    @Test
    void accountDefaultsAreReadOnlyAndNoPurge() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            UUID t = insertTenant(c, "defaults");
            insertAccount(c, t, "a1", "acc_DEFAULTS00000000000000000A");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT read_only, allow_purge, status, failed_logins, credential_version, revision"
                            + " FROM mailbox_account WHERE tenant_id = ? AND id = 'a1'")) {
                ps.setObject(1, t);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).isTrue();
                    assertThat(rs.getBoolean(2)).isFalse();
                    assertThat(rs.getString(3)).isEqualTo("active");
                    assertThat(rs.getInt(4)).isZero();
                    assertThat(rs.getInt(5)).isEqualTo(1);
                    assertThat(rs.getLong(6)).isEqualTo(1L);
                }
            }
        }
    }

    @Test
    void accountIdIsUniquePerTenantButHandleIsUniqueGlobally() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            UUID t1 = insertTenant(c, "iso-1");
            UUID t2 = insertTenant(c, "iso-2");
            insertAccount(c, t1, "same", "acc_UNIQUE0000000000000000001A");
            insertAccount(c, t2, "same", "acc_UNIQUE0000000000000000002A");
            assertThatThrownBy(() -> insertAccount(c, t2, "other", "acc_UNIQUE0000000000000000001A"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void deletingAnAccountDeletesItsCredentials() throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            UUID t = insertTenant(c, "cascade");
            insertAccount(c, t, "acct", "acc_CASCADE00000000000000000A");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO credential (tenant_id, account_id, version, kind, kek_id, wrapped_dek, nonce,"
                            + " ciphertext) VALUES (?, 'acct', 1, 'password', 'local-1', '\\x01', '\\x02', '\\x03')")) {
                ps.setObject(1, t);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM mailbox_account WHERE tenant_id = ? AND id = 'acct'")) {
                ps.setObject(1, t);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM credential WHERE tenant_id = ?")) {
                ps.setObject(1, t);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
        }
    }

    @Test
    void cleanupRemovesOnlyExpiredWebSessionsAndPendingLogins() throws Exception {
        Instant now = Instant.now();
        try (Connection c = db.dataSource().getConnection()) {
            UUID t = insertTenant(c, "cleanup");
            for (int i = 0; i < 2; i++) {
                Instant expires = i == 0 ? now.minus(Duration.ofMinutes(1)) : now.plus(Duration.ofMinutes(10));
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO web_session (id_hash, tenant_id, csrf_secret, auth_time, expires_at)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
                    ps.setBytes(1, new byte[] {(byte) i, 1});
                    ps.setObject(2, t);
                    ps.setBytes(3, new byte[] {9});
                    ps.setObject(4, java.sql.Timestamp.from(now));
                    ps.setObject(5, java.sql.Timestamp.from(expires));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO oidc_pending (state_hash, nonce, pkce_verifier, expires_at)"
                                + " VALUES (?, 'n', 'v', ?)")) {
                    ps.setBytes(1, new byte[] {(byte) i, 2});
                    ps.setObject(2, java.sql.Timestamp.from(expires));
                    ps.executeUpdate();
                }
            }
            ExpiredRowCleanup.Result result = new ExpiredRowCleanup(db.dataSource()).deleteExpired(now);

            assertThat(result.webSessions()).isEqualTo(1);
            assertThat(result.oidcPending()).isEqualTo(1);
            assertThat(count(c, "web_session")).isEqualTo(1);
            assertThat(count(c, "oidc_pending")).isEqualTo(1);
        }
    }
}
