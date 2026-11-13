package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.oauth.RefreshResult;
import de.alexanderlindhorst.imapmcp.core.oauth.TokenAcquisitionException;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.LocalKeyWrapper;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.oauth.ItTokenClients;
import de.alexanderlindhorst.imapmcp.store.oauth.JdbcOAuthGrantStore;
import de.alexanderlindhorst.imapmcp.store.oauth.TokenProvider;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The grant lock of the XOAUTH2 refresh (SPEC 5.5 "Lock", 5.8; #498) on a real Postgres against a JDK
 * {@code HttpsServer} token stub with strict rotation: two coordinators (two grant stores on one database) never
 * reuse a retired refresh token, the credential version advances by one per exchange, a rotation leaves
 * {@code mailbox_account.revision} alone, a held row lock is {@code SERVER_BUSY} after the 10 s lock timeout (55P03),
 * and {@code invalid_grant} flips the account, bumps the revision and audits in one transaction. The grant store is
 * the production one with the production token client; only the client's resolver, trust and port point at the stub.
 */
class Xoauth2GrantLockIT {

    private static final String HOST = "token.example.com";

    @TempDir
    static Path dir;

    private static PostgresFixture postgres;
    private static Database db;
    private static Xoauth2Pki pki;
    private static Xoauth2TokenStub stub;
    private static CredentialCipher cipher;
    private static LogIds logIds;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgresFixture();
        postgres.start();
        db = Database.open(postgres.config());
        pki = new Xoauth2Pki(dir, HOST);
        stub = new Xoauth2TokenStub(pki, new Xoauth2Jwt());
        byte[] kek = new byte[32];
        Arrays.fill(kek, (byte) 4);
        cipher = new CredentialCipher(new LocalKeyWrapper("local-1", Map.of("local-1", kek)), new SecureRandom());
        byte[] master = new byte[32];
        Arrays.fill(master, (byte) 1);
        logIds = new LogIds(new KeyRing(1, Map.of(1, master)));
    }

    @AfterAll
    static void stop() {
        if (stub != null) {
            stub.close();
        }
        if (db != null) {
            db.close();
        }
        postgres.stop();
    }

    private static JdbcOAuthGrantStore coordinator(java.util.function.Consumer<String> labels) throws Exception {
        TokenProvider provider = new TokenProvider(Xoauth2Db.PROVIDER, URI.create("https://" + HOST + ":" + stub.port() + "/token"),
                "client", () -> new de.alexanderlindhorst.imapmcp.core.account.Secret("s3cret".toCharArray()));
        return new JdbcOAuthGrantStore(db.dataSource(), cipher, ItTokenClients.toLoopback(pki.clientContext(),
                stub.port()), id -> id.equals(Xoauth2Db.PROVIDER) ? java.util.Optional.of(provider)
                        : java.util.Optional.empty(), logIds, new de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder() {
                            @Override
                            public void oauthRefresh(TenantId tenant, String result) {
                                labels.accept(result);
                            }
                        });
    }

    private static Xoauth2Db.Seeded seed(String refreshToken, String sub) throws Exception {
        TenantId tenant = AccountSql.newTenant(db.dataSource());
        stub.register(refreshToken, sub);
        return Xoauth2Db.insertXoauth2Account(db.dataSource(), cipher, tenant, "a" + UUID.randomUUID().toString().substring(0, 12),
                "imap.example.com", 993, sub, refreshToken);
    }

    @Test
    void oneRotationAdvancesTheVersionByOneAndLeavesTheAccountRevisionAlone() throws Exception {
        List<String> labels = new ArrayList<>();
        Xoauth2Db.Seeded s = seed("rt-single-" + UUID.randomUUID(), "alice");
        long revision = Xoauth2Db.revision(db.dataSource(), s);
        int before = stub.exchanges();

        RefreshResult r = coordinator(labels::add).refresh(s.tenant(), s.account());

        assertThat(r).isInstanceOf(RefreshResult.Refreshed.class);
        assertThat(stub.exchanges() - before).isEqualTo(1);
        assertThat(Xoauth2Db.refreshVersion(db.dataSource(), s)).isEqualTo(2);
        assertThat(Xoauth2Db.refreshCredentials(db.dataSource(), s)).isEqualTo(1); // the old version is deleted
        assertThat(Xoauth2Db.revision(db.dataSource(), s)).isEqualTo(revision);
        assertThat(Xoauth2Db.status(db.dataSource(), s)).isEqualTo("active");
        assertThat(labels).containsExactly("ok");
    }

    @Test
    void twoCoordinatorsRacingNeverReuseARefreshTokenAndEveryExchangeAdvancesTheVersionByOne() throws Exception {
        String first = "rt-race-" + UUID.randomUUID();
        Xoauth2Db.Seeded s = seed(first, "alice");
        stub.delay(Duration.ofMillis(300)); // makes the refreshes overlap, so that only the row lock orders them
        int exchangesBefore = stub.exchanges();
        int reusesBefore = stub.reuses();
        int requestsBefore = stub.requests();
        long revision = Xoauth2Db.revision(db.dataSource(), s);
        List<String> labels = java.util.Collections.synchronizedList(new ArrayList<>());
        JdbcOAuthGrantStore a = coordinator(labels::add);
        JdbcOAuthGrantStore b = coordinator(labels::add);
        int perCoordinator = 3;
        ExecutorService pool = Executors.newFixedThreadPool(2 * perCoordinator);
        try {
            List<CompletableFuture<RefreshResult>> all = new ArrayList<>();
            for (int i = 0; i < perCoordinator; i++) {
                all.add(CompletableFuture.supplyAsync(() -> a.refresh(s.tenant(), s.account()), pool));
                all.add(CompletableFuture.supplyAsync(() -> b.refresh(s.tenant(), s.account()), pool));
            }
            for (CompletableFuture<RefreshResult> f : all) {
                assertThat(f.get(60, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(RefreshResult.Refreshed.class);
            }
        } finally {
            pool.shutdownNow();
            stub.delay(Duration.ZERO);
        }

        int refreshes = 2 * perCoordinator;
        assertThat(stub.exchanges() - exchangesBefore).isEqualTo(refreshes);
        assertThat(stub.requests() - requestsBefore).isEqualTo(refreshes); // one exchange per rotation, no retries
        assertThat(stub.reuses() - reusesBefore).isZero();
        List<String> used = stub.presentedRefreshTokens();
        List<String> mine = used.subList(used.size() - refreshes, used.size());
        assertThat(new HashSet<>(mine)).hasSize(refreshes); // every refresh token was presented exactly once
        assertThat(mine.get(0)).isEqualTo(first);
        assertThat(Xoauth2Db.refreshVersion(db.dataSource(), s)).isEqualTo(1 + refreshes);
        assertThat(Xoauth2Db.refreshCredentials(db.dataSource(), s)).isEqualTo(1);
        assertThat(Xoauth2Db.revision(db.dataSource(), s)).isEqualTo(revision);
        assertThat(labels).hasSize(refreshes).containsOnly("ok");
    }

    @Test
    void aHeldRowLockIsServerBusyAfterTheTenSecondLockTimeoutAndNothingIsExchanged() throws Exception {
        List<String> labels = new ArrayList<>();
        Xoauth2Db.Seeded s = seed("rt-held-" + UUID.randomUUID(), "alice");
        int requestsBefore = stub.requests();
        try (Connection holder = db.dataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement(
                    "SELECT 1 FROM oauth_grant WHERE tenant_id = ? AND account_id = ? FOR UPDATE")) {
                ps.setObject(1, Xoauth2Db.uuid(s.tenant()));
                ps.setString(2, s.account().value());
                ps.executeQuery();
            }
            long t0 = System.nanoTime();
            RefreshResult r = coordinator(labels::add).refresh(s.tenant(), s.account());
            Duration waited = Duration.ofNanos(System.nanoTime() - t0);

            assertThat(r).isInstanceOf(RefreshResult.LockTimeout.class);
            assertThat(waited).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(20));
            assertThat(TokenAcquisitionException.of(r).toolError(null, "rid").code()).isEqualTo(ErrorCode.SERVER_BUSY);
            holder.rollback();
        }
        assertThat(stub.requests()).isEqualTo(requestsBefore);
        assertThat(labels).containsExactly("lock_timeout");
        assertThat(Xoauth2Db.refreshVersion(db.dataSource(), s)).isEqualTo(1);
        // the lock is free again: the next refresh goes through
        assertThat(coordinator(labels::add).refresh(s.tenant(), s.account())).isInstanceOf(RefreshResult.Refreshed.class);
    }

    @Test
    void invalidGrantFlipsTheAccountBumpsTheRevisionAndAuditsInOneTransaction() throws Exception {
        List<String> labels = new ArrayList<>();
        String token = "rt-revoked-" + UUID.randomUUID();
        Xoauth2Db.Seeded s = seed(token, "alice");
        stub.revoke(token);
        long revision = Xoauth2Db.revision(db.dataSource(), s);

        RefreshResult r = coordinator(labels::add).refresh(s.tenant(), s.account());

        assertThat(r).isInstanceOf(RefreshResult.InvalidGrant.class);
        assertThat(Xoauth2Db.status(db.dataSource(), s)).isEqualTo("needs_relink");
        assertThat(Xoauth2Db.revision(db.dataSource(), s)).isEqualTo(revision + 1);
        assertThat(Xoauth2Db.auditCount(db.dataSource(), s, "credential_revoked")).isEqualTo(1);
        assertThat(Xoauth2Db.refreshVersion(db.dataSource(), s)).isEqualTo(1); // nothing rotated
        assertThat(labels).containsExactly("invalid_grant");
        assertThat(TokenAcquisitionException.of(r).toolError("https://mail.example.com/accounts", "rid").details())
                .containsEntry("reason", "relink_required");
    }
}
