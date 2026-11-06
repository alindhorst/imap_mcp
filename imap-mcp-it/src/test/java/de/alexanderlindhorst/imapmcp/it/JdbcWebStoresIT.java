package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.web.OidcPendingStore;
import de.alexanderlindhorst.imapmcp.core.web.WebSession;
import de.alexanderlindhorst.imapmcp.core.web.WebSessionStore;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.ExpiredRowCleanup;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcOidcPendingStore;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcWebSessionStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Web sessions and pending OIDC logins on Postgres (C1, SPEC 5.7). */
class JdbcWebStoresIT {

    private static PostgresFixture postgres;
    private static Database db;

    /** Clock that only moves when told to. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-06T10:00:00Z");

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

    private static void exec(String sql, Object... params) throws Exception {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    private static int count(String table) throws Exception {
        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ---- web sessions ----

    @Test
    void aCreatedSessionIsFoundByItsIdWithTenantCsrfTokenAndAuthTime() throws Exception {
        TestClock clock = new TestClock();
        JdbcWebSessionStore sessions = new JdbcWebSessionStore(db.dataSource(), clock);
        TenantId t = newTenant();
        Instant authTime = clock.instant().minusSeconds(20);

        WebSessionStore.Created created = sessions.create(t, authTime);
        WebSession found = sessions.find(created.sessionId()).orElseThrow();

        assertThat(created.sessionId()).matches("[A-Za-z0-9_-]{43}");
        assertThat(created.csrfToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(found.tenantId()).isEqualTo(t);
        assertThat(found.csrfToken()).isEqualTo(created.csrfToken());
        assertThat(found.authTime()).isEqualTo(authTime);
    }

    @Test
    void onlyTheHashOfTheSessionIdIsStored() throws Exception {
        JdbcWebSessionStore sessions = new JdbcWebSessionStore(db.dataSource(), new TestClock());
        WebSessionStore.Created created = sessions.create(newTenant(), Instant.now());

        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT id_hash FROM web_session")) {
            while (rs.next()) {
                byte[] hash = rs.getBytes(1);
                assertThat(hash).hasSize(32);
                assertThat(new String(hash, java.nio.charset.StandardCharsets.ISO_8859_1))
                        .doesNotContain(created.sessionId());
            }
        }
    }

    @Test
    void unknownAndMalformedSessionIdsFindNothing() throws Exception {
        JdbcWebSessionStore sessions = new JdbcWebSessionStore(db.dataSource(), new TestClock());
        sessions.create(newTenant(), Instant.now());

        for (String bad : new String[] {null, "", "x", "A".repeat(43), "A".repeat(44), "../etc", "\u0000".repeat(43),
                "A".repeat(5000)}) {
            assertThat(sessions.find(bad)).as("id %s", bad == null ? null : bad.length()).isEmpty();
        }
    }

    @Test
    void theSessionEndsThirtyMinutesAfterTheLastUse() throws Exception {
        TestClock clock = new TestClock();
        JdbcWebSessionStore sessions = new JdbcWebSessionStore(db.dataSource(), clock);
        String id = sessions.create(newTenant(), clock.instant()).sessionId();

        clock.advance(Duration.ofMinutes(29));
        assertThat(sessions.find(id)).isPresent(); // use at 29 min moves the deadline to 59 min
        clock.advance(Duration.ofMinutes(29));
        assertThat(sessions.find(id)).isPresent();
        clock.advance(Duration.ofMinutes(31));
        assertThat(sessions.find(id)).isEmpty();
    }

    @Test
    void theSessionEndsEightHoursAfterCreationEvenWhenUsedContinuously() throws Exception {
        TestClock clock = new TestClock();
        JdbcWebSessionStore sessions = new JdbcWebSessionStore(db.dataSource(), clock);
        String id = sessions.create(newTenant(), clock.instant()).sessionId();

        for (int i = 0; i < 15; i++) { // 15 x 29 min = 7 h 15 min
            clock.advance(Duration.ofMinutes(29));
            assertThat(sessions.find(id)).as("step %d", i).isPresent();
        }
        clock.advance(Duration.ofMinutes(29)); // 7 h 44 min
        assertThat(sessions.find(id)).isPresent();
        clock.advance(Duration.ofMinutes(17)); // 8 h 01 min
        assertThat(sessions.find(id)).isEmpty();
    }

    @Test
    void logoutDeletesTheSession() throws Exception {
        JdbcWebSessionStore sessions = new JdbcWebSessionStore(db.dataSource(), new TestClock());
        String id = sessions.create(newTenant(), Instant.now()).sessionId();

        sessions.delete(id);

        assertThat(sessions.find(id)).isEmpty();
        sessions.delete(id); // idempotent
        sessions.delete("garbage");
    }

    @Test
    void aSessionOfADisabledTenantIsNotValid() throws Exception {
        JdbcWebSessionStore sessions = new JdbcWebSessionStore(db.dataSource(), new TestClock());
        TenantId t = newTenant();
        String id = sessions.create(t, Instant.now()).sessionId();
        exec("UPDATE tenant SET status = 'disabled' WHERE id = ?", UUID.fromString(t.value()));

        assertThat(sessions.find(id)).isEmpty();
    }

    @Test
    void eachSessionBelongsToItsOwnTenantAndSessionIdsAreUnique() throws Exception {
        JdbcWebSessionStore sessions = new JdbcWebSessionStore(db.dataSource(), new TestClock());
        TenantId a = newTenant();
        TenantId b = newTenant();
        WebSessionStore.Created ca = sessions.create(a, Instant.now());
        WebSessionStore.Created cb = sessions.create(b, Instant.now());

        assertThat(ca.sessionId()).isNotEqualTo(cb.sessionId());
        assertThat(sessions.find(ca.sessionId()).orElseThrow().tenantId()).isEqualTo(a);
        assertThat(sessions.find(cb.sessionId()).orElseThrow().tenantId()).isEqualTo(b);
        assertThat(ca.csrfToken()).isNotEqualTo(cb.csrfToken());
    }

    @Test
    void theCleanupRemovesExpiredSessionsAndPendingLogins() throws Exception {
        TestClock clock = new TestClock();
        JdbcWebSessionStore sessions = new JdbcWebSessionStore(db.dataSource(), clock);
        JdbcOidcPendingStore pending = new JdbcOidcPendingStore(db.dataSource(), clock);
        String id = sessions.create(newTenant(), clock.instant()).sessionId();
        pending.begin("n", "v");
        clock.advance(Duration.ofHours(1));
        String alive = sessions.create(newTenant(), clock.instant()).sessionId();

        ExpiredRowCleanup.Result r = new ExpiredRowCleanup(db.dataSource()).deleteExpired(clock.instant());

        assertThat(r.webSessions()).isGreaterThanOrEqualTo(1);
        assertThat(r.oidcPending()).isGreaterThanOrEqualTo(1);
        assertThat(sessions.find(id)).isEmpty();
        assertThat(sessions.find(alive)).isPresent();
    }

    // ---- pending logins ----

    @Test
    void aPendingLoginIsConsumedExactlyOnce() throws Exception {
        JdbcOidcPendingStore pending = new JdbcOidcPendingStore(db.dataSource(), new TestClock());

        String state = pending.begin("the-nonce", "the-verifier");
        OidcPendingStore.Pending first = pending.consume(state).orElseThrow();

        assertThat(state).matches("[A-Za-z0-9_-]{43}");
        assertThat(first.nonce()).isEqualTo("the-nonce");
        assertThat(first.pkceVerifier()).isEqualTo("the-verifier");
        assertThat(pending.consume(state)).isEmpty(); // replay
    }

    @Test
    void concurrentConsumeOfTheSameStateSucceedsOnce() throws Exception {
        JdbcOidcPendingStore pending = new JdbcOidcPendingStore(db.dataSource(), new TestClock());
        String state = pending.begin("n", "v");
        java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    if (pending.consume(state).isPresent()) {
                        wins.incrementAndGet();
                    }
                }));
            }
            for (var f : futures) {
                f.get();
            }
        }

        assertThat(wins).hasValue(1);
    }

    @Test
    void anExpiredPendingLoginCannotBeConsumed() throws Exception {
        TestClock clock = new TestClock();
        JdbcOidcPendingStore pending = new JdbcOidcPendingStore(db.dataSource(), clock);
        String state = pending.begin("n", "v");

        clock.advance(OidcPendingStore.TTL.plusSeconds(1));

        assertThat(pending.consume(state)).isEmpty();
    }

    @Test
    void unknownAndMalformedStatesConsumeNothing() throws Exception {
        JdbcOidcPendingStore pending = new JdbcOidcPendingStore(db.dataSource(), new TestClock());
        pending.begin("n", "v");

        for (String bad : new String[] {null, "", "x", "A".repeat(43), "A".repeat(5000), "\u0000".repeat(43)}) {
            assertThat(pending.consume(bad)).isEmpty();
        }
    }

    @Test
    void expiredPendingLoginsDoNotCountTowardsTheCap() throws Exception {
        exec("TRUNCATE oidc_pending");
        TestClock clock = new TestClock();
        JdbcOidcPendingStore pending = new JdbcOidcPendingStore(db.dataSource(), clock, 3);
        for (int i = 0; i < 3; i++) {
            pending.begin("n" + i, "v");
        }
        assertThatThrownBy(() -> pending.begin("n", "v")).isInstanceOf(OidcPendingStore.LimitReachedException.class);

        clock.advance(OidcPendingStore.TTL.plusSeconds(1));

        assertThat(pending.begin("fresh", "v")).isNotBlank();
    }

    @Test
    void onlyTheHashOfTheStateIsStored() throws Exception {
        JdbcOidcPendingStore pending = new JdbcOidcPendingStore(db.dataSource(), new TestClock());
        String state = pending.begin("n", "v");

        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT state_hash FROM oidc_pending")) {
            while (rs.next()) {
                assertThat(rs.getBytes(1)).hasSize(32);
                assertThat(new String(rs.getBytes(1), java.nio.charset.StandardCharsets.ISO_8859_1))
                        .doesNotContain(state);
            }
        }
    }

    @Test
    void theNumberOfPendingLoginsIsCapped() throws Exception {
        exec("TRUNCATE oidc_pending");
        JdbcOidcPendingStore pending = new JdbcOidcPendingStore(db.dataSource(), new TestClock(), 5);
        for (int i = 0; i < 5; i++) {
            pending.begin("n" + i, "v");
        }

        assertThatThrownBy(() -> pending.begin("n", "v")).isInstanceOf(OidcPendingStore.LimitReachedException.class);
        assertThat(count("oidc_pending")).isEqualTo(5);
    }

    @Test
    void theTenantProvisionerFindsCreatesAndReportsDisabledTenants() throws Exception {
        var provisioner = new de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantProvisioner(
                new de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantDirectory(db.dataSource()));

        assertThat(provisioner.find("https://idp.example", "prov-1")).isEmpty();
        var created = provisioner.create("https://idp.example", "prov-1");
        assertThat(created.disabled()).isFalse();
        assertThat(provisioner.find("https://idp.example", "prov-1").orElseThrow().tenantId())
                .isEqualTo(created.tenantId());
        assertThat(provisioner.create("https://idp.example", "prov-1").tenantId()).isEqualTo(created.tenantId());
    }
}
