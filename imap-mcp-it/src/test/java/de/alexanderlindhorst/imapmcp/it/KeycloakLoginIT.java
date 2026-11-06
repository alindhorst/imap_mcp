package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.imap.LoginProbe;
import de.alexanderlindhorst.imapmcp.core.linking.AccountLinking;
import de.alexanderlindhorst.imapmcp.core.linking.ProbeRateLimiter;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.LocalKeyWrapper;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAuditLog;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcCredentialStore;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcLinkedAccountStore;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcOidcPendingStore;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantDirectory;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantProvisioner;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcWebSessionStore;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import de.alexanderlindhorst.imapmcp.transport.http.web.HttpOidcProvider;
import de.alexanderlindhorst.imapmcp.transport.http.web.WebConfig;
import de.alexanderlindhorst.imapmcp.transport.http.web.WebSurface;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The OIDC login of the linking surface against a real Keycloak, with the JDBC stores on Postgres (C1, SPEC 5.8):
 * the "browser" is an HTTP client that follows the authorization redirect, fills in Keycloak's login form and
 * delivers the code to our callback.
 */
class KeycloakLoginIT {

    private static KeycloakFixture keycloak;
    private static PostgresFixture postgres;
    private static Database db;
    private static HttpLocalServer server;
    private static final int MAX_ACCOUNTS = 3;
    private static int port;
    private static String origin;
    private static JdbcAccountRepository accountRepo;
    private static JdbcCredentialStore credentialStore;
    /** The probe is scripted here (the real one is tested against GreenMail in core); SSRF and the rest are real. */
    private static final java.util.List<String> PROBED = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile LoginProbe.Result probeResult = LoginProbe.Result.OK;

    @BeforeAll
    static void start() throws Exception {
        keycloak = new KeycloakFixture();
        keycloak.start();
        postgres = new PostgresFixture();
        postgres.start();
        db = Database.open(postgres.config());
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        origin = "https://127.0.0.1:" + port;
        WebConfig config = new WebConfig(URI.create(origin), keycloak.issuer(), KeycloakFixture.CLIENT_ID,
                () -> new Secret(KeycloakFixture.CLIENT_SECRET.toCharArray()), "sub", "imap-user",
                "realm_access.roles", false, true);
        com.nimbusds.jose.jwk.source.JWKSource<SecurityContext> keys =
                JWKSourceBuilder.<SecurityContext>create(new URL(keycloak.certsUrl())).build();
        Clock clock = Clock.systemUTC();
        byte[] kek = new byte[32];
        java.util.Arrays.fill(kek, (byte) 4);
        byte[] master = new byte[32];
        java.util.Arrays.fill(master, (byte) 1);
        KeyRing ring = new KeyRing(1, java.util.Map.of(1, master));
        credentialStore = new JdbcCredentialStore(db.dataSource(), new CredentialCipher(
                new LocalKeyWrapper("local-1", java.util.Map.of("local-1", kek)), new java.security.SecureRandom()),
                new LogIds(ring), MetricsRecorder.NOOP);
        accountRepo = new JdbcAccountRepository(db.dataSource());
        LoginProbe probe = (account, password) -> {
            PROBED.add(account.host() + ":" + account.port() + ":" + new String(password));
            return probeResult;
        };
        AccountLinking linking = new AccountLinking(accountRepo,
                new JdbcLinkedAccountStore(db.dataSource(), accountRepo, credentialStore), probe,
                SsrfPolicy.of(java.util.List.of(), true, java.util.List.of()), new JdbcAuditLog(db.dataSource(), ring),
                new ProbeRateLimiter(clock, 10, java.time.Duration.ofHours(1), 100), clock, MAX_ACCOUNTS, true);
        server = HttpLocalServer.builder().port(port).webSurface(WebSurface.of(config,
                HttpOidcProvider.create(config, keys), new JdbcOidcPendingStore(db.dataSource(), clock),
                new JdbcWebSessionStore(db.dataSource(), clock),
                new JdbcTenantProvisioner(new JdbcTenantDirectory(db.dataSource())), linking, clock)).build();
        server.start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (db != null) {
            db.close();
        }
        postgres.stop();
        keycloak.stop();
    }

