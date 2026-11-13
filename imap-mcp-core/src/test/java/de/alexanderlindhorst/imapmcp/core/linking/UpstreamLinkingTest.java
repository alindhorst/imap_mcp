package de.alexanderlindhorst.imapmcp.core.linking;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.audit.RecordingAuditLog;
import de.alexanderlindhorst.imapmcp.core.imap.LoginProbe;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UpstreamLinkingTest {

    private static final TenantId T1 = new TenantId("00000000-0000-4000-8000-000000000001");
    private static final TenantId T2 = new TenantId("00000000-0000-4000-8000-000000000002");
    private static final String ADDRESS = "me@example.org";
    private static final String CODE = "code-canary-1";
    private static final String ACCESS = "access-canary-1";
    private static final String REFRESH = "refresh-canary-1";
    private static final String MAIL_SCOPE = "https://mail.example/";

    private static final UpstreamProviderInfo GOOGLE = new UpstreamProviderInfo("google", "Google",
            URI.create("https://accounts.example/o/auth"), "client-1", List.of(MAIL_SCOPE),
            "https://mcp.example/accounts/oauth/google/callback", "imap.gmail.example", 993,
            MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GMAIL, true);
    private static final UpstreamProviderInfo OTHER = new UpstreamProviderInfo("other", "Other",
            URI.create("https://idp.example/authorize?tenant=x"), "client-2",
            List.of("https://imap.example/IMAP.All", "offline_access"),
            "https://mcp.example/accounts/oauth/other/callback", "imap.other.example", 993,
            MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, false);
    /** A provider whose fixed IMAP endpoint the SSRF policy refuses (port and TLS rule). */
    private static final UpstreamProviderInfo BAD_HOST = new UpstreamProviderInfo("badhost", "Bad",
            URI.create("https://idp.example/authorize"), "client-3", List.of("s"),
            "https://mcp.example/accounts/oauth/badhost/callback", "imap.bad.example", 143,
            MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, false);

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-13T10:00:00Z");

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

    /** One in-memory world: repository, pending store, account store. */
    private static final class Fake implements AccountRepository, UpstreamPendingStore, UpstreamAccountStore {
        final List<ResolvedAccount> accounts = new ArrayList<>();
        final Map<String, Pending> pending = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<char[]> storedRefresh = new ArrayList<>();
        final List<String> storedScopes = new ArrayList<>();
        int pendingCap = Integer.MAX_VALUE;
        boolean tableFull;
        RuntimeException storeFailure;
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
        public String begin(Pending p) {
            if (tableFull) {
                throw new LimitReachedException();
            }
            if (pending.values().stream().filter(x -> x.tenant().equals(p.tenant())).count() >= pendingCap) {
                throw new TenantLimitReachedException();
            }
            String state = "state-" + pending.size() + "-" + (++counter);
            pending.put(state, p);
            return state;
        }

        @Override
        public Optional<Pending> consume(String state) {
            return Optional.ofNullable(pending.remove(state));
        }

        @Override
        public ResolvedAccount create(TenantId tenant, NewUpstreamAccount a, char[] refreshToken, int maxAccounts,
                String requestId) {
            if (storeFailure != null) {
                throw storeFailure;
            }
            if (findByTenant(tenant).stream().anyMatch(r -> a.providerId().equals(r.account().providerId())
                    && ADDRESS_EQ.test(r.account().address(), a.address()))) {
                throw new DuplicateException();
            }
            if (findByTenant(tenant).size() >= maxAccounts) {
                throw new LinkedAccountStore.LimitReachedException();
            }
            calls.add("create:" + tenant.value() + ":" + a.providerId() + ":" + a.address() + ":" + a.label());
            storedRefresh.add(refreshToken.clone());
            storedScopes.add(a.scope());
            int n = ++counter;
            MailAccount m = new MailAccount(new AccountId("acct-" + n), a.label(), a.address(), a.host(), a.port(),
                    a.security(), a.flavor(), a.address(), new SecretRef("db:"), a.readOnly(), false,
                    MailAccount.Auth.XOAUTH2, MailAccount.Status.ACTIVE, 1L, a.providerId());
            ResolvedAccount r = new ResolvedAccount(tenant, m, "acc_handle" + n);
            accounts.add(r);
            return r;
        }

        @Override
        public boolean relink(TenantId tenant, AccountId id, char[] refreshToken, String scope, String requestId) {
            if (storeFailure != null) {
                throw storeFailure;
            }
            calls.add("relink:" + tenant.value() + ":" + id.value());
            storedRefresh.add(refreshToken.clone());
            storedScopes.add(scope);
            return findByTenantAndId(tenant, id).isPresent();
        }
    }

    private static final java.util.function.BiPredicate<String, String> ADDRESS_EQ = String::equalsIgnoreCase;

    private final Fake fake = new Fake();
    private final RecordingAuditLog audit = new RecordingAuditLog();
    private final TestClock clock = new TestClock();
    private final List<UpstreamProviderInfo> configured = List.of(GOOGLE, OTHER, BAD_HOST);

    private final List<String> exchanged = new ArrayList<>();
    private final List<Secret> issuedSecrets = new ArrayList<>();
    private UpstreamCodeExchange.Result exchangeResult;
    private final UpstreamCodeExchange exchange = (provider, code, verifier) -> {
        exchanged.add(provider.id() + ":" + new String(code) + ":" + new String(verifier));
        return exchangeResult != null ? exchangeResult : granted(REFRESH, null);
    };

    private final List<MailAccount> probed = new ArrayList<>();
    private final List<String> probedTokens = new ArrayList<>();
    private LoginProbe.Result probeResult = LoginProbe.Result.OK;

    private UpstreamCodeExchange.Result granted(String refresh, String scope) {
        Secret access = new Secret(ACCESS.toCharArray());
        issuedSecrets.add(access);
        Secret r = refresh == null ? null : new Secret(refresh.toCharArray());
        if (r != null) {
            issuedSecrets.add(r);
        }
        return new UpstreamCodeExchange.Result.Granted(access, r, scope);
    }

    private ProbeRateLimiter probeBudget = new ProbeRateLimiter(clock, 10, Duration.ofHours(1), 100);
    private ProbeRateLimiter startBudget = new ProbeRateLimiter(clock, 20, Duration.ofHours(1), 100);
    private int maxAccounts = 10;

    private UpstreamLinker linker() {
        UpstreamProviders providers = new UpstreamProviders() {
            @Override
            public List<UpstreamProviderInfo> all() {
                return configured;
            }

            @Override
            public Optional<UpstreamProviderInfo> find(String id) {
                return configured.stream().filter(p -> p.id().equals(id)).findFirst();
            }
        };
        return new UpstreamLinking(fake, providers, fake, fake, exchange, (account, token) -> {
            probed.add(account);
            probedTokens.add(new String(token));
            return probeResult;
        }, SsrfPolicy.of(List.of(), true, List.of()), audit, probeBudget, startBudget, maxAccounts);
    }

    private static Map<String, String> query(String location) {
        URI u = URI.create(location);
        Map<String, String> m = new HashMap<>();
        for (String kv : u.getRawQuery().split("&")) {
            int eq = kv.indexOf('=');
            m.put(kv.substring(0, eq), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String s256(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private String startLink(UpstreamLinker l, TenantId t, UpstreamProviderInfo p) {
        UpstreamLinker.Started s = l.start(t, p.id(), "Work", ADDRESS, true, null, "req-1");
        assertThat(s.outcome()).isEqualTo(UpstreamOutcome.OK);
        return s.state();
    }

    private static ResolvedAccount oauthAccount(TenantId t, String n, String providerId, String address) {
        MailAccount m = new MailAccount(new AccountId("acct-" + n), "Mine", address, "imap.gmail.example", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GMAIL, address, new SecretRef("db:"), true,
                false, MailAccount.Auth.XOAUTH2, MailAccount.Status.NEEDS_RELINK, 4L, providerId);
        return new ResolvedAccount(t, m, "acc_handle" + n);
    }

    private static ResolvedAccount passwordAccount(TenantId t, String n) {
        MailAccount m = new MailAccount(new AccountId("acct-" + n), "Pw", ADDRESS, "imap.example", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, ADDRESS, new SecretRef("db:"), true,
                false);
        return new ResolvedAccount(t, m, "acc_handle" + n);
    }

    private List<String> auditResults() {
        return audit.events.stream().map(e -> e.type() + ":" + e.result()).toList();
    }

    // ---- start ----

    @Test
    void startWritesThePendingRowAndRedirectsToTheProviderWithPkceLoginHintAndGoogleExtras() throws Exception {
        UpstreamLinker l = linker();

        UpstreamLinker.Started s = l.start(T1, "google", "  Work  ", " " + ADDRESS + " ", false, null, "req-1");

        assertThat(s.outcome()).isEqualTo(UpstreamOutcome.OK);
        UpstreamPendingStore.Pending p = fake.pending.get(s.state());
        assertThat(p.tenant()).isEqualTo(T1);
        assertThat(p.providerId()).isEqualTo("google");
        assertThat(p.label()).isEqualTo("Work");
        assertThat(p.address()).isEqualTo(ADDRESS);
        assertThat(p.readOnly()).isFalse();
        assertThat(p.relinkAccount()).isNull();
        assertThat(s.location()).startsWith("https://accounts.example/o/auth?");
        Map<String, String> q = query(s.location());
        assertThat(q).containsEntry("response_type", "code").containsEntry("client_id", "client-1")
                .containsEntry("redirect_uri", GOOGLE.redirectUri()).containsEntry("scope", MAIL_SCOPE)
                .containsEntry("state", s.state()).containsEntry("code_challenge_method", "S256")
                .containsEntry("code_challenge", s256(p.pkceVerifier())).containsEntry("login_hint", ADDRESS)
                .containsEntry("access_type", "offline").containsEntry("prompt", "consent");
        assertThat(p.pkceVerifier()).hasSizeGreaterThanOrEqualTo(43).matches("[A-Za-z0-9_-]+");
        assertThat(s.location()).doesNotContain("nonce").doesNotContain("openid");
        assertThat(audit.events).isEmpty();
    }

    @Test
    void startForAProviderWithAQueryInItsEndpointAndNoGoogleExtras() {
        UpstreamLinker.Started s = linker().start(T1, "other", "Work", ADDRESS, true, null, "r");

        assertThat(s.location()).startsWith("https://idp.example/authorize?tenant=x&response_type=code");
        assertThat(query(s.location())).containsEntry("scope", "https://imap.example/IMAP.All offline_access")
                .containsEntry("login_hint", ADDRESS).doesNotContainKeys("access_type", "prompt");
    }

    @Test
    void startRefusesBadInputAndUnknownProvidersWithoutWritingAnything() {
        UpstreamLinker l = linker();
        String longLabel = "x".repeat(81);

        assertThat(l.start(T1, "nope", "Work", ADDRESS, true, null, "r").outcome()).isEqualTo(UpstreamOutcome.NOT_FOUND);
        assertThat(l.start(T1, null, "Work", ADDRESS, true, null, "r").outcome()).isEqualTo(UpstreamOutcome.NOT_FOUND);
        for (String bad : new String[] {"", "   ", "no-at-sign", "a@", "@b.org", "a b@c.org", "a@b@c.org", "a@b.org\n",
                "a@b.org&scope=x", "<a>@b.org", "a@b.org‮"}) {
            assertThat(l.start(T1, "google", "Work", bad, true, null, "r").outcome())
                    .as("address %s", bad.replace("\n", "\\n")).isEqualTo(UpstreamOutcome.INVALID_INPUT);
        }
        assertThat(l.start(T1, "google", longLabel, ADDRESS, true, null, "r").outcome())
                .isEqualTo(UpstreamOutcome.INVALID_INPUT);
        assertThat(l.start(T1, "google", null, ADDRESS, true, null, "r").outcome())
                .isEqualTo(UpstreamOutcome.INVALID_INPUT);
        assertThat(fake.pending).isEmpty();
    }

    @Test
    void startChecksTheAccountLimitTheProbeBudgetTheStartLimitAndThePendingCaps() {
        maxAccounts = 1;
        fake.accounts.add(passwordAccount(T1, "1"));
        UpstreamLinker l = linker();
        assertThat(l.start(T1, "google", "Work", ADDRESS, true, null, "r").outcome())
                .isEqualTo(UpstreamOutcome.ACCOUNT_LIMIT);
        assertThat(l.start(T2, "google", "Work", ADDRESS, true, null, "r").outcome()).isEqualTo(UpstreamOutcome.OK);

        for (int i = 0; i < 10; i++) {
            assertThat(probeBudget.tryAcquire(T2)).isTrue();
        }
        assertThat(l.start(T2, "google", "Work", ADDRESS, true, null, "r").outcome())
                .isEqualTo(UpstreamOutcome.RATE_LIMITED);
        assertThat(probeBudget.hasRoom(T2)).isFalse(); // looking took nothing

        startBudget = new ProbeRateLimiter(clock, 2, Duration.ofHours(1), 100);
        UpstreamLinker l2 = linker();
        TenantId t3 = new TenantId("00000000-0000-4000-8000-000000000003");
        assertThat(l2.start(t3, "google", "W", ADDRESS, true, null, "r").outcome()).isEqualTo(UpstreamOutcome.OK);
        assertThat(l2.start(t3, "google", "W", ADDRESS, true, null, "r").outcome()).isEqualTo(UpstreamOutcome.OK);
        assertThat(l2.start(t3, "google", "W", ADDRESS, true, null, "r").outcome())
                .isEqualTo(UpstreamOutcome.START_LIMIT);
        clock.now = clock.now.plus(Duration.ofMinutes(61));
        assertThat(l2.start(t3, "google", "W", ADDRESS, true, null, "r").outcome()).isEqualTo(UpstreamOutcome.OK);
    }

    @Test
    void startRefusesAFourthPendingFlowOfOneTenantAndAFullTable() {
        fake.pendingCap = 3;
        UpstreamLinker l = linker();
        for (int i = 0; i < 3; i++) {
            assertThat(l.start(T1, "google", "W", ADDRESS, true, null, "r").outcome()).isEqualTo(UpstreamOutcome.OK);
        }
        assertThat(l.start(T1, "google", "W", ADDRESS, true, null, "r").outcome())
                .isEqualTo(UpstreamOutcome.PENDING_LIMIT);
        assertThat(l.start(T2, "google", "W", ADDRESS, true, null, "r").outcome()).isEqualTo(UpstreamOutcome.OK);

        fake.tableFull = true;
        assertThat(l.start(T2, "google", "W", ADDRESS, true, null, "r").outcome()).isEqualTo(UpstreamOutcome.UNAVAILABLE);
    }

    @Test
    void startRefusalsWriteABestEffortAuditRowWithoutTheAddress() {
        maxAccounts = 1;
        fake.accounts.add(passwordAccount(T1, "1"));
        audit.failEvent = true;

        assertThat(linker().start(T1, "google", "Work", ADDRESS, true, null, "req-9").outcome())
                .isEqualTo(UpstreamOutcome.ACCOUNT_LIMIT); // a failing audit write never changes the answer
        audit.failEvent = false;
        linker().start(T1, "google", "Work", ADDRESS, true, null, "req-9");

        assertThat(audit.events).containsExactly(new EventInfo("link", T1, null, "req-9", "refused_limit"));
    }

    @Test
    void relinkStartUsesTheAccountsProviderAndAddressAndIgnoresTheFormValues() {
        ResolvedAccount a = oauthAccount(T1, "7", "google", "Stored@Example.org");
        fake.accounts.add(a);

        UpstreamLinker.Started s = linker().start(T1, "google", "Other label", "someone@else.org", false,
                a.handle(), "r");

        assertThat(s.outcome()).isEqualTo(UpstreamOutcome.OK);
        UpstreamPendingStore.Pending p = fake.pending.get(s.state());
        assertThat(p.relinkAccount()).isEqualTo(a.account().id());
        assertThat(p.address()).isEqualTo("Stored@Example.org");
        assertThat(p.label()).isEqualTo("Mine");
        assertThat(p.readOnly()).isTrue();
        assertThat(query(s.location())).containsEntry("login_hint", "Stored@Example.org");
    }

    @Test
    void relinkStartRefusesForeignPasswordAndOtherProviderAccountsAsNotFound() {
        fake.accounts.add(oauthAccount(T1, "7", "google", ADDRESS));
        fake.accounts.add(passwordAccount(T1, "8"));
        UpstreamLinker l = linker();

        assertThat(l.start(T2, "google", "x", "y", true, "acc_handle7", "r").outcome())
                .isEqualTo(UpstreamOutcome.NOT_FOUND); // tenant B cannot name tenant A's account
        assertThat(l.start(T1, "google", "x", "y", true, "acc_handle8", "r").outcome())
                .isEqualTo(UpstreamOutcome.NOT_FOUND); // password and xoauth2 do not mix
        assertThat(l.start(T1, "other", "x", "y", true, "acc_handle7", "r").outcome())
                .isEqualTo(UpstreamOutcome.NOT_FOUND); // the provider is fixed
        assertThat(l.start(T1, "google", "x", "y", true, "acc_unknown", "r").outcome())
                .isEqualTo(UpstreamOutcome.NOT_FOUND);
        assertThat(fake.pending).isEmpty();
    }

    @Test
    void relinkStartDoesNotCheckTheAccountLimit() {
        maxAccounts = 1;
        fake.accounts.add(oauthAccount(T1, "7", "google", ADDRESS));

        assertThat(linker().start(T1, "google", "x", "y", true, "acc_handle7", "r").outcome())
                .isEqualTo(UpstreamOutcome.OK);
    }

    // ---- callback: the state and its binding ----

    @Test
    void callbackWithUnknownReplayedMismatchedStateOrCookieIsExpiredAndTouchesNothing() {
        UpstreamLinker l = linker();
        String state = startLink(l, T1, GOOGLE);

        assertThat(l.callback("unknown", "unknown", "google", CODE, null)).isEqualTo(done(UpstreamOutcome.EXPIRED));
        assertThat(l.callback(null, null, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.EXPIRED));
        assertThat(l.callback(state, null, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.EXPIRED));
        // the state above is burned now, whatever else was wrong: even a correct request fails (replay)
        assertThat(l.callback(state, state, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.EXPIRED));

        String second = startLink(l, T1, GOOGLE);
        assertThat(l.callback(second, "another-cookie", "google", CODE, null)).isEqualTo(done(UpstreamOutcome.EXPIRED));
        String third = startLink(l, T1, GOOGLE);
        assertThat(l.callback(third, third, "other", CODE, null)).isEqualTo(done(UpstreamOutcome.EXPIRED));

        assertThat(exchanged).isEmpty();
        assertThat(fake.calls).isEmpty();
        assertThat(audit.events).isEmpty();
    }

    @Test
    void anotherTenantsCookieCannotCompleteThePendingStateOfThisTenant() {
        UpstreamLinker l = linker();
        String stateOfA = startLink(l, T1, GOOGLE);
        String stateOfB = startLink(l, T2, GOOGLE);

        // tenant B's browser holds B's cookie (or none) and replays A's state
        assertThat(l.callback(stateOfA, stateOfB, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.EXPIRED));

        assertThat(exchanged).isEmpty();
        assertThat(fake.accounts).isEmpty();
        assertThat(audit.events).isEmpty();
    }

    private static UpstreamLinker.Completed done(UpstreamOutcome o) {
        return new UpstreamLinker.Completed(o, false);
    }

    // ---- callback: provider answers ----

    @Test
    void accessDeniedIsDeniedAndOtherErrorsAreProviderErrorsAndNothingIsExchanged() {
        UpstreamLinker l = linker();
        String s1 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s1, s1, "google", null, "access_denied")).isEqualTo(done(UpstreamOutcome.DENIED));
        String s2 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s2, s2, "google", null, "server_error")).isEqualTo(done(UpstreamOutcome.PROVIDER_ERROR));
        String s3 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s3, s3, "google", CODE, "invalid_scope")).isEqualTo(done(UpstreamOutcome.PROVIDER_ERROR));
        String s4 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s4, s4, "google", null, null)).isEqualTo(done(UpstreamOutcome.PROVIDER_ERROR));
        String s5 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s5, s5, "google", "", null)).isEqualTo(done(UpstreamOutcome.PROVIDER_ERROR));

        assertThat(auditResults()).containsExactly("link:denied", "link:provider_error", "link:provider_error",
                "link:provider_error", "link:provider_error");
        assertThat(audit.events).allSatisfy(e -> assertThat(e.tenant()).isEqualTo(T1));
        assertThat(exchanged).isEmpty();
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void exchangeTimeoutOrServerErrorIsProviderUnavailableAndInvalidGrantIsIncomplete() {
        UpstreamLinker l = linker();
        exchangeResult = new UpstreamCodeExchange.Result.Unavailable();
        String s1 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s1, s1, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.PROVIDER_UNAVAILABLE));

        exchangeResult = new UpstreamCodeExchange.Result.InvalidGrant();
        String s2 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s2, s2, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.INCOMPLETE));

        assertThat(auditResults()).containsExactly("link:exchange_failed", "link:exchange_failed");
        assertThat(probed).isEmpty();
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void noRefreshTokenOrAMissingRequiredScopeIsIncomplete() {
        UpstreamLinker l = linker();
        exchangeResult = granted(null, null);
        String s1 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s1, s1, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.INCOMPLETE));

        exchangeResult = granted("", null);
        String s2 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s2, s2, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.INCOMPLETE));

        exchangeResult = granted(REFRESH, "https://other.example/scope");
        String s3 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s3, s3, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.INCOMPLETE));

        assertThat(auditResults()).containsExactly("link:exchange_failed", "link:exchange_failed",
                "link:exchange_failed");
        assertThat(probed).isEmpty();
        assertThat(fake.calls).isEmpty();
        // the tokens of every failed attempt are wiped
        assertThat(issuedSecrets).allSatisfy(x -> assertThat(x.chars()).containsOnly('\0'));
    }

    // ---- callback: probe ----

    @Test
    void aFailingXoauth2ProbeStoresNothingAndIsProbeFailedForAuthAndUnreachable() {
        UpstreamLinker l = linker();
        for (LoginProbe.Result r : new LoginProbe.Result[] {LoginProbe.Result.AUTH_FAILED,
                LoginProbe.Result.UNREACHABLE}) {
            probeResult = r;
            String s = startLink(l, T1, GOOGLE);
            assertThat(l.callback(s, s, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.PROBE_FAILED));
        }

        assertThat(auditResults()).containsExactly("link:probe_failed", "link:probe_failed");
        assertThat(fake.calls).isEmpty();
        assertThat(probed).hasSize(2);
        assertThat(issuedSecrets).allSatisfy(x -> assertThat(x.chars()).containsOnly('\0'));
    }

    @Test
    void aHostTheSsrfPolicyRefusesIsProbeFailedWithoutAProbeOrAProbeBudgetSlot() {
        UpstreamLinker l = linker();
        String s = startLink(l, T1, BAD_HOST);

        assertThat(l.callback(s, s, "badhost", CODE, null)).isEqualTo(done(UpstreamOutcome.PROBE_FAILED));

        assertThat(probed).isEmpty();
        assertThat(auditResults()).containsExactly("link:probe_failed");
        assertThat(probeBudget.hasRoom(T1)).isTrue();
    }

    @Test
    void anExhaustedProbeBudgetAtTheCallbackIsProbeFailedAndTheProbeCountsInTheLimiter() {
        UpstreamLinker l = linker();
        String s = startLink(l, T1, GOOGLE);
        for (int i = 0; i < 10; i++) {
            probeBudget.tryAcquire(T1);
        }

        assertThat(l.callback(s, s, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.PROBE_FAILED));
        assertThat(probed).isEmpty();
        assertThat(auditResults()).containsExactly("link:probe_failed");

        ProbeRateLimiter fresh = probeBudget = new ProbeRateLimiter(clock, 1, Duration.ofHours(1), 10);
        UpstreamLinker l2 = linker();
        String s2 = startLink(l2, T1, GOOGLE);
        assertThat(l2.callback(s2, s2, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.OK));
        assertThat(fresh.hasRoom(T1)).isFalse();
    }

    // ---- callback: storing ----

    @Test
    void successStoresTheAccountWithTheRefreshTokenAfterAProbeAsTheEnteredAddress() {
        UpstreamLinker l = linker();
        exchangeResult = granted(REFRESH, MAIL_SCOPE + " extra");
        UpstreamLinker.Started st = l.start(T1, "google", "Work", ADDRESS, false, null, "r");
        String verifier = fake.pending.get(st.state()).pkceVerifier();

        UpstreamLinker.Completed c = l.callback(st.state(), st.state(), "google", CODE, null);

        assertThat(c).isEqualTo(new UpstreamLinker.Completed(UpstreamOutcome.OK, false));
        assertThat(exchanged).containsExactly("google:" + CODE + ":" + verifier);
        assertThat(probed).hasSize(1);
        MailAccount p = probed.get(0);
        assertThat(p.username()).isEqualTo(ADDRESS);
        assertThat(p.address()).isEqualTo(ADDRESS);
        assertThat(p.host()).isEqualTo("imap.gmail.example");
        assertThat(p.port()).isEqualTo(993);
        assertThat(p.flavor()).isEqualTo(MailAccount.Flavor.GMAIL);
        assertThat(p.providerId()).isEqualTo("google");
        assertThat(probedTokens).containsExactly(ACCESS);
        assertThat(fake.calls).containsExactly("create:" + T1.value() + ":google:" + ADDRESS + ":Work");
        assertThat(new String(fake.storedRefresh.get(0))).isEqualTo(REFRESH);
        assertThat(fake.storedScopes).containsExactly(MAIL_SCOPE + " extra");
        assertThat(fake.accounts).hasSize(1);
        assertThat(fake.accounts.get(0).account().readOnly()).isFalse();
        assertThat(audit.events).isEmpty(); // the success row is the store's, in its transaction
        assertThat(issuedSecrets).allSatisfy(x -> assertThat(x.chars()).containsOnly('\0'));
        assertThat(fake.pending).isEmpty();
    }

    @Test
    void aProviderThatDoesNotEchoTheScopeIsTakenAsGrantedAsRequestedAndOfflineAccessNeedsNoEcho() {
        UpstreamLinker l = linker();
        exchangeResult = granted(REFRESH, null);
        String s1 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s1, s1, "google", CODE, null).outcome()).isEqualTo(UpstreamOutcome.OK);
        assertThat(fake.storedScopes.get(0)).isEqualTo(MAIL_SCOPE);

        exchangeResult = granted(REFRESH, "https://imap.example/IMAP.All");
        String s2 = startLink(l, T1, OTHER);
        assertThat(l.callback(s2, s2, "other", CODE, null).outcome()).isEqualTo(UpstreamOutcome.OK);
    }

    @Test
    void theSameAddressOfTheSameProviderTwiceInATenantIsADuplicateAndAllowedInAnotherTenant() {
        UpstreamLinker l = linker();
        String s1 = startLink(l, T1, GOOGLE);
        assertThat(l.callback(s1, s1, "google", CODE, null).outcome()).isEqualTo(UpstreamOutcome.OK);

        String s2 = l.start(T1, "google", "Again", ADDRESS.toUpperCase(), true, null, "r").state();
        assertThat(l.callback(s2, s2, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.DUPLICATE));
        assertThat(auditResults()).containsExactly("link:duplicate");

        String s3 = startLink(l, T2, GOOGLE);
        assertThat(l.callback(s3, s3, "google", CODE, null).outcome()).isEqualTo(UpstreamOutcome.OK);
        assertThat(fake.accounts).hasSize(2);

        String s4 = startLink(l, T1, OTHER); // another provider with the same address is no duplicate
        assertThat(l.callback(s4, s4, "other", CODE, null).outcome()).isEqualTo(UpstreamOutcome.OK);
    }

    @Test
    void theAccountLimitRecheckedInTheTransactionIsLimitWithItsAuditRow() {
        UpstreamLinker l = linker();
        String s = startLink(l, T1, GOOGLE);
        maxAccounts = 1;
        UpstreamLinker strict = linker();
        fake.accounts.add(passwordAccount(T1, "1")); // another tab filled the last slot meanwhile

        assertThat(strict.callback(s, s, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.LIMIT));

        assertThat(auditResults()).containsExactly("link:limit");
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void aStoreFailureIsUnavailableWithAnAuditAttemptAndNothingStored() {
        UpstreamLinker l = linker();
        fake.storeFailure = new LinkedAccountStore.UnavailableException();
        String s = startLink(l, T1, GOOGLE);

        assertThat(l.callback(s, s, "google", CODE, null)).isEqualTo(done(UpstreamOutcome.UNAVAILABLE));

        assertThat(auditResults()).containsExactly("link:unavailable");
        assertThat(fake.accounts).isEmpty();
    }

    @Test
    void anAuditFailureOnARefusalNeverChangesTheAnswer() {
        UpstreamLinker l = linker();
        audit.failEvent = true;
        String s = startLink(l, T1, GOOGLE);

        assertThat(l.callback(s, s, "google", null, "access_denied")).isEqualTo(done(UpstreamOutcome.DENIED));
    }

    // ---- callback: re-link ----

    @Test
    void relinkSuccessProbesAsTheStoredAddressOnTheStoredHostAndWritesANewVersion() {
        ResolvedAccount a = oauthAccount(T1, "7", "google", "Stored@Example.org");
        fake.accounts.add(a);
        UpstreamLinker l = linker();
        UpstreamLinker.Started st = l.start(T1, "google", null, null, true, a.handle(), "r");

        UpstreamLinker.Completed c = l.callback(st.state(), st.state(), "google", CODE, null);

        assertThat(c).isEqualTo(new UpstreamLinker.Completed(UpstreamOutcome.OK, true));
        assertThat(probed.get(0).username()).isEqualTo("Stored@Example.org");
        assertThat(probed.get(0).host()).isEqualTo("imap.gmail.example");
        assertThat(fake.calls).containsExactly("relink:" + T1.value() + ":acct-7");
        assertThat(new String(fake.storedRefresh.get(0))).isEqualTo(REFRESH);
        assertThat(audit.events).isEmpty();
    }

    @Test
    void relinkFailuresAreAuditedAsRelinkAgainstTheAccountAndStoreNothing() {
        ResolvedAccount a = oauthAccount(T1, "7", "google", ADDRESS);
        fake.accounts.add(a);
        UpstreamLinker l = linker();
        probeResult = LoginProbe.Result.AUTH_FAILED; // e.g. the user authorized a different mailbox

        UpstreamLinker.Started st = l.start(T1, "google", null, null, true, a.handle(), "r");
        UpstreamLinker.Completed c = l.callback(st.state(), st.state(), "google", CODE, null);

        assertThat(c).isEqualTo(new UpstreamLinker.Completed(UpstreamOutcome.PROBE_FAILED, true));
        assertThat(audit.events).containsExactly(new EventInfo("relink", T1, a.account().id(),
                audit.events.get(0).requestId(), "probe_failed"));
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void relinkOfAnAccountThatWasUnlinkedOrChangedMeanwhileIsExpired() {
        ResolvedAccount a = oauthAccount(T1, "7", "google", ADDRESS);
        fake.accounts.add(a);
        UpstreamLinker l = linker();
        UpstreamLinker.Started st = l.start(T1, "google", null, null, true, a.handle(), "r");
        fake.accounts.clear();

        assertThat(l.callback(st.state(), st.state(), "google", CODE, null).outcome())
                .isEqualTo(UpstreamOutcome.EXPIRED);

        assertThat(fake.calls).isEmpty();
        assertThat(probed).isEmpty();
    }

    // ---- providers ----

    @Test
    void providersAreTheConfiguredOnesInOrder() {
        UpstreamLinker l = linker();

        assertThat(l.providers()).containsExactly(GOOGLE, OTHER, BAD_HOST);
        assertThat(l.provider("other")).contains(OTHER);
        assertThat(l.provider("nope")).isEmpty();
        assertThat(l.provider(null)).isEmpty();
    }
}
