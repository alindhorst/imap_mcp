package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
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
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository.NewAccount;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code purge_messages} in {@code http-public} (SPEC 4 v3b "Purge over HTTP", D8): the real components with
 * {@code purge.enabled} and {@code http.stateful}, Postgres, Dovecot (a real {@code \Trash} role) behind the
 * SSRF-checked connect, tokens signed locally for two principals. The SDK client answers the form elicitation that
 * travels on the call's event stream. Covered: the confirmation round trip, a decline, a client that cannot elicit,
 * the write scope, the audit rows (with {@code confirmation}, no content), sessions bound to the (issuer, subject)
 * principal, and the scope check on {@code GET} and {@code DELETE}.
 */
@Testcontainers
class HttpPublicPurgeIT {

    private static final String ISSUER = "https://idp.example.com/realms/test";
    private static final String RESOURCE = "https://mail.example.com/mcp";
    private static final String BOTH = "imap-mcp:read imap-mcp:write";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer(DovecotContainer.Profile.FULL);

    @TempDir
    static Path dir;

    private static PostgresFixture postgres;
    private static PublicComponents components;
    private static HttpLocalServer server;
    private static RSAKey signing;
    private static final Map<String, TenantId> tenants = new LinkedHashMap<>();
    private static final Map<String, ResolvedAccount> boxes = new LinkedHashMap<>();
    private static final List<McpSchema.ElicitFormRequest> requests = new CopyOnWriteArrayList<>();
    private static final AtomicInteger connects = new AtomicInteger();

