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

        TenantId created = dir.create("https://idp.example", "alice");
        TenantInfo found = dir.find("https://idp.example", "alice").orElseThrow();

        assertThat(UUID.fromString(created.value())).isNotNull();
        assertThat(found.tenantId()).isEqualTo(created);
        assertThat(found.disabled()).isFalse();
        assertThat(dir.create("https://idp.example", "alice")).isEqualTo(created);
    }

    @Test
    void sameSubjectUnderAnotherIssuerIsAnotherTenant() {
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), new TestClock(), 100);

        TenantId a = dir.create("https://idp-a.example", "bob");

        assertThat(dir.find("https://idp-b.example", "bob")).isEmpty();
        assertThat(dir.create("https://idp-b.example", "bob")).isNotEqualTo(a);
    }

    @Test
    void aHitIsCachedForFiveMinutesAndReloadedAfterwards() throws Exception {
        TestClock clock = new TestClock();
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), clock, 100);
        TenantId t = dir.create("https://idp.example", "carol");
        assertThat(dir.find("https://idp.example", "carol")).isPresent();

        exec("UPDATE tenant SET status = 'disabled' WHERE id = ?", UUID.fromString(t.value()));
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(dir.find("https://idp.example", "carol").orElseThrow().disabled()).isFalse(); // still cached

        clock.advance(Duration.ofSeconds(2));
        assertThat(dir.find("https://idp.example", "carol").orElseThrow().disabled()).isTrue(); // reloaded
    }

    @Test
    void invalidateDropsTheEntryAtOnce() throws Exception {
        JdbcTenantDirectory dir = new JdbcTenantDirectory(db.dataSource(), new TestClock(), 100);
        TenantId t = dir.create("https://idp.example", "dave");
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
}
