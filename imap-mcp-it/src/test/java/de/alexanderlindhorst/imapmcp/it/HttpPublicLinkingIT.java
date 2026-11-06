package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import de.alexanderlindhorst.imapmcp.app.HttpPublicServer;
import de.alexanderlindhorst.imapmcp.app.PublicComponents;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository.NewAccount;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The linking surface wired into http-public (B3 part 3, SPEC 5.3): the real {@link PublicComponents} and
 * {@link HttpPublicServer} with a real Keycloak (browser login with PKCE) and Postgres. The IMAP side is the pinned
 * session factory of the components, whose resolver is a test seam: the allowlisted name does not resolve, so a link
 * attempt reaches the real probe and fails as unreachable (a success against GreenMail is not possible here: the
 * SSRF port rule allows 993/143 only). The success path of the pages is covered by {@code KeycloakLoginIT}.
 */
class HttpPublicLinkingIT {

    private static final String PASSWORD_CANARY = "mailbox-pw-canary";
    private static final List<String> RESOLVED = new CopyOnWriteArrayList<>();

    @TempDir
    static Path dir;

    private static KeycloakFixture keycloak;
    private static PostgresFixture postgres;
    private static PublicComponents components;
    private static HttpLocalServer server;
    private static JdbcAccountRepository accounts;
    private static int port;
    private static String origin;

    private static String key(int seed) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    @BeforeAll
    static void start() throws Exception {
        keycloak = new KeycloakFixture();
        postgres = new PostgresFixture();
        org.testcontainers.lifecycle.Startables.deepStart(keycloak, postgres).join();
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        origin = "https://127.0.0.1:" + port;
        Path file = dir.resolve("config.yaml");
        // the loader insists on an https issuer; the Keycloak container is plain http and is swapped in below
        Files.writeString(file, """
                db:
                  url: %s
                  username: %s
                  password: env:DB_PW
                oauth:
                  issuer: https://idp.example.com/realms/imap
                  resource: https://127.0.0.1:%d/mcp
                  requiredRole: imap-user
                  webClientId: %s
                  webClientSecret: env:WEB_SECRET
                public:
                  baseUrl: %s
                  maxAccountsPerTenant: 3
                security:
                  handleKeys:
                    - id: 1
                      key: env:HANDLE_KEY_1
                  signingKeyId: 1
                ssrf:
                  allowedHosts: ["imap.example.com"]
                kms:
                  provider: local
                  local:
                    currentKekId: kek-1
                    keks:
                      - id: kek-1
                        key: env:KEK_1
                http:
                  port: %d
                  allowedHosts: ["127.0.0.1:*"]
                """.formatted(postgres.getJdbcUrl(), postgres.getUsername(), port, KeycloakFixture.CLIENT_ID, origin,
                port));
        Map<String, String> env = Map.of("DB_PW", postgres.getPassword(), "WEB_SECRET", KeycloakFixture.CLIENT_SECRET,
                "HANDLE_KEY_1", key(1), "KEK_1", key(2));
        PublicConfig loaded = PublicConfigLoader.load(file, env);
        PublicConfig.Oauth o = loaded.oauth();
        PublicConfig config = new PublicConfig(loaded.imap(), loaded.pool(), loaded.secrets(), loaded.tools(),
                loaded.http(), loaded.db(), new PublicConfig.Oauth(keycloak.issuer(), o.resource(), o.subjectClaim(),
                        o.requiredRole(), o.roleClaim(), o.allowWithoutRole(), o.webClientId(), o.webClientSecret()),
                loaded.pub(), loaded.ssrf(), loaded.kms(), loaded.purgeEnabled(), loaded.security(),
                loaded.logLevel());
        AppPaths paths = AppPaths.resolve(new String[] {"--config", file.toString()},
                Map.of(AppPaths.ENV_STATE_DIR, dir.resolve("state").toString()), dir, dir);
        components = PublicComponents.createForTest(config, paths, Clock.systemUTC(), env, p -> p,
                policy -> new PinnedSocketFactory(policy, host -> {
                    RESOLVED.add(host);
                    throw new UnknownHostException("test resolver");
                }, a -> false, p -> true));
        accounts = new JdbcAccountRepository(components.database().dataSource());
        com.nimbusds.jose.jwk.source.JWKSource<SecurityContext> keys =
                JWKSourceBuilder.<SecurityContext>create(new URL(keycloak.certsUrl())).build();
        server = HttpPublicServer.buildForTest(config, components, keys, true);
        server.start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (components != null) {
            components.close();
        }
        postgres.stop();
        keycloak.stop();
    }

