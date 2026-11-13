package de.alexanderlindhorst.imapmcp.it;

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
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.LocalKeyWrapper;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The production {@code http-public} wiring for the XOAUTH2 smoke (#328): the real token endpoint client and the real
 * provider (no stub, no loopback seam), Postgres for the grants (the one thing that needs Docker), and tokens for the
 * MCP side signed locally. One account per {@link #seed} call, each in its own tenant. The only deviation from
 * production is the clock, which {@link #advance} moves so that the cached access token counts as expired (forced
 * refresh). Nothing here prints a token or a result body.
 */
final class OAuthSmokeStack implements AutoCloseable {

    private static final String ISSUER = "https://idp.example.com/realms/smoke";
    private static final String RESOURCE = "https://mail.example.com/mcp";
    private static final String BOTH = "imap-mcp:read imap-mcp:write";
    private static final String KEK_ID = "kek-1";

    /** A clock that runs from the system clock plus an offset the test can move forward. */
    static final class SkewClock extends Clock {

        private volatile Duration skew = Duration.ZERO;

        void advance(Duration d) {
            skew = skew.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(skew);
        }
    }

    /** A seeded account: the handle the tools take and the tenant it lives in (the principal subject). */
    record Box(SmokeOAuthEnv.Settings settings, Xoauth2Db.Seeded seeded, String subject) {
        String handle() {
            return seeded.handle();
        }
    }

    private final Path dir;
    private final PostgresFixture postgres = new PostgresFixture();
    private final SkewClock clock = new SkewClock();
    private final RSAKey signing;
    private final CredentialCipher cipher;
    private PublicComponents components;
    private HttpLocalServer server;

    private OAuthSmokeStack(Path dir) throws Exception {
        this.dir = dir;
        this.signing = new RSAKeyGenerator(2048).keyID("k1").generate();
        byte[] kek = new byte[32];
        Arrays.fill(kek, (byte) 2);
        this.cipher = new CredentialCipher(new LocalKeyWrapper(KEK_ID, Map.of(KEK_ID, kek)), new SecureRandom());
    }

    private static String b64(int seed) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    /** The configuration of the app for one provider; {@code purge} turns on the stateful transport and the tool. */
    static String config(String dbUrl, String dbUser, SmokeOAuthEnv.Settings s, boolean purge) {
        return """
                db:
                  url: %s
                  username: %s
                  password: env:SMOKE_STACK_DB_PW
                oauth:
                  issuer: %s
                  resource: %s
                  requiredRole: imap-mcp-user
                  webClientId: imap-mcp-web
                  webClientSecret: env:SMOKE_STACK_WEB_SECRET
                  upstream:
                    providers:
                %s\
                public:
                  baseUrl: https://mail.example.com
                security:
                  handleKeys:
                    - id: 1
                      key: env:SMOKE_STACK_HANDLE_KEY
                  signingKeyId: 1
                ssrf:
                  allowAnyPublicHost: true
                kms:
                  provider: local
                  local:
                    currentKekId: %s
                    keks:
                      - id: %s
                        key: env:SMOKE_STACK_KEK
                http:
                  allowedHosts: ["mail.example.com:*", "127.0.0.1:*"]
                  stateful: %s
                tools:
                  deadline: 60s
                imap:
                  readTimeout: 60s
                %s""".formatted(dbUrl, dbUser, ISSUER, RESOURCE, s.providerYaml(), KEK_ID, KEK_ID, purge,
                purge ? "purge:\n  enabled: true\n" : "");
    }

    static OAuthSmokeStack start(SmokeOAuthEnv.Settings s) throws Exception {
        OAuthSmokeStack stack = new OAuthSmokeStack(Files.createTempDirectory("imap-mcp-oauth-smoke"));
        try {
            stack.boot(s);
            return stack;
        } catch (Exception | Error e) {
            stack.close();
            throw e;
        }
    }

    private void boot(SmokeOAuthEnv.Settings s) throws Exception {
        postgres.start();
        Map<String, String> env = new HashMap<>(System.getenv()); // the client secret reference resolves here
        env.put("SMOKE_STACK_DB_PW", postgres.getPassword());
        env.put("SMOKE_STACK_WEB_SECRET", UUID.randomUUID().toString());
        env.put("SMOKE_STACK_HANDLE_KEY", b64(1));
        env.put("SMOKE_STACK_KEK", b64(2));
        env.put("IMAP_MCP_HTTP_PORT", "8081");
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, config(postgres.getJdbcUrl(), postgres.getUsername(), s, s.purgeSubject().isPresent()));
        PublicConfig config = PublicConfigLoader.load(file, env);
        AppPaths paths = AppPaths.resolve(new String[] {"--config", file.toString()},
                Map.of(AppPaths.ENV_STATE_DIR, dir.resolve("state").toString()), dir, dir);
        components = PublicComponents.create(config, paths, clock, env);
        server = HttpPublicServer.build(config.withPort(0), components,
                new ImmutableJWKSet<>(new JWKSet(signing.toPublicJWK())));
        server.start();
    }

    DataSource ds() {
        return components.database().dataSource();
    }

    void advance(Duration d) {
        clock.advance(d);
    }

    /** A new tenant with one XOAUTH2 account of the provider holding {@code refreshToken}. */
    Box seed(SmokeOAuthEnv.Settings s, String refreshToken, boolean readOnly, boolean allowPurge) throws Exception {
        String subject = "smoke-" + UUID.randomUUID();
        TenantId tenant = components.tenantDirectory().create(ISSUER, subject).tenantId();
        boolean google = s.provider() == SmokeEnv.Provider.GMAIL;
        Xoauth2Db.Seeded seeded = Xoauth2Db.insertXoauth2Account(ds(), cipher, tenant,
                "a" + UUID.randomUUID().toString().substring(0, 12), s.providerKind(),
                google ? "https://mail.google.com/" : "https://outlook.office.com/IMAP.AccessAsUser.All offline_access",
                google ? "imap.gmail.com" : "outlook.office365.com", 993, s.user(), readOnly, allowPurge, refreshToken);
        return new Box(s, seeded, subject);
    }

    private String mcpToken(String subject) throws Exception {
        long now = System.currentTimeMillis();
        // six hours: the skewed clock of a forced refresh must not outrun the token the test itself signed
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(RESOURCE).subject(subject)
                .issueTime(new Date(now)).expirationTime(new Date(now + 6 * 3_600_000L)).claim("scope", BOTH).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1")
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(signing));
        return jwt.serialize();
    }

    /** A client for the box's principal; {@code elicit} answers every confirmation form with accept. */
    McpSyncClient client(Box box, boolean elicit) throws Exception {
        String token = mcpToken(box.subject());
        McpClient.SyncSpec spec = McpClient.sync(HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + server.port()).endpoint("/mcp")
                .httpRequestCustomizer((b, m, u, body, ctx) -> b.header("Authorization", "Bearer " + token)).build())
                .requestTimeout(Duration.ofSeconds(120)).initializationTimeout(Duration.ofSeconds(30));
        if (elicit) {
            spec = spec.capabilities(McpSchema.ClientCapabilities.builder().elicitation(true, false).build())
                    .elicitation(req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of()));
        }
        McpSyncClient c = spec.build();
        c.initialize();
        return c;
    }

    /** One call on a fresh session: the payload, {@code {"error":{...}}} for an error result. */
    Map<String, Object> call(Box box, String tool, Object... kv) throws Exception {
        try (McpSyncClient c = client(box, false)) {
            return call(c, box, tool, kv);
        }
    }

    static Map<String, Object> call(McpSyncClient c, Box box, String tool, Object... kv) {
        Map<String, Object> args = new HashMap<>();
        args.put("account", box.handle());
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        return ToolResults.payload(c.callTool(new McpSchema.CallToolRequest(tool, args)));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> payload, String key) {
        return (List<Map<String, Object>>) payload.get(key);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> payload, String key) {
        return (Map<String, Object>) payload.get(key);
    }

    /** The error code or reason only; never the payload, which may carry mail content. */
    static String errorOf(Map<String, Object> payload) {
        Map<String, Object> e = map(payload, "error");
        if (e == null) {
            return "none";
        }
        Map<String, Object> d = map(e, "details");
        return e.get("code") + (d != null && d.get("reason") != null ? "/" + d.get("reason") : "");
    }

    @Override
    public void close() {
        try {
            if (server != null) {
                server.stop();
            }
        } catch (Exception ignored) {
            // shutting down
        }
        if (components != null) {
            components.close();
        }
        postgres.stop();
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (java.io.IOException ignored) {
            // temp dir, best effort
        }
    }
}