    private static String b64(int seed) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgresFixture();
        postgres.start();
        signing = new RSAKeyGenerator(2048).keyID("k1").generate();
        String yaml = """
                db:
                  url: %s
                  username: %s
                  password: env:DB_PW
                oauth:
                  issuer: %s
                  resource: %s
                  requiredRole: imap-mcp-user
                  webClientId: imap-mcp-web
                  webClientSecret: env:WEB_SECRET
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
                purge:
                  enabled: true
                http:
                  stateful: true
                  initializeRatePerMinute: 20
                  allowedHosts: ["mail.example.com:*", "127.0.0.1:*"]
                tools:
                  deadline: 60s
                """.formatted(postgres.getJdbcUrl(), postgres.getUsername(), ISSUER, RESOURCE);
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, yaml);
        Map<String, String> env = Map.of("DB_PW", postgres.getPassword(), "WEB_SECRET", "web-secret", "HANDLE_KEY_1", b64(1), "KEK_1", b64(2),
                "IMAP_MCP_HTTP_PORT", "8081");
        PublicConfig config = PublicConfigLoader.load(file, env);
        AppPaths paths = AppPaths.resolve(new String[] {"--config", file.toString()},
                Map.of(AppPaths.ENV_STATE_DIR, dir.resolve("state").toString()), dir, dir);
        components = PublicComponents.createForTest(config, paths, Clock.systemUTC(), env, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, policy -> new de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory(policy,
                host -> {
            connects.incrementAndGet(); // one name resolution per new IMAP connection
            return new java.net.InetAddress[] {java.net.InetAddress.getLoopbackAddress()};
        }, a -> false, p -> true));
        JdbcAccountRepository accounts = new JdbcAccountRepository(components.database().dataSource());
        for (String user : List.of("alice", "bob", "dave", "frank", "erin")) { // dave: the decline cooldown is per tenant and account
            TenantId tenant = components.tenantDirectory().create(ISSUER, user).tenantId();
            tenants.put(user, tenant);
            ResolvedAccount box = accounts.create(tenant, new NewAccount(user + " box", null, "imap.example.com",
                    DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC,
                    "purge-pub-" + user, false, !user.equals("erin"))); // erin: allow_purge false
            components.credentials().insert(tenant, box.account().id(), 1, DovecotContainer.PASSWORD.toCharArray());
            boxes.put(user, box);
        }
        server = HttpPublicServer.build(config.withPort(0), components,
                new ImmutableJWKSet<>(new JWKSet(signing.toPublicJWK())));
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
        if (postgres != null) {
            postgres.stop();
        }
    }

    /** D12: {@code http.initializeRatePerMinute} reaches the server; capacity is twice the rate. */
    @Test
    void initializeRateFromTheConfigurationLimitsAPrincipalWith429() throws Exception {
        components.tenantDirectory().create(ISSUER, "ivan");
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
        String token = token("ivan", BOTH);
        HttpResponse<String> last = null;
        for (int i = 0; i < 40; i++) { // capacity 2 x 20
            last = postInitialize(init, token);
            assertThat(last.statusCode()).as("initialize " + i).isEqualTo(200);
        }
        last = postInitialize(init, token);
        assertThat(last.statusCode()).isEqualTo(429);
        assertThat(last.headers().firstValue("Retry-After")).isPresent();
        assertThat(last.headers().firstValue("WWW-Authenticate")).isEmpty();
        // another principal is unaffected
        assertThat(postInitialize(init, token("alice", BOTH)).statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> postInitialize(String body, String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ---- helpers ----

    private static String token(String subject, String scope) throws Exception {
        long now = System.currentTimeMillis();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(ISSUER).audience(RESOURCE).subject(subject)
                .issueTime(new Date(now)).expirationTime(new Date(now + 600_000));
        if (scope != null) {
            b.claim("scope", scope);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1")
                .type(JOSEObjectType.JWT).build(), b.build());
        jwt.sign(new RSASSASigner(signing));
        return jwt.serialize();
    }

    private static McpSyncClient client(String token,
            Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> handler) {
        McpClient.SyncSpec spec = McpClient.sync(HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + server.port()).endpoint("/mcp")
                .httpRequestCustomizer((b, m, u, body, ctx) -> b.header("Authorization", "Bearer " + token)).build())
                .requestTimeout(Duration.ofSeconds(120)).initializationTimeout(Duration.ofSeconds(30));
        if (handler != null) {
            spec = spec.capabilities(McpSchema.ClientCapabilities.builder().elicitation(true, false).build())
                    .elicitation(req -> {
                        requests.add(req);
                        return handler.apply(req);
                    });
        }
        McpSyncClient c = spec.build();
        c.initialize();
        return c;
    }

    private static Map<String, Object> call(McpSyncClient c, String tool, Map<String, Object> args) {
        return ToolResults.payload(c.callTool(new McpSchema.CallToolRequest(tool, args)));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    private static String account(String user) {
        return boxes.get(user).handle();
    }

    private static String trash(McpSyncClient c, String user) {
        return list(call(c, "list_folders", Map.of("account", account(user))), "folders").stream()
                .filter(f -> "trash".equals(f.get("role"))).map(f -> (String) f.get("folder")).findFirst().orElseThrow();
    }

    private static void seed(McpSyncClient c, String user, String... subjects) throws MessagingException {
        String trash = trash(c, user);
        try (MailSeeder s = DOVECOT.seeder("purge-pub-" + user)) {
            for (String subject : subjects) {
                s.appendPlain(trash, subject, "body of " + subject);
            }
        }
    }

    private static Map<String, String> handles(McpSyncClient c, String user) {
        Map<String, Object> search = call(c, "search_messages",
                Map.of("account", account(user), "folder", trash(c, user), "page_size", 100));
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> m : list(search, "messages")) {
            out.put((String) map(m, "untrusted").get("subject"), (String) m.get("handle"));
        }
        return out;
    }

    private static List<String> subjects(McpSyncClient c, String user) throws MessagingException {
        try (MailSeeder s = DOVECOT.seeder("purge-pub-" + user)) {
            IMAPFolder f = (IMAPFolder) s.store().getFolder(trash(c, user));
            f.open(Folder.READ_ONLY);
            try {
                List<String> out = new ArrayList<>();
                for (var m : f.getMessages()) {
                    out.add(m.getSubject());
                }
                return out;
            } finally {
                f.close(false);
            }
        }
    }

    private static Map<String, Object> purge(McpSyncClient c, String user, String... handles) {
        return call(c, "purge_messages", Map.of("account", account(user), "handles", List.of(handles)));
    }

    private static HttpResponse<String> raw(String method, String body, String token, String session)
            throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18");
        if (token != null) {
            r.header("Authorization", "Bearer " + token);
        }
        if (session != null) {
            r.header("Mcp-Session-Id", session);
        }
        return HTTP.send(r.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int count(String sql) throws Exception {
        try (var c = components.database().dataSource().getConnection();
                var st = c.createStatement();
                var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
            + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";

    // ---- tests ----

    @Test
    void purgeMessagesIsRegisteredWhenPurgeIsEnabledOverTheStatefulTransport() throws Exception {
        try (McpSyncClient c = client(token("alice", BOTH), null)) {
            assertThat(c.listTools().tools()).extracting(McpSchema.Tool::name).contains("purge_messages");
        }
    }

    @Test
    void theConfirmationRoundTripsOverTheCallsStreamAndTheAuditRowsCarryTheConfirmation() throws Exception {
        requests.clear();
        try (McpSyncClient c = client(token("alice", BOTH),
                req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of()))) {
            seed(c, "alice", "pub-gone-1", "pub-keep");
            Map<String, String> h = handles(c, "alice");
            Map<String, Object> r = purge(c, "alice", h.get("pub-gone-1"));
            assertThat(r).containsEntry("confirmation", "elicited");
            assertThat(list(r, "results")).extracting(i -> i.get("status")).containsExactly("ok");
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).message()).startsWith("Permanently delete 1 message from the Trash folder")
                    .doesNotContain("pub-gone");
            assertThat(subjects(c, "alice")).contains("pub-keep").doesNotContain("pub-gone-1");
        }
        String alice = tenants.get("alice").value();
        assertThat(count("SELECT count(*) FROM audit_event WHERE tool = 'purge_messages' AND tenant_id = '" + alice
                + "'::uuid AND confirmation = 'elicited' AND item_status = 'ok' AND ref_hash IS NOT NULL")).isEqualTo(1);
        try (var c = components.database().dataSource().getConnection();
                var st = c.createStatement();
                var rs = st.executeQuery("SELECT string_agg(row_to_json(a)::text, '') FROM audit_event a")) {
            rs.next();
            assertThat(rs.getString(1)).doesNotContain("pub-gone").doesNotContain(account("alice"))
                    .doesNotContain("Trash");
        }
    }

    @Test
    void aDeclineDeletesNothingAndIsAuditedPerItemWithoutAnItemStatus() throws Exception {
        try (McpSyncClient c = client(token("dave", BOTH),
                req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null))) {
            seed(c, "dave", "pub-declined");
            String h = handles(c, "dave").get("pub-declined");
            Map<String, Object> r = purge(c, "dave", h);
            assertThat(map(map(r, "error"), "details")).containsEntry("reason", "not_confirmed")
                    .containsEntry("elicitation", "declined");
            assertThat(subjects(c, "dave")).contains("pub-declined");
        }
        assertThat(count("SELECT count(*) FROM audit_event WHERE tool = 'purge_messages' AND tenant_id = '"
                + tenants.get("dave").value() + "'::uuid AND confirmation = 'declined' AND error_code = "
                + "'PERMISSION_DENIED' AND item_status IS NULL")).isPositive();
    }

    @Test
    void aClientThatCannotElicitGetsConfirmationUnavailableAndNothingIsDeleted() throws Exception {
        try (McpSyncClient c = client(token("bob", BOTH), null)) {
            seed(c, "bob", "pub-no-elicit");
            String h = handles(c, "bob").get("pub-no-elicit");
            Map<String, Object> r = purge(c, "bob", h);
            assertThat(map(map(r, "error"), "details")).containsEntry("reason", "confirmation_unavailable");
            assertThat(subjects(c, "bob")).contains("pub-no-elicit");
        }
    }

    @Test
    void purgeNeedsTheWriteScope() throws Exception {
        try (McpSyncClient c = client(token("bob", "imap-mcp:read"),
                req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of()))) {
            Map<String, Object> r = purge(c, "bob", "msg_" + "A".repeat(40));
            assertThat(r.toString()).contains("insufficient_scope").contains("imap-mcp:write");
        }
    }

    @Test
    void anotherPrincipalsAccountIsUnknownToPurgeToo() throws Exception {
        try (McpSyncClient c = client(token("bob", BOTH),
                req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of()))) {
            Map<String, Object> r = call(c, "purge_messages",
                    Map.of("account", account("alice"), "handles", List.of("msg_" + "A".repeat(40))));
            assertThat(map(r, "error")).containsEntry("code", "ACCOUNT_NOT_FOUND");
        }
    }

    @Test
    void aSessionIsBoundToTheIssuerAndSubjectOfItsTokenAndAnotherPrincipalGets404() throws Exception {
        HttpResponse<String> init = raw("POST", INIT, token("alice", BOTH), null);
        assertThat(init.statusCode()).isEqualTo(200);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(raw("POST", LIST, token("alice", BOTH), session).statusCode()).isEqualTo(200);
        HttpResponse<String> foreign = raw("POST", LIST, token("bob", BOTH), session);
        HttpResponse<String> unknown = raw("POST", LIST, token("bob", BOTH), "no-such-session");
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).isEqualTo(unknown.body());
        for (String m : List.of("GET", "DELETE")) {
            assertThat(raw(m, null, token("bob", BOTH), session).statusCode()).as(m).isEqualTo(404);
        }
        assertThat(raw("POST", LIST, token("alice", BOTH), session).statusCode()).isEqualTo(200); // still alive
        assertThat(raw("DELETE", null, token("alice", BOTH), session).statusCode()).isIn(200, 204);
        assertThat(raw("POST", LIST, token("alice", BOTH), session).statusCode()).isEqualTo(404);
    }

    @Test
    void getAndDeleteGoThroughTheTokenAndTheReadScopeCheckBeforeAnySession() throws Exception {
        for (String m : List.of("GET", "DELETE")) {
            HttpResponse<String> none = raw(m, null, null, "any");
            assertThat(none.statusCode()).as(m + " no token").isEqualTo(401);
            HttpResponse<String> noScope = raw(m, null, token("alice", "openid"), "any");
            assertThat(noScope.statusCode()).as(m + " no scope").isEqualTo(403);
            assertThat(noScope.headers().firstValue("WWW-Authenticate"))
                    .hasValueSatisfying(v -> assertThat(v)
                            .startsWith("Bearer realm=\"imap-mcp\", error=\"insufficient_scope\", resource_metadata=\"")
                            .endsWith("\", scope=\"imap-mcp:read imap-mcp:write\""));
        }
    }

    @Test
    void twoPrincipalsWithoutATenantRowStillHaveSeparateSessions() throws Exception {
        // both map to the one sentinel tenant: the session binds to (issuer, subject), not to the tenant
        HttpResponse<String> init = raw("POST", INIT, token("ghost-one", BOTH), null);
        assertThat(init.statusCode()).isEqualTo(200);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(raw("POST", LIST, token("ghost-one", BOTH), session).statusCode()).isEqualTo(200);
        assertThat(raw("POST", LIST, token("ghost-two", BOTH), session).statusCode()).isEqualTo(404);
        assertThat(raw("GET", null, token("ghost-two", BOTH), session).statusCode()).isEqualTo(404);
        assertThat(raw("DELETE", null, token("ghost-two", BOTH), session).statusCode()).isEqualTo(404);
        assertThat(raw("POST", LIST, token("ghost-one", BOTH), session).statusCode()).isEqualTo(200);
    }

    @Test
    void aSessionApprovalCoversTheNextCallOfThatSessionOnlyAndIsAudited() throws Exception {
        requests.clear();
        Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> allowForSession = req ->
                new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("allow_for_session", true));
        Map<String, String> h;
        try (McpSyncClient first = client(token("frank", BOTH), allowForSession)) {
            seed(first, "frank", "app-1", "app-2", "app-3");
            h = handles(first, "frank");
            assertThat(purge(first, "frank", h.get("app-1"))).containsEntry("confirmation", "elicited");
            assertThat(purge(first, "frank", h.get("app-2"))).containsEntry("confirmation", "session_approval");
            assertThat(requests).hasSize(1);
        }
        // a second session of the same principal is asked again
        try (McpSyncClient second = client(token("frank", BOTH),
                req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of()))) {
            assertThat(purge(second, "frank", h.get("app-3"))).containsEntry("confirmation", "elicited");
        }
        assertThat(count("SELECT count(*) FROM audit_event WHERE tool = 'purge_messages' AND tenant_id = '"
                + tenants.get("frank").value() + "'::uuid AND confirmation = 'session_approval' AND item_status = 'ok'"))
                .isEqualTo(1);
    }

    @Test
    void anAccountWithoutAllowPurgeIsPurgeDisabledWithoutAnyImapConnection() throws Exception {
        int before = connects.get();
        try (McpSyncClient c = client(token("erin", BOTH),
                req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of()))) {
            Map<String, Object> r = call(c, "purge_messages",
                    Map.of("account", account("erin"), "handles", List.of("msg_" + "A".repeat(40))));
            assertThat(map(map(r, "error"), "details")).containsEntry("reason", "purge_disabled");
        }
        assertThat(connects.get()).as("IMAP connections opened").isEqualTo(before);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tool = 'purge_messages' AND tenant_id = '"
                + tenants.get("erin").value() + "'::uuid AND error_code = 'PERMISSION_DENIED' AND ref_hash IS NULL"))
                .isPositive();
    }
}
