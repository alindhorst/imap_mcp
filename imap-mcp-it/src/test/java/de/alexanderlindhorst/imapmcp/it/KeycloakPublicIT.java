package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import de.alexanderlindhorst.imapmcp.app.HttpPublicServer;
import de.alexanderlindhorst.imapmcp.app.PublicComponents;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository.NewAccount;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code http-public} resource server against real access tokens (SPEC 5.8 "v3b tests", D1): Keycloak issues the
 * tokens (password grant of the public test clients), the server validates them with Keycloak's real signing keys
 * and maps (issuer, subject) to tenants in a real Postgres. Covered: a valid token, wrong audience, expired, another
 * issuer, crafted {@code none} and HMAC tokens, the scope gates of D3, the verbatim 401/403 challenges and the
 * metadata endpoints, handles and cursors of one user failing for another with the identical payload and without
 * any IMAP connection, a credential row copied to another tenant (no LOGIN), and one tenant's load not exhausting
 * another's pool. The IMAP side is GreenMail behind the SSRF-checked connect (host names resolve to loopback by a
 * test seam that also counts the connections).
 */
class KeycloakPublicIT {

    private static final String REALM = KeycloakResourceFixture.REALM;
    private static final String ISSUER = KeycloakResourceFixture.issuer(REALM);
    private static final String RESOURCE = KeycloakResourceFixture.RESOURCE;
    private static final String METADATA_URL = "https://mail.example.com/.well-known/oauth-protected-resource/mcp";
    private static final String MISSING = "Bearer realm=\"imap-mcp\", resource_metadata=\"" + METADATA_URL + "\"";
    private static final String INVALID = "Bearer realm=\"imap-mcp\", error=\"invalid_token\", resource_metadata=\""
            + METADATA_URL + "\"";
    private static final String READ_SCOPE = "imap-mcp:read";
    private static final String BOTH_SCOPES = "imap-mcp:read imap-mcp:write";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;

    private static KeycloakResourceFixture keycloak;
    private static PostgresFixture postgres;
    private static GreenMailFixture mail;
    private static PublicComponents components;
    private static HttpLocalServer server;
    private static JdbcAccountRepository accounts;
    private static final AtomicInteger connects = new AtomicInteger();
    private static final Map<String, TenantId> tenants = new LinkedHashMap<>();
    private static final Map<String, ResolvedAccount> boxes = new LinkedHashMap<>();

