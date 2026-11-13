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
import de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.LocalKeyWrapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.testcontainers.lifecycle.Startables;

/**
 * XOAUTH2 through the wired {@code http-public} app (SPEC 5.5, 5.8; #498): a {@code generic} provider whose token
 * endpoint is a JDK {@code HttpsServer} stub (strict rotation, Nimbus JWTs that Dovecot validates), Postgres for the
 * grants, and Dovecot 2.4.5 with the oauth2 passdb ({@code xoauth2} profile) and without XOAUTH2
 * ({@code no-xoauth2} profile). The app is the production wiring; only the IMAP socket factory (loopback) and the token
 * client (the production {@code TokenEndpointClient} with the stub's resolver, port and test CA) are test seams.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Xoauth2WiredIT {

    private static final String ISSUER = "https://idp.example.com/realms/test";
    private static final String RESOURCE = "https://mail.example.com/mcp";
    private static final String TOKEN_HOST = "token.example.com";
    private static final String LINK_URL = "https://mail.example.com/accounts";
    private static final String CLIENT_SECRET = "CANARY-client-secret-5d1c";

    @TempDir
    static Path dir;

    private static final Xoauth2Jwt JWT = new Xoauth2Jwt();
    private static PostgresFixture postgres;
    private static DovecotOauth2Container withXoauth2;
    private static DovecotOauth2Container withoutXoauth2;
    private static Xoauth2Pki pki;
    private static Xoauth2TokenStub stub;
    private static RSAKey signing;
    private static PublicComponents components;
    private static HttpLocalServer server;
    private static CredentialCipher cipher;
    private static final AtomicInteger connects = new AtomicInteger();
    private static final ListAppender<ILoggingEvent> LOGS = new ListAppender<>();
    private static Level rootLevel;
    private static Xoauth2TapProxy tapXoauth2;
    private static Xoauth2TapProxy tapNone;
    private static final List<String> CANARIES = Collections.synchronizedList(new ArrayList<>());

    private static String b64(int seed) {
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
        postgres = new PostgresFixture();
        withXoauth2 = new DovecotOauth2Container(DovecotOauth2Container.Mode.XOAUTH2, JWT);
        withoutXoauth2 = new DovecotOauth2Container(DovecotOauth2Container.Mode.NONE, JWT);
        Startables.deepStart(postgres, withXoauth2, withoutXoauth2).join();
        pki = new Xoauth2Pki(dir, TOKEN_HOST);
        stub = new Xoauth2TokenStub(pki, JWT);
        tapXoauth2 = new Xoauth2TapProxy(pki.serverContext(), withXoauth2.getHost(), withXoauth2.imapsPort());
        tapNone = new Xoauth2TapProxy(pki.serverContext(), withoutXoauth2.getHost(), withoutXoauth2.imapsPort());
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
                  upstream:
                    providers:
                      stub:
                        kind: generic
                        clientId: client
                        clientSecret: env:STUB_SECRET
                        authorizationEndpoint: https://%s/authorize
                        tokenEndpoint: https://%s:%d/token
                        scopes: ["mail"]
                        imap:
                          host: imap.example.com
                          port: 993
                          security: implicit-tls
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
                """.formatted(postgres.getJdbcUrl(), postgres.getUsername(), ISSUER, RESOURCE, TOKEN_HOST,
                TOKEN_HOST, stub.port());
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, yaml);
        Map<String, String> env = Map.of("DB_PW", postgres.getPassword(), "WEB_SECRET", "web-secret", "STUB_SECRET",
                CLIENT_SECRET, "HANDLE_KEY_1", b64(1), "KEK_1", b64(2), "IMAP_MCP_HTTP_PORT", "8081");
        PublicConfig config = PublicConfigLoader.load(file, env);
        AppPaths paths = AppPaths.resolve(new String[] {"--config", file.toString()},
                Map.of(AppPaths.ENV_STATE_DIR, dir.resolve("state").toString()), dir, dir);
        javax.net.ssl.SSLContext tokenTrust = pki.clientContext();
        components = PublicComponents.createForTest(config, paths, Clock.systemUTC(), env, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, policy -> new PinnedSocketFactory(policy, host -> {
            connects.incrementAndGet(); // one name resolution per new IMAP connection
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, a -> false, p -> true), policy -> ItTokenClients.toLoopback(tokenTrust, stub.port()));
        byte[] kek = new byte[32];
        Arrays.fill(kek, (byte) 2);
        cipher = new CredentialCipher(new LocalKeyWrapper("kek-1", Map.of("kek-1", kek)), new SecureRandom());
        server = HttpPublicServer.build(config.withPort(0), components,
                new ImmutableJWKSet<>(new JWKSet(signing.toPublicJWK())));
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
        if (stub != null) {
            stub.close();
        }
        for (Xoauth2TapProxy tap : new Xoauth2TapProxy[] {tapXoauth2, tapNone}) {
            if (tap != null) {
                tap.close();
            }
        }
        for (var c : new org.testcontainers.containers.GenericContainer<?>[] {withXoauth2, withoutXoauth2, postgres}) {
            if (c != null) {
                c.stop();
            }
        }
    }

    // ---- helpers ----

    private static String mcpToken(String subject) throws Exception {
        long now = System.currentTimeMillis();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).audience(RESOURCE).subject(subject)
                .issueTime(new Date(now)).expirationTime(new Date(now + 600_000)).claim("scope", "imap-mcp:read")
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1")
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(signing));
        return jwt.serialize();
    }

    private static McpSyncClient client(String subject) throws Exception {
        String token = mcpToken(subject);
        McpSyncClient c = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.port())
                .endpoint("/mcp")
                .httpRequestCustomizer((b, m, u, body, ctx) -> b.header("Authorization", "Bearer " + token)).build())
                .requestTimeout(Duration.ofSeconds(60)).initializationTimeout(Duration.ofSeconds(30)).build();
        c.initialize();
        return c;
    }

    private static Map<String, Object> call(String subject, String tool, Map<String, Object> args) throws Exception {
        try (McpSyncClient c = client(subject)) {
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

    private static TenantId newTenant(String subject) throws Exception {
        return components.tenantDirectory().create(ISSUER, subject).tenantId();
    }

    private static Xoauth2Db.Seeded account(TenantId tenant, String id, DovecotOauth2Container dovecot, String user,
            String refreshToken) throws Exception {
        stub.register(refreshToken, user);
        CANARIES.add(refreshToken);
        Xoauth2TapProxy tap = dovecot == withXoauth2 ? tapXoauth2 : tapNone;
        return Xoauth2Db.insertXoauth2Account(components.database().dataSource(), cipher, tenant, id,
                "imap.example.com", tap.port(), user, refreshToken);
    }

    private static String canary() {
        return "rt-CANARY-" + UUID.randomUUID();
    }

    private static String shortId() {
        return "a" + UUID.randomUUID().toString().substring(0, 12);
    }

    private static javax.sql.DataSource ds() {
        return components.database().dataSource();
    }

    // ---- Dovecot xoauth2 profile ----

    @Test
    @Order(1)
    void theReadToolsWorkOverXoauth2WithPeekAndNoFlagChange() throws Exception {
        TenantId tenant = newTenant("sub-read");
        Xoauth2Db.Seeded box = account(tenant, shortId(), withXoauth2, "alice", canary());
        try (MailSeeder seeder = new MailSeeder(withXoauth2.getHost(), withXoauth2.imapsPort(), "alice",
                DovecotOauth2Container.PASSWORD)) {
            seeder.appendPlain("INBOX", "peek me one", "body one");
            seeder.appendPlain("INBOX", "peek me two", "body two");
            int exchangesBefore = stub.exchanges();
            int wireFrom = tapXoauth2.clientWire().length();

            Map<String, Object> folders = call("sub-read", "list_folders", Map.of("account", box.handle()));
            assertThat(folders.toString()).contains("INBOX");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> found = (List<Map<String, Object>>) call("sub-read", "search_messages",
                    Map.of("account", box.handle(), "folder", "INBOX")).get("messages");
            assertThat(found).isNotEmpty();
            for (Map<String, Object> m : found) {
                assertThat(call("sub-read", "get_message", Map.of("account", box.handle(), "message",
                        m.get("handle"))).toString()).contains("body");
            }

            assertThat(stub.exchanges() - exchangesBefore).as("one refresh, the token is cached").isEqualTo(1);
            Folder inbox = seeder.store().getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                for (Message m : inbox.getMessages()) {
                    assertThat(m.isSet(Flags.Flag.SEEN)).as("\\Seen of message %d", m.getMessageNumber()).isFalse();
                }
            } finally {
                inbox.close(false);
            }
            String wire = tapXoauth2.clientWire().substring(wireFrom);
            assertThat(wire).contains("AUTHENTICATE XOAUTH2").contains("BODY.PEEK[").doesNotContain("LOGIN");
            assertThat(wire.lines().filter(l -> l.contains("FETCH") && l.contains("BODY[")).toList())
                    .as("a FETCH without PEEK").isEmpty();
        }
    }

    @Test
    @Order(2)
    void anExpiredTokenIsRefreshedAndTheCallRetriesOnce() throws Exception {
        TenantId tenant = newTenant("sub-expired");
        Xoauth2Db.Seeded box = account(tenant, shortId(), withXoauth2, "alice", canary());
        AtomicBoolean expireNext = new AtomicBoolean(true);
        stub.accessTokens((sub, n) -> expireNext.getAndSet(false) ? JWT.token(sub, Duration.ofSeconds(-30))
                : JWT.token(sub, Duration.ofMinutes(5)));
        try {
            int exchangesBefore = stub.exchanges();

            Map<String, Object> out = call("sub-expired", "list_folders", Map.of("account", box.handle()));

            assertThat(out).doesNotContainKey("error");
            assertThat(stub.exchanges() - exchangesBefore).as("the first token, then one renewal").isEqualTo(2);
            assertThat(Xoauth2Db.status(ds(), box)).isEqualTo("active");
        } finally {
            stub.accessTokenTtl(Duration.ofMinutes(5));
        }
    }

    @Test
    @Order(3)
    void aTokenRejectedAfterTheRetryIsXoauth2RejectedAndNeverCountsTowardsNeedsRelink() throws Exception {
        TenantId tenant = newTenant("sub-rejected");
        Xoauth2Db.Seeded box = account(tenant, shortId(), withXoauth2, "alice", canary());
        long revision = Xoauth2Db.revision(ds(), box);
        stub.accessTokenTtl(Duration.ofSeconds(-30)); // every token the provider issues is already expired
        try {
            int exchangesBefore = stub.exchanges();
            for (int i = 0; i < 4; i++) { // more than the three refusals that flip a password account
                Map<String, Object> err = error(call("sub-rejected", "list_folders", Map.of("account", box.handle())));
                assertThat(err).containsEntry("code", "AUTH_FAILED").containsEntry("retryable", false);
                assertThat(details(err)).containsEntry("reason", "xoauth2_rejected");
            }
            // the first call takes a token and renews it once; later calls find the (rejected) token in the cache
            // and renew it once
            assertThat(stub.exchanges() - exchangesBefore).as("1 + 1 retry, then 1 renewal per call").isEqualTo(5);
        } finally {
            stub.accessTokenTtl(Duration.ofMinutes(5));
        }
        assertThat(Xoauth2Db.status(ds(), box)).isEqualTo("active");
        assertThat(Xoauth2Db.revision(ds(), box)).isEqualTo(revision);
        assertThat(Xoauth2Db.scalar(ds(), "SELECT failed_logins FROM mailbox_account WHERE tenant_id = ? AND id = ?",
                Xoauth2Db.uuid(tenant), box.account().value())).isZero();
        assertThat(Xoauth2Db.auditCount(ds(), box, "credential_revoked")).isZero();
        // and it recovers by itself once the provider issues good tokens
        assertThat(call("sub-rejected", "list_folders", Map.of("account", box.handle()))).doesNotContainKey("error");
    }

    // ---- Dovecot no-xoauth2 profile ----

    @Test
    @Order(4)
    void aServerWithoutXoauth2IsNeverSentTheTokenInALogin() throws Exception {
        TenantId tenant = newTenant("sub-guard");
        Xoauth2Db.Seeded box = account(tenant, shortId(), withoutXoauth2, "alice", canary());
        int wireFrom = tapNone.clientWire().length();
        int serverFrom = tapNone.serverWire().length();
        int connectionsBefore = tapNone.connections();

        Map<String, Object> out = call("sub-guard", "list_folders", Map.of("account", box.handle()));

        // the guard's refusal is reported like a refused token: AUTH_FAILED xoauth2_rejected, not retryable
        assertThat(error(out)).containsEntry("code", "AUTH_FAILED").containsEntry("retryable", false);
        assertThat(details(error(out))).containsEntry("reason", "xoauth2_rejected");
        assertThat(tapNone.connections()).as("the server was reached").isGreaterThan(connectionsBefore);
        assertThat(tapNone.serverWire().substring(serverFrom)).as("the server advertised no XOAUTH2")
                .contains("CAPABILITY").doesNotContain("XOAUTH2");
        String wire = tapNone.clientWire().substring(wireFrom);
        assertThat(wire.lines().filter(l -> l.matches("(?i)^\\S+ (LOGIN|AUTHENTICATE)\\b.*")).toList())
                .as("no LOGIN or AUTHENTICATE command on the wire").isEmpty();
        for (String issued : stub.issuedAccessTokens()) {
            assertThat(wire).doesNotContain(issued);
        }
        assertThat(Xoauth2Db.status(ds(), box)).isEqualTo("active");
    }

    // ---- tenant isolation ----

    @Test
    @Order(5)
    void theSameAccountIdUnderTwoTenantsKeepsSeparateGrantsAndCachesAndAForeignHandleDoesNothing() throws Exception {
        TenantId a = newTenant("sub-tenant-a");
        TenantId b = newTenant("sub-tenant-b");
        String id = shortId();
        String refreshA = canary();
        String refreshB = canary();
        Xoauth2Db.Seeded boxA = account(a, id, withXoauth2, "alice", refreshA);
        Xoauth2Db.Seeded boxB = account(b, id, withXoauth2, "bob", refreshB);
        assertThat(boxA.account()).isEqualTo(boxB.account());
        assertThat(boxA.handle()).isNotEqualTo(boxB.handle());

        // each tenant gets a token for its own Dovecot user: a cache or grant lookup on the account id alone would
        // hand one tenant the other's token, and Dovecot answers NO to a token of another user
        assertThat(call("sub-tenant-a", "list_folders", Map.of("account", boxA.handle()))).doesNotContainKey("error");
        assertThat(call("sub-tenant-b", "list_folders", Map.of("account", boxB.handle()))).doesNotContainKey("error");
        assertThat(call("sub-tenant-a", "list_folders", Map.of("account", boxA.handle()))).doesNotContainKey("error");
        assertThat(stub.presentedRefreshTokens()).contains(refreshA, refreshB);
        assertThat(Xoauth2Db.refreshVersion(ds(), boxA)).isEqualTo(2);
        assertThat(Xoauth2Db.refreshVersion(ds(), boxB)).isEqualTo(2);

        // a foreign handle: zero I/O (no token request, no IMAP connection), same answer as an unknown handle
        int requests = stub.requests();
        int connectsBefore = connects.get();
        Map<String, Object> foreign = error(call("sub-tenant-b", "list_folders", Map.of("account", boxA.handle())));
        Map<String, Object> unknown = error(call("sub-tenant-b", "list_folders",
                Map.of("account", Xoauth2Db.newHandle())));
        assertThat(foreign.get("code")).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(foreign).isEqualTo(withoutRequestId(unknown, foreign));
        assertThat(stub.requests()).isEqualTo(requests);
        assertThat(connects.get()).isEqualTo(connectsBefore);
        assertThat(Xoauth2Db.status(ds(), boxB)).isEqualTo("active");
    }

    private static Map<String, Object> withoutRequestId(Map<String, Object> other, Map<String, Object> like) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(other);
        m.put("request_id", like.get("request_id"));
        return m;
    }

    // ---- invalid_grant ----

    @Test
    @Order(6)
    void invalidGrantFlipsTheAccountAndTheNextCallFailsWithRelinkRequiredWithoutAnyIo() throws Exception {
        TenantId tenant = newTenant("sub-revoked");
        String refresh = canary();
        Xoauth2Db.Seeded box = account(tenant, shortId(), withXoauth2, "alice", refresh);
        long revision = Xoauth2Db.revision(ds(), box);
        stub.revoke(refresh);

        Map<String, Object> first = error(call("sub-revoked", "list_folders", Map.of("account", box.handle())));

        assertThat(first).containsEntry("code", "AUTH_FAILED").containsEntry("retryable", false);
        assertThat(details(first)).containsEntry("reason", "relink_required").containsEntry("link_url", LINK_URL);
        assertThat(Xoauth2Db.status(ds(), box)).isEqualTo("needs_relink");
        assertThat(Xoauth2Db.revision(ds(), box)).isEqualTo(revision + 1);
        assertThat(Xoauth2Db.auditCount(ds(), box, "credential_revoked")).isEqualTo(1);

        int requests = stub.requests();
        int connectsBefore = connects.get();
        Map<String, Object> second = error(call("sub-revoked", "list_folders", Map.of("account", box.handle())));
        assertThat(details(second)).containsEntry("reason", "relink_required").containsEntry("link_url", LINK_URL);
        assertThat(stub.requests()).as("no token request").isEqualTo(requests);
        assertThat(connects.get()).as("no IMAP connection").isEqualTo(connectsBefore);
    }

    // ---- logs ----

    @Test
    @Order(100)
    void noLogLineAuditRowOrWireDumpHoldsATokenOrTheClientSecret() throws Exception {
        List<String> secrets = new ArrayList<>(CANARIES);
        secrets.addAll(stub.issuedRefreshTokens());
        secrets.addAll(stub.issuedAccessTokens());
        secrets.add(CLIENT_SECRET);
        assertThat(secrets).hasSizeGreaterThan(10);
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
        // the wire of the no-XOAUTH2 server never carried a token (the XOAUTH2 server's wire does, base64 in SASL)
        assertThat(tapNone.clientWire()).doesNotContain("LOGIN").doesNotContain("AUTHENTICATE");
        assertThat(audit).contains("credential_revoked");
    }
}