    // ---- helpers (the browser side follows KeycloakLoginIT) ----

    private final HttpClient noRedirect = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> ours(String method, String path, String cookie, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        HttpRequest r = "POST".equals(method)
                ? b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build()
                : b.GET().build();
        return noRedirect.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void remember(Map<String, String> jar, HttpResponse<?> r) {
        for (String c : r.headers().allValues("set-cookie")) {
            String pair = c.split(";")[0];
            int eq = pair.indexOf('=');
            jar.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
    }

    private record Session(String cookie, String csrf, String tenantId) {
    }

    /** Full browser login at Keycloak and back; returns the session cookie, the CSRF token and the tenant. */
    private Session signIn(String user, String password) throws Exception {
        HttpResponse<String> start = ours("GET", "/accounts/login", null, null);
        assertThat(start.statusCode()).isEqualTo(302);
        String binding = start.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        Map<String, String> jar = new LinkedHashMap<>();
        HttpClient kc = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> page = kc.send(HttpRequest.newBuilder(URI.create(
                start.headers().firstValue("location").orElseThrow())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        remember(jar, page);
        Matcher m = Pattern.compile("action=\"([^\"]+)\"").matcher(page.body());
        assertThat(m.find()).as("login form").isTrue();
        String cookies = jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
        HttpResponse<String> posted = kc.send(HttpRequest.newBuilder(URI.create(m.group(1).replace("&amp;", "&")))
                .header("Cookie", cookies).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + enc(user) + "&password=" + enc(password)
                        + "&credentialId=")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(posted.statusCode()).isEqualTo(302);
        URI back = URI.create(posted.headers().firstValue("location").orElseThrow());
        assertThat(back.toString()).startsWith(origin + "/accounts/callback?");
        HttpResponse<String> callback = ours("GET", back.getRawPath() + "?" + back.getRawQuery(), binding, null);
        assertThat(callback.statusCode()).isEqualTo(200);
        String cookie = callback.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("__Host-session=")).findFirst().orElseThrow().split(";")[0];
        HttpResponse<String> home = ours("GET", "/accounts", cookie, null);
        Matcher csrf = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(home.body());
        assertThat(csrf.find()).isTrue();
        return new Session(cookie, csrf.group(1), newestSessionTenant());
    }

    private static String newestSessionTenant() throws Exception {
        try (var c = components.database().dataSource().getConnection(); var st = c.createStatement();
                var rs = st.executeQuery("SELECT tenant_id FROM web_session ORDER BY created_at DESC LIMIT 1")) {
            rs.next();
            return rs.getString(1);
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
        Map<String, String> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("host", host);
        f.put("port", "993");
        f.put("security", "IMPLICIT_TLS");
        f.put("username", "carol-mailbox@example.org");
        f.put("password", PASSWORD_CANARY);
        f.put("access", "read_only");
        return f;
    }

    private static int count(String sql, Object... params) throws Exception {
        try (var c = components.database().dataSource().getConnection(); var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ---- tests ----

    @Test
    void accountsWithoutASessionRedirectsToTheLoginAndTheLoginGoesToTheIdentityProviderWithPkce() throws Exception {
        HttpResponse<String> home = ours("GET", "/accounts", null, null);
        assertThat(home.statusCode()).isEqualTo(302);
        assertThat(home.headers().firstValue("location").orElseThrow()).endsWith("/accounts/login");
        assertThat(home.headers().firstValue("cache-control")).contains("no-store");
        assertThat(home.headers().firstValue("content-security-policy").orElseThrow()).contains("frame-ancestors 'none'");

        HttpResponse<String> login = ours("GET", "/accounts/login", null, null);

        assertThat(login.statusCode()).isEqualTo(302);
        String location = login.headers().firstValue("location").orElseThrow();
        assertThat(location).startsWith(keycloak.issuer()).contains("code_challenge_method=S256")
                .contains("client_id=" + KeycloakFixture.CLIENT_ID)
                .contains("redirect_uri=" + enc(origin + "/accounts/callback"));
        assertThat(login.headers().firstValue("set-cookie").orElseThrow()).startsWith("__Host-oidc=");
    }

    @Test
    void theMcpFilterChainStaysUnchangedBesideTheLinkingSurface() throws Exception {
        // a bearer token is demanded on /mcp and never on /accounts
        HttpResponse<String> mcp = ours("POST", "/mcp", null, null);
        assertThat(mcp.statusCode()).isEqualTo(401);
        assertThat(mcp.headers().firstValue("www-authenticate").orElseThrow()).contains("resource_metadata=");
        assertThat(ours("GET", "/accounts/login", null, null).statusCode()).isEqualTo(302);
        // a session cookie is no bearer token
        Session s = signInOnce();
        assertThat(ours("POST", "/mcp", s.cookie(), null).statusCode()).isEqualTo(401);
        // and a bearer token is no session
        assertThat(ours("GET", "/accounts", null, "not-a-session").statusCode()).isEqualTo(302);
    }

    private Session signInOnce() throws Exception {
        return signIn(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);
    }

    @Test
    void aLinkAttemptRunsTheSsrfChecksThenTheRealProbeOnThePinnedFactoryAndStoresNothing() throws Exception {
        Session s = signIn(KeycloakFixture.CAROL, KeycloakFixture.CAROL_PASSWORD);
        UUID tenant = UUID.fromString(s.tenantId());
        RESOLVED.clear();

        HttpResponse<String> local = form(s, "/accounts/link", linkFields("Local", "127.0.0.1"));
        HttpResponse<String> other = form(s, "/accounts/link", linkFields("Other", "mail.example.org"));
        assertThat(local.statusCode()).isEqualTo(400);
        assertThat(other.statusCode()).isEqualTo(400);
        assertThat(RESOLVED).as("refused by the policy before any connect").isEmpty();

        HttpResponse<String> unreachable = form(s, "/accounts/link", linkFields("Work", "imap.example.com"));

        assertThat(unreachable.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(unreachable.body()).doesNotContain(PASSWORD_CANARY);
        assertThat(RESOLVED).as("the probe connected through the pinned socket factory").contains("imap.example.com");
        assertThat(accounts.findByTenant(new TenantId(s.tenantId()))).isEmpty();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'link'"
                + " AND result = 'refused_host'", tenant)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'link'"
                + " AND result = 'refused_probe_unreachable'", tenant)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = ? AND event_type = 'tenant_created'",
                tenant)).isEqualTo(1);
    }

    @Test
    void anotherTenantNeverSeesTheAccountsOfTheSessionOwner() throws Exception {
        Session alice = signIn(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);
        var created = accounts.create(new TenantId(alice.tenantId()), new NewAccount("Alice only", null,
                "imap.example.com", 993, MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "alice",
                true, false));
        components.credentials().insert(new TenantId(alice.tenantId()), created.account().id(), 1,
                "pw".toCharArray());
        assertThat(ours("GET", "/accounts", alice.cookie(), null).body()).contains("Alice only");

        Session carol = signIn(KeycloakFixture.CAROL, KeycloakFixture.CAROL_PASSWORD);

        assertThat(carol.tenantId()).isNotEqualTo(alice.tenantId());
        assertThat(ours("GET", "/accounts", carol.cookie(), null).body()).doesNotContain("Alice only");
        assertThat(form(carol, "/accounts/unlink", Map.of("account", created.handle())).statusCode()).isEqualTo(404);
        assertThat(accounts.findByTenantAndHandle(new TenantId(alice.tenantId()), created.handle())).isPresent();
    }
}