    private static String b64(int seed) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    @BeforeAll
    static void start() throws Exception {
        keycloak = new KeycloakResourceFixture();
        keycloak.start();
        postgres = new PostgresFixture();
        postgres.start();
        String yaml = """
                db:
                  url: %s
                  username: %s
                  password: env:DB_PW
                oauth:
                  issuer: %s
                  resource: %s
                  requiredRole: imap-mcp-user
                public:
                  baseUrl: https://mail.example.com
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
                  allowedHosts: ["mail.example.com:*", "127.0.0.1:*"]
                """.formatted(postgres.getJdbcUrl(), postgres.getUsername(), ISSUER, RESOURCE);
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, yaml);
        Map<String, String> env = Map.of("DB_PW", postgres.getPassword(), "HANDLE_KEY_1", b64(1), "KEK_1", b64(2),
                "IMAP_MCP_HTTP_PORT", "8081");
        PublicConfig config = PublicConfigLoader.load(file, env);
        AppPaths paths = AppPaths.resolve(new String[] {"--config", file.toString()},
                Map.of(AppPaths.ENV_STATE_DIR, dir.resolve("state").toString()), dir, dir);
        mail = GreenMailFixture.start();
        for (String user : List.of("alice", "bob", "carol")) {
            mail.createUser(user + "@localhost", user, "right");
        }
        components = PublicComponents.createForTest(config, paths, Clock.systemUTC(), env, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, policy -> new de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory(policy, host -> {
            connects.incrementAndGet(); // one name resolution per new IMAP connection
            return new java.net.InetAddress[] {java.net.InetAddress.getLoopbackAddress()};
        }, a -> false, p -> true));
        accounts = new JdbcAccountRepository(components.database().dataSource());
        for (String user : List.of("alice", "bob", "carol")) {
            TenantId tenant = components.tenantDirectory().create(ISSUER, subjectOf(token(user, READ_SCOPE))).tenantId();
            tenants.put(user, tenant);
            ResolvedAccount box = accounts.create(tenant, new NewAccount(user + " box", null, "imap.example.com",
                    mail.imapsPort(), MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, user, false,
                    false));
            components.credentials().insert(tenant, box.account().id(), 1, "right".toCharArray());
            boxes.put(user, box);
        }
        try (MailSeeder seeder = mail.seeder("alice", "right")) {
            for (int i = 1; i <= 3; i++) {
                seeder.appendPlain("INBOX", "alice message " + i, "body " + i);
            }
        }
        JWKSet keys = JWKSet.load(new java.net.URL(keycloak.certsUrl(REALM)));
        server = HttpPublicServer.build(config.withPort(0), components, new ImmutableJWKSet<>(keys));
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
        if (mail != null) {
            mail.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private static String tokenFor(String realm, String client, String user, String scope) throws Exception {
        String body = "grant_type=password&client_id=" + client + "&username=" + user + "&password=" + user + "-pw"
                + (scope == null ? "" : "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8));
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(keycloak.tokenEndpoint(realm)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as("token endpoint: %s", r.body()).isEqualTo(200);
        return (String) McpJsonDefaults.getMapper().readValue(r.body(), Map.class).get("access_token");
    }

    private static String token(String user, String scope) throws Exception {
        return tokenFor(REALM, "connector", user, scope);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> claimsOf(String jwt) throws Exception {
        return McpJsonDefaults.getMapper().readValue(
                new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8), Map.class);
    }

    private static String subjectOf(String jwt) throws Exception {
        return (String) claimsOf(jwt).get("sub");
    }

    private static HttpResponse<String> rpc(String token, String body) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (token != null) {
            r.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(r.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final String LIST_ACCOUNTS = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"list_accounts\",\"arguments\":{}}}";

    private static McpSyncClient client(String token) {
        McpSyncClient c = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.port())
                .endpoint("/mcp")
                .httpRequestCustomizer((b, m, u, body, ctx) -> b.header("Authorization", "Bearer " + token)).build())
                .requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(30)).build();
        c.initialize();
        return c;
    }

    private static Map<String, Object> call(McpSyncClient c, String tool, Map<String, Object> args) {
        return ToolResults.payload(c.callTool(new McpSchema.CallToolRequest(tool, args)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> payload) {
        Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) payload.get("error"));
        e.remove("request_id");
        return e;
    }

    private static int count(String sql) throws Exception {
        try (var c = components.database().dataSource().getConnection();
                var st = c.createStatement();
                var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ---- valid tokens and the 401 challenge ----

    @Test
    void aValidTokenReachesTheToolAndSeesOnlyItsOwnTenant() throws Exception {
        try (McpSyncClient alice = client(token("alice", READ_SCOPE))) {
            Map<String, Object> list = call(alice, "list_accounts", Map.of());
            assertThat(list.toString()).contains(boxes.get("alice").handle())
                    .doesNotContain(boxes.get("bob").handle()).doesNotContain(boxes.get("carol").handle());
        }
    }

    @Test
    void noTokenAndGarbageAreA401WithTheVerbatimChallenge() throws Exception {
        HttpResponse<String> none = rpc(null, LIST_ACCOUNTS);
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().allValues("WWW-Authenticate")).containsExactly(MISSING);
        HttpResponse<String> junk = rpc("not.a.token", LIST_ACCOUNTS);
        assertThat(junk.statusCode()).isEqualTo(401);
        assertThat(junk.headers().allValues("WWW-Authenticate")).containsExactly(INVALID);
    }

    @Test
    void aTokenForAnotherAudienceIsRejected() throws Exception {
        String wrong = tokenFor(REALM, "connector-wrong-aud", "alice", READ_SCOPE);
        assertThat(claimsOf(wrong).get("aud").toString()).contains(KeycloakResourceFixture.WRONG_AUDIENCE)
                .doesNotContain(RESOURCE);
        HttpResponse<String> r = rpc(wrong, LIST_ACCOUNTS);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().allValues("WWW-Authenticate")).containsExactly(INVALID);
    }

    @Test
    void aTokenOfAnotherIssuerIsRejectedEvenForTheSameUserNameAndAudience() throws Exception {
        String other = tokenFor(KeycloakResourceFixture.OTHER_REALM, "connector", "alice", READ_SCOPE);
        assertThat(claimsOf(other).get("iss")).isEqualTo(KeycloakResourceFixture.issuer("imap-other"));
        assertThat(claimsOf(other).get("aud").toString()).contains(RESOURCE);
        HttpResponse<String> r = rpc(other, LIST_ACCOUNTS);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().allValues("WWW-Authenticate")).containsExactly(INVALID);
    }

    @Test
    void anExpiredTokenIsRejectedOnceThe60SecondSkewIsOver() throws Exception {
        String shortLived = tokenFor(REALM, "connector-short", "alice", READ_SCOPE);
        long exp = ((Number) claimsOf(shortLived).get("exp")).longValue();
        assertThat(rpc(shortLived, LIST_ACCOUNTS).statusCode()).isEqualTo(200); // inside its life
        long waitMillis = (exp + 62) * 1000 - System.currentTimeMillis();
        Thread.sleep(Math.max(0, waitMillis));
        HttpResponse<String> r = rpc(shortLived, LIST_ACCOUNTS);
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().allValues("WWW-Authenticate")).containsExactly(INVALID);
    }

