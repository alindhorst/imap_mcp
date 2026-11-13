package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamPendingStore;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.ExpiredRowCleanup;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcUpstreamPendingStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code upstream_pending} on Postgres: single use, expiry, the caps, the tenant binding and the stored form (X1-2). */
class JdbcUpstreamPendingStoreIT {

    private static PostgresFixture postgres;
    private static Database db;

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-13T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

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
    void emptyTable() throws Exception {
        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement()) {
            st.execute("TRUNCATE upstream_pending"); // the table cap counts every tenant's rows
        }
    }

    private static UpstreamPendingStore.Pending draft(TenantId t, AccountId relink) {
        return new UpstreamPendingStore.Pending(t, "google", "verifier-canary-0123456789012345678901234567",
                "Work", "me@example.org", false, relink);
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

    @Test
    void aPendingFlowIsConsumedExactlyOnceWithAllItsFields() throws Exception {
        TestClock clock = new TestClock();
        JdbcUpstreamPendingStore store = new JdbcUpstreamPendingStore(db.dataSource(), clock);
        TenantId t = AccountSql.newTenant(db.dataSource());

        String state = store.begin(draft(t, null));

        assertThat(state).matches("[A-Za-z0-9_-]{43}");
        UpstreamPendingStore.Pending p = store.consume(state).orElseThrow();
        assertThat(p.tenant()).isEqualTo(t);
        assertThat(p.providerId()).isEqualTo("google");
        assertThat(p.pkceVerifier()).startsWith("verifier-canary");
        assertThat(p.label()).isEqualTo("Work");
        assertThat(p.address()).isEqualTo("me@example.org");
        assertThat(p.readOnly()).isFalse();
        assertThat(p.relinkAccount()).isNull();
        assertThat(store.consume(state)).isEmpty(); // replay
    }

    @Test
    void aRelinkFlowCarriesTheAccountOfItsTenantOnly() throws Exception {
        TestClock clock = new TestClock();
        JdbcUpstreamPendingStore store = new JdbcUpstreamPendingStore(db.dataSource(), clock);
        TenantId a = AccountSql.newTenant(db.dataSource());
        TenantId b = AccountSql.newTenant(db.dataSource());
        MailAccount m = new MailAccount(new AccountId("acctrelinkpending0000000001"), "x", "me@example.org",
                "imap.example.org", 993, MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GMAIL,
                "me@example.org", new SecretRef("db:"), true, false, MailAccount.Auth.XOAUTH2,
                MailAccount.Status.ACTIVE, 0L, "google");
        AccountSql.insertAccount(db.dataSource(), a, m, "acc_pendingrelink000000000001");

        String state = store.begin(draft(a, m.id()));
        assertThat(store.consume(state).orElseThrow().relinkAccount()).isEqualTo(m.id());

        // another tenant cannot point a pending flow at this account (foreign key on tenant and account)
        assertThatThrownBy(() -> store.begin(draft(b, m.id()))).isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(UpstreamPendingStore.TenantLimitReachedException.class);
    }

    @Test
    void aPendingFlowExpiresAfterTenMinutesAndExpiredFlowsDoNotCount() throws Exception {
        TestClock clock = new TestClock();
        JdbcUpstreamPendingStore store = new JdbcUpstreamPendingStore(db.dataSource(), clock, 2);
        TenantId t = AccountSql.newTenant(db.dataSource());
        String state = store.begin(draft(t, null));
        store.begin(draft(t, null));
        clock.advance(Duration.ofMinutes(9));
        assertThatThrownBy(() -> store.begin(draft(AccountSql.newTenant(db.dataSource()), null)))
                .isInstanceOf(UpstreamPendingStore.LimitReachedException.class); // table cap of 2 reached

        clock.advance(Duration.ofMinutes(2));

        assertThat(store.consume(state)).isEmpty();
        assertThat(store.begin(draft(t, null))).isNotNull(); // the expired ones no longer count
    }

    @Test
    void atMostThreeUnexpiredFlowsPerTenantAndTheLimitIsPerTenant() throws Exception {
        TestClock clock = new TestClock();
        JdbcUpstreamPendingStore store = new JdbcUpstreamPendingStore(db.dataSource(), clock);
        TenantId a = AccountSql.newTenant(db.dataSource());
        TenantId b = AccountSql.newTenant(db.dataSource());
        for (int i = 0; i < 3; i++) {
            store.begin(draft(a, null));
        }

        assertThatThrownBy(() -> store.begin(draft(a, null)))
                .isInstanceOf(UpstreamPendingStore.TenantLimitReachedException.class);
        assertThat(store.begin(draft(b, null))).isNotNull();
        assertThat(count("SELECT count(*) FROM upstream_pending WHERE tenant_id = ?", UUID.fromString(a.value())))
                .isEqualTo(3);
    }

    @Test
    void concurrentStartsOfOneTenantNeverExceedThree() throws Exception {
        TestClock clock = new TestClock();
        JdbcUpstreamPendingStore store = new JdbcUpstreamPendingStore(db.dataSource(), clock);
        TenantId t = AccountSql.newTenant(db.dataSource());
        var pool = Executors.newFixedThreadPool(8);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Callable<Object> c = () -> {
                try {
                    store.begin(draft(t, null));
                    ok.incrementAndGet();
                } catch (UpstreamPendingStore.TenantLimitReachedException e) {
                    // expected for the later ones
                }
                return null;
            };
            fs.add(pool.submit(c));
        }
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(3);
    }

    @Test
    void concurrentConsumeOfOneStateSucceedsOnce() throws Exception {
        TestClock clock = new TestClock();
        JdbcUpstreamPendingStore store = new JdbcUpstreamPendingStore(db.dataSource(), clock);
        String state = store.begin(draft(AccountSql.newTenant(db.dataSource()), null));
        var pool = Executors.newFixedThreadPool(6);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> store.consume(state).isPresent()));
        }
        int hits = 0;
        for (Future<Boolean> f : fs) {
            hits += f.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(hits).isEqualTo(1);
    }

    @Test
    void unknownAndMalformedStatesConsumeNothing() {
        JdbcUpstreamPendingStore store = new JdbcUpstreamPendingStore(db.dataSource(), new TestClock());

        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("")).isEmpty();
        assertThat(store.consume("short")).isEmpty();
        assertThat(store.consume("x".repeat(43))).isEmpty();
        assertThat(store.consume("'; DROP TABLE upstream_pending; --")).isEmpty();
    }

    @Test
    void onlyTheHashOfTheStateIsStored() throws Exception {
        TestClock clock = new TestClock();
        JdbcUpstreamPendingStore store = new JdbcUpstreamPendingStore(db.dataSource(), clock);
        TenantId t = AccountSql.newTenant(db.dataSource());
        String state = store.begin(draft(t, null));

        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT state_hash, expires_at - created_at FROM upstream_pending WHERE tenant_id = ?")) {
            ps.setObject(1, UUID.fromString(t.value()));
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBytes(1)).hasSize(32).isNotEqualTo(state.getBytes());
                assertThat(rs.getString(2)).isEqualTo("00:10:00");
            }
        }
    }

    @Test
    void theCleanupRemovesExpiredUpstreamFlows() throws Exception {
        TestClock clock = new TestClock();
        JdbcUpstreamPendingStore store = new JdbcUpstreamPendingStore(db.dataSource(), clock);
        TenantId t = AccountSql.newTenant(db.dataSource());
        store.begin(draft(t, null));
        clock.advance(Duration.ofMinutes(11));
        store.begin(draft(t, null));

        ExpiredRowCleanup.Result r = new ExpiredRowCleanup(db.dataSource()).deleteExpired(clock.instant());

        assertThat(r.upstreamPending()).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM upstream_pending WHERE tenant_id = ?", UUID.fromString(t.value())))
                .isEqualTo(1);
    }
}
