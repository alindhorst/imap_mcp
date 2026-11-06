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
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
 * Embedded {@code download_attachment} end to end over http-public (SPEC 4 "v3b: tools in http-public", 5.8): real
 * Postgres, the real components and filter chain, tokens signed locally, a GreenMail IMAPS server behind the
 * SSRF-checked connect (host name {@code imap.example.com} pinned to loopback by the test seam). Handles come from
 * real {@code search_messages} and {@code list_attachments} calls.
 */
class HttpPublicDownloadIT {

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
    private static TenantId tenantBob;
    private static ResolvedAccount aliceAccount;
    private static ResolvedAccount bobAccount;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final java.util.Random RANDOM = new java.util.Random(11);
    private static byte[] pdf;
    private static byte[] png;
    private static byte[] exe;
    private static byte[] big;

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
                public:
                  baseUrl: https://mail.example.com
                security:
                  handleKeys:
                    - id: 1
                      key: env:HANDLE_KEY_1
                  signingKeyId: 1
                ssrf:
                  allowedHosts: ["*.example.com"]
                tools:
                  download:
                    inlineMaxBytes: 20KiB
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
        mail.createUser("alice@localhost", "alice", "pw");
        mail.createUser("bob@localhost", "bob", "pw");
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
        tenantBob = directory.create(ISSUER, "bob").tenantId();
        aliceAccount = accounts.create(tenantAlice, new NewAccount("Alice box", null, "imap.example.com",
                mail.imapsPort(), MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "alice", true, false));
        components.credentials().insert(tenantAlice, aliceAccount.account().id(), 1, "pw".toCharArray());
        bobAccount = accounts.create(tenantBob, new NewAccount("Bob box", null, "imap.example.com",
                mail.imapsPort(), MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "bob", true, false));
        components.credentials().insert(tenantBob, bobAccount.account().id(), 1, "pw".toCharArray());
        pdf = randomBytes(5000);
        png = randomBytes(3000);
        exe = randomBytes(100);
        big = randomBytes(40_000);
        try (MailSeeder seeder = mail.seeder("alice", "pw")) {
            seeder.appendMultipart("INBOX", "download fixture", "see attached", "<p>see attached</p>", List.of(
                    new MailSeeder.Attachment("doc.pdf", "application/pdf", pdf),
                    new MailSeeder.Attachment("pic.png", "image/png", png),
                    new MailSeeder.Attachment("tool.exe", "application/x-msdownload", exe),
                    new MailSeeder.Attachment("big.bin", "application/octet-stream", big)));
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
        if (mail != null) {
            mail.close();
        }
        postgres.stop();
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


    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** The JSON-RPC response as a tree; the transport may answer plain JSON or a single SSE event. */
    private static JsonNode rpcResult(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).isEqualTo(200);
        String body = r.body().strip();
        if (body.startsWith("event:") || body.startsWith("data:")) {
            body = body.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5).strip())
                    .findFirst().orElseThrow();
        }
        return JSON.readTree(body).get("result");
    }

    private static JsonNode call(String subject, String tool, String argumentsJson) throws Exception {
        return rpcResult(callTool(token(subject, "imap-mcp:read"), tool, argumentsJson));
    }

    private static String messageHandle() throws Exception {
        JsonNode r = call("alice", "search_messages",
                "{\"account\":\"" + aliceAccount.handle() + "\",\"folder\":\"INBOX\"}");
        JsonNode handle = r.get("structuredContent").findValue("handle");
        assertThat(handle).as(r.toString()).isNotNull();
        return handle.asString();
    }

    private static String partOf(String handle, String filename) throws Exception {
        JsonNode r = call("alice", "list_attachments",
                "{\"account\":\"" + aliceAccount.handle() + "\",\"message\":\"" + handle + "\"}");
        for (JsonNode a : r.get("structuredContent").get("attachments")) {
            if (filename.equals(a.get("untrusted").get("filename").asString())) {
                return a.get("part").asString();
            }
        }
        throw new AssertionError("no attachment " + filename + " in " + r);
    }

    private static JsonNode download(String subject, String account, String handle, String part) throws Exception {
        return call(subject, "download_attachment",
                "{\"account\":\"" + account + "\",\"message\":\"" + handle + "\",\"part\":\"" + part + "\"}");
    }

    private static java.util.Map<String, Long> fileTree() throws Exception {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toMap(
                    p -> dir.relativize(p).toString(), p -> p.toFile().length(), (x, y) -> x));
        }
    }

    // ---- tests ----

    @Test
    void anAttachmentComesBackAsTheTextBlockPlusOneEmbeddedResourceByteExact() throws Exception {
        String handle = messageHandle();

        JsonNode r = download("alice", aliceAccount.handle(), handle, partOf(handle, "doc.pdf"));

        assertThat(r.path("isError").asBoolean(false)).as(r.toString()).isFalse();
        JsonNode content = r.get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("type").asString()).isEqualTo("text");
        JsonNode structured = r.get("structuredContent");
        assertThat(JSON.readTree(content.get(0).get("text").asString())).isEqualTo(structured);
        assertThat(structured.get("delivery").asString()).isEqualTo("embedded");
        assertThat(structured.get("directory").isNull()).isTrue();
        assertThat(structured.get("renamed").asBoolean()).isFalse();
        assertThat(structured.get("bytes").asLong()).isEqualTo(pdf.length);
        assertThat(structured.get("sha256").asString()).isEqualTo(sha256(pdf));
        assertThat(structured.get("untrusted").get("saved_as").isNull()).isTrue();
        assertThat(structured.get("untrusted").get("path").isNull()).isTrue();
        JsonNode resource = content.get(1);
        assertThat(resource.get("type").asString()).isEqualTo("resource");
        JsonNode blob = resource.get("resource");
        assertThat(blob.get("uri").asString()).isEqualTo("imap-mcp://attachment/" + sha256(pdf));
        assertThat(blob.get("mimeType").asString()).isEqualTo("application/pdf");
        byte[] received = Base64.getDecoder().decode(blob.get("blob").asString());
        assertThat(received).isEqualTo(pdf);
        assertThat(sha256(received)).isEqualTo(structured.get("sha256").asString());
    }

    @Test
    void theMimeTypeComesFromTheFixedListOtherwiseOctetStream() throws Exception {
        String handle = messageHandle();

        JsonNode image = download("alice", aliceAccount.handle(), handle, partOf(handle, "pic.png"));
        JsonNode exeResult = download("alice", aliceAccount.handle(), handle, partOf(handle, "tool.exe"));

        assertThat(image.get("content").get(1).get("resource").get("mimeType").asString()).isEqualTo("image/png");
        JsonNode other = exeResult.get("content").get(1).get("resource");
        assertThat(other.get("mimeType").asString()).isEqualTo("application/octet-stream");
        assertThat(Base64.getDecoder().decode(other.get("blob").asString())).isEqualTo(exe);
        assertThat(exeResult.get("structuredContent").get("untrusted").get("content_type").asString())
                .isEqualTo("application/x-msdownload");
    }

    @Test
    void aPartAboveInlineMaxBytesIsTooLargeWithOneTextBlockAndNoStructuredContent() throws Exception {
        String handle = messageHandle();

        JsonNode r = download("alice", aliceAccount.handle(), handle, partOf(handle, "big.bin"));

        assertThat(r.get("isError").asBoolean()).isTrue();
        assertThat(r.get("content")).hasSize(1);
        assertThat(r.has("structuredContent")).isFalse();
        JsonNode error = JSON.readTree(r.get("content").get(0).get("text").asString()).get("error");
        assertThat(error.get("code").asString()).isEqualTo("TOO_LARGE");
        assertThat(error.get("details").get("max").asLong()).isEqualTo(20 * 1024);
    }

    @Test
    void theDownloadChangesNothingOnTheServerOrOnDisk() throws Exception {
        String handle = messageHandle();
        String part = partOf(handle, "doc.pdf");
        var filesBefore = fileTree();
        int countBefore;
        try (MailSeeder seeder = mail.seeder("alice", "pw")) {
            countBefore = seeder.messageCount("INBOX");
        }

        JsonNode r = download("alice", aliceAccount.handle(), handle, part);

        assertThat(r.path("isError").asBoolean(false)).isFalse();
        assertThat(fileTree()).as("nothing is written to disk").isEqualTo(filesBefore);
        try (MailSeeder seeder = mail.seeder("alice", "pw")) {
            assertThat(seeder.messageCount("INBOX")).isEqualTo(countBefore);
            var inbox = seeder.store().getFolder("INBOX");
            inbox.open(jakarta.mail.Folder.READ_ONLY);
            try {
                for (var m : inbox.getMessages()) {
                    assertThat(m.getFlags().contains(jakarta.mail.Flags.Flag.SEEN)).as("PEEK: still unread").isFalse();
                }
            } finally {
                inbox.close(false);
            }
        }
    }

    @Test
    void anotherTenantCannotUseTheHandleOrTheAccount() throws Exception {
        String handle = messageHandle();
        String part = partOf(handle, "doc.pdf");

        // bob with his own account handle and alice's message handle: the handle is signed for alice's account
        JsonNode foreignHandle = download("bob", bobAccount.handle(), handle, part);
        // bob with alice's account handle
        JsonNode foreignAccount = download("bob", aliceAccount.handle(), handle, part);

        for (JsonNode r : List.of(foreignHandle, foreignAccount)) {
            assertThat(r.get("isError").asBoolean()).isTrue();
            assertThat(r.get("content")).hasSize(1);
            assertThat(r.get("content").get(0).get("text").asString()).doesNotContain("blob");
        }
        assertThat(foreignHandle.get("content").get(0).get("text").asString()).contains("INVALID_HANDLE");
        assertThat(foreignAccount.get("content").get(0).get("text").asString()).contains("ACCOUNT_NOT_FOUND");
    }
}
