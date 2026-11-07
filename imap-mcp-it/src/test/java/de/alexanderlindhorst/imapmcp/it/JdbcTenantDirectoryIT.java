package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantDirectory;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantDirectory.TenantInfo;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tenant lookup by (iss, subject), cached for 5 minutes and bounded (A8, SPEC 5.3). */
class JdbcTenantDirectoryIT {

    private static PostgresFixture postgres;
    private static Database db;

    /** Test clock that only moves when told to. */
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

    private static void exec(String sql, Object... params) throws Exception {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ps.executeUpdate();
        }
    }

    @Test
    void unknownPrincipalHasNoTenantAndNothingIsCreatedByLookup() {
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), new TestClock(), 100);

        assertThat(dir.find("https://idp.example", "nobody")).isEmpty();
        assertThat(dir.find("https://idp.example", "nobody")).isEmpty();
    }

    @Test
    void createThenFindReturnsTheSameTenantAndCreateIsIdempotent() {
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), new TestClock(), 100);

        TenantId created = dir.create("https://idp.example", "alice").tenantId();
        TenantInfo found = dir.find("https://idp.example", "alice").orElseThrow();

        assertThat(UUID.fromString(created.value())).isNotNull();
        assertThat(found.tenantId()).isEqualTo(created);
        assertThat(found.disabled()).isFalse();
        assertThat(dir.create("https://idp.example", "alice").tenantId()).isEqualTo(created);
    }

    @Test
    void sameSubjectUnderAnotherIssuerIsAnotherTenant() {
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), new TestClock(), 100);

        TenantId a = dir.create("https://idp-a.example", "bob").tenantId();

        assertThat(dir.find("https://idp-b.example", "bob")).isEmpty();
        assertThat(dir.create("https://idp-b.example", "bob").tenantId()).isNotEqualTo(a);
    }

    @Test
    void aHitIsCachedForThirtySecondsAndReloadedAfterwards() throws Exception {
        TestClock clock = new TestClock();
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), clock, 100);
        TenantId t = dir.create("https://idp.example", "carol").tenantId();
        assertThat(dir.find("https://idp.example", "carol")).isPresent();

        exec("UPDATE tenant SET status = 'disabled' WHERE id = ?", UUID.fromString(t.value()));
        clock.advance(Duration.ofSeconds(29));
        assertThat(dir.find("https://idp.example", "carol").orElseThrow().disabled()).isFalse(); // still cached

        clock.advance(Duration.ofSeconds(2));
        assertThat(dir.find("https://idp.example", "carol").orElseThrow().disabled()).isTrue(); // reloaded
    }

    @Test
    void invalidateDropsTheEntryAtOnce() throws Exception {
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), new TestClock(), 100);
        TenantId t = dir.create("https://idp.example", "dave").tenantId();
        dir.find("https://idp.example", "dave");
        exec("UPDATE tenant SET status = 'disabled' WHERE id = ?", UUID.fromString(t.value()));

        dir.invalidate("https://idp.example", "dave");

        assertThat(dir.find("https://idp.example", "dave").orElseThrow().disabled()).isTrue();
    }

    @Test
    void theCacheIsBounded() {
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), new TestClock(), 3);
        for (int i = 0; i < 10; i++) {
            dir.create("https://idp.example", "bounded-" + i);
            dir.find("https://idp.example", "bounded-" + i);
        }

        assertThat(dir.cachedEntries()).isLessThanOrEqualTo(3);
    }

    @Test
    void oversizedIssuerOrSubjectIsNotFoundAndNotCached() {
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), new TestClock(), 100);

        assertThat(dir.find("https://idp.example", "x".repeat(5000))).isEmpty();
        assertThat(dir.find("y".repeat(5000), "s")).isEmpty();
        assertThat(dir.cachedEntries()).isZero();
    }

    @Test
    void missesAreNotCachedAndARowInsertedBySqlIsFoundAtOnce() throws Exception {
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), new TestClock(), 100);
        assertThat(dir.find("https://idp.example", "late")).isEmpty();
        assertThat(dir.cachedEntries()).isZero();

        UUID id = UUID.randomUUID();
        exec("INSERT INTO tenant (id, issuer, subject) VALUES (?, 'https://idp.example', 'late')", id);

        assertThat(dir.find("https://idp.example", "late").orElseThrow().tenantId().value()).isEqualTo(id.toString());
    }

    @Test
    void concurrentCreateForTheSamePrincipalGivesOneRowAndOneId() throws Exception {
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), new TestClock(), 100);
        java.util.Set<TenantId> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> ids.add(dir.create("https://idp.example", "racer").tenantId())));
            }
            for (var f : futures) {
                f.get();
            }
        }

        assertThat(ids).hasSize(1);
        try (Connection c = db.dataSource().getConnection();
                var st = c.createStatement();
                var rs = st.executeQuery("SELECT count(*) FROM tenant WHERE issuer = 'https://idp.example'"
                        + " AND subject = 'racer'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void aLoadThatStartedBeforeInvalidateDoesNotCacheTheStaleRow() throws Exception {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO tenant (id, issuer, subject) VALUES (?, 'https://idp.example', 'stale-race')", id);
        SlowReads slow = new SlowReads(db.dataSource());
        JdbcTenantDirectory dir = new JdbcTenantDirectory(slow.dataSource(), new TestClock(), 100);
        slow.arm();

        var loader = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> dir.find("https://idp.example", "stale-race").orElseThrow());
        slow.awaitReadDone(); // the loader has read the active row and is held before it can cache it
        exec("UPDATE tenant SET status = 'disabled' WHERE id = ?", id);
        dir.invalidate("https://idp.example", "stale-race");
        slow.release();
        assertThat(loader.get(5, java.util.concurrent.TimeUnit.SECONDS).disabled()).isFalse(); // its own stale read

        assertThat(dir.find("https://idp.example", "stale-race").orElseThrow().disabled()).isTrue();
    }

    /** DataSource whose next tenant SELECT returns its rows and then waits, to interleave an invalidate. */
    private static final class SlowReads {
        private final javax.sql.DataSource delegate;
        private volatile boolean armed;
        private final java.util.concurrent.CountDownLatch readDone = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        SlowReads(javax.sql.DataSource delegate) {
            this.delegate = delegate;
        }

        void arm() {
            armed = true;
        }

        void awaitReadDone() throws InterruptedException {
            assertThat(readDone.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        void release() {
            release.countDown();
        }

        javax.sql.DataSource dataSource() {
            return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {javax.sql.DataSource.class}, (proxy, method, args) -> {
                        Object r = invoke(delegate, method, args);
                        if (method.getName().equals("getConnection")) {
                            return wrap(Connection.class, (Connection) r);
                        }
                        return r;
                    });
        }

        private <T> T wrap(Class<T> type, T target) {
            return type.cast(java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {type}, (proxy, method, args) -> {
                        Object r = invoke(target, method, args);
                        if (method.getName().equals("prepareStatement")) {
                            boolean tenantSelect = armed && String.valueOf(args[0]).startsWith("SELECT id, status FROM tenant");
                            return wrapStatement((PreparedStatement) r, tenantSelect);
                        }
                        return r;
                    }));
        }

        private PreparedStatement wrapStatement(PreparedStatement target, boolean hold) {
            return (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                        Object r = invoke(target, method, args);
                        if (hold && method.getName().equals("executeQuery")) {
                            armed = false;
                            // materialise nothing: the cursor was read by the caller after we return, so read first
                            java.sql.ResultSet rs = (java.sql.ResultSet) r;
                            java.sql.ResultSet snapshot = snapshot(rs);
                            readDone.countDown();
                            release.await();
                            return snapshot;
                        }
                        return r;
                    });
        }

        private static java.sql.ResultSet snapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
            javax.sql.rowset.CachedRowSet cached = javax.sql.rowset.RowSetProvider.newFactory().createCachedRowSet();
            cached.populate(rs);
            return cached;
        }

        private static Object invoke(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
            try {
                return m.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
