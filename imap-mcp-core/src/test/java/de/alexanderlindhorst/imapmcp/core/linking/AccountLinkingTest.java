package de.alexanderlindhorst.imapmcp.core.linking;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.audit.RecordingAuditLog;
import de.alexanderlindhorst.imapmcp.core.imap.LoginProbe;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccountLinkingTest {

    private static final TenantId T1 = new TenantId("00000000-0000-4000-8000-000000000001");
    private static final TenantId T2 = new TenantId("00000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final Instant FRESH = NOW.minusSeconds(60);
    private static final Instant STALE = NOW.minusSeconds(6 * 60);
    private static final char[] PASSWORD = "pw-canary-123".toCharArray();

    /** Clock the tests can move. */
    private static final class TestClock extends Clock {
        Instant now = NOW;

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

    private record ProbeCall(String host, int port, MailAccount.Security security, String username, String password) {
    }

    private final List<ProbeCall> probes = new ArrayList<>();
    private LoginProbe.Result probeResult = LoginProbe.Result.OK;
    private final LoginProbe probe = (account, password) -> {
        probes.add(new ProbeCall(account.host(), account.port(), account.security(), account.username(),
                new String(password)));
        return probeResult;
    };

    /** In-memory account store that also acts as the repository, keyed by tenant. */
    private static final class Fake implements AccountRepository, LinkedAccountStore {
        final List<ResolvedAccount> accounts = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        RuntimeException failWith;
        int counter;

        @Override
        public List<ResolvedAccount> findByTenant(TenantId tenant) {
            return accounts.stream().filter(a -> a.tenantId().equals(tenant)).toList();
        }

        @Override
        public Optional<ResolvedAccount> findByTenantAndHandle(TenantId tenant, String handle) {
            return findByTenant(tenant).stream().filter(a -> a.handle().equals(handle)).findFirst();
        }

        @Override
        public Optional<ResolvedAccount> findByTenantAndId(TenantId tenant, AccountId id) {
            return findByTenant(tenant).stream().filter(a -> a.account().id().equals(id)).findFirst();
        }

        @Override
        public ResolvedAccount create(TenantId tenant, LinkRequest request, char[] password, int maxAccounts,
                String requestId) {
            if (failWith != null) {
                throw failWith;
            }
            if (findByTenant(tenant).size() >= maxAccounts) {
                throw new LimitReachedException();
            }
            calls.add("create:" + request.host() + ":" + new String(password));
            MailAccount a = new MailAccount(new AccountId("acct-" + (++counter)), request.label(), null,
                    request.host(), request.port(), request.security(), MailAccount.Flavor.GENERIC,
                    request.username(), new SecretRef("db:"), request.readOnly(), request.allowPurge());
            ResolvedAccount r = new ResolvedAccount(tenant, a, "acc_handle" + counter);
            accounts.add(r);
            return r;
        }

        @Override
        public boolean relink(TenantId tenant, AccountId id, char[] password, String requestId) {
            if (failWith != null) {
                throw failWith;
            }
            calls.add("relink:" + id.value() + ":" + new String(password));
            return findByTenantAndId(tenant, id).isPresent();
        }

        @Override
        public boolean updateSettings(TenantId tenant, AccountId id, String label, boolean readOnly,
                boolean allowPurge, String requestId) {
            calls.add("settings:" + id.value() + ":" + label + ":" + readOnly + ":" + allowPurge);
            return findByTenantAndId(tenant, id).isPresent();
        }

        @Override
        public boolean unlink(TenantId tenant, AccountId id, String requestId) {
            calls.add("unlink:" + id.value());
            return accounts.removeIf(a -> a.tenantId().equals(tenant) && a.account().id().equals(id));
        }
    }

    private final Fake fake = new Fake();
    private final RecordingAuditLog audit = new RecordingAuditLog();
    private final TestClock clock = new TestClock();

    private AccountLinking linking(boolean purgeEnabled, int maxAccounts) {
        return new AccountLinking(fake, fake, probe, SsrfPolicy.of(List.of(), true, List.of()), audit,
                new ProbeRateLimiter(clock, 10, Duration.ofHours(1), 100), clock, maxAccounts, purgeEnabled);
    }

    private static LinkRequest request(String host, int port, MailAccount.Security sec, boolean readOnly,
            boolean allowPurge) {
        return new LinkRequest("Work", host, port, sec, "me@example.org", readOnly, allowPurge);
    }

    private static LinkRequest good() {
        return request("IMAP.Example.com", 993, MailAccount.Security.IMPLICIT_TLS, true, false);
    }

    // ---- link ----

    @Test
    void linkProbesWithTheNormalizedHostThenStores() {
        LinkOutcome o = linking(false, 10).link(T1, good(), PASSWORD, FRESH, "req-1");

        assertThat(o).isEqualTo(LinkOutcome.OK);
        assertThat(probes).singleElement().satisfies(p -> {
            assertThat(p.host()).isEqualTo("imap.example.com");
            assertThat(p.port()).isEqualTo(993);
            assertThat(p.security()).isEqualTo(MailAccount.Security.IMPLICIT_TLS);
            assertThat(p.username()).isEqualTo("me@example.org");
            assertThat(p.password()).isEqualTo("pw-canary-123");
        });
        assertThat(fake.calls).containsExactly("create:imap.example.com:pw-canary-123");
        assertThat(fake.accounts).singleElement().satisfies(a -> {
            assertThat(a.account().readOnly()).isTrue();
            assertThat(a.account().allowPurge()).isFalse();
        });
    }

    @Test
    void aFailedProbeStoresNothingAndWritesARefusalRow() {
        probeResult = LoginProbe.Result.AUTH_FAILED;
        assertThat(linking(false, 10).link(T1, good(), PASSWORD, FRESH, "req-1"))
                .isEqualTo(LinkOutcome.PROBE_AUTH_FAILED);
        probeResult = LoginProbe.Result.UNREACHABLE;
        assertThat(linking(false, 10).link(T1, good(), PASSWORD, FRESH, "req-2"))
                .isEqualTo(LinkOutcome.PROBE_UNREACHABLE);

        assertThat(fake.accounts).isEmpty();
        assertThat(fake.calls).isEmpty();
        assertThat(audit.events).extracting(e -> e.type() + ":" + e.result())
                .containsExactly("link:refused_probe_auth", "link:refused_probe_unreachable");
    }

    @Test
    void hostsThePolicyRefusesNeverReachTheProbeOrTheStore() {
        for (LinkRequest bad : List.of(
                request("127.0.0.1", 993, MailAccount.Security.IMPLICIT_TLS, true, false),
                request("[::1]", 993, MailAccount.Security.IMPLICIT_TLS, true, false),
                request("localhost", 993, MailAccount.Security.IMPLICIT_TLS, true, false),
                request("imap.example.com", 25, MailAccount.Security.IMPLICIT_TLS, true, false),
                request("imap.example.com", 143, MailAccount.Security.IMPLICIT_TLS, true, false),
                request("imap.example.com", 993, MailAccount.Security.STARTTLS, true, false),
                request("a b.example.com", 993, MailAccount.Security.IMPLICIT_TLS, true, false),
                request("0x7f.1", 993, MailAccount.Security.IMPLICIT_TLS, true, false))) {
            assertThat(linking(false, 10).link(T1, bad, PASSWORD, FRESH, "r")).as(bad.host())
                    .isEqualTo(LinkOutcome.HOST_REFUSED);
        }

        assertThat(probes).isEmpty();
        assertThat(fake.accounts).isEmpty();
        assertThat(audit.events).allSatisfy(e -> assertThat(e.result()).isEqualTo("refused_host"));
    }

    @Test
    void refusalRowsNeverCarryHostUsernameOrAddress() {
        linking(false, 10).link(T1, request("127.0.0.1", 993, MailAccount.Security.IMPLICIT_TLS, true, false),
                PASSWORD, FRESH, "r");
        probeResult = LoginProbe.Result.AUTH_FAILED;
        linking(false, 10).link(T1, good(), PASSWORD, FRESH, "r");

        assertThat(audit.events.toString()).doesNotContain("127.0.0.1").doesNotContain("example")
                .doesNotContain("me@").doesNotContain("pw-canary");
    }

    @Test
    void atMostTenProbesPerTenantAndHourAndTheLimitIsPerTenant() {
        AccountLinking l = linking(false, 100);
        probeResult = LoginProbe.Result.AUTH_FAILED; // probes count even when they fail

        for (int i = 0; i < 10; i++) {
            assertThat(l.link(T1, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.PROBE_AUTH_FAILED);
        }
        assertThat(l.link(T1, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.RATE_LIMITED);
        assertThat(probes).hasSize(10);
        assertThat(l.link(T2, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.PROBE_AUTH_FAILED);

        clock.now = clock.now.plus(Duration.ofMinutes(61));
        assertThat(l.link(T1, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.PROBE_AUTH_FAILED);
    }

    @Test
    void relinkAndLinkShareTheTenantsProbeBudget() {
        AccountLinking l = linking(false, 100);
        l.link(T1, good(), PASSWORD, FRESH, "r");
        String handle = fake.accounts.get(0).handle();
        for (int i = 0; i < 9; i++) {
            assertThat(l.relink(T1, handle, PASSWORD, "r")).isEqualTo(LinkOutcome.OK);
        }

        assertThat(l.relink(T1, handle, PASSWORD, "r")).isEqualTo(LinkOutcome.RATE_LIMITED);
        assertThat(l.link(T1, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.RATE_LIMITED);
    }

    @Test
    void theTenAccountCapStopsBeforeAnyProbe() {
        AccountLinking l = linking(false, 2);
        assertThat(l.link(T1, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.OK);
        assertThat(l.link(T1, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.OK);
        int probesBefore = probes.size();

        assertThat(l.link(T1, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.ACCOUNT_LIMIT);

        assertThat(probes).hasSize(probesBefore);
        assertThat(l.link(T2, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.OK);
    }

    @Test
    void aRaceOnTheCapReportedByTheStoreIsAccountLimit() {
        fake.failWith = new LinkedAccountStore.LimitReachedException();

        assertThat(linking(false, 10).link(T1, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.ACCOUNT_LIMIT);
    }

    @Test
    void allowPurgeNeedsTheDeploymentSwitchAndALoginFromTheLastFiveMinutes() {
        LinkRequest purge = request("imap.example.com", 993, MailAccount.Security.IMPLICIT_TLS, false, true);

        assertThat(linking(false, 10).link(T1, purge, PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.PURGE_DISABLED);
        assertThat(linking(true, 10).link(T1, purge, PASSWORD, STALE, "r")).isEqualTo(LinkOutcome.REAUTH_REQUIRED);
        assertThat(probes).isEmpty();
        assertThat(linking(true, 10).link(T1, purge, PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.OK);
        assertThat(fake.accounts.get(0).account().allowPurge()).isTrue();
    }

    @Test
    void allowPurgeTogetherWithReadOnlyIsInvalid() {
        LinkRequest both = request("imap.example.com", 993, MailAccount.Security.IMPLICIT_TLS, true, true);

        assertThat(linking(true, 10).link(T1, both, PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.INVALID_INPUT);
    }

    @Test
    void malformedInputIsInvalidWithoutAnyProbe() {
        AccountLinking l = linking(false, 10);
        MailAccount.Security tls = MailAccount.Security.IMPLICIT_TLS;
        List<LinkRequest> bad = List.of(
                new LinkRequest("", "imap.example.com", 993, tls, "u", true, false),
                new LinkRequest("  ", "imap.example.com", 993, tls, "u", true, false),
                new LinkRequest("x".repeat(81), "imap.example.com", 993, tls, "u", true, false),
                new LinkRequest("a\nb", "imap.example.com", 993, tls, "u", true, false),
                new LinkRequest("l", "imap.example.com", 993, tls, "", true, false),
                new LinkRequest("l", "imap.example.com", 993, tls, "u\r\nA1 LOGOUT", true, false),
                new LinkRequest("l", "imap.example.com", 993, tls, "u".repeat(256), true, false),
                new LinkRequest("l", "", 993, tls, "u", true, false),
                new LinkRequest("l", "imap.example.com", 0, tls, "u", true, false),
                new LinkRequest("l", "imap.example.com", 70000, tls, "u", true, false),
                new LinkRequest("l", "imap.example.com", 993, null, "u", true, false));

        for (LinkRequest r : bad) {
            assertThat(l.link(T1, r, PASSWORD, FRESH, "r")).as(r.label() + "/" + r.username())
                    .isEqualTo(LinkOutcome.INVALID_INPUT);
        }
        for (char[] pw : new char[][] {new char[0], "a\r\nb".toCharArray(), "a\0b".toCharArray(),
                "p".repeat(1025).toCharArray()}) {
            assertThat(l.link(T1, good(), pw, FRESH, "r")).isEqualTo(LinkOutcome.INVALID_INPUT);
        }
        assertThat(probes).isEmpty();
    }

    @Test
    void aKeyServiceOutageStoresNothingAndIsReportedGenerically() {
        fake.failWith = new LinkedAccountStore.UnavailableException();

        assertThat(linking(false, 10).link(T1, good(), PASSWORD, FRESH, "r")).isEqualTo(LinkOutcome.UNAVAILABLE);
        assertThat(fake.accounts).isEmpty();
    }

    @Test
    void aFailingRefusalRowDoesNotChangeTheOutcome() {
        audit.failEvent = true;
        probeResult = LoginProbe.Result.AUTH_FAILED;

        assertThat(linking(false, 10).link(T1, good(), PASSWORD, FRESH, "r"))
                .isEqualTo(LinkOutcome.PROBE_AUTH_FAILED);
    }

    @Test
    void thePasswordIsNeitherRetainedNorShownInTextualForms() {
        LinkRequest r = good();
        linking(false, 10).link(T1, r, PASSWORD, FRESH, "r");

        assertThat(r.toString()).doesNotContain("example").doesNotContain("me@");
        assertThat(String.valueOf(PASSWORD)).isEqualTo("pw-canary-123"); // the caller's array is not wiped here
        assertThat(audit.events.toString()).doesNotContain("pw-canary");
    }

    // ---- relink ----

    @Test
    void relinkProbesTheStoredEndpointWithTheNewPasswordThenStoresTheCredential() {
        AccountLinking l = linking(false, 10);
        l.link(T1, good(), PASSWORD, FRESH, "r");
        probes.clear();

        LinkOutcome o = l.relink(T1, fake.accounts.get(0).handle(), "new-pw".toCharArray(), "r2");

        assertThat(o).isEqualTo(LinkOutcome.OK);
        assertThat(probes).singleElement().satisfies(p -> {
            assertThat(p.host()).isEqualTo("imap.example.com");
            assertThat(p.password()).isEqualTo("new-pw");
        });
        assertThat(fake.calls).contains("relink:acct-1:new-pw");
    }

    @Test
    void aFailedRelinkProbeChangesNothing() {
        AccountLinking l = linking(false, 10);
        l.link(T1, good(), PASSWORD, FRESH, "r");
        fake.calls.clear();
        probeResult = LoginProbe.Result.AUTH_FAILED;

        assertThat(l.relink(T1, fake.accounts.get(0).handle(), "wrong".toCharArray(), "r2"))
                .isEqualTo(LinkOutcome.PROBE_AUTH_FAILED);

        assertThat(fake.calls).isEmpty();
    }

    @Test
    void relinkOfAnUnknownMalformedOrForeignHandleIsNotFoundWithoutProbe() {
        AccountLinking l = linking(false, 10);
        l.link(T1, good(), PASSWORD, FRESH, "r");
        String handle = fake.accounts.get(0).handle();
        probes.clear();

        assertThat(l.relink(T2, handle, PASSWORD, "r")).isEqualTo(LinkOutcome.NOT_FOUND);
        assertThat(l.relink(T1, "acc_nope", PASSWORD, "r")).isEqualTo(LinkOutcome.NOT_FOUND);
        assertThat(l.relink(T1, null, PASSWORD, "r")).isEqualTo(LinkOutcome.NOT_FOUND);
        assertThat(probes).isEmpty();
    }

    @Test
    void relinkRechecksTheStoredEndpointAgainstThePolicyBeforeBudgetAndProbe() {
        AccountLinking l = linking(false, 10);
        l.link(T1, good(), PASSWORD, FRESH, "r");
        String handle = fake.accounts.get(0).handle();
        probes.clear();
        // the operator has narrowed the allowlist since the account was linked
        AccountLinking narrowed = new AccountLinking(fake, fake, probe,
                SsrfPolicy.of(List.of("other.example.com"), false, List.of()), audit,
                new ProbeRateLimiter(clock, 10, Duration.ofHours(1), 100), clock, 10, false);
        audit.events.clear();

        assertThat(narrowed.relink(T1, handle, PASSWORD, "r2")).isEqualTo(LinkOutcome.HOST_REFUSED);

        assertThat(probes).isEmpty();
        assertThat(fake.calls).noneMatch(c -> c.startsWith("relink"));
        assertThat(audit.events).extracting(e -> e.type() + ":" + e.result()).containsExactly("relink:refused_host");
    }

    // ---- settings ----

    @Test
    void settingsChangeLabelAndFlagsOfTheTenantsOwnAccount() {
        AccountLinking l = linking(true, 10);
        l.link(T1, good(), PASSWORD, FRESH, "r");
        String handle = fake.accounts.get(0).handle();

        assertThat(l.updateSettings(T1, handle, new SettingsRequest("Renamed", false, false), FRESH, "r"))
                .isEqualTo(LinkOutcome.OK);

        assertThat(fake.calls).contains("settings:acct-1:Renamed:false:false");
    }

    @Test
    void turningOnAllowPurgeNeedsTheSwitchAndAFreshLoginButKeepingItDoesNot() {
        AccountLinking on = linking(true, 10);
        on.link(T1, request("imap.example.com", 993, MailAccount.Security.IMPLICIT_TLS, false, false), PASSWORD,
                FRESH, "r");
        String handle = fake.accounts.get(0).handle();
        SettingsRequest enable = new SettingsRequest("Work", false, true);

        assertThat(linking(false, 10).updateSettings(T1, handle, enable, FRESH, "r"))
                .isEqualTo(LinkOutcome.PURGE_DISABLED);
        assertThat(on.updateSettings(T1, handle, enable, STALE, "r")).isEqualTo(LinkOutcome.REAUTH_REQUIRED);
        assertThat(on.updateSettings(T1, handle, enable, FRESH, "r")).isEqualTo(LinkOutcome.OK);
        // the account now has allow_purge; saving other settings with a stale login does not need a new login
        Fake.class.cast(fake).accounts.set(0, withAllowPurge(fake.accounts.get(0)));
        assertThat(on.updateSettings(T1, handle, new SettingsRequest("Other", false, true), STALE, "r"))
                .isEqualTo(LinkOutcome.OK);
        // turning it off never needs a login
        assertThat(on.updateSettings(T1, handle, new SettingsRequest("Other", false, false), STALE, "r"))
                .isEqualTo(LinkOutcome.OK);
    }

    private static ResolvedAccount withAllowPurge(ResolvedAccount r) {
        MailAccount a = r.account();
        return new ResolvedAccount(r.tenantId(), new MailAccount(a.id(), a.label(), a.address(), a.host(), a.port(),
                a.security(), a.flavor(), a.username(), a.secretRef(), false, true), r.handle());
    }

    @Test
    void settingsRejectBadLabelsAndTheReadOnlyPurgeCombinationAndForeignHandles() {
        AccountLinking l = linking(true, 10);
        l.link(T1, good(), PASSWORD, FRESH, "r");
        String handle = fake.accounts.get(0).handle();
        fake.calls.clear();

        assertThat(l.updateSettings(T1, handle, new SettingsRequest("", true, false), FRESH, "r"))
                .isEqualTo(LinkOutcome.INVALID_INPUT);
        assertThat(l.updateSettings(T1, handle, new SettingsRequest("a\nb", true, false), FRESH, "r"))
                .isEqualTo(LinkOutcome.INVALID_INPUT);
        assertThat(l.updateSettings(T1, handle, new SettingsRequest("ok", true, true), FRESH, "r"))
                .isEqualTo(LinkOutcome.INVALID_INPUT);
        assertThat(l.updateSettings(T2, handle, new SettingsRequest("ok", true, false), FRESH, "r"))
                .isEqualTo(LinkOutcome.NOT_FOUND);
        assertThat(fake.calls).isEmpty();
    }

    // ---- unlink ----

    @Test
    void unlinkRemovesTheTenantsAccountAndRefusesForeignOrUnknownHandles() {
        AccountLinking l = linking(false, 10);
        l.link(T1, good(), PASSWORD, FRESH, "r");
        String handle = fake.accounts.get(0).handle();

        assertThat(l.unlink(T2, handle, "r")).isEqualTo(LinkOutcome.NOT_FOUND);
        assertThat(l.unlink(T1, "garbage", "r")).isEqualTo(LinkOutcome.NOT_FOUND);
        assertThat(fake.accounts).hasSize(1);
        assertThat(l.unlink(T1, handle, "r")).isEqualTo(LinkOutcome.OK);
        assertThat(fake.accounts).isEmpty();
        assertThat(l.unlink(T1, handle, "r")).isEqualTo(LinkOutcome.NOT_FOUND);
    }

    @Test
    void listIsScopedToTheTenant() {
        AccountLinking l = linking(false, 10);
        l.link(T1, good(), PASSWORD, FRESH, "r");
        l.link(T2, good(), PASSWORD, FRESH, "r");

        assertThat(l.list(T1)).hasSize(1).allSatisfy(a -> assertThat(a.tenantId()).isEqualTo(T1));
    }
}
