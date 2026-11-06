package de.alexanderlindhorst.imapmcp.transport.http.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The linking surface end to end against a scripted IdP: login, callback, session, CSRF, headers (C1). */
class AccountsFlowTest {

    static final String CSP = "default-src 'none'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

    FakeIdp idp;
    InMemoryStores.Sessions sessions;
    InMemoryStores.Pending pending;
    InMemoryStores.Tenants tenants;
    de.alexanderlindhorst.imapmcp.core.audit.RecordingAuditLog audit;
    HttpLocalServer server;
    int port;
    String origin;
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    /** Collects events from the server threads without a concurrent-modification risk. */
    static final class CollectingAppender extends ch.qos.logback.core.AppenderBase<ILoggingEvent> {
        final java.util.List<ILoggingEvent> list = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            list.add(event);
        }
    }

    CollectingAppender logs;

    @BeforeEach
    void setUp() throws Exception {
        idp = new FakeIdp();
        sessions = new InMemoryStores.Sessions();
        pending = new InMemoryStores.Pending();
        audit = new de.alexanderlindhorst.imapmcp.core.audit.RecordingAuditLog();
        tenants = new InMemoryStores.Tenants(audit);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs = new CollectingAppender();
        logs.start();
        root.addAppender(logs);
        root.setLevel(Level.DEBUG);
        start(c -> c);
    }

    @AfterEach
    void tearDown() throws Exception {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        if (server != null) {
            server.stop();
        }
        idp.close();
    }

    record Config(String requiredRole, boolean allowWithoutRole) {
    }

    void start(UnaryOperator<Config> variant) throws Exception {
        if (server != null) {
            server.stop();
        }
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        origin = "https://127.0.0.1:" + port;
        Config v = variant.apply(new Config("imap-user", false));
        WebConfig config = new WebConfig(URI.create(origin), idp.issuer, FakeIdp.CLIENT_ID,
                () -> new Secret(FakeIdp.CLIENT_SECRET.toCharArray()), "sub", v.requiredRole(), "realm_access.roles",
                v.allowWithoutRole(), true);
        OidcProvider provider = HttpOidcProvider.create(config,
                new ImmutableJWKSet<>(new JWKSet(idp.key.toPublicJWK())));
        server = HttpLocalServer.builder().port(port)
                .webSurface(WebSurface.of(config, provider, pending, sessions, tenants, new LinkingPagesTest.FakeLinker(),
                        Clock.systemUTC())).build();
        server.start();
    }

    // ---- HTTP helpers ----

    record Resp(int status, Map<String, List<String>> headers, String body) {
        String header(String name) {
            List<String> v = headers.get(name.toLowerCase());
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        List<String> setCookies() {
            return headers.getOrDefault("set-cookie", List.of());
        }

        String cookieHeader(String name) {
            return setCookies().stream().filter(c -> c.startsWith(name + "=")).findFirst().orElse(null);
        }

        String cookieValue(String name) {
            String c = cookieHeader(name);
            return c == null ? null : c.substring(name.length() + 1, c.indexOf(';') < 0 ? c.length() : c.indexOf(';'));
        }
    }

    Resp send(String method, String path, String cookie, Map<String, String> extraHeaders, String formBody)
            throws Exception {
        return sendTo("http://127.0.0.1:" + port + path, method, cookie, extraHeaders, formBody);
    }

    Resp sendTo(String url, String method, String cookie, Map<String, String> extraHeaders, String formBody)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        extraHeaders.forEach(b::header);
        if (formBody != null) {
            b.header("Content-Type", "application/x-www-form-urlencoded");
            b.method(method, HttpRequest.BodyPublishers.ofString(formBody));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.headers().map(), r.body());
    }

    Resp get(String path, String cookie) throws Exception {
        return send("GET", path, cookie, Map.of(), null);
    }

    static Map<String, String> query(String uri) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = URI.create(uri).getRawQuery();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    /** The redirect of GET /accounts/login, its query and the binding cookie. */
    record Started(Resp resp, Map<String, String> query) {
        String state() {
            return query.get("state");
        }

        String bindingCookie() {
            return "__Host-oidc=" + resp.cookieValue("__Host-oidc");
        }
    }

    Started startLogin() throws Exception {
        Resp r = get("/accounts/login", null);
        return new Started(r, r.status() == 302 ? query(r.header("location")) : Map.of());
    }

    Resp callback(Started s, String code) throws Exception {
        idp.nonce = s.query().get("nonce");
        return get("/accounts/callback?code=" + code + "&state=" + URLEncoder.encode(s.state(), StandardCharsets.UTF_8),
                s.bindingCookie());
    }

    /** A completed login; returns the session cookie header value pair for later requests. */
    String login() throws Exception {
        Resp r = callback(startLogin(), "good-code");
        assertThat(r.status()).isEqualTo(200);
        return "__Host-session=" + r.cookieValue("__Host-session");
    }

    String csrfOf(String sessionCookie) {
        return sessions.find(sessionCookie.substring("__Host-session=".length())).orElseThrow().csrfToken();
    }

    void assertSecurityHeaders(Resp r) {
        assertThat(r.header("Cache-Control")).isEqualTo("no-store");
        assertThat(r.header("Content-Security-Policy")).isEqualTo(CSP);
        assertThat(r.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.header("Referrer-Policy")).isEqualTo("no-referrer");
    }

    // ---- login redirect ----

    @Test
    void loginRedirectsToTheIdpWithCodeFlowPkceStateAndNonce() throws Exception {
        Started s = startLogin();

        assertThat(s.resp().status()).isEqualTo(302);
        assertThat(s.resp().header("location")).startsWith(idp.authorizationEndpoint() + "?");
        Map<String, String> q = s.query();
        assertThat(q).containsEntry("response_type", "code").containsEntry("client_id", FakeIdp.CLIENT_ID)
                .containsEntry("redirect_uri", origin + "/accounts/callback").containsEntry("scope", "openid")
                .containsEntry("code_challenge_method", "S256");
        assertThat(q.get("state")).matches("[A-Za-z0-9_-]{43}");
        assertThat(q.get("nonce")).matches("[A-Za-z0-9_-]{22,}");
        assertThat(q.get("code_challenge")).matches("[A-Za-z0-9_-]{43}");
        assertThat(q).doesNotContainKey("client_secret").doesNotContainKey("prompt");
        assertThat(pending.size()).isEqualTo(1);
        assertThat(s.resp().cookieHeader("__Host-oidc"))
                .isEqualTo("__Host-oidc=" + s.state() + "; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=600");
        assertSecurityHeaders(s.resp());
    }

    @Test
    void reauthAsksTheIdpToPromptForCredentialsAgain() throws Exception {
        Resp r = get("/accounts/login?reauth=1", null);

        assertThat(query(r.header("location"))).containsEntry("prompt", "login").containsEntry("max_age", "0");
    }

    @Test
    void aHeadRequestStartsNoLogin() throws Exception {
        Resp r = send("HEAD", "/accounts/login", null, Map.of(), null);

        assertThat(r.status()).isEqualTo(405);
        assertThat(pending.size()).isZero();
    }

    @Test
    void theSessionKeepsTheTokensAuthTimeAndAMissingOrFutureOneIsNeverFresh() throws Exception {
        long authTime = System.currentTimeMillis() / 1000 - 120;
        idp.claims = b -> b.claim("auth_time", authTime);
        callback(startLogin(), "c");
        assertThat(sessions.byId.values()).singleElement().satisfies(ws ->
                assertThat(ws.authTime()).isEqualTo(java.time.Instant.ofEpochSecond(authTime)));

        sessions.byId.clear();
        idp.claims = b -> b.claim("auth_time", null);
        callback(startLogin(), "c");
        assertThat(sessions.byId.values()).singleElement().satisfies(ws ->
                assertThat(ws.authTime()).isEqualTo(java.time.Instant.EPOCH));

        sessions.byId.clear();
        idp.claims = b -> b.claim("auth_time", System.currentTimeMillis() / 1000 + 3600);
        callback(startLogin(), "c");
        assertThat(sessions.byId.values()).singleElement().satisfies(ws ->
                assertThat(ws.authTime()).isEqualTo(java.time.Instant.EPOCH));
    }

    @Test
    void theLoginsStateAndNonceAreFreshEachTime() throws Exception {
        Started a = startLogin();
        Started b = startLogin();

        assertThat(a.state()).isNotEqualTo(b.state());
        assertThat(a.query().get("nonce")).isNotEqualTo(b.query().get("nonce"));
        assertThat(a.query().get("code_challenge")).isNotEqualTo(b.query().get("code_challenge"));
    }

    @Test
    void tooManyPendingLoginsGiveA503() throws Exception {
        pending.limit = 1;
        startLogin();

        Resp r = get("/accounts/login", null);

        assertThat(r.status()).isEqualTo(503);
        assertSecurityHeaders(r);
    }

    // ---- callback ----

    @Test
    void aSuccessfulCallbackExchangesTheCodeWithPkceAndTheClientSecretAndStartsASession() throws Exception {
        Started s = startLogin();

        Resp r = callback(s, "good-code");

        assertThat(r.status()).isEqualTo(200);
        FakeIdp.TokenRequest t = idp.lastTokenRequest();
        assertThat(t.authorization()).isEqualTo(FakeIdp.basic(FakeIdp.CLIENT_ID, FakeIdp.CLIENT_SECRET));
        assertThat(t.form()).containsEntry("grant_type", "authorization_code").containsEntry("code", "good-code")
                .containsEntry("redirect_uri", origin + "/accounts/callback");
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(t.form().get("code_verifier").getBytes(StandardCharsets.US_ASCII));
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
                .isEqualTo(s.query().get("code_challenge"));
        assertThat(t.form()).doesNotContainKey("client_secret");
        assertThat(tenants.created).isEqualTo(1);
        assertThat(sessions.size()).isEqualTo(1);
        assertThat(pending.size()).isZero();
        String sessionCookie = r.cookieHeader("__Host-session");
        assertThat(sessionCookie).matches("__Host-session=[A-Za-z0-9_-]{43}; Path=/; Secure; HttpOnly; "
                + "SameSite=Strict; Max-Age=28800");
        assertThat(r.cookieHeader("__Host-oidc")).startsWith("__Host-oidc=; ").contains("Max-Age=0");
        assertThat(r.body()).contains("http-equiv=\"refresh\"").contains("url=/accounts").doesNotContain("<script")
                .contains("<a href=\"/accounts\">Continue</a>");
        assertSecurityHeaders(r);
    }

    @Test
    void theSessionIsForTheTenantOfTheTokensIssuerAndSubject() throws Exception {
        login();

        assertThat(tenants.find(idp.issuer, "alice")).isPresent();
        assertThat(sessions.byId.values()).singleElement()
                .satisfies(ws -> assertThat(ws.tenantId()).isEqualTo(tenants.find(idp.issuer, "alice").get().tenantId()));
    }

    @Test
    void creatingATenantWritesATenantCreatedRowWithoutSubjectOrIssuer() throws Exception {
        login();

        assertThat(audit.events).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("tenant_created");
            assertThat(e.result()).isEqualTo("ok");
            assertThat(e.tenant()).isEqualTo(tenants.find(idp.issuer, "alice").get().tenantId());
            assertThat(e.toString()).doesNotContain("alice").doesNotContain(idp.issuer);
        });
    }

    @Test
    void aFailingAuditWriteRefusesTheLoginFailClosed() throws Exception {
        audit.failEvent = true;

        Resp r = callback(startLogin(), "good-code");

        assertThat(r.status()).isEqualTo(503);
        assertThat(sessions.size()).isZero();
        assertThat(r.cookieHeader("__Host-session")).isNull();
        assertThat(tenants.find(idp.issuer, "alice")).isEmpty(); // no row, so no tenant either
    }

    @Test
    void anExistingTenantWritesNoTenantCreatedRow() throws Exception {
        tenants.create(idp.issuer, "alice", "setup");

        callback(startLogin(), "good-code");

        assertThat(audit.events).isEmpty();
    }

    @Test
    void withoutTheRequiredRoleNothingIsCreatedAndThereIsNoSession() throws Exception {
        idp.claims = b -> b.claim("realm_access", Map.of("roles", List.of("offline_access")));

        Resp r = callback(startLogin(), "good-code");

        assertThat(r.status()).isEqualTo(403);
        assertThat(tenants.created).isZero();
        assertThat(sessions.size()).isZero();
        assertThat(r.cookieHeader("__Host-session")).isNull();
        assertSecurityHeaders(r);
    }

    @Test
    void anExistingTenantMayLogInWithoutTheRoleBecauseTheRoleOnlyGuardsCreation() throws Exception {
        tenants.create(idp.issuer, "alice", "setup");
        idp.claims = b -> b.claim("realm_access", Map.of("roles", List.of()));

        Resp r = callback(startLogin(), "good-code");

        assertThat(r.status()).isEqualTo(200);
        assertThat(tenants.created).isEqualTo(1); // only the one set up by the test
        assertThat(sessions.size()).isEqualTo(1);
    }

    @Test
    void withoutARoleCheckConfiguredTheTenantIsCreatedForAnyone() throws Exception {
        start(c -> new Config(null, true));
        idp.claims = b -> b.claim("realm_access", null);

        Resp r = callback(startLogin(), "good-code");

        assertThat(r.status()).isEqualTo(200);
        assertThat(tenants.created).isEqualTo(1);
    }

    @Test
    void aDisabledTenantGetsNoSession() throws Exception {
        tenants.create(idp.issuer, "alice", "setup");
        tenants.disable(idp.issuer, "alice");

        Resp r = callback(startLogin(), "good-code");

        assertThat(r.status()).isEqualTo(403);
        assertThat(sessions.size()).isZero();
        assertThat(r.cookieHeader("__Host-session")).isNull();
    }

    @Test
    void anUnknownStateIsRejectedBeforeAnyTokenRequest() throws Exception {
        Resp r = get("/accounts/callback?code=c&state=" + "A".repeat(43), "__Host-oidc=" + "A".repeat(43));

        assertThat(r.status()).isEqualTo(400);
        assertThat(idp.tokenRequestCount()).isZero();
        assertSecurityHeaders(r);
    }

    @Test
    void aStateCannotBeUsedTwice() throws Exception {
        Started s = startLogin();
        assertThat(callback(s, "good-code").status()).isEqualTo(200);
        int tokenCalls = idp.tokenRequestCount();

        Resp replay = callback(s, "good-code");

        assertThat(replay.status()).isEqualTo(400);
        assertThat(idp.tokenRequestCount()).isEqualTo(tokenCalls);
        assertThat(sessions.size()).isEqualTo(1);
    }

    @Test
    void theCallbackNeedsTheBindingCookieOfTheBrowserThatStartedTheLogin() throws Exception {
        Started s = startLogin();
        idp.nonce = s.query().get("nonce");
        String callbackPath = "/accounts/callback?code=c&state=" + s.state();

        assertThat(get(callbackPath, null).status()).isEqualTo(400);
        assertThat(idp.tokenRequestCount()).isZero();
        // the state was burned by the failed attempt, so even the right cookie does not help afterwards
        assertThat(get(callbackPath, s.bindingCookie()).status()).isEqualTo(400);
        // another browser's cookie (login CSRF: the attacker's state in the victim's browser)
        Started s2 = startLogin();
        assertThat(get("/accounts/callback?code=c&state=" + s2.state(), "__Host-oidc=" + "B".repeat(43)).status())
                .isEqualTo(400);
        assertThat(idp.tokenRequestCount()).isZero();
        assertThat(sessions.size()).isZero();
    }

    @Test
    void anIdpErrorIsAnswered400WithoutEchoingIt() throws Exception {
        Started s = startLogin();

        Resp r = get("/accounts/callback?error=access_denied&error_description=%3Cscript%3Ealert(1)%3C/script%3E"
                + "&state=" + s.state(), s.bindingCookie());

        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body()).doesNotContain("script").doesNotContain("access_denied");
        assertThat(idp.tokenRequestCount()).isZero();
        assertThat(sessions.size()).isZero();
    }

    @Test
    void aFailingTokenEndpointIsA502WithoutTheIdpsText() throws Exception {
        idp.tokenStatus = 400;

        Resp r = callback(startLogin(), "bad-code");

        assertThat(r.status()).isEqualTo(502);
        assertThat(r.body()).doesNotContain("idp-detail-canary").doesNotContain("invalid_grant");
        assertThat(sessions.size()).isZero();
        assertSecurityHeaders(r);
    }

    @Test
    void aTokenResponseWithoutAnIdTokenOrWithABadOneStartsNoSession() throws Exception {
        idp.omitIdToken = true;
        assertThat(callback(startLogin(), "c").status()).isEqualTo(502);
        idp.omitIdToken = false;

        idp.claims = b -> b.audience("another-client");
        assertThat(callback(startLogin(), "c").status()).isIn(400, 502);
        idp.claims = b -> b.issuer("https://evil.example");
        assertThat(callback(startLogin(), "c").status()).isIn(400, 502);
        idp.claims = b -> b.claim("nonce", "not-the-nonce");
        assertThat(callback(startLogin(), "c").status()).isIn(400, 502);
        idp.claims = b -> { };
        idp.signingKey = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("idp-1").generate();
        assertThat(callback(startLogin(), "c").status()).isIn(400, 502);

        assertThat(sessions.size()).isZero();
        assertThat(tenants.created).isZero();
    }

    // ---- session, CSRF, logout ----

    @Test
    void thePagesNeedASessionAndOtherwiseSendYouToLogin() throws Exception {
        for (String cookie : new String[] {null, "__Host-session=" + "A".repeat(43), "session=" + "A".repeat(43)}) {
            Resp r = get("/accounts", cookie);
            assertThat(r.status()).isEqualTo(302);
            assertThat(r.header("location")).isEqualTo("/accounts/login");
            assertSecurityHeaders(r);
        }
    }

    @Test
    void aValidSessionSeesTheHomePageWithALogoutFormCarryingTheCsrfToken() throws Exception {
        String cookie = login();

        Resp r = get("/accounts", cookie);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("action=\"/accounts/logout\"").contains("method=\"post\"")
                .contains("name=\"csrf\" value=\"" + csrfOf(cookie) + "\"").doesNotContain("<script");
        assertThat(r.header("Content-Type")).isEqualTo("text/html;charset=utf-8");
        assertSecurityHeaders(r);
    }

    @Test
    void twoSessionCookiesAtOnceCountAsNone() throws Exception {
        String cookie = login();

        Resp r = get("/accounts", cookie + "; " + cookie);

        assertThat(r.status()).isEqualTo(302);
    }

    @Test
    void aPostWithoutOrWithAWrongCsrfTokenIsForbiddenAndChangesNothing() throws Exception {
        String cookie = login();

        for (String body : new String[] {"", "csrf=", "csrf=wrong", "csrf=" + csrfOf(cookie) + "x",
                "other=" + csrfOf(cookie)}) {
            Resp r = send("POST", "/accounts/logout", cookie, Map.of(), body);
            assertThat(r.status()).as(body).isEqualTo(403);
            assertSecurityHeaders(r);
        }
        assertThat(sessions.size()).isEqualTo(1);
    }

    @Test
    void aPostFromAnotherOriginIsForbiddenEvenWithTheRightToken() throws Exception {
        String cookie = login();
        String csrf = "csrf=" + csrfOf(cookie);

        for (String o : new String[] {"https://evil.example", "null", "http://127.0.0.1:" + port, origin + ".evil"}) {
            Resp r = send("POST", "/accounts/logout", cookie, Map.of("Origin", o), csrf);
            assertThat(r.status()).as(o).isEqualTo(403);
        }
        assertThat(sessions.size()).isEqualTo(1);
    }

    @Test
    void logoutWithTheTokenAndThePublicOriginEndsTheSessionAndClearsTheCookie() throws Exception {
        String cookie = login();

        Resp r = send("POST", "/accounts/logout", cookie, Map.of("Origin", origin), "csrf=" + csrfOf(cookie));

        assertThat(r.status()).isEqualTo(200);
        assertThat(sessions.size()).isZero();
        assertThat(r.cookieHeader("__Host-session")).startsWith("__Host-session=; ").contains("Max-Age=0")
                .contains("Secure").contains("HttpOnly").contains("SameSite=Strict");
        assertThat(get("/accounts", cookie).status()).isEqualTo(302);
        assertSecurityHeaders(r);
    }

    @Test
    void anAbsentOriginIsAcceptedBecauseTheTokenStillHolds() throws Exception {
        String cookie = login();

        Resp r = send("POST", "/accounts/logout", cookie, Map.of(), "csrf=" + csrfOf(cookie));

        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    void theCsrfTokenIsReadFromTheBodyNeverFromTheQuery() throws Exception {
        String cookie = login();
        String csrf = csrfOf(cookie);

        Resp inQuery = send("POST", "/accounts/logout?csrf=" + csrf, cookie, Map.of(), "");
        Resp both = send("POST", "/accounts/logout?x=1", cookie, Map.of(), "csrf=" + csrf);

        assertThat(inQuery.status()).isEqualTo(403);
        assertThat(both.status()).isEqualTo(403); // a query string on a POST is refused altogether
        assertThat(sessions.size()).isEqualTo(1);
    }

    @Test
    void methodsOtherThanGetHeadAndPostAreRefusedByTheFilter() throws Exception {
        String cookie = login();

        for (String m : new String[] {"PUT", "DELETE", "PATCH", "OPTIONS"}) {
            Resp r = send(m, "/accounts", cookie, Map.of(), m.equals("OPTIONS") ? null : "csrf=x");
            assertThat(r.status()).as(m).isEqualTo(405);
            assertSecurityHeaders(r);
        }
    }

    @Test
    void aPostWithoutASessionIsForbiddenNotRedirected() throws Exception {
        Resp r = send("POST", "/accounts/logout", null, Map.of(), "csrf=x");

        assertThat(r.status()).isEqualTo(403);
    }

    // ---- request guards and headers ----

    @Test
    void aForeignHostHeaderIsRejected() throws Exception {
        Resp r = sendTo("http://localhost:" + port + "/accounts/login", "GET", null, Map.of(), null);

        assertThat(r.status()).isEqualTo(403);
        assertSecurityHeaders(r);
        assertThat(pending.size()).isZero();
    }

    @Test
    void wrongMethodsAndUnknownPathsAreRefusedWithTheSecurityHeaders() throws Exception {
        String cookie = login();

        Resp post = send("POST", "/accounts/login", null, Map.of(), "");
        Resp getLogout = get("/accounts/logout", cookie);
        Resp unknown = get("/accounts/nothing-here", cookie);

        assertThat(post.status()).isEqualTo(405);
        assertThat(getLogout.status()).isEqualTo(405);
        assertThat(unknown.status()).isEqualTo(404);
        assertSecurityHeaders(getLogout);
        assertSecurityHeaders(unknown);
    }

    @Test
    void theMcpPathIsNotServedByTheLinkingSurface() throws Exception {
        Resp r = get("/mcp", null);

        assertThat(r.body()).doesNotContain("Signed in");
    }

    // ---- logging ----

    @Test
    void nothingSensitiveIsLoggedAcrossSuccessAndFailures() throws Exception {
        Started s = startLogin();
        callback(s, "code-canary");
        idp.tokenStatus = 400;
        callback(startLogin(), "code-canary-2");
        idp.tokenStatus = 200;
        get("/accounts/callback?code=zzz&state=" + s.state(), s.bindingCookie());
        String sessionCookie = "__Host-session=" + "Q".repeat(43);
        get("/accounts", sessionCookie);

        // our own loggers; DEBUG of the JDK and Jetty internals is not ours (and off outside this test)
        String all = String.join("\n", logs.list.stream().filter(e -> e.getLoggerName().startsWith("de.alexanderlindhorst"))
                .map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).doesNotContain(FakeIdp.CLIENT_SECRET).doesNotContain("code-canary").doesNotContain(s.state())
                .doesNotContain(s.query().get("nonce")).doesNotContain("at-canary").doesNotContain("rt-canary")
                .doesNotContain("alice").doesNotContain("idp-detail-canary").doesNotContain("Q".repeat(43));
        assertThat(logs.list).noneMatch(e -> e.getThrowableProxy() != null && e.getLoggerName().contains("imapmcp"));
    }
}
