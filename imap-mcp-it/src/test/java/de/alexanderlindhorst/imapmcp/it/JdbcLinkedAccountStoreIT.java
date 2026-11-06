package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.linking.LinkRequest;
import de.alexanderlindhorst.imapmcp.core.linking.LinkedAccountStore;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.KeyServiceUnavailableException;
import de.alexanderlindhorst.imapmcp.store.crypto.KeyWrapper;
import de.alexanderlindhorst.imapmcp.store.crypto.LocalKeyWrapper;
import de.alexanderlindhorst.imapmcp.store.crypto.WrappedKey;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAuditLog;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcCredentialStore;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcLinkedAccountStore;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The transactional linking store on Postgres: cap, atomicity, re-link, settings, unlink, audit (C2). */
class JdbcLinkedAccountStoreIT {

    private static PostgresFixture postgres;
    private static Database db;

    private final List<String> operations = new ArrayList<>();
    private final MetricsRecorder metrics = new MetricsRecorder() {
        @Override
        public void operation(TenantId tenant, String tool, String op, Duration latency) {
            operations.add(tool + "/" + op);
        }
    };
    private LogIds logIds;
    private JdbcAccountRepository accounts;
    private JdbcCredentialStore credentials;
    private JdbcLinkedAccountStore store;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

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

    private static KeyWrapper localWrapper() {
        byte[] kek = new byte[32];
        Arrays.fill(kek, (byte) 4);
        return new LocalKeyWrapper("local-1", Map.of("local-1", kek));
    }

    private void wire(KeyWrapper wrapper) {
        byte[] master = new byte[32];
        Arrays.fill(master, (byte) 1);
        logIds = new LogIds(new KeyRing(1, Map.of(1, master)));
        credentials = new JdbcCredentialStore(db.dataSource(), new CredentialCipher(wrapper, new SecureRandom()),
                logIds, metrics);
        accounts = new JdbcAccountRepository(db.dataSource());
        store = new JdbcLinkedAccountStore(db.dataSource(), accounts, credentials);
    }

    @BeforeEach
    void setUp() {
        wire(localWrapper());
        logger = (Logger) LoggerFactory.getLogger(JdbcCredentialStore.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    private static LinkRequest request(String label) {
        return new LinkRequest(label, "imap.secret-host.example", 993, MailAccount.Security.IMPLICIT_TLS,
                "secret-user@example.org", true, false);
    }

    private static int count(String sql, Object... params) throws Exception {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String plaintext(TenantId t, AccountId id) {
        try (var s = credentials.resolve(t, id).orElseThrow()) {
            return new String(s.chars());
        }
    }

    // ---- create ----

    @Test
    void createStoresTheAccountTheEncryptedCredentialAndTheAuditRowTogether() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());

        ResolvedAccount r = store.create(t, request("Work"), "the-password".toCharArray(), 10, "req-1");

        assertThat(accounts.findByTenantAndHandle(t, r.handle())).isPresent();
        assertThat(r.account().status()).isEqualTo(MailAccount.Status.ACTIVE);
        assertThat(plaintext(t, r.account().id())).isEqualTo("the-password");
        UUID tu = UUID.fromString(t.value());
        assertThat(count("SELECT count(*) FROM credential WHERE tenant_id = ? AND version = 1", tu)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'link'"
                + " AND result = 'ok' AND account_id = ? AND request_id = 'req-1'", tu, r.account().id().value()))
                .isEqualTo(1);
    }