    @Test
    void craftedAlgNoneAndHmacTokensAreRejected() throws Exception {
        Map<String, Object> real = claimsOf(token("alice", READ_SCOPE));
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(RESOURCE)
                .subject((String) real.get("sub")).claim("scope", READ_SCOPE)
                .expirationTime(new Date(System.currentTimeMillis() + 300_000)).build();
        String none = new PlainJWT(claims).serialize();
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        SignedJWT hmac = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        hmac.sign(new MACSigner(secret));
        for (String crafted : List.of(none, hmac.serialize())) {
            HttpResponse<String> r = rpc(crafted, LIST_ACCOUNTS);
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.headers().allValues("WWW-Authenticate")).containsExactly(INVALID);
        }
    }

    // ---- scopes (D3) ----

    @Test
    void aTokenWithoutTheReadScopeIsA403InsufficientScopeWithTheVerbatimChallenge() throws Exception {
        String noScope = token("alice", null);
        assertThat(String.valueOf(claimsOf(noScope).get("scope"))).doesNotContain("imap-mcp");
        HttpResponse<String> r = rpc(noScope, LIST_ACCOUNTS);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.headers().allValues("WWW-Authenticate"))
                .containsExactly("Bearer error=\"insufficient_scope\", scope=\"imap-mcp:read\"");
    }

    @Test
    void aWriteCallWithoutTheWriteScopeIsPermissionDeniedInsufficientScopeAndWithItGoesThrough() throws Exception {
        String args = boxes.get("alice").handle();
        try (McpSyncClient readOnly = client(token("alice", READ_SCOPE))) {
            Map<String, Object> refused = call(readOnly, "create_folder", Map.of("account", args, "name", "NewFolder"));
            Map<String, Object> e = error(refused);
            assertThat(e).containsEntry("code", "PERMISSION_DENIED").containsEntry("retryable", false);
            assertThat(refused.toString()).contains("insufficient_scope").contains("imap-mcp:write");
        }
        try (McpSyncClient writer = client(token("alice", BOTH_SCOPES))) {
            Map<String, Object> done = call(writer, "create_folder", Map.of("account", args, "name", "NewFolder"));
            assertThat(done).containsEntry("created", true);
        }
    }

    // ---- metadata ----

    @Test
    void theMetadataEndpointsNeedNoTokenAndNameTheResourceTheIssuerAndTheScopes() throws Exception {
        for (String path : List.of("/.well-known/oauth-protected-resource",
                "/.well-known/oauth-protected-resource/mcp")) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = McpJsonDefaults.getMapper().readValue(r.body(), Map.class);
            assertThat(doc).containsEntry("resource", RESOURCE).containsEntry("authorization_servers", List.of(ISSUER))
                    .containsEntry("bearer_methods_supported", List.of("header"))
                    .containsEntry("scopes_supported", List.of("imap-mcp:read", "imap-mcp:write"));
        }
    }

    // ---- two users ----

    @Test
    void handlesAndCursorsOfOneUserFailForTheOtherWithTheIdenticalPayloadAndNoImapConnection() throws Exception {
        String account = boxes.get("alice").handle();
        String handle;
        String cursor;
        try (McpSyncClient alice = client(token("alice", READ_SCOPE))) {
            Map<String, Object> page = call(alice, "search_messages",
                    Map.of("account", account, "folder", "INBOX", "page_size", 1));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) page.get("messages");
            handle = (String) messages.get(0).get("handle");
            cursor = (String) page.get("next_cursor");
            assertThat(cursor).isNotBlank();
            assertThat(call(alice, "get_message", Map.of("account", account, "message", handle))).containsKey("handle");
        }
        String bobBox = boxes.get("bob").handle(); // bob has not made any IMAP connection so far
        int before = connects.get();
        try (McpSyncClient bob = client(token("bob", READ_SCOPE))) {
            Map<String, Object> foreignAccount = call(bob, "get_message", Map.of("account", account, "message", handle));
            assertThat(error(foreignAccount)).containsEntry("code", "ACCOUNT_NOT_FOUND");
            Map<String, Object> garbageAccount = call(bob, "get_message",
                    Map.of("account", "acc_" + "A".repeat(26), "message", handle));
            assertThat(error(foreignAccount)).isEqualTo(error(garbageAccount));

            Map<String, Object> foreignMessage = call(bob, "get_message", Map.of("account", bobBox, "message", handle));
            assertThat(error(foreignMessage)).containsEntry("code", "INVALID_HANDLE");
            Map<String, Object> garbageMessage = call(bob, "get_message",
                    Map.of("account", bobBox, "message", "msg_" + "A".repeat(60)));
            assertThat(error(foreignMessage).get("hint")).isEqualTo(error(garbageMessage).get("hint"));
            assertThat(error(foreignMessage).get("message")).isEqualTo(error(garbageMessage).get("message"));

            assertThat(connects.get()).as("IMAP connections opened for the refused handles").isEqualTo(before);

            // A cursor is bound to the folder's full name, so the folder is resolved on a connection first (the
            // MAC check follows, before any SEARCH): the failure is the same as for a garbage cursor.
            Map<String, Object> foreignCursor = call(bob, "search_messages",
                    Map.of("account", bobBox, "folder", "INBOX", "page_size", 1, "cursor", cursor));
            assertThat(error(foreignCursor)).containsEntry("code", "INVALID_CURSOR");
            Map<String, Object> garbageCursor = call(bob, "search_messages",
                    Map.of("account", bobBox, "folder", "INBOX", "page_size", 1, "cursor", "cur_" + "A".repeat(60)));
            assertThat(error(foreignCursor)).isEqualTo(error(garbageCursor));
        }
    }

    @Test
    void aCredentialRowCopiedToAnotherTenantFailsToDecryptAndNoLoginIsMade() throws Exception {
        String carolBox = boxes.get("carol").handle();
        try (var c = components.database().dataSource().getConnection();
                var ps = c.prepareStatement("UPDATE credential b SET wrapped_dek = a.wrapped_dek, nonce = a.nonce,"
                        + " ciphertext = a.ciphertext, kek_id = a.kek_id FROM credential a"
                        + " WHERE b.tenant_id = ?::uuid AND a.tenant_id = ?::uuid")) {
            ps.setString(1, tenants.get("carol").value());
            ps.setString(2, tenants.get("alice").value());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        int before = connects.get();
        try (McpSyncClient carol = client(token("carol", READ_SCOPE))) {
            Map<String, Object> r = call(carol, "list_folders", Map.of("account", carolBox));
            assertThat(error(r)).containsEntry("code", "INTERNAL_ERROR");
            assertThat(r.toString()).doesNotContain("right");
        }
        assertThat(connects.get()).as("no connection means no LOGIN").isEqualTo(before);
    }

    @Test
    void oneTenantsConcurrentLoadDoesNotExhaustAnotherTenantsPool() throws Exception {
        String aliceBox = boxes.get("alice").handle();
        String bobBox = boxes.get("bob").handle();
        String aliceToken = token("alice", READ_SCOPE);
        String bobToken = token("bob", READ_SCOPE);
        List<Thread> load = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            load.add(Thread.ofVirtual().start(() -> {
                try (McpSyncClient c = client(aliceToken)) {
                    for (int n = 0; n < 5; n++) {
                        c.callTool(new McpSchema.CallToolRequest("search_messages",
                                Map.of("account", aliceBox, "folder", "INBOX")));
                    }
                } catch (RuntimeException ignored) {
                    // SERVER_BUSY / transport errors under load are the point of the test, not a failure of it
                }
            }));
        }
        try (McpSyncClient bob = client(bobToken)) {
            for (int i = 0; i < 5; i++) {
                Map<String, Object> r = call(bob, "list_folders", Map.of("account", bobBox));
                assertThat(r).as("bob during alice's load").doesNotContainKey("error").containsKey("folders");
            }
        }
        for (Thread t : load) {
            t.join(Duration.ofSeconds(120));
        }
        assertThat(count("SELECT count(*) FROM tenant")).isEqualTo(3);
    }
}
