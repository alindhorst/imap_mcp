package de.alexanderlindhorst.imapmcp.transport.http.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.audit.RecordingAuditLog;
import de.alexanderlindhorst.imapmcp.core.linking.LinkedAccountStore;
import de.alexanderlindhorst.imapmcp.core.imap.LoginProbe;
import de.alexanderlindhorst.imapmcp.core.linking.ProbeRateLimiter;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamAccountStore;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamCodeExchange;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamLinking;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamPendingStore;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviderInfo;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviders;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.web.WebSessionStore;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The upstream OAuth linking surface (X1-2, SPEC 5.3): provider buttons, start, callback pages per outcome, CSRF and
 * Origin, cookie attributes, tenant binding and the log scan. The linker is the real {@code UpstreamLinking} over
 * in-memory stores, with a fake provider (code exchange) and probe.
 */
class UpstreamLinkingPagesTest {

    static final TenantId A = LinkingPagesTest.TENANT;
    static final TenantId B = new TenantId("00000000-0000-4000-8000-0000000000bb");
    static final String ADDRESS = "me@example.org";
    static final String CODE = "code-canary-7c1";
    static final String ACCESS = "access-canary-7c1";
    static final String REFRESH = "refresh-canary-7c1";
    static final String DESCRIPTION = "description-canary-7c1";
    static final String MAIL_SCOPE = "https://mail.example/";

    static final UpstreamProviderInfo GOOGLE = new UpstreamProviderInfo("google", "Google",
            URI.create("https://accounts.example/o/auth"), "client-1", List.of(MAIL_SCOPE),
            "https://127.0.0.1/accounts/oauth/google/callback", "imap.gmail.example", 993,
            MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GMAIL, true);
    static final UpstreamProviderInfo OTHER = new UpstreamProviderInfo("other", "Other <b>",
            URI.create("https://idp.example/authorize"), "client-2", List.of("imap"),
            "https://127.0.0.1/accounts/oauth/other/callback", "imap.other.example", 993,
            MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, false);

    /** One in-memory world: repository, pending store and account store of the upstream flow. */
    static final class World implements AccountRepository, UpstreamPendingStore, UpstreamAccountStore {
        final List<ResolvedAccount> accounts = new CopyOnWriteArrayList<>();
        final Map<String, Pending> pending = new LinkedHashMap<>();
        final List<String> stored = new CopyOnWriteArrayList<>();
        final SecureRandom random = new SecureRandom();
        volatile RuntimeException storeFailure;
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
        public synchronized String begin(Pending p) {
            if (pending.values().stream().filter(x -> x.tenant().equals(p.tenant())).count() >= MAX_PER_TENANT) {
                throw new TenantLimitReachedException();
            }
            byte[] b = new byte[32];
            random.nextBytes(b);
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            pending.put(state, p);
            return state;
        }

        @Override
        public synchronized Optional<Pending> consume(String state) {
            return Optional.ofNullable(pending.remove(state));
        }

        @Override
        public synchronized ResolvedAccount create(TenantId tenant, NewUpstreamAccount a, char[] refreshToken,
                int maxAccounts, String requestId) {
            if (storeFailure != null) {
                throw storeFailure;
            }
            if (findByTenant(tenant).stream().anyMatch(r -> a.providerId().equals(r.account().providerId())
                    && a.address().equalsIgnoreCase(r.account().address()))) {
                throw new DuplicateException();
            }
            if (findByTenant(tenant).size() >= maxAccounts) {
                throw new LinkedAccountStore.LimitReachedException();
            }
            int n = ++counter;
            stored.add("create:" + tenant.value() + ":" + a.providerId() + ":" + a.address() + ":" + a.label() + ":"
                    + new String(refreshToken) + ":" + a.scope());
            MailAccount m = new MailAccount(new AccountId("acct-" + n), a.label(), a.address(), a.host(), a.port(),
                    a.security(), a.flavor(), a.address(), new SecretRef("db:"), a.readOnly(), false,
                    MailAccount.Auth.XOAUTH2, MailAccount.Status.ACTIVE, 1L, a.providerId());
            ResolvedAccount r = new ResolvedAccount(tenant, m, "acc_oauthhandle" + "x".repeat(14) + (2 + n % 6));
            accounts.add(r);
            return r;
        }

