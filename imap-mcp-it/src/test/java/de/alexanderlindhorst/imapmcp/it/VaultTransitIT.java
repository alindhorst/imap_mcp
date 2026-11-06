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
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialDecryptException;
import de.alexanderlindhorst.imapmcp.store.crypto.KeyServiceUnavailableException;
import de.alexanderlindhorst.imapmcp.store.crypto.KeyWrapException;
import de.alexanderlindhorst.imapmcp.store.crypto.VaultAuth;
import de.alexanderlindhorst.imapmcp.store.crypto.VaultConfig;
import de.alexanderlindhorst.imapmcp.store.crypto.VaultTransitKeyWrapper;
import de.alexanderlindhorst.imapmcp.store.crypto.WrappedKey;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcCredentialStore;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;

/** Vault Transit key wrapper against a real Vault (dev mode) and the JDBC credential store (C3, SPEC 5.8). */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VaultTransitIT {

    private static final String ROOT = "root-token-for-tests";
    private static final String KEY = "imap-mcp";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String ACCOUNT = "acct-one";

    private static VaultContainer<?> vault;
    private static PostgresFixture postgres;
    private static Database db;
    private static URI address;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        vault = new VaultContainer<>(DockerImageName.parse(ItImages.VAULT).asCompatibleSubstituteFor("hashicorp/vault"))
                .withVaultToken(ROOT);
        vault.start();
        address = URI.create("http://" + vault.getHost() + ":" + vault.getMappedPort(8200));
        admin("POST", "/v1/sys/mounts/transit", "{\"type\":\"transit\"}");
        admin("POST", "/v1/transit/keys/" + KEY, "{\"type\":\"aes256-gcm96\"}");
        postgres = new PostgresFixture();
        postgres.start();
        db = Database.open(postgres.config());
    }

    @AfterAll
    static void stop() {
        if (db != null) {
            db.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
        vault.stop();
    }

    private static String admin(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(address.resolve(path)).header("X-Vault-Token", ROOT)
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as("%s %s", method, path).isBetween(200, 299);
        return r.body();
    }

    private static String field(String json, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static VaultConfig config(VaultAuth auth) {
        return new VaultConfig(address, "transit", KEY, auth, null, Duration.ofSeconds(5), true);
    }

    private static VaultAuth.Token rootToken() {
        return new VaultAuth.Token(new SecretRef("env:T"));
    }

    private static VaultTransitKeyWrapper wrapper() {
        return new VaultTransitKeyWrapper(config(rootToken()), ref -> new Secret(ROOT.toCharArray()),
                Clock.systemUTC());
    }

    private static byte[] dek(int fill) {
        byte[] d = new byte[32];
        Arrays.fill(d, (byte) fill);
        return d;
    }

    @Test
    @Order(1)
    void wrapAndUnwrapRoundTripWithTheKeyNameAsKekId() {
        VaultTransitKeyWrapper w = wrapper();

        WrappedKey wrapped = w.wrap(dek(5), TENANT, ACCOUNT);

        assertThat(wrapped.kekId()).isEqualTo(KEY);
        assertThat(new String(wrapped.bytes(), StandardCharsets.US_ASCII)).startsWith("vault:v1:");
        assertThat(w.unwrap(wrapped.kekId(), wrapped.bytes(), TENANT, ACCOUNT)).isEqualTo(dek(5));
    }

    @Test
    @Order(2)
    void aRowCopiedToAnotherTenantOrAccountFailsInTheCredentialCipherEvenThoughVaultUnwraps() {
        CredentialCipher cipher = new CredentialCipher(wrapper(), new SecureRandom());
        CredentialCipher.Sealed sealed = cipher.seal(TENANT, ACCOUNT, 1, "pw".toCharArray());

        assertThatThrownBy(() -> cipher.open(TENANT, "acct-two", 1, sealed))
                .isInstanceOf(CredentialDecryptException.class);
        assertThatThrownBy(() -> cipher.open("22222222-2222-2222-2222-222222222222", ACCOUNT, 1, sealed))
                .isInstanceOf(CredentialDecryptException.class);
        try (Secret ok = cipher.open(TENANT, ACCOUNT, 1, sealed)) {
            assertThat(new String(ok.chars())).isEqualTo("pw");
        }
    }

    @Test
    @Order(3)
    void aTamperedWrappedDekIsAnIntegrityFailure() {
        VaultTransitKeyWrapper w = wrapper();
        byte[] wrapped = w.wrap(dek(5), TENANT, ACCOUNT).bytes().clone();
        int last = wrapped.length - 6; // inside the base64 payload, not the padding
        wrapped[last] = (byte) (wrapped[last] == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> w.unwrap(KEY, wrapped, TENANT, ACCOUNT)).isInstanceOf(KeyWrapException.class)
                .isNotInstanceOf(KeyServiceUnavailableException.class);
    }

    @Test
    @Order(4)
    void aRotatedKeyStillDecryptsOldRowsAndNewWrapsUseTheLatestVersion() throws Exception {
        VaultTransitKeyWrapper w = wrapper();
        WrappedKey old = w.wrap(dek(1), TENANT, ACCOUNT);

        admin("POST", "/v1/transit/keys/" + KEY + "/rotate", null);
        WrappedKey fresh = w.wrap(dek(2), TENANT, ACCOUNT);

        assertThat(new String(old.bytes(), StandardCharsets.US_ASCII)).startsWith("vault:v1:");
        assertThat(new String(fresh.bytes(), StandardCharsets.US_ASCII)).startsWith("vault:v2:");
        assertThat(w.unwrap(KEY, old.bytes(), TENANT, ACCOUNT)).isEqualTo(dek(1));
        assertThat(w.unwrap(KEY, fresh.bytes(), TENANT, ACCOUNT)).isEqualTo(dek(2));
    }

    @Test
    @Order(5)
    void rewrapReplacesOnlyTheWrappedDekAndKeepsItDecryptable() throws Exception {
        VaultTransitKeyWrapper w = wrapper();
        WrappedKey old = w.wrap(dek(3), TENANT, ACCOUNT);
        admin("POST", "/v1/transit/keys/" + KEY + "/rotate", null);

        String reply = admin("POST", "/v1/transit/rewrap/" + KEY, "{\"ciphertext\":\""
                + new String(old.bytes(), StandardCharsets.US_ASCII) + "\"}");
        String rewrapped = field(reply, "ciphertext");

        assertThat(rewrapped).startsWith("vault:v3:");
        assertThat(w.unwrap(KEY, rewrapped.getBytes(StandardCharsets.US_ASCII), TENANT, ACCOUNT)).isEqualTo(dek(3));
    }

    @Test
    @Order(6)
    void approleLoginWorksWithOneLoginForSeveralCalls() throws Exception {
        admin("POST", "/v1/sys/auth/approle", "{\"type\":\"approle\"}");
        admin("PUT", "/v1/sys/policies/acl/imap-mcp",
                "{\"policy\":\"path \\\"transit/*\\\" { capabilities = [\\\"create\\\", \\\"update\\\"] }\"}");
        admin("POST", "/v1/auth/approle/role/imap", "{\"token_policies\":\"imap-mcp\",\"token_ttl\":\"1h\"}");
        String roleId = field(admin("GET", "/v1/auth/approle/role/imap/role-id", null), "role_id");
        String secretId = field(admin("POST", "/v1/auth/approle/role/imap/secret-id", "{}"), "secret_id");
        AtomicInteger reads = new AtomicInteger();
        Function<SecretRef, Secret> resolver = ref -> {
            reads.incrementAndGet();
            return new Secret((ref.value().equals("env:ROLE") ? roleId : secretId).toCharArray());
        };
        VaultTransitKeyWrapper w = new VaultTransitKeyWrapper(config(
                new VaultAuth.AppRole("approle", new SecretRef("env:ROLE"), new SecretRef("env:SEC"))), resolver,
                Clock.systemUTC());

        WrappedKey wrapped = w.wrap(dek(7), TENANT, ACCOUNT);
        assertThat(w.unwrap(KEY, wrapped.bytes(), TENANT, ACCOUNT)).isEqualTo(dek(7));
        assertThat(reads.get()).isEqualTo(2); // one login for both calls

    }

    @Test
    @Order(7)
    void aWrongTokenIsUnavailabilityAfterTheOneReAuthentication() {
        VaultTransitKeyWrapper w = new VaultTransitKeyWrapper(config(rootToken()),
                ref -> new Secret("wrong-token".toCharArray()), Clock.systemUTC());

        assertThatThrownBy(() -> w.wrap(dek(1), TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class)
                .satisfies(e -> assertThat(((KeyServiceUnavailableException) e).failureClass()).isEqualTo("http_403"));
    }

    // ---- end to end with Postgres: JdbcCredentialStore + CredentialCipher over Vault ----

    private static TenantId newTenant() throws Exception {
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

    private static AccountId newAccount(TenantId t, String id) throws Exception {
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO mailbox_account (tenant_id, id, handle, label, address, host, port, security,"
                                + " flavor, username, auth) VALUES (?, ?, ?, 'l', 'a@example.org', 'h', 993,"
                                + " 'IMPLICIT_TLS', 'GENERIC', 'u', 'password')")) {
            ps.setObject(1, UUID.fromString(t.value()));
            ps.setString(2, id);
            ps.setString(3, "acc_" + UUID.randomUUID());
            ps.executeUpdate();
        }
        return new AccountId(id);
    }

    private final List<String> operations = new ArrayList<>();
    private final MetricsRecorder metrics = new MetricsRecorder() {
        @Override
        public void operation(TenantId tenant, String tool, String op, Duration latency) {
            operations.add(tool + "/" + op);
        }
    };

    private JdbcCredentialStore store(VaultTransitKeyWrapper w) {
        byte[] master = new byte[32];
        Arrays.fill(master, (byte) 1);
        return new JdbcCredentialStore(db.dataSource(), new CredentialCipher(w, new SecureRandom()),
                new LogIds(new KeyRing(1, Map.of(1, master))), metrics);
    }

    @Test
    @Order(8)
    void credentialsRoundTripThroughPostgresAndVaultAndATamperedRowIsADecryptFailure() throws Exception {
        TenantId t = newTenant();
        AccountId a = newAccount(t, "vault-acct");
        JdbcCredentialStore s = store(wrapper());
        s.insert(t, a, 1, "pw-via-vault".toCharArray());

        try (Secret secret = s.resolve(t, a).orElseThrow()) {
            assertThat(new String(secret.chars())).isEqualTo("pw-via-vault");
        }
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "UPDATE credential SET wrapped_dek = ? WHERE tenant_id = ?")) {
            byte[] w = new byte[] {'v', 'a', 'u', 'l', 't', ':', 'v', '1', ':', 'Z', 'm', '9', 'v'};
            ps.setBytes(1, w);
            ps.setObject(2, UUID.fromString(t.value()));
            ps.executeUpdate();
        }
        Logger logger = (Logger) LoggerFactory.getLogger(JdbcCredentialStore.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThatThrownBy(() -> s.resolve(t, a)).isInstanceOf(CredentialDecryptException.class);
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR).hasSize(1);
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    @Order(10)
    void aSealedVaultGivesInternalErrorWithTheUnavailabilityWarnAndMetricAndNoLogin() throws Exception {
        TenantId t = newTenant();
        AccountId a = newAccount(t, "sealed-acct");
        JdbcCredentialStore s = store(wrapper());
        s.insert(t, a, 1, "pw".toCharArray());
        admin("PUT", "/v1/sys/seal", null);

        Logger logger = (Logger) LoggerFactory.getLogger(JdbcCredentialStore.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
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
            MailAccount account = new MailAccount(a, "l", null, "127.0.0.1", mailServer.getLocalPort(),
                    MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "u", new SecretRef("db:"));
            StorePool pool = new StorePool(sessions, s, new CapabilityCache(), key -> account, PoolSettings.DEFAULTS);
            PoolKey key = PoolKey.of(new AccountContext(t), new ResolvedAccount(t, account, "acc_TESTHANDLE"));

            assertThatThrownBy(() -> pool.borrow(key)).satisfies(e -> {
                assertThat(ImapErrorMapper.map(e, "req").error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertThat(root).isInstanceOf(KeyServiceUnavailableException.class);
            });
            pool.close();
            mailServer.close();
            acceptor.join(2000);
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(connections).hasValue(0);
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("provider=vault").contains("class=http_503");
        assertThat(operations).contains("credentials/key_service_unavailable")
                .doesNotContain("credentials/decrypt_failure");
    }

    @Test
    @Order(9)
    void aFlippedByteOfTheStoredWrappedDekIsADecryptFailureThroughThePoolWithoutALogin() throws Exception {
        TenantId t = newTenant();
        AccountId a = newAccount(t, "flip-acct");
        JdbcCredentialStore s = store(wrapper());
        s.insert(t, a, 1, "pw".toCharArray());
        try (Connection c = db.dataSource().getConnection();
                PreparedStatement sel = c.prepareStatement("SELECT wrapped_dek FROM credential WHERE tenant_id = ?");
                PreparedStatement upd = c.prepareStatement("UPDATE credential SET wrapped_dek = ? WHERE tenant_id = ?")) {
            sel.setObject(1, UUID.fromString(t.value()));
            byte[] wrapped;
            try (var rs = sel.executeQuery()) {
                rs.next();
                wrapped = rs.getBytes(1);
            }
            int at = wrapped.length - 8; // inside the base64 payload
            wrapped[at] = (byte) (wrapped[at] == 'A' ? 'B' : 'A');
            upd.setBytes(1, wrapped);
            upd.setObject(2, UUID.fromString(t.value()));
            upd.executeUpdate();
        }
        operations.clear();

        int connections = borrowAndCountConnections(s, t, a, CredentialDecryptException.class);

        assertThat(connections).isZero();
        assertThat(operations).contains("credentials/decrypt_failure").doesNotContain("credentials/key_service_unavailable");
    }

    private int borrowAndCountConnections(JdbcCredentialStore s, TenantId t, AccountId a, Class<?> expectedRoot)
            throws Exception {
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
            MailAccount account = new MailAccount(a, "l", null, "127.0.0.1", mailServer.getLocalPort(),
                    MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "u", new SecretRef("db:"));
            StorePool pool = new StorePool(sessions, s, new CapabilityCache(), key -> account, PoolSettings.DEFAULTS);
            PoolKey key = PoolKey.of(new AccountContext(t), new ResolvedAccount(t, account, "acc_TESTHANDLE"));
            assertThatThrownBy(() -> pool.borrow(key)).satisfies(e -> {
                assertThat(ImapErrorMapper.map(e, "req").error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertThat(root).isInstanceOf(expectedRoot);
            });
            pool.close();
            mailServer.close();
            acceptor.join(2000);
        }
        return connections.get();
    }

    @Test
    @Order(11)
    void aStoppedVaultIsUnreachableUnavailability() {
        VaultTransitKeyWrapper w = wrapper();
        vault.stop();

        assertThatThrownBy(() -> w.wrap(dek(1), TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class)
                .satisfies(e -> assertThat(((KeyServiceUnavailableException) e).failureClass())
                        .isIn("unreachable", "timeout"));
    }
}
