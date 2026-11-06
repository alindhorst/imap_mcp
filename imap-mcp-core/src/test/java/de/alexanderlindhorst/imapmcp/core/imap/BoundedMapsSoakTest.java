package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Soak of the per-tenant and per-account maps of this package with many more synthetic tenants than their caps
 * (SPEC 5.5, 5.8 "bounded maps"): they stay at or below the cap while the load runs and are empty again after the
 * idle expiry.
 */
class BoundedMapsSoakTest {

    private static final int TENANTS = 20_000;

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-06T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static MailAccount account(int i) {
        return new MailAccount(new AccountId("acc" + i), "l", null, "imap.example.org", 993, Security.IMPLICIT_TLS,
                Flavor.GENERIC, "u", new SecretRef("env:X"));
    }

    private static PoolKey key(int i) {
        TenantId t = new TenantId("tenant-" + i);
        return PoolKey.of(new AccountContext(t), new ResolvedAccount(t, account(i), "acc_" + i));
    }

    @Test
    void sessionsStayBelowTheCapAndExpireWhenIdle() {
        MutableClock clock = new MutableClock();
        try (ImapSessionFactory f = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> p, false, clock,
                ImapSessionFactory.MAX_SESSIONS)) {
            int worst = 0;
            for (int i = 0; i < TENANTS; i++) {
                f.sessionFor(key(i), account(i));
                worst = Math.max(worst, f.sessionCount());
            }
            assertThat(worst).isLessThanOrEqualTo(ImapSessionFactory.MAX_SESSIONS);
            assertThat(f.sessionCount()).isEqualTo(ImapSessionFactory.MAX_SESSIONS);
            clock.now = clock.now.plus(ImapSessionFactory.SESSION_IDLE_EXPIRY).plusSeconds(1);
            f.sessionFor(key(TENANTS), account(TENANTS)); // any call drops the expired entries
            assertThat(f.sessionCount()).isEqualTo(1);
        }
    }

    @Test
    void capabilitiesStayBelowTheCapAndExpireWhenIdle() {
        MutableClock clock = new MutableClock();
        CapabilityCache cache = new CapabilityCache(Duration.ofMinutes(10), clock);
        ImapCapabilities caps = ImapCapabilities.fromNames(List.of("IMAP4rev1"));
        int worst = 0;
        for (int i = 0; i < TENANTS; i++) {
            cache.put(key(i), caps);
            worst = Math.max(worst, cache.size());
        }
        assertThat(worst).isLessThanOrEqualTo(CapabilityCache.MAX_ENTRIES);
        assertThat(cache.size()).isEqualTo(CapabilityCache.MAX_ENTRIES);
        clock.now = clock.now.plus(Duration.ofDays(2));
        cache.put(key(TENANTS), caps);
        assertThat(cache.size()).isEqualTo(1);
    }
}
