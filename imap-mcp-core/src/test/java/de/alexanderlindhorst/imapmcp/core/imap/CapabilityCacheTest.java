package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CapabilityCacheTest {

    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    private static final ImapCapabilities CAPS = ImapCapabilities.fromNames(Set.of("MOVE"));

    /** Mutable test clock. */
    private static final class FakeClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneOffset getZone() {
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

    private static PoolKey key(String tenant, String account) {
        TenantId t = new TenantId(tenant);
        MailAccount a = new MailAccount(new AccountId(account), "Label", null, "imap.example.org", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "user", new SecretRef("env:x"));
        return PoolKey.of(new AccountContext(t), new ResolvedAccount(t, a));
    }

    @Test
    void defaultTtlIsTenMinutes() {
        assertThat(CapabilityCache.DEFAULT_TTL).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void missingBeforePut() {
        CapabilityCache cache = new CapabilityCache(new FakeClock());
        assertThat(cache.get(key("t1", "a"))).isInstanceOf(CapabilityCache.Lookup.Missing.class);
    }

    @Test
    void freshJustBeforeDefaultTtlAndExpiredJustAfter() {
        FakeClock clock = new FakeClock();
        CapabilityCache cache = new CapabilityCache(clock);
        PoolKey key = key("t1", "a");
        cache.put(key, CAPS);

        clock.now = T0.plusSeconds(9 * 60 + 59);
        assertThat(cache.get(key)).isInstanceOfSatisfying(CapabilityCache.Lookup.Fresh.class, f -> {
            assertThat(f.capabilities()).isEqualTo(CAPS);
            assertThat(f.detectedAt()).isEqualTo(T0);
        });

        clock.now = T0.plusSeconds(10 * 60 + 1);
        assertThat(cache.get(key)).isInstanceOfSatisfying(CapabilityCache.Lookup.Expired.class, e -> {
            assertThat(e.capabilities()).isEqualTo(CAPS);
            assertThat(e.detectedAt()).isEqualTo(T0);
        });
    }

    @Test
    void ttlOverrideIsRespected() {
        FakeClock clock = new FakeClock();
        CapabilityCache cache = new CapabilityCache(Duration.ofSeconds(30), clock);
        PoolKey key = key("t1", "a");
        cache.put(key, CAPS);
        clock.now = T0.plusSeconds(29);
        assertThat(cache.get(key)).isInstanceOf(CapabilityCache.Lookup.Fresh.class);
        clock.now = T0.plusSeconds(31);
        assertThat(cache.get(key)).isInstanceOf(CapabilityCache.Lookup.Expired.class);
    }

    @Test
    void putAgainRestampsEntry() {
        FakeClock clock = new FakeClock();
        CapabilityCache cache = new CapabilityCache(clock);
        PoolKey key = key("t1", "a");
        cache.put(key, CAPS);
        clock.now = T0.plusSeconds(700);
        cache.put(key, CAPS);
        assertThat(cache.get(key)).isInstanceOfSatisfying(CapabilityCache.Lookup.Fresh.class,
                f -> assertThat(f.detectedAt()).isEqualTo(T0.plusSeconds(700)));
    }

    @Test
    void sameAccountIdUnderTwoTenantsIsSeparated() {
        CapabilityCache cache = new CapabilityCache(new FakeClock());
        cache.put(key("t1", "shared"), CAPS);
        assertThat(cache.get(key("t1", "shared"))).isInstanceOf(CapabilityCache.Lookup.Fresh.class);
        assertThat(cache.get(key("t2", "shared"))).isInstanceOf(CapabilityCache.Lookup.Missing.class);
    }

    @Test
    void invalidateRemovesEntry() {
        CapabilityCache cache = new CapabilityCache(new FakeClock());
        PoolKey key = key("t1", "a");
        cache.put(key, CAPS);
        cache.invalidate(key);
        assertThat(cache.get(key)).isInstanceOf(CapabilityCache.Lookup.Missing.class);
    }
}
