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
    void theStatefulTransportIsRefusedUntilItIsWired() {
        String yaml = BASE + "http:\n  stateful: true\n";
        assertThatThrownBy(() -> start(yaml, env())).hasMessageContaining("http.stateful");
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

    @Test
    void theVaultProviderIsRefusedAtStartup() {
        String yaml = BASE.replace("provider: local", "provider: vault");
        assertThatThrownBy(() -> start(yaml, env())).hasMessageContaining("kms.provider");
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
