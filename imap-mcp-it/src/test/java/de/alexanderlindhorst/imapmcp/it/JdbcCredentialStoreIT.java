package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialDecryptException;
import de.alexanderlindhorst.imapmcp.store.crypto.LocalKeyWrapper;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcCredentialStore;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** JDBC {@code CredentialStore} with envelope encryption on Postgres (A7, SPEC 5.3). */
class JdbcCredentialStoreIT {

    private static PostgresFixture postgres;
    private static Database db;

    private final List<String> operations = new ArrayList<>();
    private final MetricsRecorder metrics = new MetricsRecorder() {
        @Override
        public void operation(TenantId tenant, String tool, String op, Duration latency) {
            operations.add(tool + "/" + op);
        }
    };
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;
    private JdbcCredentialStore store;

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

    @BeforeEach
    void setUp() {
        byte[] kek = new byte[32];
        Arrays.fill(kek, (byte) 4);
        CredentialCipher cipher = new CredentialCipher(new LocalKeyWrapper("local-1", Map.of("local-1", kek)),
                new SecureRandom());
        byte[] master = new byte[32];
        Arrays.fill(master, (byte) 1);
        LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, master)));
        store = new JdbcCredentialStore(db.dataSource(), cipher, logIds, metrics);
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

    private static TenantId newTenant() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO tenant (id, issuer, subject) VALUES (?, 'https://idp.example', ?)")) {
            ps.setObject(1, id);
            ps.setString(2, id.toString());
            ps.executeUpdate();
        }
        return new TenantId(id.toString());
    }

    private static AccountId newAccount(TenantId tenant, String id) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mailbox_account (tenant_id, id, handle, label, address, host, port, security,"
                                + " flavor, username, auth) VALUES (?, ?, ?, 'l', 'a@example.org', 'h', 993,"
                                + " 'IMPLICIT_TLS', 'GENERIC', 'u', 'password')")) {
            ps.setObject(1, UUID.fromString(tenant.value()));
            ps.setString(2, id);
            ps.setString(3, "acc_" + UUID.randomUUID());
            ps.executeUpdate();
        }
        return new AccountId(id);
    }

    private static void copyCredential(TenantId fromTenant, AccountId fromAccount, TenantId toTenant,
            AccountId toAccount) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO credential (tenant_id, account_id, version, kind, kek_id, wrapped_dek, nonce,"
                                + " ciphertext) SELECT ?, ?, version, kind, kek_id, wrapped_dek, nonce, ciphertext"
                                + " FROM credential WHERE tenant_id = ? AND account_id = ?")) {
            ps.setObject(1, UUID.fromString(toTenant.value()));
            ps.setString(2, toAccount.value());
            ps.setObject(3, UUID.fromString(fromTenant.value()));
            ps.setString(4, fromAccount.value());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private static String read(Secret s) {
        try (s) {
            return new String(s.chars());
        }
    }

    @Test
    void insertedCredentialResolvesAtConnectTime() throws Exception {
        TenantId t = newTenant();
        AccountId a = newAccount(t, "acct-1");
        store.insert(t, a, 1, "s3cr3t-canary".toCharArray());

        assertThat(store.resolve(t, a)).hasValueSatisfying(s -> assertThat(read(s)).isEqualTo("s3cr3t-canary"));
    }

    @Test
    void thePlaintextIsNotInTheRow() throws Exception {
        TenantId t = newTenant();
        AccountId a = newAccount(t, "acct-2");
        store.insert(t, a, 1, "plain-canary-xyz".toCharArray());

        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT ciphertext, wrapped_dek, nonce, kek_id FROM credential WHERE tenant_id = ?")) {
            ps.setObject(1, UUID.fromString(t.value()));
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(new String(rs.getBytes(1), java.nio.charset.StandardCharsets.ISO_8859_1))
                        .doesNotContain("plain-canary");
                assertThat(rs.getBytes(3)).hasSize(12);
                assertThat(rs.getString(4)).isEqualTo("local-1");
            }
        }
    }

    @Test
    void anotherTenantCannotResolveTheAccount() throws Exception {
        TenantId t1 = newTenant();
        TenantId t2 = newTenant();
        AccountId a = newAccount(t1, "acct-3");
        store.insert(t1, a, 1, "pw".toCharArray());

        assertThat(store.resolve(t2, a)).isEmpty();
    }

    @Test
    void unknownAccountAndNonUuidTenantResolveToEmpty() throws Exception {
        TenantId t = newTenant();

        assertThat(store.resolve(t, new AccountId("nope"))).isEmpty();
        assertThat(store.resolve(new TenantId("not-a-uuid"), new AccountId("nope"))).isEmpty();
    }

    @Test
    void onlyTheCurrentCredentialVersionIsUsed() throws Exception {
        TenantId t = newTenant();
        AccountId a = newAccount(t, "acct-4");
        store.insert(t, a, 1, "old".toCharArray());
        store.insert(t, a, 2, "new".toCharArray());
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE mailbox_account SET credential_version = 2 WHERE tenant_id = ? AND id = ?")) {
            ps.setObject(1, UUID.fromString(t.value()));
            ps.setString(2, a.value());
            ps.executeUpdate();
        }

        assertThat(store.resolve(t, a)).hasValueSatisfying(s -> assertThat(read(s)).isEqualTo("new"));
    }

    @Test
    void aRowCopiedToAnotherAccountFailsWithOneErrorLineWithHashedIdsAndAMetric() throws Exception {
        TenantId t = newTenant();
        AccountId victim = newAccount(t, "victim-acct");
        AccountId attacker = newAccount(t, "attacker-acct");
        store.insert(t, victim, 1, "victim-pw-canary".toCharArray());
        copyCredential(t, victim, t, attacker);

        assertThatThrownBy(() -> store.resolve(t, attacker)).isInstanceOf(CredentialDecryptException.class);

        assertFailureLoggedOnce(t, attacker);
    }

    @Test
    void aRowCopiedToAnotherTenantFailsWithOneErrorLineWithHashedIds() throws Exception {
        TenantId t1 = newTenant();
        TenantId t2 = newTenant();
        AccountId a1 = newAccount(t1, "same-id");
        AccountId a2 = newAccount(t2, "same-id");
        store.insert(t1, a1, 1, "victim-pw-canary".toCharArray());
        copyCredential(t1, a1, t2, a2);

        assertThatThrownBy(() -> store.resolve(t2, a2)).isInstanceOf(CredentialDecryptException.class);

        assertFailureLoggedOnce(t2, a2);
    }

    private void assertFailureLoggedOnce(TenantId tenant, AccountId account) {
        List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        String text = errors.get(0).getFormattedMessage();
        assertThat(text).doesNotContain(tenant.value()).doesNotContain(account.value())
                .doesNotContain("victim-pw-canary");
        assertThat(errors.get(0).getThrowableProxy()).isNull();
        assertThat(operations).contains("credentials/decrypt_failure");
    }

    @Test
    void aFailedDecryptEndsAsInternalErrorAndNeverOpensAConnectionToTheMailServer() throws Exception {
        TenantId t = newTenant();
        AccountId victim = newAccount(t, "victim-acct2");
        AccountId attacker = newAccount(t, "attacker-acct2");
        store.insert(t, victim, 1, "victim-pw-canary".toCharArray());
        copyCredential(t, victim, t, attacker);
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket mailServer = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
                ImapSessionFactory sessions = new ImapSessionFactory(new ImapTimeouts(Duration.ofSeconds(2),
                        Duration.ofSeconds(2), Duration.ofSeconds(2)))) {
            Thread acceptor = Thread.startVirtualThread(() -> {
                try {
                    while (true) {
                        mailServer.accept().close();
                        connections.incrementAndGet();
                    }
                } catch (java.io.IOException e) {
                    // closed by the test
                }
            });
            MailAccount account = new MailAccount(attacker, "l", null, "127.0.0.1", mailServer.getLocalPort(),
                    MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "u", new SecretRef("env:UNUSED"));
            StorePool pool = new StorePool(sessions, store, new CapabilityCache(), key -> account,
                    PoolSettings.DEFAULTS);
            PoolKey key = PoolKey.of(new AccountContext(t), new ResolvedAccount(t, account, "acc_TESTHANDLE"));

            assertThatThrownBy(() -> pool.borrow(key)).satisfies(e ->
                    assertThat(ImapErrorMapper.map(e, "req").error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
            pool.close();
            mailServer.close();
            acceptor.join(2000);
        }

        assertThat(connections).hasValue(0);
    }
}
