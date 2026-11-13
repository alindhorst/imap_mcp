package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup failures of the http-public wiring that happen before the database is opened (SPEC 5.7): every message names
 * the config path and never key material.
 */
class PublicComponentsStartupTest {

    private static final String BASE = """
            db:
              url: jdbc:postgresql://127.0.0.1:1/none
              username: u
              password: env:DB_PW
            oauth:
              issuer: https://idp.example.com/realms/test
              resource: https://mail.example.com/mcp
              requiredRole: r
              webClientId: web
              webClientSecret: env:WEB_SECRET
            public:
              baseUrl: https://mail.example.com
            security:
              handleKeys:
                - id: 1
                  key: env:HK1
                - id: 2
                  key: env:HK2
              signingKeyId: 1
            ssrf:
              allowedHosts: ["imap.example.com"]
            kms:
              provider: local
              local:
                currentKekId: a
                keks:
                  - id: a
                    key: env:KEK_A
                  - id: b
                    key: env:KEK_B
            """;

    @TempDir
    Path dir;

    private static String key(int seed) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    private Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("DB_PW", "pw");
        env.put("WEB_SECRET", "web-secret");
        env.put("HK1", key(1));
        env.put("HK2", key(2));
        env.put("KEK_A", key(3));
        env.put("KEK_B", key(4));
        return env;
    }

    private void start(String yaml, Map<String, String> env) throws IOException {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, yaml);
        PublicConfig config = PublicConfigLoader.load(file, env);
        AppPaths paths = new AppPaths(file, dir.resolve("state"), dir.resolve("logs"));
        PublicComponents.create(config, paths, Clock.systemUTC(), env).close();
    }

    @Test
    void anUnresolvableWebClientSecretFailsStartupNamingThePathBeforeTheDatabaseIsOpened() {
        Map<String, String> env = env();
        env.remove("WEB_SECRET");
        assertThatThrownBy(() -> start(BASE, env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("oauth.webClientSecret").hasMessageContaining("config.yaml")
                .hasMessageNotContaining("web-secret");
    }

    @Test
    void aMalformedHandleKeyNamesTheConfigPathAndNotTheValue() {
        Map<String, String> env = env();
        env.put("HK2", "not-a-valid-key-SECRETVALUE");
        assertThatThrownBy(() -> start(BASE, env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("security.handleKeys[1].key").hasMessageNotContaining("SECRETVALUE");
    }

    @Test
    void theSameKeyUnderTwoHandleKeyIdsIsRefused() {
        Map<String, String> env = env();
        env.put("HK2", key(1));
        assertThatThrownBy(() -> start(BASE, env)).hasMessageContaining("security.handleKeys[1].key")
                .hasMessageContaining("same key");
    }

    @Test
    void anUnresolvableKeyReferenceNamesThePath() {
        Map<String, String> env = env();
        env.remove("HK1");
        assertThatThrownBy(() -> start(BASE, env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("security.handleKeys[0].key");
    }

    @Test
    void aMalformedKekAndTheSameKekUnderTwoIdsAreRefused() {
        Map<String, String> env = env();
        env.put("KEK_B", "short");
        assertThatThrownBy(() -> start(BASE, env)).hasMessageContaining("kms.local.keks[1].key");
        Map<String, String> same = env();
        same.put("KEK_B", key(3));
        assertThatThrownBy(() -> start(BASE, same)).hasMessageContaining("kms.local.keks[1].key")
                .hasMessageContaining("same key");
    }

    @Test
    void aBadSsrfPatternFailsStartupNamingTheKey() {
        String yaml = BASE.replace("allowedHosts: [\"imap.example.com\"]", "allowedHosts: [\"*.com\"]");
        assertThatThrownBy(() -> start(yaml, env())).hasMessageContaining("ssrf.");
    }

    @Test
    void aBadCidrFailsStartupNamingTheKey() {
        String yaml = BASE.replace("allowedHosts: [\"imap.example.com\"]",
                "allowedHosts: [\"imap.example.com\"]\n  extraBlockedCidrs: [\"not-a-cidr\"]");
        assertThatThrownBy(() -> start(yaml, env())).hasMessageContaining("ssrf.");
    }

    @Test
    void theStatefulTransportIsNoLongerRefusedAtStartup() {
        // the wiring now goes on to open the database (unreachable here), which is the failure that is reported
        String yaml = BASE + "http:\n  stateful: true\n";
        assertThatThrownBy(() -> start(yaml, env())).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("database").hasMessageNotContaining("not available in this build");
    }

    @Test
    void purgeIsRegisteredOnlyWithPurgeEnabledOverTheStatefulTransport() throws IOException {
        Path file = dir.resolve("predicate.yaml");
        for (var c : new String[][] {{"", "false"}, {"http:\n  stateful: true\n", "false"},
                {"purge:\n  enabled: true\nhttp:\n  stateful: true\ntools:\n  deadline: 60s\n", "true"}}) {
            Files.writeString(file, BASE + c[0]);
            assertThat(PublicComponents.purgeRegistered(PublicConfigLoader.load(file, env()))).as(c[0])
                    .isEqualTo(Boolean.parseBoolean(c[1]));
        }
    }

    @Test
    void sessionsAreBoundToIssuerAndSubjectOfTheTokenAndNotToTheTenant() {
        var request = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(request.getAttribute(de.alexanderlindhorst.imapmcp.transport.http.JwtTokenFilter
                .PRINCIPAL_ATTRIBUTE)).thenReturn(new de.alexanderlindhorst.imapmcp.transport.http.JwtPrincipal(
                        "https://idp.example.com/realms/test", "alice", Map.of()));
        var other = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(other.getAttribute(de.alexanderlindhorst.imapmcp.transport.http.JwtTokenFilter
                .PRINCIPAL_ATTRIBUTE)).thenReturn(new de.alexanderlindhorst.imapmcp.transport.http.JwtPrincipal(
                        "https://idp.example.com/realms/test", "bob", Map.of()));
        var none = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        assertThat(HttpPublicServer.sessionPrincipal(request)).isNotNull()
                .isNotEqualTo(HttpPublicServer.sessionPrincipal(other));
        assertThat(HttpPublicServer.sessionPrincipal(none)).isNull();
    }

    @Test
    void theFingerprintIsTwelveHexCharactersAndDiffersPerKey() {
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) 1);
        assertThat(PublicComponents.fingerprint(a)).matches("[0-9a-f]{12}").isEqualTo(PublicComponents.fingerprint(a))
                .isNotEqualTo(PublicComponents.fingerprint(b));
    }

    @Test
    void anUnresolvableKekReferenceNamesThePath() {
        Map<String, String> env = env();
        env.remove("KEK_A");
        assertThatThrownBy(() -> start(BASE, env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("kms.local.keks[0].key");
    }

    @Test
    void aMalformedKekValueIsNeverInTheMessage() {
        Map<String, String> env = env();
        env.put("KEK_A", "malformed-SECRETKEK-value");
        assertThatThrownBy(() -> start(BASE, env)).hasMessageContaining("kms.local.keks[0].key")
                .hasMessageNotContaining("SECRETKEK");
    }

    // ---- Vault: startup key check (SPEC 5.7) ----

    private com.sun.net.httpserver.HttpServer vault;
    private volatile int vaultStatus = 200;
    private volatile String vaultBody = "{\"data\":{\"type\":\"aes256-gcm96\"}}";
    private final java.util.List<String> vaultRequests = new java.util.concurrent.CopyOnWriteArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void stopVault() {
        if (vault != null) {
            vault.stop(0);
        }
    }

    private String vaultYaml() throws IOException {
        vault = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        vault.createContext("/", ex -> {
            vaultRequests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                    + ex.getRequestHeaders().getFirst("X-Vault-Token"));
            byte[] b = vaultBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(vaultStatus, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        vault.start();
        return without(BASE, "kms") + """
                kms:
                  provider: vault
                  vault:
                    address: http://127.0.0.1:%d
                    allowInsecureHttp: true
                    keyName: imap-mcp
                    auth:
                      method: token
                      token: env:VAULT_TOKEN
                """.formatted(vault.getAddress().getPort());
    }

    private static String without(String yaml, String key) {
        StringBuilder out = new StringBuilder();
        boolean skipping = false;
        for (String line : yaml.split("\n")) {
            if (!line.startsWith(" ") && !line.isEmpty()) {
                skipping = line.startsWith(key + ":");
            }
            if (!skipping) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void aVaultKeyOfTheRightTypePassesTheCheckAndStartupContinuesToTheDatabase() throws IOException {
        Map<String, String> env = env();
        env.put("VAULT_TOKEN", "s.token-SECRET");
        assertThatThrownBy(() -> start(vaultYaml(), env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("Cannot open the database");
        assertThat(vaultRequests).containsExactly("GET /v1/transit/keys/imap-mcp s.token-SECRET");
    }

    @Test
    void aVaultKeyOfAnotherTypeOrMissingFailsStartupNamingTheKeyName() throws IOException {
        Map<String, String> env = env();
        env.put("VAULT_TOKEN", "t");
        String yaml = vaultYaml();
        vaultBody = "{\"data\":{\"type\":\"rsa-4096\"}}";
        assertThatThrownBy(() -> start(yaml, env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("kms.vault.keyName").hasMessageNotContaining("rsa-4096");
        vaultStatus = 404;
        vaultBody = "{\"errors\":[]}";
        assertThatThrownBy(() -> start(yaml, env)).hasMessageContaining("kms.vault.keyName");
    }

    @Test
    void anUnreachableOrRefusingVaultFailsStartupWithoutLeakingTheTokenOrTheResponse() throws IOException {
        Map<String, String> env = env();
        env.put("VAULT_TOKEN", "s.token-SECRET");
        String yaml = vaultYaml();
        vaultStatus = 503;
        vaultBody = "{\"errors\":[\"vault is sealed RESPONSETEXT\"]}";
        assertThatThrownBy(() -> start(yaml, env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("kms.vault").hasMessageNotContaining("SECRET")
                .hasMessageNotContaining("RESPONSETEXT");
        vault.stop(0);
        assertThatThrownBy(() -> start(yaml, env)).hasMessageContaining("kms.vault").hasMessageNotContaining("SECRET");
    }

    @Test
    void anUnresolvableVaultTokenReferenceFailsStartupNamingTheKms() throws IOException {
        String yaml = vaultYaml();
        assertThatThrownBy(() -> start(yaml, env())).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("kms.vault");
    }

    @Test
    void anUnreadableCaCertFailsStartupNamingTheKey() throws IOException {
        Map<String, String> env = env();
        env.put("VAULT_TOKEN", "t");
        String yaml = vaultYaml().replace("keyName: imap-mcp", "keyName: imap-mcp\n    caCert: /nonexistent/ca.pem");
        assertThatThrownBy(() -> start(yaml, env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("kms.vault.caCert");
    }

    @Test
    void aNotFoundKeyTextNamesTheMountAndTheKeyName() throws IOException {
        Map<String, String> env = env();
        env.put("VAULT_TOKEN", "t");
        String yaml = vaultYaml();
        vaultStatus = 404;
        vaultBody = "{\"errors\":[]}";
        assertThatThrownBy(() -> start(yaml, env)).hasMessageContaining("kms.vault.transitMount")
                .hasMessageContaining("kms.vault.keyName");
    }

    @Test
    void theCaCertMessageNamesTheKeyOnce() throws IOException {
        Map<String, String> env = env();
        env.put("VAULT_TOKEN", "t");
        String yaml = vaultYaml().replace("keyName: imap-mcp", "keyName: imap-mcp\n    caCert: /nonexistent/ca.pem");
        assertThatThrownBy(() -> start(yaml, env)).satisfies(e -> assertThat(e.getMessage().split("kms\\.vault\\.caCert", -1))
                .hasSize(2));
    }

    @Test
    void anApproleLoginFailureAtStartupNamesTheKmsAndNotTheSecret() throws IOException {
        Map<String, String> env = env();
        env.put("RID", "role-id-SECRET");
        env.put("SID", "secret-id-SECRET");
        String yaml = vaultYaml().replace("method: token\n      token: env:VAULT_TOKEN",
                "method: approle\n      roleId: env:RID\n      secretId: env:SID");
        vaultStatus = 403;
        vaultBody = "{\"errors\":[\"permission denied RESPONSETEXT\"]}";
        assertThatThrownBy(() -> start(yaml, env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("kms.vault").hasMessageNotContaining("SECRET")
                .hasMessageNotContaining("RESPONSETEXT");
        assertThat(vaultRequests).anyMatch(r -> r.startsWith("POST /v1/auth/approle/login"));
    }

    @Test
    void noInsecureHttpWarningWithAnHttpsAddressEvenIfTheFlagIsSet() throws IOException {
        Logger log = (Logger) LoggerFactory.getLogger("imapmcp.app");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level old = log.getLevel();
        log.setLevel(Level.TRACE);
        appender.start();
        log.addAppender(appender);
        try {
            Map<String, String> env = env();
            env.put("VAULT_TOKEN", "t");
            String yaml = vaultYaml().replace("address: http://", "address: https://");
            assertThatThrownBy(() -> start(yaml, env)).isInstanceOf(AppStartupException.class); // nothing speaks TLS
            assertThat(appender.list).noneSatisfy(e -> assertThat(e.getFormattedMessage())
                    .contains("kms.vault.allowInsecureHttp"));
        } finally {
            log.detachAppender(appender);
            log.setLevel(old);
        }
    }

    @Test
    void insecureHttpToVaultGivesAStartupWarning() throws IOException {
        Logger log = (Logger) LoggerFactory.getLogger("imapmcp.app");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level old = log.getLevel();
        log.setLevel(Level.TRACE);
        appender.start();
        log.addAppender(appender);
        try {
            Map<String, String> env = env();
            env.put("VAULT_TOKEN", "t");
            String yaml = vaultYaml();
            assertThatThrownBy(() -> start(yaml, env)).isInstanceOf(AppStartupException.class);
            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("kms.vault.allowInsecureHttp");
            });
        } finally {
            log.detachAppender(appender);
            log.setLevel(old);
        }
    }

    private List<ILoggingEvent> startupLog(String yaml) {
        Logger log = (Logger) LoggerFactory.getLogger("imapmcp.app");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level old = log.getLevel();
        log.setLevel(Level.TRACE);
        appender.start();
        log.addAppender(appender);
        try {
            assertThatThrownBy(() -> start(yaml, env())).isInstanceOf(AppStartupException.class); // no database here
            return new java.util.ArrayList<>(appender.list);
        } finally {
            log.detachAppender(appender);
            log.setLevel(old);
        }
    }

    private static final String UPSTREAM = BASE.replace("allowedHosts: [\"imap.example.com\"]",
            "allowedHosts: [\"imap.example.com\", \"imap.gmail.com\", \"outlook.office365.com\"]")
            .replace("  webClientSecret: env:WEB_SECRET\n",
            "  webClientSecret: env:WEB_SECRET\n  upstream:\n    providers:\n"
                    + "      gmail:\n        kind: google\n        clientId: CLIENT-ID-CANARY\n"
                    + "        clientSecret: env:G_SECRET\n"
                    + "      work-ms:\n        kind: microsoft\n        clientId: CLIENT-ID-CANARY-2\n"
                    + "        clientSecret: env:M_SECRET\n"
                    + "      corp:\n        kind: generic\n        clientId: CLIENT-ID-CANARY-3\n"
                    + "        clientSecret: env:C_SECRET\n"
                    + "        authorizationEndpoint: https://login.corp.example.net/authorize\n"
                    + "        tokenEndpoint: https://login.corp.example.net/token\n"
                    + "        scopes: [\"imap\"]\n        imap:\n          host: imap.example.com\n");

    private Map<String, String> upstreamEnv() {
        Map<String, String> env = env();
        env.put("G_SECRET", "GSECRET-CANARY");
        env.put("M_SECRET", "MSECRET-CANARY");
        env.put("C_SECRET", "CSECRET-CANARY");
        return env;
    }

    private List<ILoggingEvent> startupLog(String yaml, Map<String, String> env) {
        Logger log = (Logger) LoggerFactory.getLogger("imapmcp");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level old = log.getLevel();
        log.setLevel(Level.TRACE);
        appender.start();
        log.addAppender(appender);
        try {
            assertThatThrownBy(() -> start(yaml, env)).isInstanceOf(AppStartupException.class); // no database here
            return new java.util.ArrayList<>(appender.list);
        } finally {
            log.detachAppender(appender);
            log.setLevel(old);
        }
    }

    @Test
    void anUnresolvableUpstreamClientSecretFailsStartupNamingThePathBeforeTheDatabaseIsOpened() {
        Map<String, String> env = upstreamEnv();
        env.remove("M_SECRET");
        assertThatThrownBy(() -> start(UPSTREAM, env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("oauth.upstream.providers.work-ms.clientSecret")
                .hasMessageContaining("config.yaml").hasMessageNotContaining("CANARY");
    }

    @Test
    void eachGoogleAndMicrosoftProviderGivesAnUnverifiedWarningWithItsIdOnly() {
        List<ILoggingEvent> log = startupLog(UPSTREAM, upstreamEnv());
        for (String id : List.of("gmail", "work-ms")) {
            assertThat(log).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("not verified against the real provider").contains(id);
            });
        }
        assertThat(log).filteredOn(e -> e.getFormattedMessage().contains("not verified against the real provider"))
                .hasSize(2).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("corp"));
    }

    @Test
    void theStartupLogHoldsNeitherAClientIdNorAClientSecret() {
        List<ILoggingEvent> log = startupLog(UPSTREAM, upstreamEnv());
        assertThat(log).isNotEmpty().allSatisfy(e -> assertThat(e.getFormattedMessage() + e.getThrowableProxy())
                .doesNotContain("CANARY"));
    }

    @Test
    void noUnverifiedWarningWithoutGoogleOrMicrosoftProviders() {
        assertThat(startupLog(BASE)).noneSatisfy(
                e -> assertThat(e.getFormattedMessage()).contains("not verified against the real provider"));
    }

    @Test
    void initializeRateWithoutStatefulGivesAStartupWarning() {
        assertThat(startupLog(BASE + "http:\n  initializeRatePerMinute: 5\n")).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("http.initializeRatePerMinute").contains("http.stateful");
        });
    }

    @Test
    void noInitializeRateWarningWhenStatefulOrWhenTheKeyIsNotSet() {
        assertThat(startupLog(BASE + "http:\n  stateful: true\n  initializeRatePerMinute: 5\n")).noneSatisfy(
                e -> assertThat(e.getFormattedMessage()).contains("http.initializeRatePerMinute"));
        assertThat(startupLog(BASE)).noneSatisfy(
                e -> assertThat(e.getFormattedMessage()).contains("http.initializeRatePerMinute"));
    }

    @Test
    void allowWithoutRoleGivesAStartupWarning() {
        Logger log = (Logger) LoggerFactory.getLogger("imapmcp.app");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level old = log.getLevel();
        log.setLevel(Level.TRACE);
        appender.start();
        log.addAppender(appender);
        try {
            String yaml = BASE.replace("  requiredRole: r\n", "  allowWithoutRole: true\n");
            assertThatThrownBy(() -> start(yaml, env())).isInstanceOf(AppStartupException.class); // no database here
            assertThat(appender.list).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getFormattedMessage()).contains("oauth.allowWithoutRole");
            });
        } finally {
            log.detachAppender(appender);
            log.setLevel(old);
        }
    }

    @Test
    void thePoolSizeReachesTheDatabaseConfiguration() {
        PublicConfig.Db db = new PublicConfig.Db("jdbc:postgresql://h/d", "u",
                new de.alexanderlindhorst.imapmcp.core.account.SecretRef("env:X"), 37);
        assertThat(PublicComponents.dbConfig(db, "pw".toCharArray()).maxPoolSize()).isEqualTo(37);
    }
}