    private final HttpClient noRedirect = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> ours(String pathAndQuery, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pathAndQuery));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return noRedirect.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** What the browser ends up with after the IdP step: the callback URL (path and query) or null. */
    private record IdpResult(String callbackPathAndQuery, String bindingCookie, String loginPage) {
    }

    /** Starts a login at our server and plays the browser at Keycloak's login form. */
    private IdpResult browserLogin(String username, String password) throws Exception {
        HttpResponse<String> start = ours("/accounts/login", null);
        assertThat(start.statusCode()).isEqualTo(302);
        String binding = start.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        // Keycloak marks its cookies Secure even over http, which a CookieManager would not send back: hand-made jar.
        Map<String, String> jar = new java.util.LinkedHashMap<>();
        HttpClient kc = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> page = kc.send(HttpRequest.newBuilder(URI.create(
                start.headers().firstValue("location").orElseThrow())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        remember(jar, page);
        assertThat(page.statusCode()).isEqualTo(200);
        Matcher m = Pattern.compile("action=\"([^\"]+)\"").matcher(page.body());
        assertThat(m.find()).as("login form").isTrue();
        String action = m.group(1).replace("&amp;", "&");
        String form = "username=" + enc(username) + "&password=" + enc(password) + "&credentialId=";
        HttpResponse<String> posted = kc.send(HttpRequest.newBuilder(URI.create(action))
                .header("Cookie", cookieHeader(jar)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (posted.statusCode() != 302) {
            return new IdpResult(null, binding, posted.body());
        }
        URI back = URI.create(posted.headers().firstValue("location").orElseThrow());
        assertThat(back.toString()).startsWith(origin + "/accounts/callback?");
        return new IdpResult(back.getRawPath() + "?" + back.getRawQuery(), binding, null);
    }

    private static void remember(Map<String, String> jar, HttpResponse<?> r) {
        for (String c : r.headers().allValues("set-cookie")) {
            String pair = c.split(";")[0];
            int eq = pair.indexOf('=');
            jar.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
    }

    private static String cookieHeader(Map<String, String> jar) {
        return jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int count(String sql, Object... params) throws Exception {
        try (Connection c = db.dataSource().getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void aUserWithTheRoleLogsInGetsATenantAndASessionAndCanLogOut() throws Exception {
        IdpResult idp = browserLogin(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);

        HttpResponse<String> callback = ours(idp.callbackPathAndQuery(), idp.bindingCookie());

        assertThat(callback.statusCode()).isEqualTo(200);
        List<String> cookies = callback.headers().allValues("set-cookie");
        String session = cookies.stream().filter(c -> c.startsWith("__Host-session=")).findFirst().orElseThrow();
        assertThat(session).contains("Secure").contains("HttpOnly").contains("SameSite=Strict").contains("Path=/")
                .doesNotContain("Domain");
        assertThat(count("SELECT count(*) FROM tenant WHERE issuer = ?", keycloak.issuer())).isGreaterThanOrEqualTo(1);
        String sessionCookie = session.split(";")[0];
        HttpResponse<String> home = ours("/accounts", sessionCookie);
        assertThat(home.statusCode()).isEqualTo(200);
        Matcher csrf = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(home.body());
        assertThat(csrf.find()).isTrue();
        HttpResponse<String> out = noRedirect.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/accounts/logout")).header("Cookie", sessionCookie).header("Origin", origin)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("csrf=" + csrf.group(1))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(out.statusCode()).isEqualTo(200);
        assertThat(ours("/accounts", sessionCookie).statusCode()).isEqualTo(302);
    }

    @Test
    void aUserWithoutTheRoleGetsNoTenantAndNoSession() throws Exception {
        int before = count("SELECT count(*) FROM tenant");
        IdpResult idp = browserLogin(KeycloakFixture.BOB, KeycloakFixture.BOB_PASSWORD);

        HttpResponse<String> callback = ours(idp.callbackPathAndQuery(), idp.bindingCookie());

        assertThat(callback.statusCode()).isEqualTo(403);
        assertThat(callback.headers().allValues("set-cookie")).noneMatch(c -> c.startsWith("__Host-session=")
                && !c.startsWith("__Host-session=;"));
        assertThat(count("SELECT count(*) FROM tenant")).isEqualTo(before);
    }

    @Test
    void aWrongPasswordNeverReachesOurCallback() throws Exception {
        IdpResult idp = browserLogin(KeycloakFixture.ALICE, "not-the-password");

        assertThat(idp.callbackPathAndQuery()).isNull();
        assertThat(idp.loginPage()).isNotBlank();
    }

    @Test
    void aReplayedCallbackIsRefused() throws Exception {
        IdpResult idp = browserLogin(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);
        assertThat(ours(idp.callbackPathAndQuery(), idp.bindingCookie()).statusCode()).isEqualTo(200);

        assertThat(ours(idp.callbackPathAndQuery(), idp.bindingCookie()).statusCode()).isEqualTo(400);
    }

    @Test
    void aCallbackWithoutTheBindingCookieOfTheBrowserIsRefused() throws Exception {
        IdpResult idp = browserLogin(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);

        assertThat(ours(idp.callbackPathAndQuery(), null).statusCode()).isEqualTo(400);
    }

    // ---- linking pages (C2) ----

    /** A signed-in user: the session cookie pair and the CSRF token of its home page. */
    private record Session(String cookie, String csrf, String tenantId) {
    }

    private Session signIn(String user, String password) throws Exception {
        IdpResult idp = browserLogin(user, password);
        HttpResponse<String> callback = ours(idp.callbackPathAndQuery(), idp.bindingCookie());
        assertThat(callback.statusCode()).isEqualTo(200);
        String cookie = callback.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("__Host-session=")).findFirst().orElseThrow().split(";")[0];
        HttpResponse<String> home = ours("/accounts", cookie);
        Matcher m = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(home.body());
        assertThat(m.find()).isTrue();
        String tenant = tenantOf(user);
        return new Session(cookie, m.group(1), tenant);
    }

    /** The tenant of the session that was just created (the newest row; the tests run one after another). */
    private static String tenantOf(String username) throws Exception {
        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement();
                var rs = st.executeQuery("SELECT tenant_id FROM web_session ORDER BY created_at DESC LIMIT 1")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Starts a test from an empty account list of the signed-in tenant (the tenants persist across tests). */
    private static void removeAccountsOf(Session s) throws Exception {
        try (Connection c = db.dataSource().getConnection();
                var ps = c.prepareStatement("DELETE FROM mailbox_account WHERE tenant_id = ?")) {
            ps.setObject(1, java.util.UUID.fromString(s.tenantId()));
            ps.executeUpdate();
        }
    }

    private HttpResponse<String> form(Session s, String path, Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder("csrf=").append(enc(s.csrf()));
        fields.forEach((k, v) -> body.append('&').append(enc(k)).append('=').append(enc(v)));
        return noRedirect.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", s.cookie()).header("Origin", origin)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> linkFields(String label, String host) {
        Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put("label", label);
        f.put("host", host);
        f.put("port", "993");
        f.put("security", "IMPLICIT_TLS");
        f.put("username", "carol-mailbox@example.org");
        f.put("password", "mailbox-pw-canary");
        f.put("access", "read_only");
        return f;
    }

    private static de.alexanderlindhorst.imapmcp.core.tenancy.TenantId tenant(Session s) {
        return new de.alexanderlindhorst.imapmcp.core.tenancy.TenantId(s.tenantId());
    }

    @Test
    void aSignedInUserLinksChangesRelinksAndUnlinksAnAccountEndToEnd() throws Exception {
        probeResult = LoginProbe.Result.OK;
        PROBED.clear();
        Session s = signIn(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);
        removeAccountsOf(s);
        int okBefore = count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND result = 'ok'"
                + " AND event_type IN ('link','settings','relink','unlink')", java.util.UUID.fromString(s.tenantId()));

        // link
        HttpResponse<String> linked = form(s, "/accounts/link", linkFields("Work", "imap.example.com"));
        assertThat(linked.statusCode()).isEqualTo(303);
        assertThat(linked.headers().firstValue("location")).contains("/accounts");
        assertThat(linked.body()).doesNotContain("mailbox-pw-canary");
        assertThat(PROBED).containsExactly("imap.example.com:993:mailbox-pw-canary");
        var accounts = accountRepo.findByTenant(tenant(s));
        assertThat(accounts).singleElement().satisfies(r -> {
            assertThat(r.account().label()).isEqualTo("Work");
            assertThat(r.account().readOnly()).isTrue();
            assertThat(r.account().allowPurge()).isFalse();
            assertThat(r.account().secretRef().value()).isEqualTo("db:");
        });
        var created = accounts.get(0);
        try (Secret secret = credentialStore.resolve(tenant(s), created.account().id()).orElseThrow()) {
            assertThat(new String(secret.chars())).isEqualTo("mailbox-pw-canary");
        }
        assertThat(ours("/accounts", s.cookie()).body()).contains("Work").doesNotContain("mailbox-pw-canary");

        // settings (a fresh login allows turning on allow_purge; the deployment switch is on in this test)
        HttpResponse<String> settings = form(s, "/accounts/settings", Map.of("account", created.handle(),
                "label", "Renamed", "access", "read_write", "allow_purge", "on"));
        assertThat(settings.statusCode()).isEqualTo(303);
        var changed = accountRepo.findByTenantAndHandle(tenant(s), created.handle()).orElseThrow().account();
        assertThat(changed.label()).isEqualTo("Renamed");
        assertThat(changed.readOnly()).isFalse();
        assertThat(changed.allowPurge()).isTrue();

        // re-link after three refused logins
        long revision = changed.revision();
        for (int i = 0; i < 3; i++) {
            accountRepo.recordRefusedLogin(tenant(s), created.account().id(), revision);
        }
        assertThat(accountRepo.findByTenantAndHandle(tenant(s), created.handle()).orElseThrow().account().status())
                .isEqualTo(de.alexanderlindhorst.imapmcp.core.account.MailAccount.Status.NEEDS_RELINK);
        HttpResponse<String> relinked = form(s, "/accounts/relink", Map.of("account", created.handle(),
                "password", "second-pw-canary"));
        assertThat(relinked.statusCode()).isEqualTo(303);
        var after = accountRepo.findByTenantAndHandle(tenant(s), created.handle()).orElseThrow().account();
        assertThat(after.status()).isEqualTo(de.alexanderlindhorst.imapmcp.core.account.MailAccount.Status.ACTIVE);
        assertThat(after.revision()).isGreaterThan(revision + 1);
        try (Secret secret = credentialStore.resolve(tenant(s), created.account().id()).orElseThrow()) {
            assertThat(new String(secret.chars())).isEqualTo("second-pw-canary");
        }

        // unlink
        HttpResponse<String> unlinked = form(s, "/accounts/unlink", Map.of("account", created.handle()));
        assertThat(unlinked.statusCode()).isEqualTo(303);
        assertThat(accountRepo.findByTenantAndHandle(tenant(s), created.handle())).isEmpty();
        assertThat(credentialStore.resolve(tenant(s), created.account().id())).isEmpty();
        assertThat(form(s, "/accounts/unlink", Map.of("account", created.handle())).statusCode()).isEqualTo(404);

        // audit: one ok row per action, none holding host, user name, label or password
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND result = 'ok'"
                + " AND event_type IN ('link','settings','relink','unlink')", java.util.UUID.fromString(s.tenantId())))
                .isEqualTo(okBefore + 4);
        try (Connection c = db.dataSource().getConnection(); var st = c.createStatement();
                var rs = st.executeQuery("SELECT row_to_json(a)::text FROM audit_event a")) {
            while (rs.next()) {
                assertThat(rs.getString(1)).doesNotContain("imap.example.com").doesNotContain("carol-mailbox")
                        .doesNotContain("Renamed").doesNotContain("canary");
            }
        }
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'tenant_created'",
                java.util.UUID.fromString(s.tenantId()))).isEqualTo(1);
    }

    @Test
    void aFailedProbeOrARefusedHostStoresNothingAndWritesRefusalRows() throws Exception {
        Session s = signIn(KeycloakFixture.CAROL, KeycloakFixture.CAROL_PASSWORD);
        removeAccountsOf(s);
        int refusedBefore = count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'link'"
                + " AND result LIKE 'refused_%'", java.util.UUID.fromString(s.tenantId()));
        PROBED.clear();
        probeResult = LoginProbe.Result.AUTH_FAILED;
        HttpResponse<String> wrong = form(s, "/accounts/link", linkFields("Nope", "imap.example.com"));
        probeResult = LoginProbe.Result.OK;
        HttpResponse<String> ssrf = form(s, "/accounts/link", linkFields("Local", "127.0.0.1"));
        HttpResponse<String> ssrf2 = form(s, "/accounts/link", linkFields("Meta", "169.254.169.254"));

        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(ssrf.statusCode()).isEqualTo(400);
        assertThat(ssrf2.statusCode()).isEqualTo(400);
        assertThat(PROBED).hasSize(1); // only the first reached the probe; the SSRF refusals did not
        assertThat(accountRepo.findByTenant(tenant(s))).isEmpty();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'link'"
                + " AND result LIKE 'refused_%'", java.util.UUID.fromString(s.tenantId())))
                .isEqualTo(refusedBefore + 3);
    }

    @Test
    void anotherTenantNeverSeesOrChangesAnAccount() throws Exception {
        probeResult = LoginProbe.Result.OK;
        Session alice = signIn(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);
        removeAccountsOf(alice);
        form(alice, "/accounts/link", linkFields("Alice only", "imap.example.com"));
        var handle = accountRepo.findByTenant(tenant(alice)).get(0).handle();
        Session carol = signIn(KeycloakFixture.CAROL, KeycloakFixture.CAROL_PASSWORD);
        assertThat(carol.tenantId()).isNotEqualTo(alice.tenantId());

        assertThat(ours("/accounts", carol.cookie()).body()).doesNotContain("Alice only");
        assertThat(ours("/accounts/relink?account=" + handle, carol.cookie()).statusCode()).isEqualTo(404);
        assertThat(form(carol, "/accounts/unlink", Map.of("account", handle)).statusCode()).isEqualTo(404);
        assertThat(form(carol, "/accounts/settings", Map.of("account", handle, "label", "mine")).statusCode())
                .isEqualTo(404);
        assertThat(form(carol, "/accounts/relink", Map.of("account", handle, "password", "x")).statusCode())
                .isEqualTo(404);
        assertThat(accountRepo.findByTenantAndHandle(tenant(alice), handle)).isPresent();
        assertThat(accountRepo.findByTenantAndHandle(tenant(alice), handle).orElseThrow().account().label())
                .isEqualTo("Alice only");
    }

    @Test
    void theAccountCapStopsTheFourthAccountBeforeAnyProbe() throws Exception {
        probeResult = LoginProbe.Result.OK;
        Session alice = signIn(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);
        removeAccountsOf(alice);
        for (int i = 0; i < MAX_ACCOUNTS; i++) {
            assertThat(form(alice, "/accounts/link", linkFields("A" + i, "imap.example.com")).statusCode())
                    .isEqualTo(303);
        }
        PROBED.clear();

        HttpResponse<String> fourth = form(alice, "/accounts/link", linkFields("A4", "imap.example.com"));

        assertThat(fourth.statusCode()).isEqualTo(409);
        assertThat(PROBED).isEmpty();
        assertThat(accountRepo.findByTenant(tenant(alice))).hasSize(MAX_ACCOUNTS);
    }
}
