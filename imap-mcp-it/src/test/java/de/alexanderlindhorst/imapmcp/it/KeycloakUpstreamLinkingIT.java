package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.alexanderlindhorst.imapmcp.app.HttpPublicServer;
import de.alexanderlindhorst.imapmcp.app.PublicComponents;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader;
import de.alexanderlindhorst.imapmcp.core.net.ItPinnedSockets;
import de.alexanderlindhorst.imapmcp.store.oauth.ItTokenClients;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.testcontainers.lifecycle.Startables;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keycloak as the {@code generic} upstream OAuth provider of the XOAUTH2 accounts (X1-3, #500; SPEC 5.3 "Upstream OAuth
 * linking", 5.5, 5.8): the real {@link PublicComponents} and {@link HttpPublicServer}, a real Keycloak (the realm
 * client {@value KeycloakFixture#UPSTREAM_CLIENT_ID}: PKCE S256, Revoke Refresh Token, max reuse 0), Postgres, and
 * Dovecot 2.4.5 validating Keycloak's access tokens locally with the realm key that the test reads from the JWKS
 * endpoint at run time. The token endpoint is the production client reaching Keycloak through a TLS-terminating relay
 * (the production client insists on https and a test CA); the IMAP socket factory and the clock are the usual test
 * seams of {@link PublicComponents#createForTest}. The browser is played by the test.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KeycloakUpstreamLinkingIT {

    private static final String PROVIDER = "keycloak";
    private static final String TOKEN_HOST = "token.example.com";
    private static final String USER_ADDRESS = "alice@example.org";
    private static final String REALM_PATH = "/realms/" + KeycloakFixture.REALM + "/protocol/openid-connect";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern REFRESH = Pattern.compile("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ACCESS = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

    @TempDir
    static Path dir;

    private static KeycloakFixture keycloak;
    private static PostgresFixture postgres;
    private static DovecotOauth2Container dovecot;
    private static Xoauth2Pki pki;
    private static Xoauth2TapProxy front;
    private static Xoauth2TapProxy tap;
    private static final AtomicInteger CONNECTS = new AtomicInteger();
    private static RSAKey mcpSigning;
    private static PublicComponents components;
    private static HttpLocalServer server;
    private static int port;
    private static String origin;
    private static String frontOrigin;
    private static final ListAppender<ILoggingEvent> LOGS = new ListAppender<>();
    private static Level rootLevel;

    // state shared by the ordered tests
    private static Session aliceSession;
    private static String aliceTenant;
    private static String aliceSub;
    private static String accountId;
    private static String accountHandle;

    private static String key(int seed) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    @BeforeAll
    static void start() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLevel = root.getLevel();
        root.setLevel(Level.DEBUG); // the test classpath configures root OFF
        LOGS.start();
        root.addAppender(LOGS);
        keycloak = new KeycloakFixture();
        postgres = new PostgresFixture();
        dovecot = new DovecotOauth2Container();
        // all create requests before the long Keycloak wait (a late create hit a closed Docker socket in the gate)
        Startables.deepStart(keycloak, postgres, dovecot).join();
        pki = new Xoauth2Pki(dir, TOKEN_HOST);
        front = new Xoauth2TapProxy(pki.serverContext(), keycloak.getHost(), URI.create(keycloak.baseUrl()).getPort(),
                true).rewriteHost(URI.create(keycloak.baseUrl()).getAuthority());
        frontOrigin = "https://" + TOKEN_HOST + ":" + front.port();
        // Keycloak puts the host of the authorization request (the browser reaches the container's own address) into
        // the tokens as issuer and wants it again at every refresh: the relay presents the token client's requests
        // under that same host
        dovecot.useIssuer(keycloak.issuer());
        trustRealmKey();
        // the provider's IMAP port is the configured 993: the test socket factory maps the connect to this relay
        tap = new Xoauth2TapProxy(pki.serverContext(), dovecot.getHost(), dovecot.imapsPort());
        mcpSigning = new RSAKeyGenerator(2048).keyID("k1").generate();
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
                  upstream:
                    providers:
                      %s:
                        kind: generic
                        label: Keycloak
                        clientId: %s
                        clientSecret: env:UP_SECRET
                        authorizationEndpoint: %s%s/auth
                        tokenEndpoint: %s%s/token
                        scopes: ["email"]
                        imap:
                          host: imap.example.com
                          port: 993
                          security: implicit-tls
                public:
                  baseUrl: %s
                  maxAccountsPerTenant: 3
                security:
                  handleKeys:
                    - id: 1
                      key: env:HANDLE_KEY_1
                  signingKeyId: 1
                ssrf:
                  allowedHosts: ["*.example.com"]
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
                """.formatted(postgres.getJdbcUrl(), postgres.getUsername(), port, KeycloakFixture.CLIENT_ID,
                PROVIDER, KeycloakFixture.UPSTREAM_CLIENT_ID, frontOrigin, REALM_PATH, frontOrigin, REALM_PATH,
                origin, port));
        Map<String, String> env = Map.of("DB_PW", postgres.getPassword(), "WEB_SECRET", KeycloakFixture.CLIENT_SECRET,
                "UP_SECRET", KeycloakFixture.UPSTREAM_CLIENT_SECRET, "HANDLE_KEY_1", key(1), "KEK_1", key(2));
        PublicConfig loaded = PublicConfigLoader.load(file, env);
        PublicConfig.Oauth o = loaded.oauth();
        PublicConfig config = new PublicConfig(loaded.imap(), loaded.pool(), loaded.secrets(), loaded.tools(),
                loaded.http(), loaded.db(), new PublicConfig.Oauth(keycloak.issuer(), o.resource(), o.subjectClaim(),
                        o.requiredRole(), o.roleClaim(), o.allowWithoutRole(), o.webClientId(), o.webClientSecret(),
                        o.upstreamProviders()),
                loaded.pub(), loaded.ssrf(), loaded.kms(), loaded.purgeEnabled(), loaded.security(),
                loaded.logLevel());
        AppPaths paths = AppPaths.resolve(new String[] {"--config", file.toString()},
                Map.of(AppPaths.ENV_STATE_DIR, dir.resolve("state").toString()), dir, dir);
        javax.net.ssl.SSLContext tokenTrust = pki.clientContext();
        components = PublicComponents.createForTest(config, paths, Clock.systemUTC(), env, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, policy -> ItPinnedSockets.remapped(policy, host -> {
            CONNECTS.incrementAndGet(); // one name resolution per new IMAP connection
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, p -> tap.port()),
                policy -> ItTokenClients.toLoopback(tokenTrust, front.port()));
        // one key set for both jobs: Keycloak's keys validate the ID tokens of the web login, ours the MCP bearer tokens
        List<JWK> keys = new ArrayList<>(JWKSet.parse(http(HttpRequest.newBuilder(URI.create(keycloak.certsUrl()))
                .GET().build()).body()).getKeys());
        keys.add(mcpSigning.toPublicJWK());
        server = HttpPublicServer.buildForTest(config, components, new ImmutableJWKSet<>(new JWKSet(keys)), true);
        server.start();
    }

    @AfterAll
    static void stop() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(LOGS);
        root.setLevel(rootLevel);
        if (server != null) {
            server.stop();
        }
        if (components != null) {
            components.close();
        }
        for (Xoauth2TapProxy p : new Xoauth2TapProxy[] {front, tap}) {
            if (p != null) {
                p.close();
            }
        }
        for (var c : new org.testcontainers.containers.GenericContainer<?>[] {dovecot, postgres, keycloak}) {
            if (c != null) {
                c.stop();
            }
        }
    }

    /** The realm's RS256 signing key from the JWKS endpoint, as the key Dovecot trusts for this client's tokens. */
    private static void trustRealmKey() throws Exception {
        String jwks = http(HttpRequest.newBuilder(URI.create(keycloak.certsUrl())).GET().build()).body();
        RSAKey signing = JWKSet.parse(jwks).getKeys().stream()
                .filter(k -> KeyUse.SIGNATURE.equals(k.getKeyUse()) && "RS256".equals(String.valueOf(k.getAlgorithm())))
                .map(JWK::toRSAKey).findFirst().orElseThrow();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(signing.toRSAPublicKey().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        dovecot.trustKey(KeycloakFixture.UPSTREAM_CLIENT_ID, signing.getKeyID(), pem);
    }

    // ---- helpers ----

    private static final HttpClient PLAIN = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private static HttpResponse<String> http(HttpRequest r) throws Exception {
        return PLAIN.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static HttpResponse<String> ours(String method, String path, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return http("POST".equals(method) ? b.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build() : b.GET().build());
    }

    private static void remember(Map<String, String> jar, HttpResponse<?> r) {
        for (String c : r.headers().allValues("set-cookie")) {
            String pair = c.split(";")[0];
            int eq = pair.indexOf('=');
            jar.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
    }

    /** The browser at Keycloak: opens {@code url} (rewritten from the configured https host), logs in, returns the redirect. */
    private static URI loginAtKeycloak(String url, String user, String password) throws Exception {
        Map<String, String> jar = new LinkedHashMap<>();
        HttpResponse<String> page = http(HttpRequest.newBuilder(URI.create(url)).GET().build());
        assertThat(page.statusCode()).as("authorization page").isEqualTo(200);
        remember(jar, page);
        Matcher m = Pattern.compile("action=\"([^\"]+)\"").matcher(page.body());
        assertThat(m.find()).as("login form").isTrue();
        String cookies = jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining("; "));
        HttpResponse<String> posted = http(HttpRequest.newBuilder(URI.create(m.group(1).replace("&amp;", "&")))
                .header("Cookie", cookies).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + enc(user) + "&password=" + enc(password)
                        + "&credentialId=")).build());
        assertThat(posted.statusCode()).isEqualTo(302);
        return URI.create(posted.headers().firstValue("location").orElseThrow());
    }

    private record Session(String cookie, String csrf, String tenantId) {
    }

    /** Web login at the linking surface (the {@code imap-mcp-web} client of the same realm). */
    private static Session signIn(String user, String password) throws Exception {
        HttpResponse<String> start = ours("GET", "/accounts/login", null);
        assertThat(start.statusCode()).isEqualTo(302);
        String binding = start.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        URI back = loginAtKeycloak(start.headers().firstValue("location").orElseThrow(), user, password);
        assertThat(back.toString()).startsWith(origin + "/accounts/callback?");
        HttpResponse<String> callback = ours("GET", back.getRawPath() + "?" + back.getRawQuery(), binding);
        assertThat(callback.statusCode()).isEqualTo(200);
        String cookie = callback.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("__Host-session=")).findFirst().orElseThrow().split(";")[0];
        HttpResponse<String> home = ours("GET", "/accounts", cookie);
        Matcher csrf = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(home.body());
        assertThat(csrf.find()).isTrue();
        return new Session(cookie, csrf.group(1), newestSessionTenant());
    }

    private static String newestSessionTenant() throws Exception {
        return Xoauth2Db.text(ds(), "SELECT tenant_id::text FROM web_session ORDER BY created_at DESC LIMIT 1");
    }

    private static HttpResponse<String> form(Session s, String path, Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder("csrf=").append(enc(s.csrf()));
        fields.forEach((k, v) -> body.append('&').append(enc(k)).append('=').append(enc(v)));
        return http(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", s.cookie()).header("Origin", origin)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    /** Start at our surface, log in at Keycloak, return to our callback: the page of the callback. */
    private static HttpResponse<String> linkThroughKeycloak(Session s, Map<String, String> startFields,
            String user, String password) throws Exception {
        HttpResponse<String> started = form(s, "/accounts/oauth/" + PROVIDER + "/start", startFields);
        assertThat(started.statusCode()).isEqualTo(303);
        String location = started.headers().firstValue("location").orElseThrow();
        assertThat(location).startsWith(frontOrigin + REALM_PATH + "/auth?").contains("code_challenge_method=S256")
                .contains("client_id=" + KeycloakFixture.UPSTREAM_CLIENT_ID).contains("login_hint=")
                .contains("redirect_uri=" + enc(origin + "/accounts/oauth/" + PROVIDER + "/callback"));
        String cookie = started.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("__Host-upstream=")).findFirst().orElseThrow().split(";")[0];
        // only the user's browser goes to the authorization endpoint: the test reaches the container directly
        URI back = loginAtKeycloak(keycloak.baseUrl() + location.substring(frontOrigin.length()), user, password);
        assertThat(back.toString()).startsWith(origin + "/accounts/oauth/" + PROVIDER + "/callback?");
        return ours("GET", back.getRawPath() + "?" + back.getRawQuery(), cookie);
    }

    private static DataSource ds() {
        return components.database().dataSource();
    }

    private static long accountCount(String tenant) throws Exception {
        return Xoauth2Db.scalar(ds(), "SELECT count(*) FROM mailbox_account WHERE tenant_id = ?::uuid", tenant);
    }

    private static String accountText(String column) throws Exception {
        return Xoauth2Db.text(ds(), "SELECT " + column + " FROM mailbox_account WHERE tenant_id = ?::uuid AND id = ?",
                aliceTenant, accountId);
    }

    private static long grantVersion() throws Exception {
        return Xoauth2Db.scalar(ds(), "SELECT refresh_version FROM oauth_grant WHERE tenant_id = ?::uuid"
                + " AND account_id = ?", aliceTenant, accountId);
    }

    private static long audit(String type, String result) throws Exception {
        return Xoauth2Db.scalar(ds(), "SELECT count(*) FROM audit_event WHERE tenant_id = ?::uuid"
                + " AND event_type = ? AND result = ?", aliceTenant, type, result);
    }

    /** Refresh tokens Keycloak issued so far, in order, read from what the relay saw going back to our client. */
    private static List<String> issuedRefreshTokens() {
        return all(REFRESH, front.serverWire());
    }

    private static List<String> issuedAccessTokens() {
        return all(ACCESS, front.serverWire());
    }

    private static List<String> all(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String mcpToken(String subject) throws Exception {
        long now = System.currentTimeMillis();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(keycloak.issuer())
                .audience("https://127.0.0.1:" + port + "/mcp").subject(subject).issueTime(new Date(now))
                .expirationTime(new Date(now + 600_000)).claim("scope", "imap-mcp:read").build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1")
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(mcpSigning));
        return jwt.serialize();
    }

    private static Map<String, Object> call(String subject, String tool, Map<String, Object> args) throws Exception {
        String token = mcpToken(subject);
        try (McpSyncClient c = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((b, m, u, body, ctx) -> b.header("Authorization", "Bearer " + token)).build())
                .requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(30)).build()) {
            c.initialize();
            return ToolResults.payload(c.callTool(new McpSchema.CallToolRequest(tool, args)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("error");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> error) {
        return (Map<String, Object>) error.get("details");
    }

    // ---- Keycloak admin (master realm, test credentials of the fixture) ----

    private static String adminToken() throws Exception {
        HttpResponse<String> r = http(HttpRequest.newBuilder(URI.create(keycloak.tokenEndpoint("master")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username="
                        + enc(KeycloakFixture.ADMIN_USER) + "&password=" + enc(KeycloakFixture.ADMIN_PASSWORD)))
                .build());
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body()).get("access_token").asString();
    }

    private static String userId(String username) throws Exception {
        HttpResponse<String> r = http(HttpRequest.newBuilder(URI.create(keycloak.baseUrl() + "/admin/realms/"
                + KeycloakFixture.REALM + "/users?exact=true&username=" + enc(username)))
                .header("Authorization", "Bearer " + adminToken()).GET().build());
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body()).get(0).get("id").asString();
    }

    private static void logoutAllSessions(String userId) throws Exception {
        HttpResponse<String> r = http(HttpRequest.newBuilder(URI.create(keycloak.baseUrl() + "/admin/realms/"
                + KeycloakFixture.REALM + "/users/" + userId + "/logout"))
                .header("Authorization", "Bearer " + adminToken()).POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(r.statusCode()).isEqualTo(204);
    }

    /** Kills the pooled mailbox connection; the call that finds it dead fails (retryable), the next one reconnects. */
    private static void reconnect() throws Exception {
        tap.dropConnections();
        call(aliceSub, "list_folders", Map.of("account", accountHandle));
    }

    private static Map<String, String> startFields(String label) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("address", USER_ADDRESS);
        f.put("access", "read_only");
        return f;
    }

    // ---- tests ----

    @Test
    @Order(1)
    void codeAndPkceLinkTheAccountThroughKeycloak() throws Exception {
        aliceSession = signIn(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);
        aliceTenant = aliceSession.tenantId();
        aliceSub = userId(KeycloakFixture.ALICE);

        // Keycloak requires PKCE S256 of this client: a completed code exchange proves the verifier was sent
        HttpResponse<String> done = linkThroughKeycloak(aliceSession, startFields("Keycloak mailbox"),
                KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);

        assertThat(done.statusCode()).isEqualTo(200);
        assertThat(accountCount(aliceTenant)).isEqualTo(1);
        accountId = Xoauth2Db.text(ds(), "SELECT id FROM mailbox_account WHERE tenant_id = ?::uuid", aliceTenant);
        accountHandle = Xoauth2Db.text(ds(), "SELECT handle FROM mailbox_account WHERE tenant_id = ?::uuid",
                aliceTenant);
        assertThat(accountText("auth")).isEqualTo("xoauth2");
        assertThat(accountText("provider_id")).isEqualTo(PROVIDER);
        assertThat(accountText("status")).isEqualTo("active");
        assertThat(accountText("username")).isEqualTo(USER_ADDRESS);
        assertThat(grantVersion()).isEqualTo(1);
        assertThat(Xoauth2Db.scalar(ds(), "SELECT count(*) FROM credential WHERE tenant_id = ?::uuid"
                + " AND account_id = ? AND kind = 'oauth_refresh' AND version = 1", aliceTenant, accountId)).isEqualTo(1);
        assertThat(audit("link", "ok")).isEqualTo(1);
        assertThat(issuedRefreshTokens()).as("the code exchange returned a refresh token").hasSize(1);
        // the probe authenticated with XOAUTH2 (Keycloak token, local validation at Dovecot), never a LOGIN
        assertThat(tap.clientWire()).contains("AUTHENTICATE XOAUTH2").doesNotContain(" LOGIN ");
    }

    @Test
    @Order(2)
    void theReadToolsWorkWithPeekAndNoFlagChange() throws Exception {
        try (MailSeeder seeder = new MailSeeder(dovecot.getHost(), dovecot.imapsPort(), USER_ADDRESS,
                DovecotOauth2Container.PASSWORD)) {
            seeder.appendPlain("INBOX", "peek me one", "body one");
            seeder.appendPlain("INBOX", "peek me two", "body two");
            int wireFrom = tap.clientWire().length();

            assertThat(call(aliceSub, "list_folders", Map.of("account", accountHandle)).toString()).contains("INBOX");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> found = (List<Map<String, Object>>) call(aliceSub, "search_messages",
                    Map.of("account", accountHandle, "folder", "INBOX")).get("messages");
            assertThat(found).hasSize(2);
            for (Map<String, Object> m : found) {
                assertThat(call(aliceSub, "get_message", Map.of("account", accountHandle, "message",
                        m.get("handle"))).toString()).contains("body");
            }

            Folder inbox = seeder.store().getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                for (Message m : inbox.getMessages()) {
                    assertThat(m.isSet(Flags.Flag.SEEN)).as("\\Seen of message %d", m.getMessageNumber()).isFalse();
                }
            } finally {
                inbox.close(false);
            }
            String wire = tap.clientWire().substring(wireFrom);
            assertThat(wire).contains("AUTHENTICATE XOAUTH2").contains("BODY.PEEK[").doesNotContain("LOGIN");
            assertThat(wire.lines().filter(l -> l.contains("FETCH") && l.contains("BODY[")).toList())
                    .as("a FETCH without PEEK").isEmpty();
        }
    }

    @Test
    @Order(3)
    void anotherTenantNeverReachesTheAccountOrItsStartFlow() throws Exception {
        Session carol = signIn(KeycloakFixture.CAROL, KeycloakFixture.CAROL_PASSWORD);
        String carolSub = userId(KeycloakFixture.CAROL);
        assertThat(carol.tenantId()).isNotEqualTo(aliceTenant);
        int tokenWire = front.clientWire().length();
        int connections = CONNECTS.get();

        Map<String, Object> foreign = error(call(carolSub, "list_folders", Map.of("account", accountHandle)));

        assertThat(foreign).containsEntry("code", "ACCOUNT_NOT_FOUND");
        assertThat(form(carol, "/accounts/oauth/" + PROVIDER + "/start", Map.of("account", accountHandle))
                .statusCode()).isEqualTo(404);
        assertThat(front.clientWire().length()).as("no token request").isEqualTo(tokenWire);
        assertThat(CONNECTS.get()).as("no IMAP connection").isEqualTo(connections);
        assertThat(accountCount(carol.tenantId())).isZero();
    }

    @Test
    @Order(4)
    void aForcedRefreshRotatesTheStoredTokenAndTheNextRefreshPresentsTheNewOne() throws Exception {
        // Keycloak's access tokens live 5 minutes and the cache renews 5 minutes before expiry, so a token is never
        // reused: a new mailbox connection (the pooled one is dropped) always refreshes and rotates the token
        List<String> before = issuedRefreshTokens();
        String first = before.get(before.size() - 1);
        long versionBefore = grantVersion();
        int wireFrom = front.clientWire().length();
        reconnect();

        assertThat(call(aliceSub, "list_folders", Map.of("account", accountHandle))).doesNotContainKey("error");

        List<String> issued = issuedRefreshTokens();
        assertThat(issued).hasSizeGreaterThan(before.size());
        String second = issued.get(issued.size() - 1);
        assertThat(second).isNotEqualTo(first);
        assertThat(front.clientWire().substring(wireFrom)).contains("grant_type=refresh_token").contains(first);
        assertThat(grantVersion()).isGreaterThan(versionBefore);

        // the stored new token carries the account: it is presented next and rotates in turn
        int wireFrom2 = front.clientWire().length();
        long versionMid = grantVersion();
        reconnect();
        assertThat(call(aliceSub, "list_folders", Map.of("account", accountHandle))).doesNotContainKey("error");
        assertThat(front.clientWire().substring(wireFrom2)).contains(second);
        assertThat(issuedRefreshTokens().get(issuedRefreshTokens().size() - 1)).isNotEqualTo(second);
        assertThat(grantVersion()).isGreaterThan(versionMid);
        assertThat(accountText("status")).isEqualTo("active");
        // Keycloak's own reuse detection (a replayed retired token) is not asserted here: its outcome depends on
        // Keycloak's per-session bookkeeping, and the contract of this server is that a token is never presented twice
        // (the rotation above) and that invalid_grant leads to relink_required (the next test).
    }

    @Test
    @Order(5)
    void aRevokedKeycloakSessionGivesRelinkRequiredAndAReLinkRestoresTheAccount() throws Exception {
        long revision = Long.parseLong(accountText("revision"));
        long revoked = audit("credential_revoked", "invalid_grant");
        logoutAllSessions(aliceSub);
        // the pooled mailbox connection would need no token; the call after its death refreshes and is refused
        reconnect();
        Map<String, Object> first = error(call(aliceSub, "list_folders", Map.of("account", accountHandle)));

        assertThat(first).containsEntry("code", "AUTH_FAILED").containsEntry("retryable", false);
        assertThat(details(first)).containsEntry("reason", "relink_required");
        assertThat(accountText("status")).isEqualTo("needs_relink");
        assertThat(Long.parseLong(accountText("revision"))).isEqualTo(revision + 1);
        assertThat(audit("credential_revoked", "invalid_grant")).isEqualTo(revoked + 1);

        int tokenWire = front.clientWire().length();
        int connections = tap.connections();
        Map<String, Object> second = error(call(aliceSub, "list_folders", Map.of("account", accountHandle)));
        assertThat(details(second)).containsEntry("reason", "relink_required");
        assertThat(front.clientWire().length()).as("no token request").isEqualTo(tokenWire);
        assertThat(tap.connections()).as("no IMAP connection").isEqualTo(connections);

        reLink();
        assertThat(call(aliceSub, "list_folders", Map.of("account", accountHandle))).doesNotContainKey("error");
    }

    /** Re-link of the account through a fresh web session and Keycloak login; checks the account's recovery. */
    private static void reLink() throws Exception {
        Session again = signIn(KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);
        assertThat(again.tenantId()).isEqualTo(aliceTenant);
        long revision = Long.parseLong(accountText("revision"));
        long credentials = Xoauth2Db.scalar(ds(), "SELECT count(*) FROM credential WHERE tenant_id = ?::uuid"
                + " AND account_id = ? AND kind = 'oauth_refresh'", aliceTenant, accountId);
        long linked = audit("relink", "ok");

        HttpResponse<String> done = linkThroughKeycloak(again, Map.of("account", accountHandle),
                KeycloakFixture.ALICE, KeycloakFixture.ALICE_PASSWORD);

        assertThat(done.statusCode()).isEqualTo(200);
        assertThat(accountText("status")).isEqualTo("active");
        assertThat(accountCount(aliceTenant)).as("same account, no second row").isEqualTo(1);
        assertThat(Xoauth2Db.scalar(ds(), "SELECT count(*) FROM credential WHERE tenant_id = ?::uuid"
                + " AND account_id = ? AND kind = 'oauth_refresh'", aliceTenant, accountId))
                .as("a new credential version").isGreaterThanOrEqualTo(credentials);
        assertThat(Long.parseLong(accountText("revision"))).isGreaterThan(revision);
        assertThat(audit("relink", "ok")).isEqualTo(linked + 1);
    }

    @Test
    @Order(100)
    void noLogLineAuditRowOrWireDumpOfTheLinkLeaksATokenOrTheClientSecret() throws Exception {
        List<String> secrets = new ArrayList<>(issuedRefreshTokens());
        secrets.addAll(issuedAccessTokens());
        secrets.add(KeycloakFixture.UPSTREAM_CLIENT_SECRET);
        assertThat(secrets).hasSizeGreaterThan(6);
        List<ILoggingEvent> events;
        synchronized (LOGS.list) {
            events = new ArrayList<>(LOGS.list);
        }
        assertThat(events).as("the log capture works").isNotEmpty();
        for (ILoggingEvent e : events) {
            String text = e.getFormattedMessage() + " " + (e.getThrowableProxy() == null ? ""
                    : ThrowableProxyUtil.asString(e.getThrowableProxy())) + " " + e.getMDCPropertyMap();
            for (String secret : secrets) {
                assertThat(text).as("log line of %s", e.getLoggerName()).doesNotContain(secret);
            }
        }
        String audit = String.join("\n", Xoauth2Db.auditRows(ds()));
        for (String secret : secrets) {
            assertThat(audit).doesNotContain(secret);
        }
        assertThat(audit).contains("credential_revoked");
    }
}
