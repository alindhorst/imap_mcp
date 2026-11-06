package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --mode http-public} in the shaded jar (SPEC 2, 5.7): Flyway (ServiceLoader plugins) and the Postgres driver
 * work inside the jar, the service becomes healthy, serves the RFC 9728 metadata, and startup failures are one line
 * on stderr that names the config path and never a secret.
 */
class HttpPublicJarIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String HANDLE_KEY = key(1);
    private static final String KEK = key(2);

    private static PostgresFixture postgres;

    @TempDir
    static Path dir;

    @BeforeAll
    static void start() {
        postgres = new PostgresFixture();
        postgres.withPassword("pw-CANARY-9f3a71"); // distinctive, so that a leak in the log is detectable
        postgres.start();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    private static String key(int seed) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static ServerConfig config(Path root, String dbUrl, int port, String extraYaml) throws IOException {
        Files.createDirectories(root);
        Path file = root.resolve("config.yaml");
        Files.writeString(file, """
                db:
                  url: %s
                  username: %s
                  password: env:DB_PW
                oauth:
                  issuer: https://idp.example.com/realms/test
                  resource: https://mail.example.com/mcp
                  requiredRole: imap-mcp-user
                  webClientId: imap-mcp-web
                  webClientSecret: env:WEB_SECRET
                public:
                  baseUrl: https://mail.example.com
                security:
                  handleKeys:
                    - id: 1
                      key: env:HANDLE_KEY
                  signingKeyId: 1
                ssrf:
                  allowedHosts: ["imap.example.com"]
                kms:
                  provider: local
                  local:
                    currentKekId: kek-1
                    keks:
                      - id: kek-1
                        key: env:KEK
                http:
                  port: %d
                  allowedHosts: ["127.0.0.1:*", "mail.example.com:*"]
                %s""".formatted(dbUrl, postgres.getUsername(), port, extraYaml));
        Map<String, String> env = new HashMap<>();
        env.put("DB_PW", postgres.getPassword());
        env.put("WEB_SECRET", "web-secret");
        env.put("HANDLE_KEY", HANDLE_KEY);
        env.put("KEK", KEK);
        env.put("IMAP_MCP_STATE_DIR", root.resolve("state").toString());
        env.put("IMAP_MCP_LOG_DIR", root.resolve("logs").toString());
        return new ServerConfig(file, root.resolve("state"), root.resolve("logs"), env, List.of());
    }

    private static boolean healthy(int port) throws Exception {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (IOException notYet) {
            return false;
        }
    }

    @Test
    void theJarMigratesTheDatabaseBecomesHealthyAndServesTheMetadata() throws Exception {
        int port = freePort();
        ServerConfig cfg = config(dir.resolve("ok"), postgres.getJdbcUrl(), port, "");
        try (RunningServer s = JarLauncher.start(cfg, "http-public")) {
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (!healthy(port)) {
                assertThat(s.process().isAlive()).as("server alive: " + s.stderr()).isTrue();
                assertThat(System.nanoTime()).as("healthy in time").isLessThan(deadline);
                Thread.sleep(250);
            }
            HttpResponse<String> meta = HTTP.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + "/.well-known/oauth-protected-resource/mcp")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(meta.statusCode()).isEqualTo(200);
            assertThat(meta.body()).contains("\"resource\":\"https://mail.example.com/mcp\"")
                    .contains("\"authorization_servers\":[\"https://idp.example.com/realms/test\"]");
            HttpResponse<String> noToken = HTTP.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + "/mcp")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(noToken.statusCode()).isEqualTo(401);
            assertThat(s.stdout().available()).isZero();
        }
        String log = Files.readString(cfg.logFile());
        assertThat(log).contains("handle key id=1 fingerprint=").contains("local KEK id=kek-1 fingerprint=")
                .doesNotContain(HANDLE_KEY).doesNotContain(KEK).doesNotContain(postgres.getPassword());
    }

    @Test
    void aMissingDatabaseFailsStartupNamingTheKey() throws Exception {
        ServerConfig cfg = config(dir.resolve("nodb"), postgres.getJdbcUrl(), freePort(), "");
        String yaml = Files.readString(cfg.configFile()).replaceFirst("(?s)db:.*?oauth:", "oauth:");
        Files.writeString(cfg.configFile(), yaml);
        try (RunningServer s = JarLauncher.start(cfg, "http-public")) {
            assertThat(s.waitFor(60)).isTrue();
            assertThat(s.exitCode()).isEqualTo(1);
            assertThat(s.stderr()).contains("startup failed").contains("db.url: required");
            assertThat(s.stdout().readAllBytes()).isEmpty();
        }
    }

    @Test
    void anUnreachableDatabaseFailsStartupWithoutLeakingTheUrlOrThePassword() throws Exception {
        ServerConfig cfg = config(dir.resolve("baddb"), "jdbc:postgresql://127.0.0.1:1/none", freePort(), "");
        try (RunningServer s = JarLauncher.start(cfg, "http-public")) {
            assertThat(s.waitFor(60)).isTrue();
            assertThat(s.exitCode()).isEqualTo(1);
            assertThat(s.stderr()).contains("startup failed").contains("Cannot open the database")
                    .doesNotContain(postgres.getPassword()).doesNotContain("127.0.0.1:1");
        }
    }

    @Test
    void aNonHttpsIssuerAndStaticTokenAndDownloadDirFailStartup() throws Exception {
        for (String[] bad : new String[][] {
                {"issuer: https://idp.example.com/realms/test", "issuer: http://idp.example.com/realms/test",
                        "oauth.issuer"},
                {"http:\n  port:", "http:\n  token: env:X\n  port:", "http.token"}}) {
            ServerConfig cfg = config(dir.resolve("bad-" + bad[2]), postgres.getJdbcUrl(), freePort(), "");
            Files.writeString(cfg.configFile(), Files.readString(cfg.configFile()).replace(bad[0], bad[1]));
            try (RunningServer s = JarLauncher.start(cfg, "http-public")) {
                assertThat(s.waitFor(60)).isTrue();
                assertThat(s.exitCode()).isEqualTo(1);
                assertThat(s.stderr()).contains("startup failed").contains(bad[2]);
            }
        }
        ServerConfig cfg = config(dir.resolve("bad-dir"), postgres.getJdbcUrl(), freePort(),
                "tools:\n  download:\n    dir: /tmp\n");
        try (RunningServer s = JarLauncher.start(cfg, "http-public")) {
            assertThat(s.waitFor(60)).isTrue();
            assertThat(s.exitCode()).isEqualTo(1);
            assertThat(s.stderr()).contains("tools.download.dir");
        }
    }
}