        @Override
        public synchronized boolean relink(TenantId tenant, AccountId id, char[] refreshToken, String scope,
                String requestId) {
            stored.add("relink:" + tenant.value() + ":" + id.value() + ":" + new String(refreshToken));
            return findByTenantAndId(tenant, id).isPresent();
        }
    }

    final LinkingPagesTest.FakeLinker passwords = new LinkingPagesTest.FakeLinker();
    final World world = new World();
    final RecordingAuditLog audit = new RecordingAuditLog();
    final InMemoryStores.Sessions sessions = new InMemoryStores.Sessions();
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final List<ILoggingEvent> logs = new CopyOnWriteArrayList<>();
    final List<String> exchanged = new CopyOnWriteArrayList<>();
    final List<String> probed = new CopyOnWriteArrayList<>();

    volatile UpstreamCodeExchange.Result exchangeResult;
    volatile LoginProbe.Result probeResult = LoginProbe.Result.OK;
    volatile List<UpstreamProviderInfo> configured = List.of(GOOGLE, OTHER);
    ProbeRateLimiter startBudget = new ProbeRateLimiter(Clock.systemUTC(), 20, Duration.ofHours(1), 100);
    int maxAccounts = 10;

    HttpLocalServer server;
    int port;
    String origin;
    String cookieA;
    String csrfA;
    String cookieB;
    String csrfB;
    ch.qos.logback.core.AppenderBase<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ch.qos.logback.core.AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                logs.add(event);
            }
        };
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.DEBUG);
        start();
    }

    @AfterEach
    void tearDown() throws Exception {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
        if (server != null) {
            server.stop();
        }
    }

    UpstreamCodeExchange.Result granted(String refresh, String scope) {
        return new UpstreamCodeExchange.Result.Granted(new Secret(ACCESS.toCharArray()),
                refresh == null ? null : new Secret(refresh.toCharArray()), scope);
    }

    void start() throws Exception {
        if (server != null) {
            server.stop();
        }
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        origin = "https://127.0.0.1:" + port;
        WebConfig config = new WebConfig(URI.create(origin), "https://idp.example/realms/x", "client",
                () -> new Secret("s".toCharArray()), "sub", "imap-user", null, false);
        OidcProvider stub = new OidcProvider() {
            @Override
            public URI authorizationEndpoint() throws OidcException {
                throw new OidcException(OidcException.UNAVAILABLE);
            }

            @Override
            public IdToken exchange(String code, String verifier, String nonce) throws OidcException {
                throw new OidcException(OidcException.UNAVAILABLE);
            }
        };
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
        UpstreamCodeExchange exchange = (provider, code, verifier) -> {
            exchanged.add(provider.id() + ":" + new String(code) + ":" + new String(verifier));
            return exchangeResult != null ? exchangeResult : granted(REFRESH, null);
        };
        UpstreamLinking upstream = new UpstreamLinking(world, providers, world, world, exchange, (account, token) -> {
            probed.add(account.username() + ":" + new String(token));
            return probeResult;
        }, SsrfPolicy.of(List.of(), true, List.of()), audit,
                new ProbeRateLimiter(Clock.systemUTC(), 10, Duration.ofHours(1), 100), startBudget, maxAccounts);
        server = HttpLocalServer.builder().port(port).webSurface(WebSurface.of(config, stub,
                new InMemoryStores.Pending(), sessions, new InMemoryStores.Tenants(new RecordingAuditLog()),
                passwords, Clock.systemUTC(),
                de.alexanderlindhorst.imapmcp.transport.http.KeysUnavailableReporter.withoutMetrics(), upstream))
                .build();
        server.start();
        WebSessionStore.Created ca = sessions.create(A, Instant.now());
        cookieA = "__Host-session=" + ca.sessionId();
        csrfA = ca.csrfToken();
        WebSessionStore.Created cb = sessions.create(B, Instant.now());
        cookieB = "__Host-session=" + cb.sessionId();
        csrfB = cb.csrfToken();
    }

    record Resp(int status, Map<String, List<String>> headers, String body) {
        String header(String n) {
            List<String> v = headers.get(n.toLowerCase());
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        List<String> all(String n) {
            List<String> v = headers.get(n.toLowerCase());
            return v == null ? List.of() : v;
        }
    }

    Resp send(String method, String path, String cookieHeader, Map<String, String> headers, Map<String, String> form)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (cookieHeader != null) {
            b.header("Cookie", cookieHeader);
        }
        headers.forEach(b::header);
        if (form != null) {
            StringBuilder body = new StringBuilder();
            form.forEach((k, v) -> body.append(body.length() == 0 ? "" : "&")
                    .append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
            b.header("Content-Type", "application/x-www-form-urlencoded");
            b.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.headers().map(), r.body());
    }

    Resp getA(String path) throws Exception {
        return send("GET", path, cookieA, Map.of(), null);
    }

    Resp postA(String path, Map<String, String> form) throws Exception {
        Map<String, String> withCsrf = new LinkedHashMap<>(form);
        withCsrf.putIfAbsent("csrf", csrfA);
        return send("POST", path, cookieA, Map.of("Origin", origin), withCsrf);
    }

    static Map<String, String> startForm() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("label", "Private");
        f.put("address", ADDRESS);
        f.put("access", "read_only");
        return f;
    }

    /** Starts a flow as tenant A and returns the state of the 303's cookie. */
    String startAsA() throws Exception {
        Resp r = postA("/accounts/oauth/google/start", startForm());
        assertThat(r.status()).isEqualTo(303);
        return stateOf(r);
    }

    static String stateOf(Resp start) {
        String c = start.all("set-cookie").stream().filter(x -> x.startsWith("__Host-upstream=")).findFirst()
                .orElseThrow();
        return c.substring("__Host-upstream=".length(), c.indexOf(';'));
    }

    /** The callback as the browser returns from the provider: the binding cookie, and no session cookie. */
    Resp callback(String id, String query, String bindingCookie) throws Exception {
        return send("GET", "/accounts/oauth/" + id + "/callback?" + query,
                bindingCookie == null ? null : "__Host-upstream=" + bindingCookie, Map.of(), null);
    }

    static String q(String k, String v) {
        return k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    static ResolvedAccount oauthAccount(TenantId t, String n, MailAccount.Status status) {
        MailAccount m = new MailAccount(new AccountId("acct-oauth" + n), "Mine", ADDRESS, "imap.gmail.example", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GMAIL, ADDRESS, new SecretRef("db:"), true,
                false, MailAccount.Auth.XOAUTH2, status, 4L, "google");
        return new ResolvedAccount(t, m, "acc_oauthhandle" + "a".repeat(14) + n);
    }

    void assertSafePage(Resp r) {
        assertThat(r.header("Cache-Control")).isEqualTo("no-store");
        assertThat(r.header("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(r.body()).doesNotContain("<script").doesNotContain(CODE).doesNotContain(ACCESS)
                .doesNotContain(REFRESH).doesNotContain(DESCRIPTION);
    }

    // ---- /accounts: buttons ----

    @Test
    void theHomePageOffersOneButtonPerConfiguredProviderEscaped() throws Exception {
        Resp r = getA("/accounts");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("href=\"/accounts/oauth/google/start\"").contains("Google")
                .contains("href=\"/accounts/oauth/other/start\"").contains("Other &lt;b&gt;")
                .doesNotContain("Other <b>");
    }

    @Test
    void withoutProvidersOrAtTheAccountLimitThereAreNoProviderButtons() throws Exception {
        configured = List.of();
        assertThat(getA("/accounts").body()).doesNotContain("/accounts/oauth/");

        configured = List.of(GOOGLE);
        passwords.max = 1;
        passwords.accounts.add(LinkingPagesTest.account("Work", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa",
                MailAccount.Status.ACTIVE, false));
        assertThat(getA("/accounts").body()).doesNotContain("/accounts/oauth/");
    }

    @Test
    void anUpstreamAccountOffersAuthorizeAgainInsteadOfANewPassword() throws Exception {
        ResolvedAccount oauth = oauthAccount(A, "2", MailAccount.Status.NEEDS_RELINK);
        passwords.accounts.add(oauth);

        Resp r = getA("/accounts");

        assertThat(r.body()).contains("/accounts/oauth/google/start?account=" + oauth.handle())
                .contains("needs to be authorized again").doesNotContain("/accounts/relink?account=" + oauth.handle())
                .doesNotContain("needs a new password");
    }

    @Test
    void thePasswordRelinkPageOfAnUpstreamAccountIsNotFound() throws Exception {
        ResolvedAccount oauth = oauthAccount(A, "2", MailAccount.Status.ACTIVE);
        passwords.accounts.add(oauth);

        assertThat(getA("/accounts/relink?account=" + oauth.handle()).status()).isEqualTo(404);
    }

    // ---- start form ----

    @Test
    void theStartFormHasLabelAddressAndAccessWithReadOnlyDefaultAndNoPassword() throws Exception {
        Resp r = getA("/accounts/oauth/google/start");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("action=\"/accounts/oauth/google/start\"").contains("method=\"post\"")
                .contains("name=\"csrf\" value=\"" + csrfA + "\"").contains("name=\"label\"")
                .contains("name=\"address\"").contains("<option value=\"read_only\" selected>")
                .doesNotContain("type=\"password\"").doesNotContain("name=\"host\"");
        assertThat(r.body()).contains("Google");
        assertSafePage(r);
        // Chrome applies form-action to the redirect of the form's POST: the provider's origin must be allowed
        assertThat(r.header("Content-Security-Policy")).isEqualTo(
                "default-src 'none'; form-action 'self' https://accounts.example; base-uri 'none'; "
                        + "frame-ancestors 'none'");
    }

    @Test
    void theStartFormOfAnUnknownProviderIsNotFoundAndNeedsASession() throws Exception {
        assertThat(getA("/accounts/oauth/nope/start").status()).isEqualTo(404);
        assertThat(getA("/accounts/oauth/Google/start").status()).isEqualTo(404);
        Resp noSession = send("GET", "/accounts/oauth/google/start", null, Map.of(), null);
        assertThat(noSession.status()).isEqualTo(302);
        assertThat(noSession.header("location")).isEqualTo("/accounts/login");
    }

    @Test
    void theRelinkFormNamesTheAccountOfTheSessionsTenantOnly() throws Exception {
        ResolvedAccount oauth = oauthAccount(A, "2", MailAccount.Status.NEEDS_RELINK);
        passwords.accounts.add(oauth);

        Resp r = getA("/accounts/oauth/google/start?account=" + oauth.handle());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("name=\"account\" value=\"" + oauth.handle() + "\"").contains("Mine")
                .doesNotContain("name=\"label\"").doesNotContain("name=\"address\"");

        Resp foreign = send("GET", "/accounts/oauth/google/start?account=" + oauth.handle(), cookieB, Map.of(), null);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(getA("/accounts/oauth/google/start?account=acc_unknown").status()).isEqualTo(404);
    }

    // ---- start ----

    @Test
    void startAnswers303ToTheProviderWithLoginHintPkceAndTheBindingCookie() throws Exception {
        Map<String, String> form = startForm();
        form.put("tenant", B.value()); // a client-supplied tenant is never used
        form.put("tenant_id", B.value());

        Resp r = postA("/accounts/oauth/google/start", form);

        assertThat(r.status()).isEqualTo(303);
        URI location = URI.create(r.header("location"));
        assertThat(location.getHost()).isEqualTo("accounts.example");
        Map<String, String> q = new HashMap<>();
        for (String kv : location.getRawQuery().split("&")) {
            q.put(kv.substring(0, kv.indexOf('=')),
                    URLDecoder.decode(kv.substring(kv.indexOf('=') + 1), StandardCharsets.UTF_8));
        }
        assertThat(q).containsEntry("login_hint", ADDRESS).containsEntry("response_type", "code")
                .containsEntry("code_challenge_method", "S256").containsEntry("access_type", "offline")
                .containsEntry("prompt", "consent").containsEntry("redirect_uri", GOOGLE.redirectUri());
        String state = stateOf(r);
        assertThat(q).containsEntry("state", state);
        assertThat(r.all("set-cookie")).contains("__Host-upstream=" + state
                + "; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=600");
        assertThat(world.pending.get(state).tenant()).isEqualTo(A);
        assertThat(world.pending.get(state).address()).isEqualTo(ADDRESS);
        assertThat(world.pending.get(state).label()).isEqualTo("Private");
        assertThat(world.pending.get(state).readOnly()).isTrue();
        assertThat(r.header("Cache-Control")).isEqualTo("no-store");
        assertThat(audit.events).isEmpty();
    }

    @Test
    void startWithoutCsrfWithAForeignOriginWithAQueryStringOrWithoutASessionIsForbiddenAndWritesNothing()
            throws Exception {
        Map<String, String> noCsrf = startForm();
        assertThat(send("POST", "/accounts/oauth/google/start", cookieA, Map.of("Origin", origin), noCsrf).status())
                .isEqualTo(403);
        Map<String, String> wrongCsrf = startForm();
        wrongCsrf.put("csrf", csrfB); // the other tenant's token is not this session's
        assertThat(send("POST", "/accounts/oauth/google/start", cookieA, Map.of("Origin", origin), wrongCsrf).status())
                .isEqualTo(403);
        Map<String, String> withCsrf = startForm();
        withCsrf.put("csrf", csrfA);
        assertThat(send("POST", "/accounts/oauth/google/start", cookieA, Map.of("Origin", "https://evil.example"),
                withCsrf).status()).isEqualTo(403);
        assertThat(send("POST", "/accounts/oauth/google/start", cookieA, Map.of("Origin", "null"), withCsrf).status())
                .isEqualTo(403);
        assertThat(send("POST", "/accounts/oauth/google/start?csrf=" + csrfA, cookieA, Map.of("Origin", origin),
                withCsrf).status()).isEqualTo(403);
        assertThat(send("POST", "/accounts/oauth/google/start", null, Map.of("Origin", origin), withCsrf).status())
                .isEqualTo(403);

        assertThat(world.pending).isEmpty();
    }

    @Test
    void startRefusalsAreFixedPagesWithoutACookieAndNothingPending() throws Exception {
        Resp invalid = postA("/accounts/oauth/google/start", Map.of("label", "x", "address", "not-an-address"));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body()).contains("check the fields");
        assertThat(postA("/accounts/oauth/nope/start", startForm()).status()).isEqualTo(404);
        maxAccounts = 1;
        passwords.accounts.add(LinkingPagesTest.account("Work", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa",
                MailAccount.Status.ACTIVE, false));
        world.accounts.add(LinkingPagesTest.account("Work", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa",
                MailAccount.Status.ACTIVE, false));
        start();
        Resp limit = postA("/accounts/oauth/google/start", startForm());
        assertThat(limit.status()).isEqualTo(409);
        assertThat(limit.body()).contains("limit of accounts");
        assertThat(limit.all("set-cookie")).noneMatch(c -> c.startsWith("__Host-upstream="));
        assertThat(world.pending).isEmpty();
    }

    @Test
    void startLimitsAnswer429AndTheFourthPendingFlowIsRefused() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(postA("/accounts/oauth/google/start", startForm()).status()).isEqualTo(303);
        }
        Resp fourth = postA("/accounts/oauth/google/start", startForm());
        assertThat(fourth.status()).isEqualTo(429);
        assertThat(fourth.body()).contains("in progress");
        // another tenant is not affected
        assertThat(send("POST", "/accounts/oauth/google/start", cookieB, Map.of("Origin", origin),
                withCsrfB(startForm())).status()).isEqualTo(303);

        world.pending.clear();
        startBudget = new ProbeRateLimiter(Clock.systemUTC(), 1, Duration.ofHours(1), 100);
        start();
        assertThat(postA("/accounts/oauth/google/start", startForm()).status()).isEqualTo(303);
        Resp limited = postA("/accounts/oauth/google/start", startForm());
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.body()).contains("Too many");
    }

    Map<String, String> withCsrfB(Map<String, String> f) {
        Map<String, String> m = new LinkedHashMap<>(f);
        m.put("csrf", csrfB);
        return m;
    }

    // ---- callback: success ----

    @Test
    void theCallbackSucceedsWithAMetaRefreshPageAndAClearedCookieAndCreatesTheAccountForThePendingTenant()
            throws Exception {
        String state = startAsA();

        Resp r = callback("google", q("state", state) + "&" + q("code", CODE), state);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("<meta http-equiv=\"refresh\" content=\"0;url=/accounts\">")
                .contains("href=\"/accounts\"").contains("linked");
        assertThat(r.all("set-cookie")).contains("__Host-upstream=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0");
        assertThat(r.header("Location")).isNull();
        assertSafePage(r);
        assertThat(world.stored).containsExactly("create:" + A.value() + ":google:" + ADDRESS + ":Private:"
                + REFRESH + ":" + MAIL_SCOPE);
        assertThat(probed).containsExactly(ADDRESS + ":" + ACCESS);
        assertThat(exchanged).hasSize(1).allMatch(x -> x.startsWith("google:" + CODE + ":"));
        assertThat(world.accounts).hasSize(1);
    }

    @Test
    void theCallbackNeedsNoSessionAndNeverTakesTheTenantFromTheRequest() throws Exception {
        String state = startAsA();

        // tenant B's session cookie and a tenant parameter ride along: both are ignored
        Resp r = send("GET", "/accounts/oauth/google/callback?" + q("state", state) + "&" + q("code", CODE) + "&"
                + q("tenant", B.value()), cookieB + "; __Host-upstream=" + state, Map.of(), null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(world.accounts).singleElement().satisfies(a -> assertThat(a.tenantId()).isEqualTo(A));
    }

    @Test
    void anUpstreamRelinkEndToEndWritesANewVersionForTheNamedAccount() throws Exception {
        ResolvedAccount oauth = oauthAccount(A, "2", MailAccount.Status.NEEDS_RELINK);
        passwords.accounts.add(oauth);
        world.accounts.add(oauth);
        Resp start = postA("/accounts/oauth/google/start", Map.of("account", oauth.handle()));
        assertThat(start.status()).isEqualTo(303);
        String state = stateOf(start);

        Resp r = callback("google", q("state", state) + "&" + q("code", CODE), state);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("again");
        assertThat(world.stored).containsExactly("relink:" + A.value() + ":" + oauth.account().id().value() + ":"
                + REFRESH);
        assertThat(probed).containsExactly(ADDRESS + ":" + ACCESS);
    }

    // ---- callback: one test per outcome row ----

    private void assertFailurePage(Resp r, int status, String text, String auditResult) {
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.body()).contains(text).contains("href=\"/accounts\"").doesNotContain("http-equiv=\"refresh\"");
        assertThat(r.all("set-cookie")).contains("__Host-upstream=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0");
        assertSafePage(r);
        assertThat(world.stored).isEmpty(); // nothing is stored on failure
        assertThat(world.accounts).isEmpty();
        if (auditResult == null) {
            assertThat(audit.events).isEmpty();
        } else {
            assertThat(audit.events).singleElement().satisfies(e -> {
                assertThat(e.type()).isEqualTo("link");
                assertThat(e.result()).isEqualTo(auditResult);
                assertThat(e.tenant()).isEqualTo(A);
                assertThat(e.account()).isNull();
            });
        }
    }

    @Test
    void outcomeUnknownStateIsRequestExpiredWithoutAuditRow() throws Exception {
        Resp r = callback("google", q("state", "unknown") + "&" + q("code", CODE), "unknown");

        assertFailurePage(r, 400, "request expired, start again", null);
        assertThat(exchanged).isEmpty();
    }

    @Test
    void outcomeMissingStateParameterAndMissingCookieAreRequestExpired() throws Exception {
        String state = startAsA();
        assertFailurePage(callback("google", q("code", CODE), state), 400, "request expired", null);
        assertFailurePage(callback("google", q("state", state) + "&" + q("code", CODE), null), 400,
                "request expired", null);
        assertThat(exchanged).isEmpty();
    }

    @Test
    void outcomeReplayedStateIsRequestExpired() throws Exception {
        String state = startAsA();
        assertThat(callback("google", q("state", state) + "&" + q("code", CODE), state).status()).isEqualTo(200);
        world.accounts.clear();
        world.stored.clear();

        assertFailurePage(callback("google", q("state", state) + "&" + q("code", CODE), state), 400,
                "request expired", null);
        assertThat(exchanged).hasSize(1);
    }

    @Test
    void outcomeCookieMismatchAndPathMismatchAreRequestExpiredAndBurnTheState() throws Exception {
        String state = startAsA();
        assertFailurePage(callback("google", q("state", state) + "&" + q("code", CODE), "another"), 400,
                "request expired", null);
        // the state is burned: even the right cookie fails now
        assertFailurePage(callback("google", q("state", state) + "&" + q("code", CODE), state), 400,
                "request expired", null);

        String second = startAsA();
        assertFailurePage(callback("other", q("state", second) + "&" + q("code", CODE), second), 400,
                "request expired", null);
        assertThat(exchanged).isEmpty();
    }

    @Test
    void outcomeAnotherTenantsBrowserCannotCompleteTheStateOfTenantA() throws Exception {
        String stateA = startAsA();
        Resp startB = send("POST", "/accounts/oauth/google/start", cookieB, Map.of("Origin", origin),
                withCsrfB(startForm()));
        String stateB = stateOf(startB);

        // B holds B's cookie (or none) and presents A's state
        Resp r = callback("google", q("state", stateA) + "&" + q("code", CODE), stateB);

        assertFailurePage(r, 400, "request expired", null);
        assertThat(exchanged).isEmpty();
        assertThat(world.pending).hasSize(1); // B's own flow is untouched
    }

    @Test
    void outcomeAccessDeniedIsDeniedWithItsAuditResult() throws Exception {
        String state = startAsA();

        Resp r = callback("google", q("state", state) + "&error=access_denied", state);

        assertFailurePage(r, 400, "not granted", "denied");
        assertThat(exchanged).isEmpty();
    }

    @Test
    void outcomeAnyOtherErrorIsAGenericRefusalThatNeverEchoesTheErrorDescription() throws Exception {
        String state = startAsA();

        Resp r = callback("google", q("state", state) + "&error=server_error&" + q("error_description", DESCRIPTION),
                state);

        assertFailurePage(r, 502, "provider refused", "provider_error");
        assertThat(exchanged).isEmpty();
    }

    @Test
    void outcomeExchangeTimeoutOrServerErrorIsProviderUnavailable() throws Exception {
        exchangeResult = new UpstreamCodeExchange.Result.Unavailable();
        String state = startAsA();

        Resp r = callback("google", q("state", state) + "&" + q("code", CODE), state);

        assertFailurePage(r, 503, "provider unavailable, try later", "exchange_failed");
        assertThat(probed).isEmpty();
    }

    @Test
    void outcomeInvalidGrantIsAuthorizationIncomplete() throws Exception {
        exchangeResult = new UpstreamCodeExchange.Result.InvalidGrant();
        String state = startAsA();

        Resp r = callback("google", q("state", state) + "&" + q("code", CODE), state);

        assertFailurePage(r, 400, "authorization incomplete, start again", "exchange_failed");
    }

    @Test
    void outcomeNoRefreshTokenAndAMissingRequiredScopeAreAuthorizationIncomplete() throws Exception {
        exchangeResult = granted(null, null);
        String s1 = startAsA();
        assertFailurePage(callback("google", q("state", s1) + "&" + q("code", CODE), s1), 400,
                "authorization incomplete", "exchange_failed");
        audit.events.clear();

        exchangeResult = granted(REFRESH, "https://other.example/scope");
        String s2 = startAsA();
        assertFailurePage(callback("google", q("state", s2) + "&" + q("code", CODE), s2), 400,
                "authorization incomplete", "exchange_failed");
        assertThat(probed).isEmpty();
    }

    @Test
    void outcomeAFailingProbeIsAGenericRefusal() throws Exception {
        probeResult = LoginProbe.Result.AUTH_FAILED;
        String state = startAsA();

        Resp r = callback("google", q("state", state) + "&" + q("code", CODE), state);

        assertFailurePage(r, 400, "could not be verified with the mail server", "probe_failed");
        assertThat(probed).hasSize(1);
    }

    @Test
    void outcomeTheSameProviderAndAddressAgainIsAlreadyLinkedUseRelink() throws Exception {
        String s1 = startAsA();
        assertThat(callback("google", q("state", s1) + "&" + q("code", CODE), s1).status()).isEqualTo(200);
        world.stored.clear();
        String s2 = startAsA();

        Resp r = callback("google", q("state", s2) + "&" + q("code", CODE), s2);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("already linked; use re-link");
        assertThat(world.stored).isEmpty();
        assertThat(world.accounts).hasSize(1);
        assertThat(audit.events).singleElement().satisfies(e -> assertThat(e.result()).isEqualTo("duplicate"));
    }

    @Test
    void outcomeTheSameAddressInAnotherTenantIsAllowed() throws Exception {
        String s1 = startAsA();
        assertThat(callback("google", q("state", s1) + "&" + q("code", CODE), s1).status()).isEqualTo(200);
        Resp startB = send("POST", "/accounts/oauth/google/start", cookieB, Map.of("Origin", origin),
                withCsrfB(startForm()));
        String sb = stateOf(startB);

        assertThat(callback("google", q("state", sb) + "&" + q("code", CODE), sb).status()).isEqualTo(200);

        assertThat(world.accounts).extracting(ResolvedAccount::tenantId).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void outcomeTheAccountLimitRecheckedInTheTransactionIsLimit() throws Exception {
        String state = startAsA();
        for (int i = 0; i < 10; i++) { // other tabs filled every slot after the start
            world.accounts.add(LinkingPagesTest.account("W" + i, "acc_" + String.valueOf(i).repeat(26),
                    MailAccount.Status.ACTIVE, false));
        }

        Resp r = callback("google", q("state", state) + "&" + q("code", CODE), state);

        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body()).contains("limit of accounts");
        assertThat(world.stored).isEmpty();
        assertThat(audit.events).singleElement().satisfies(e -> assertThat(e.result()).isEqualTo("limit"));
    }

    @Test
    void outcomeAStoreOutageIsUnavailableAndNothingIsStored() throws Exception {
        world.storeFailure = new LinkedAccountStore.UnavailableException();
        String state = startAsA();

        Resp r = callback("google", q("state", state) + "&" + q("code", CODE), state);

        assertFailurePage(r, 503, "did not work right now", "unavailable");
    }

    @Test
    void theCallbackOnlyAcceptsGetAndAnUnknownProviderIsNotFound() throws Exception {
        String state = startAsA();
        Resp post = send("POST", "/accounts/oauth/google/callback", "__Host-upstream=" + state,
                Map.of("Origin", origin), Map.of("state", state, "code", CODE));
        assertThat(post.status()).isIn(403, 405);
        assertThat(world.pending).hasSize(1); // nothing was consumed

        assertThat(callback("nope", q("state", state) + "&" + q("code", CODE), state).status()).isEqualTo(404);
        assertThat(getA("/accounts/oauth/Google/callback?" + q("state", state)).status()).isEqualTo(404);
    }

    // ---- no upstream configured ----

    @Test
    void withoutAnUpstreamLinkerTheRoutesAreNotFound() throws Exception {
        server.stop();
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        origin = "https://127.0.0.1:" + port;
        WebConfig config = new WebConfig(URI.create(origin), "https://idp.example/realms/x", "client",
                () -> new Secret("s".toCharArray()), "sub", "imap-user", null, false);
        server = HttpLocalServer.builder().port(port).webSurface(WebSurface.of(config, null,
                new InMemoryStores.Pending(), sessions, new InMemoryStores.Tenants(new RecordingAuditLog()),
                passwords, Clock.systemUTC())).build();
        server.start();

        assertThat(getA("/accounts/oauth/google/start").status()).isEqualTo(404);
        assertThat(callback("google", q("state", "x"), "x").status()).isEqualTo(404);
        assertThat(getA("/accounts").body()).doesNotContain("/accounts/oauth/");
    }

    // ---- logs ----

    @Test
    void noLogLineHoldsACodeVerifierTokenStateErrorDescriptionOrAddressAcrossTheWholeFlow() throws Exception {
        // success, then every failure path
        String ok = startAsA();
        String verifier = world.pending.get(ok).pkceVerifier();
        callback("google", q("state", ok) + "&" + q("code", CODE), ok);
        exchangeResult = new UpstreamCodeExchange.Result.InvalidGrant();
        String s1 = startAsA();
        callback("other", q("state", s1) + "&" + q("code", CODE), s1);
        String s2 = startAsA();
        callback("google", q("state", s2) + "&error=server_error&" + q("error_description", DESCRIPTION), s2);
        exchangeResult = granted(REFRESH, null);
        probeResult = LoginProbe.Result.AUTH_FAILED;
        String s3 = startAsA();
        callback("google", q("state", s3) + "&" + q("code", CODE), s3);
        world.storeFailure = new LinkedAccountStore.UnavailableException();
        probeResult = LoginProbe.Result.OK;
        String s4 = startAsA();
        callback("google", q("state", s4) + "&" + q("code", CODE), s4);

        String all = String.join("\n", logs.stream().filter(e -> e.getLoggerName().startsWith("de.alexanderlindhorst"))
                .map(e -> e.getFormattedMessage() + " " + e.getThrowableProxy()).toList());
        for (String canary : new String[] {CODE, ACCESS, REFRESH, DESCRIPTION, verifier, ok, s1, s2, s3, s4,
                ADDRESS}) {
            assertThat(all).as("log must not hold %s", canary).doesNotContain(canary);
        }
    }
}
