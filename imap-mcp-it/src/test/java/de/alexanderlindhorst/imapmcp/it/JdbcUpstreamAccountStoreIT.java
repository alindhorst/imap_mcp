package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.linking.LinkRequest;
import de.alexanderlindhorst.imapmcp.core.linking.LinkedAccountStore;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamAccountStore;
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
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcCredentialStore;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcLinkedAccountStore;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Account, grant, credential version 1 and audit of an upstream OAuth link in one transaction, and the re-link, on
 * Postgres (X1-2).
 */
class JdbcUpstreamAccountStoreIT {

    private static PostgresFixture postgres;
    private static Database db;
    private static final CredentialCipher CIPHER = new CredentialCipher(localWrapper(), new SecureRandom());

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

    private static KeyWrapper keyServiceDown() {
        return new KeyWrapper() {
            @Override
            public WrappedKey wrap(byte[] dek, String tenantId, String accountId) {
                throw new KeyServiceUnavailableException("vault", "timeout");
            }

            @Override
            public byte[] unwrap(String kekId, byte[] wrapped, String tenantId, String accountId) {
                throw new KeyServiceUnavailableException("vault", "timeout");
            }
        };
    }

    private static JdbcLinkedAccountStore store(KeyWrapper wrapper) {
        byte[] master = new byte[32];
        Arrays.fill(master, (byte) 1);
        LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, master)));
        JdbcCredentialStore credentials = new JdbcCredentialStore(db.dataSource(),
                new CredentialCipher(wrapper, new SecureRandom()), logIds, new MetricsRecorder() { });
        return new JdbcLinkedAccountStore(db.dataSource(), new JdbcAccountRepository(db.dataSource()), credentials);
    }

    private final JdbcLinkedAccountStore store = store(localWrapper());
    private final JdbcAccountRepository accounts = new JdbcAccountRepository(db.dataSource());

    private static UpstreamAccountStore.NewUpstreamAccount account(String address, String provider) {
        return new UpstreamAccountStore.NewUpstreamAccount("Work", address, provider, "imap.gmail.example", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GMAIL, false, "https://mail.example/");
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

    private static void exec(String sql, Object... params) throws Exception {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    /** Decrypts an oauth_refresh credential with the test's own cipher, independent of the code under test. */
    private static String refreshToken(TenantId t, AccountId id, int version) throws Exception {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT kek_id, wrapped_dek, nonce, ciphertext FROM credential WHERE tenant_id = ? AND account_id = ?"
                        + " AND version = ? AND kind = 'oauth_refresh'")) {
            ps.setObject(1, UUID.fromString(t.value()));
            ps.setString(2, id.value());
            ps.setInt(3, version);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("oauth_refresh version %d", version).isTrue();
                try (Secret s = CIPHER.open(t.value(), id.value(), version, new CredentialCipher.Sealed(
                        rs.getString(1), rs.getBytes(2), rs.getBytes(3), rs.getBytes(4)))) {
                    return new String(s.chars());
                }
            }
        }
    }

    // ---- create ----

    @Test
    void createStoresAccountGrantRefreshCredentialAndAuditRowTogether() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());

        ResolvedAccount r = store.create(t, account("Me@Example.org", "google"), "refresh-canary".toCharArray(), 10,
                "req-1");

        MailAccount a = r.account();
        assertThat(a.auth()).isEqualTo(MailAccount.Auth.XOAUTH2);
        assertThat(a.providerId()).isEqualTo("google");
        assertThat(a.status()).isEqualTo(MailAccount.Status.ACTIVE);
        assertThat(a.address()).isEqualTo("Me@Example.org");
        assertThat(a.username()).isEqualTo("Me@Example.org");
        assertThat(a.host()).isEqualTo("imap.gmail.example");
        assertThat(a.flavor()).isEqualTo(MailAccount.Flavor.GMAIL);
        assertThat(a.readOnly()).isFalse();
        assertThat(a.allowPurge()).isFalse();
        assertThat(r.handle()).startsWith("acc_");
        assertThat(accounts.findByTenantAndHandle(t, r.handle())).isPresent();
        UUID tu = UUID.fromString(t.value());
        assertThat(refreshToken(t, a.id(), 1)).isEqualTo("refresh-canary");
        assertThat(count("SELECT count(*) FROM oauth_grant WHERE tenant_id = ? AND account_id = ?"
                + " AND provider_id = 'google' AND scope = 'https://mail.example/' AND refresh_version = 1", tu,
                a.id().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM mailbox_account WHERE tenant_id = ? AND id = ?"
                + " AND credential_version = 1 AND auth = 'xoauth2'", tu, a.id().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'link'"
                + " AND result = 'ok' AND account_id = ? AND request_id = 'req-1'", tu, a.id().value()))
                .isEqualTo(1);
        // no plaintext anywhere: the ciphertext column does not contain the token
        assertThat(count("SELECT count(*) FROM credential WHERE tenant_id = ? AND position('refresh-canary'::bytea"
                + " in ciphertext) > 0", tu)).isZero();
    }

    @Test
    void thePasswordReaderNeverSeesAnOauthRefreshCredential() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        ResolvedAccount r = store.create(t, account("a@example.org", "google"), "r".toCharArray(), 10, "req");
        byte[] master = new byte[32];
        Arrays.fill(master, (byte) 1);
        JdbcCredentialStore passwordReader = new JdbcCredentialStore(db.dataSource(),
                new CredentialCipher(localWrapper(), new SecureRandom()), new LogIds(new KeyRing(1, Map.of(1, master))),
                new MetricsRecorder() { });

        assertThat(passwordReader.resolve(t, r.account().id())).isEmpty();
    }

    @Test
    void theSameProviderAndAddressTwiceInATenantIsADuplicateIgnoringCaseAndStoresNothing() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        store.create(t, account("me@example.org", "google"), "r1".toCharArray(), 10, "req-1");
        UUID tu = UUID.fromString(t.value());

        assertThatThrownBy(() -> store.create(t, account("ME@EXAMPLE.ORG", "google"), "r2".toCharArray(), 10,
                "req-2")).isInstanceOf(UpstreamAccountStore.DuplicateException.class);

        assertThat(count("SELECT count(*) FROM mailbox_account WHERE tenant_id = ?", tu)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credential WHERE tenant_id = ?", tu)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM oauth_grant WHERE tenant_id = ?", tu)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND request_id = 'req-2'", tu))
                .isZero();
    }

    @Test
    void theSameAddressIsAllowedInAnotherTenantAndUnderAnotherProvider() throws Exception {
        TenantId a = AccountSql.newTenant(db.dataSource());
        TenantId b = AccountSql.newTenant(db.dataSource());
        store.create(a, account("me@example.org", "google"), "r1".toCharArray(), 10, "req-1");

        assertThat(store.create(b, account("me@example.org", "google"), "r2".toCharArray(), 10, "req-2")).isNotNull();
        assertThat(store.create(a, account("me@example.org", "other"), "r3".toCharArray(), 10, "req-3")).isNotNull();
    }

    @Test
    void aPasswordAccountWithTheSameAddressIsNoDuplicate() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        store.create(t, new LinkRequest("Pw", "imap.example.org", 993, MailAccount.Security.IMPLICIT_TLS,
                "me@example.org", true, false), "pw".toCharArray(), 10, "req-0");

        assertThat(store.create(t, account("me@example.org", "google"), "r".toCharArray(), 10, "req-1")).isNotNull();
    }

    @Test
    void concurrentCreatesOfTheSameAddressSucceedOnce() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        var pool = Executors.newFixedThreadPool(6);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger dup = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> {
                try {
                    store.create(t, account("same@example.org", "google"), "r".toCharArray(), 10, "req");
                    ok.incrementAndGet();
                } catch (UpstreamAccountStore.DuplicateException e) {
                    dup.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(1);
        assertThat(dup.get()).isEqualTo(5);
    }

    @Test
    void theAccountLimitIsCheckedInTheTransactionAndStoresNothingOverIt() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        store.create(t, account("a@example.org", "google"), "r".toCharArray(), 1, "req-1");
        UUID tu = UUID.fromString(t.value());

        assertThatThrownBy(() -> store.create(t, account("b@example.org", "google"), "r".toCharArray(), 1, "req-2"))
                .isInstanceOf(LinkedAccountStore.LimitReachedException.class);

        assertThat(count("SELECT count(*) FROM mailbox_account WHERE tenant_id = ?", tu)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM oauth_grant WHERE tenant_id = ?", tu)).isEqualTo(1);
    }

    @Test
    void aKeyServiceFailureRollsEverythingBack() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        KeyWrapper down = keyServiceDown();

        assertThatThrownBy(() -> store(down).create(t, account("a@example.org", "google"), "r".toCharArray(), 10,
                "req-1")).isInstanceOf(LinkedAccountStore.UnavailableException.class);

        UUID tu = UUID.fromString(t.value());
        assertThat(count("SELECT count(*) FROM mailbox_account WHERE tenant_id = ?", tu)).isZero();
        assertThat(count("SELECT count(*) FROM oauth_grant WHERE tenant_id = ?", tu)).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ?", tu)).isZero();
    }

    // ---- re-link ----

    @Test
    void relinkWritesANewVersionMovesThePointerClearsTheFlagsAndBumpsTheRevision() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        ResolvedAccount r = store.create(t, account("me@example.org", "google"), "refresh-old".toCharArray(), 10,
                "req-1");
        AccountId id = r.account().id();
        UUID tu = UUID.fromString(t.value());
        exec("UPDATE mailbox_account SET status = 'needs_relink', failed_logins = 3 WHERE tenant_id = ? AND id = ?",
                tu, id.value());
        long revisionBefore = accounts.findByTenantAndId(t, id).orElseThrow().account().revision();

        boolean done = store.relink(t, id, "refresh-new".toCharArray(), "https://mail.example/ extra", "req-2");

        assertThat(done).isTrue();
        MailAccount after = accounts.findByTenantAndId(t, id).orElseThrow().account();
        assertThat(after.status()).isEqualTo(MailAccount.Status.ACTIVE);
        assertThat(after.revision()).isEqualTo(revisionBefore + 1);
        assertThat(count("SELECT count(*) FROM mailbox_account WHERE tenant_id = ? AND id = ?"
                + " AND failed_logins = 0 AND credential_version = 2", tu, id.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM oauth_grant WHERE tenant_id = ? AND account_id = ?"
                + " AND refresh_version = 2 AND scope = 'https://mail.example/ extra'", tu, id.value())).isEqualTo(1);
        assertThat(refreshToken(t, id, 2)).isEqualTo("refresh-new");
        assertThat(count("SELECT count(*) FROM credential WHERE tenant_id = ? AND account_id = ?", tu, id.value()))
                .isEqualTo(1); // the old version is gone
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'relink'"
                + " AND result = 'ok' AND account_id = ? AND request_id = 'req-2'", tu, id.value())).isEqualTo(1);
    }

    @Test
    void relinkOfAnotherTenantsAccountOrAPasswordAccountChangesNothing() throws Exception {
        TenantId a = AccountSql.newTenant(db.dataSource());
        TenantId b = AccountSql.newTenant(db.dataSource());
        ResolvedAccount mine = store.create(a, account("me@example.org", "google"), "refresh-old".toCharArray(), 10,
                "req-1");
        ResolvedAccount pw = store.create(a, new LinkRequest("Pw", "imap.example.org", 993,
                MailAccount.Security.IMPLICIT_TLS, "me@example.org", true, false), "pw".toCharArray(), 10, "req-pw");

        assertThat(store.relink(b, mine.account().id(), "x".toCharArray(), "s", "req-2")).isFalse();
        assertThat(store.relink(a, pw.account().id(), "x".toCharArray(), "s", "req-3")).isFalse();

        assertThat(refreshToken(a, mine.account().id(), 1)).isEqualTo("refresh-old");
        assertThat(count("SELECT count(*) FROM credential WHERE tenant_id = ?", UUID.fromString(a.value())))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE request_id IN ('req-2', 'req-3')")).isZero();
    }

    @Test
    void aKeyServiceFailureDuringRelinkKeepsTheOldVersionAndFlags() throws Exception {
        TenantId t = AccountSql.newTenant(db.dataSource());
        ResolvedAccount r = store.create(t, account("me@example.org", "google"), "refresh-old".toCharArray(), 10,
                "req-1");
        UUID tu = UUID.fromString(t.value());
        exec("UPDATE mailbox_account SET status = 'needs_relink' WHERE tenant_id = ? AND id = ?", tu,
                r.account().id().value());
        KeyWrapper down = keyServiceDown();

        assertThatThrownBy(() -> store(down).relink(t, r.account().id(), "x".toCharArray(), "s", "req-2"))
                .isInstanceOf(LinkedAccountStore.UnavailableException.class);

        assertThat(refreshToken(t, r.account().id(), 1)).isEqualTo("refresh-old");
        assertThat(count("SELECT count(*) FROM mailbox_account WHERE tenant_id = ? AND status = 'needs_relink'", tu))
                .isEqualTo(1);
    }
}