    @Test
    void auditRowsHoldNoHostUserNameOrAddress() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        ResolvedAccount r = store.create(t, request("Work"), "pw".toCharArray(), 10, "req-1");
        store.updateSettings(t, r.account().id(), "Renamed-label", true, false, "req-2");
        store.relink(t, r.account().id(), "pw2".toCharArray(), "req-3");
        store.unlink(t, r.account().id(), "req-4");

        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT row_to_json(a)::text FROM audit_event a WHERE tenant_id = '"
                        + t.value() + "'")) {
            int rows = 0;
            while (rs.next()) {
                rows++;
                assertThat(rs.getString(1)).doesNotContain("secret-host").doesNotContain("secret-user")
                        .doesNotContain("example.org").doesNotContain("Renamed-label").doesNotContain("pw");
            }
            assertThat(rows).isEqualTo(4);
        }
    }

    @Test
    void aKeyServiceOutageRollsTheWholeLinkBackAndReportsUnavailability() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        wire(new KeyWrapper() {
            @Override
            public WrappedKey wrap(byte[] dek, String tenantId, String accountId) {
                throw new KeyServiceUnavailableException("vault", "timeout");
            }

            @Override
            public byte[] unwrap(String kekId, byte[] wrapped, String tenantId, String accountId) {
                throw new KeyServiceUnavailableException("vault", "timeout");
            }
        });

        assertThatThrownBy(() -> store.create(t, request("Work"), "pw".toCharArray(), 10, "req-1"))
                .isInstanceOf(LinkedAccountStore.UnavailableException.class);

        UUID tu = UUID.fromString(t.value());
        assertThat(count("SELECT count(*) FROM mailbox_account WHERE tenant_id = ?", tu)).isZero();
        assertThat(count("SELECT count(*) FROM credential WHERE tenant_id = ?", tu)).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ?", tu)).isZero();
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement().satisfies(e ->
                assertThat(e.getFormattedMessage()).contains("provider=vault").contains("class=timeout")
                        .contains(logIds.tenant(t.value())));
        assertThat(operations).contains("credentials/key_service_unavailable");
    }

    @Test
    void aFailingAuditWriteUndoesTheLinkFailClosed() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        exec("ALTER TABLE audit_event ADD CONSTRAINT audit_block_link CHECK (event_type <> 'link') NOT VALID");
        try {
            assertThatThrownBy(() -> store.create(t, request("Work"), "pw".toCharArray(), 10, "req-1"))
                    .isInstanceOf(LinkedAccountStore.UnavailableException.class);
        } finally {
            exec("ALTER TABLE audit_event DROP CONSTRAINT audit_block_link");
        }

        UUID tu = UUID.fromString(t.value());
        assertThat(count("SELECT count(*) FROM mailbox_account WHERE tenant_id = ?", tu)).isZero();
        assertThat(count("SELECT count(*) FROM credential WHERE tenant_id = ?", tu)).isZero();
    }

    @Test
    void theAccountCapHoldsUnderConcurrencyAndPerTenant() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        TenantId other = AccountSql.newTenant(db.dataSource());
        AtomicInteger created = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    try {
                        store.create(t, request("a" + n), "pw".toCharArray(), 3, "req-" + n);
                        created.incrementAndGet();
                    } catch (LinkedAccountStore.LimitReachedException e) {
                        refused.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertThat(created).hasValue(3);
        assertThat(refused).hasValue(13);
        assertThat(count("SELECT count(*) FROM mailbox_account WHERE tenant_id = ?", UUID.fromString(t.value())))
                .isEqualTo(3);
        assertThat(store.create(other, request("o"), "pw".toCharArray(), 3, "r")).isNotNull();
    }

    // ---- relink ----

    @Test
    void relinkSwapsTheCredentialClearsNeedsRelinkResetsTheCounterAndRaisesTheRevision() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        AccountId id = store.create(t, request("Work"), "old-pw".toCharArray(), 10, "r1").account().id();
        for (int i = 0; i < 3; i++) {
            accounts.recordRefusedLogin(t, id, 1L);
        }
        MailAccount before = accounts.findByTenantAndId(t, id).orElseThrow().account();
        assertThat(before.status()).isEqualTo(MailAccount.Status.NEEDS_RELINK);

        assertThat(store.relink(t, id, "new-pw".toCharArray(), "r2")).isTrue();

        MailAccount after = accounts.findByTenantAndId(t, id).orElseThrow().account();
        assertThat(after.status()).isEqualTo(MailAccount.Status.ACTIVE);
        assertThat(after.revision()).isEqualTo(before.revision() + 1);
        assertThat(plaintext(t, id)).isEqualTo("new-pw");
        UUID tu = UUID.fromString(t.value());
        assertThat(count("SELECT count(*) FROM credential WHERE tenant_id = ? AND account_id = ?", tu, id.value()))
                .isEqualTo(1); // the old version is gone
        assertThat(count("SELECT credential_version FROM mailbox_account WHERE tenant_id = ? AND id = ?", tu,
                id.value())).isEqualTo(2);
        assertThat(count("SELECT failed_logins FROM mailbox_account WHERE tenant_id = ? AND id = ?", tu,
                id.value())).isZero();
        // a LOGIN that was still running with the old credential no longer counts
        assertThat(accounts.recordRefusedLogin(t, id, before.revision())).isNull();
        assertThat(accounts.recordRefusedLogin(t, id, after.revision()).failedLogins()).isEqualTo(1);
    }

    @Test
    void aFailedRelinkKeepsTheOldCredentialAndState() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        AccountId id = store.create(t, request("Work"), "old-pw".toCharArray(), 10, "r1").account().id();
        long revision = accounts.findByTenantAndId(t, id).orElseThrow().account().revision();
        wire(new KeyWrapper() {
            @Override
            public WrappedKey wrap(byte[] dek, String tenantId, String accountId) {
                throw new KeyServiceUnavailableException("vault", "http_503");
            }

            @Override
            public byte[] unwrap(String kekId, byte[] wrapped, String tenantId, String accountId) {
                throw new KeyServiceUnavailableException("vault", "http_503");
            }
        });

        assertThatThrownBy(() -> store.relink(t, id, "new-pw".toCharArray(), "r2"))
                .isInstanceOf(LinkedAccountStore.UnavailableException.class);

        wire(localWrapper());
        assertThat(plaintext(t, id)).isEqualTo("old-pw");
        assertThat(accounts.findByTenantAndId(t, id).orElseThrow().account().revision()).isEqualTo(revision);
    }

    @Test
    void relinkOfAForeignOrUnknownAccountChangesNothing() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        TenantId other = AccountSql.newTenant(db.dataSource());
        AccountId id = store.create(t, request("Work"), "pw".toCharArray(), 10, "r1").account().id();

        assertThat(store.relink(other, id, "x".toCharArray(), "r")).isFalse();
        assertThat(store.relink(t, new AccountId("nope"), "x".toCharArray(), "r")).isFalse();

        assertThat(plaintext(t, id)).isEqualTo("pw");
        assertThat(count("SELECT count(*) FROM credential WHERE account_id = ?", id.value())).isEqualTo(1);
    }

    @Test
    void concurrentRelinksGetDistinctVersionsAndLeaveOneCurrentCredential() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        AccountId id = store.create(t, request("Work"), "pw0".toCharArray(), 10, "r1").account().id();

        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                String pw = "pw" + i;
                futures.add(pool.submit(() -> store.relink(t, id, pw.toCharArray(), "r")));
            }
            for (Future<Boolean> f : futures) {
                assertThat(f.get()).isTrue();
            }
        }

        UUID tu = UUID.fromString(t.value());
        assertThat(count("SELECT credential_version FROM mailbox_account WHERE tenant_id = ? AND id = ?", tu,
                id.value())).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM credential WHERE tenant_id = ? AND account_id = ?", tu, id.value()))
                .isEqualTo(1);
        assertThat(plaintext(t, id)).startsWith("pw");
    }

    // ---- settings ----

    @Test
    void settingsChangeLabelAndFlagsOfTheTenantsOwnAccountOnly() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        TenantId other = AccountSql.newTenant(db.dataSource());
        AccountId id = store.create(t, request("Work"), "pw".toCharArray(), 10, "r1").account().id();

        assertThat(store.updateSettings(other, id, "Hacked", false, true, "r")).isFalse();
        assertThat(store.updateSettings(t, id, "Renamed", false, true, "r2")).isTrue();

        MailAccount a = accounts.findByTenantAndId(t, id).orElseThrow().account();
        assertThat(a.label()).isEqualTo("Renamed");
        assertThat(a.readOnly()).isFalse();
        assertThat(a.allowPurge()).isTrue();
        assertThat(a.host()).isEqualTo("imap.secret-host.example"); // the endpoint is not editable
    }

    // ---- unlink ----

    @Test
    void unlinkDeletesTheAccountAndItsCredentialsAndTheHandleIsThenUnknown() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        TenantId other = AccountSql.newTenant(db.dataSource());
        ResolvedAccount r = store.create(t, request("Work"), "pw".toCharArray(), 10, "r1");

        assertThat(store.unlink(other, r.account().id(), "r")).isFalse();
        assertThat(accounts.findByTenantAndHandle(t, r.handle())).isPresent();
        assertThat(store.unlink(t, r.account().id(), "r2")).isTrue();

        assertThat(accounts.findByTenantAndHandle(t, r.handle())).isEmpty();
        assertThat(credentials.resolve(t, r.account().id())).isEmpty();
        assertThat(count("SELECT count(*) FROM credential WHERE account_id = ?", r.account().id().value())).isZero();
        assertThat(store.unlink(t, r.account().id(), "r3")).isFalse();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'unlink'",
                UUID.fromString(t.value()))).isEqualTo(1);
    }
}
