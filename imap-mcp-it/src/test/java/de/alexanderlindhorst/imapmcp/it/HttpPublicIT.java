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
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantDirectory;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * http-public wiring end to end (SPEC 5.3, 5.7): real Postgres, the real components and filter chain, tokens signed
 * locally (the identity provider is the subject of the Keycloak IT). The IMAP side is not reachable on purpose: the
 * SSRF policy blocks loopback, which proves the pinning socket factory is installed.
 */
class HttpPublicIT {

    private static final String ISSUER = "https://idp.example.com/realms/test";
    private static final String RESOURCE = "https://mail.example.com/mcp";
    private static final String METADATA =
            "https://mail.example.com/.well-known/oauth-protected-resource/mcp";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    static Path dir;

    private static PostgresFixture postgres;
    private static GreenMailFixture mail;
    private static PublicComponents components;
    private static HttpLocalServer server;
    private static RSAKey signing;
    private static JdbcTenantDirectory directory;
    private static JdbcAccountRepository accounts;
    private static TenantId tenantAlice;
    private static ResolvedAccount aliceAccount;

    private static String b64(int seed) {
        byte[] k = new byte[32];
        new SecureRandom(new byte[] {(byte) seed}).nextBytes(k);
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
                http:
                  allowedHosts: ["mail.example.com:*", "127.0.0.1:*"]
                """.formatted(postgres.getJdbcUrl(), postgres.getUsername(), ISSUER, RESOURCE);
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, yaml);
        Map<String, String> env = Map.of("DB_PW", postgres.getPassword(), "WEB_SECRET", "web-secret", "HANDLE_KEY_1", b64(1), "KEK_1", b64(2),
                "IMAP_MCP_HTTP_PORT", "8081");
        PublicConfig config = PublicConfigLoader.load(file, env);
        AppPaths paths = AppPaths.resolve(new String[] {"--config", file.toString()},
                Map.of(AppPaths.ENV_STATE_DIR, dir.resolve("state").toString()), dir, dir);
        mail = GreenMailFixture.start();
        mail.createUser("carol@localhost", "carol", "right");
        // the SSRF-checked connect is pointed at the loopback GreenMail server for host names of the allowlist; IP
        // literals (alice's account) are still refused by the policy itself
        components = PublicComponents.createForTest(config, paths, Clock.systemUTC(), env, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, policy -> new de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory(policy,
                host -> new java.net.InetAddress[] {java.net.InetAddress.getLoopbackAddress()}, a -> false, p -> true));
        directory = components.tenantDirectory();
        accounts = new JdbcAccountRepository(components.database().dataSource());
        tenantAlice = directory.create(ISSUER, "alice").tenantId();
        aliceAccount = accounts.create(tenantAlice, new NewAccount("Alice box", null, "127.0.0.1", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "alice", false, false));
        components.credentials().insert(tenantAlice, aliceAccount.account().id(), 1, "pw".toCharArray());
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
        if (mail != null) {
            mail.close();
        }
        postgres.stop();
    }


    /** E19: error replies of the SDK carry no exception text, class names, request content or JDK version. */
    static void assertNoInternals(String what, String body) {
        assertThat(body).as(what).doesNotContain("stackTrace", "className", "fileName", "lineNumber",
                "localizedMessage", "org.eclipse.jetty", "tools.jackson", "io.modelcontextprotocol", "Exception",
                "McpSchema", "CANARY-7f3a9c", System.getProperty("java.version"));
    }

    // ---- helpers ----

    private static String token(String subject, String scope) throws Exception {
        long now = System.currentTimeMillis();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(ISSUER).audience(RESOURCE).subject(subject)
                .issueTime(new Date(now)).expirationTime(new Date(now + 300_000));
        if (scope != null) {
            b.claim("scope", scope);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1")
                .type(JOSEObjectType.JWT).build(), b.build());
        jwt.sign(new RSASSASigner(signing));
        return jwt.serialize();
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

    private static HttpResponse<String> callTool(String token, String tool, String argumentsJson) throws Exception {
        return rpc(token, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + argumentsJson + "}}");
    }

    private static int count(String sql) throws Exception {
        try (var c = components.database().dataSource().getConnection();
                var st = c.createStatement();
                var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ---- tests ----

    @Test
    void sdkErrorBodiesCarryNoInternals() throws Exception {
        String token = token("alice", "imap-mcp:read imap-mcp:write");
        for (String body : new String[] {"{not json", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":\"tools/list\"}", "{\"canary\":\"CANARY-7f3a9c\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":{}}}"}) {
            HttpResponse<String> r = rpc(token, body);
            assertNoInternals(body, r.body());
            assertThat(r.body()).as(body).contains("\"error\"");
        }
    }

    @Test
    void noTokenIs401WithTheResourceMetadataChallenge() throws Exception {
        HttpResponse<String> r = rpc(null, "{}");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).contains(
                "Bearer realm=\"imap-mcp\", resource_metadata=\"" + METADATA + "\", scope=\"imap-mcp:read imap-mcp:write\"");
    }

    @Test
    void aTokenWithoutTheReadScopeIs403InsufficientScope() throws Exception {
        HttpResponse<String> r = rpc(token("alice", "openid"), "{}");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.headers().firstValue("WWW-Authenticate"))
                .contains("Bearer realm=\"imap-mcp\", error=\"insufficient_scope\", resource_metadata=\"" + METADATA
                        + "\", scope=\"imap-mcp:read imap-mcp:write\"");
    }

    @Test
    void theFixedToolListIsRegisteredForEveryone() throws Exception {
        HttpResponse<String> r = rpc(token("stranger", "imap-mcp:read"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertThat(r.statusCode()).isEqualTo(200);
        for (String tool : List.of("list_accounts", "list_folders", "search_messages", "get_message",
                "list_attachments", "download_attachment", "set_flags", "move_messages", "copy_messages",
                "delete_messages", "create_folder")) {
            assertThat(r.body()).as(tool).contains("\"" + tool + "\"");
        }
        assertThat(r.body()).doesNotContain("purge_messages");
        // the embedded variant: read-only, no directory (SPEC 4 "v3b: tools in http-public")
        assertThat(r.body()).contains("\"readOnlyHint\":true").doesNotContain("tools.download.dir");
    }

    @Test
    void aPrincipalWithoutARowSeesNothingAndNothingIsCreated() throws Exception {
        int before = count("SELECT count(*) FROM tenant");
        HttpResponse<String> r = callTool(token("stranger", "imap-mcp:read"), "list_accounts", "{}");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"accounts\":[]").contains("\"link_url\":\"https://mail.example.com/accounts\"");
        assertThat(count("SELECT count(*) FROM tenant")).isEqualTo(before);
    }

    @Test
    void aTenantSeesOnlyItsOwnAccountAndTheSsrfPolicyBlocksLoopback() throws Exception {
        HttpResponse<String> mine = callTool(token("alice", "imap-mcp:read"), "list_accounts", "{}");
        assertThat(mine.body()).contains(aliceAccount.handle()).contains("\"status\":\"unreachable\"");
        HttpResponse<String> other = callTool(token("stranger", "imap-mcp:read"), "list_accounts", "{}");
        assertThat(other.body()).doesNotContain(aliceAccount.handle());
        HttpResponse<String> folders = callTool(token("alice", "imap-mcp:read"), "list_folders",
                "{\"account\":\"" + aliceAccount.handle() + "\"}");
        assertThat(folders.body()).contains("address_blocked");
        HttpResponse<String> foreign = callTool(token("stranger", "imap-mcp:read"), "list_folders",
                "{\"account\":\"" + aliceAccount.handle() + "\"}");
        assertThat(foreign.body()).contains("ACCOUNT_NOT_FOUND");
    }

    @Test
    void aTokenWithoutTheWriteScopeIsRefusedOnWriteTools() throws Exception {
        String args = "{\"account\":\"" + aliceAccount.handle() + "\",\"name\":\"X\"}";
        HttpResponse<String> noScope = callTool(token("alice", "imap-mcp:read"), "create_folder", args);
        assertThat(noScope.body()).contains("insufficient_scope").contains("imap-mcp:write");
    }

    @Test
    void aReadOnlyAccountRefusesWriteToolsEvenWithTheWriteScope() throws Exception {
        ResolvedAccount ro = accounts.create(tenantAlice, new NewAccount("RO", null, "127.0.0.1", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "alice", true, false));
        HttpResponse<String> r = callTool(token("alice", "imap-mcp:read imap-mcp:write"), "create_folder",
                "{\"account\":\"" + ro.handle() + "\",\"name\":\"X\"}");
        assertThat(r.body()).contains("account_read_only");
    }

    @Test
    void aTokenForAnotherAudienceIsRefusedThroughTheWiredChain() throws Exception {
        long now = System.currentTimeMillis();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience("https://other.example.com/mcp")
                .subject("alice").claim("scope", "imap-mcp:read").issueTime(new Date(now))
                .expirationTime(new Date(now + 300_000)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1")
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(signing));
        HttpResponse<String> r = rpc(jwt.serialize(), "{}");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                v -> assertThat(v).isEqualTo("Bearer realm=\"imap-mcp\", error=\"invalid_token\", resource_metadata=\""
                        + METADATA + "\", scope=\"imap-mcp:read imap-mcp:write\""));
    }

    @Test
    void aWrongHostHeaderIsRefusedBeforeTheToken() throws Exception {
        try (java.net.Socket sock = new java.net.Socket("127.0.0.1", server.port())) {
            sock.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: evil.example\r\nContent-Length: 2\r\n\r\n{}")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            sock.getOutputStream().flush();
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream()))
                    .readLine();
            assertThat(status).contains("403");
        }
    }

    @Test
    void anAccountThatNeedsARelinkIsRefusedBeforeAnyIoWithTheLinkUrl() throws Exception {
        ResolvedAccount stale = accounts.create(tenantAlice, new NewAccount("Stale", null, "127.0.0.1", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "alice", false, false));
        try (var c = components.database().dataSource().getConnection();
                var ps = c.prepareStatement("UPDATE mailbox_account SET status = 'needs_relink', revision = revision + 1"
                        + " WHERE tenant_id = ?::uuid AND id = ?")) {
            ps.setString(1, tenantAlice.value());
            ps.setString(2, stale.account().id().value());
            ps.executeUpdate();
        }
        HttpResponse<String> r = callTool(token("alice", "imap-mcp:read"), "list_folders",
                "{\"account\":\"" + stale.handle() + "\"}");
        assertThat(r.body()).contains("relink_required").contains("https://mail.example.com/accounts");
        HttpResponse<String> list = callTool(token("alice", "imap-mcp:read"), "list_accounts", "{}");
        assertThat(list.body()).contains("needs_relink");
    }

    @Test
    void aWriteCallOfAResolvedAccountLeavesAnItemLessAuditRowWithoutContent() throws Exception {
        String args = "{\"account\":\"" + aliceAccount.handle() + "\",\"name\":\"CanaryFolderName\"}";
        HttpResponse<String> r = callTool(token("alice", "imap-mcp:read imap-mcp:write"), "create_folder", args);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tool = 'create_folder' AND ref_hash IS NULL"
                + " AND result = 'failed' AND tenant_id = '" + tenantAlice.value() + "'::uuid")).isPositive();
        try (var c = components.database().dataSource().getConnection();
                var st = c.createStatement();
                var rs = st.executeQuery("SELECT string_agg(row_to_json(a)::text, '') FROM audit_event a")) {
            rs.next();
            assertThat(rs.getString(1)).doesNotContain("CanaryFolderName").doesNotContain(aliceAccount.handle());
        }
    }

    @Test
    void threeRefusedLoginsThroughTheWiredPoolFlipTheAccountAndTheNextCallIsRelinkRequired() throws Exception {
        TenantId carol = directory.create(ISSUER, "carol").tenantId();
        ResolvedAccount box = accounts.create(carol, new NewAccount("Carol box", null, "imap.example.com",
                mail.imapsPort(), MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "carol", true, false));
        components.credentials().insert(carol, box.account().id(), 1, "wrong".toCharArray());
        String args = "{\"account\":\"" + box.handle() + "\"}";
        String carolToken = token("carol", "imap-mcp:read");
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> r = callTool(carolToken, "list_folders", args);
            assertThat(r.body()).as("refusal " + (i + 1)).contains("AUTH_FAILED").doesNotContain("relink_required");
        }
        assertThat(count("SELECT count(*) FROM mailbox_account WHERE status = 'needs_relink' AND id = '"
                + box.account().id().value() + "'")).isEqualTo(1);
        HttpResponse<String> fourth = callTool(carolToken, "list_folders", args);
        assertThat(fourth.body()).contains("relink_required").contains("https://mail.example.com/accounts");
        HttpResponse<String> list = callTool(carolToken, "list_accounts", "{}");
        assertThat(list.body()).contains("needs_relink").contains("\"link_url\":\"https://mail.example.com/accounts\"");
    }

    @Test
    void metadataAndHealthAreServedWithoutAToken() throws Exception {
        for (String path : List.of("/.well-known/oauth-protected-resource",
                "/.well-known/oauth-protected-resource/mcp", "/health")) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(path).isEqualTo(200);
        }
    }

    @Test
    void theMethodGuardAndTheHostCheckComeBeforeTheToken() throws Exception {
        HttpResponse<String> get = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/mcp")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(405);
    }
}
