package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.LocalKeyWrapper;
import de.alexanderlindhorst.imapmcp.store.oauth.TokenProvider;
import de.alexanderlindhorst.imapmcp.store.oauth.TokenProviders;
import de.alexanderlindhorst.imapmcp.store.secret.BinaryProbe;
import de.alexanderlindhorst.imapmcp.store.secret.Platform;
import de.alexanderlindhorst.imapmcp.store.secret.ProcessCommandRunner;
import de.alexanderlindhorst.imapmcp.store.secret.RefCredentialStore;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The XOAUTH2 refresh chain of {@code http-public} (SPEC 5.5, 5.7): providers, token client, grant store, cache. */
class PublicOAuthUpstreamTest {

    private static final String YAML = """
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
              upstream:
                providers:
                  corp:
                    kind: generic
                    clientId: cid
                    clientSecret: env:CORP_SECRET
                    authorizationEndpoint: https://login.example.com/authorize
                    tokenEndpoint: https://login.example.com/token
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
                  key: env:HK1
              signingKeyId: 1
            ssrf:
              allowedHosts: ["imap.example.com", "login.example.com"]
            kms:
              provider: local
              local:
                currentKekId: a
                keks:
                  - id: a
                    key: env:KEK_A
            """;

    @TempDir
    Path dir;

    private static final DataSource NO_DB = (DataSource) Proxy.newProxyInstance(
            PublicOAuthUpstreamTest.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
                throw new SQLException("no database in this test");
            });

    private PublicConfig config() throws Exception {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, YAML);
        return PublicConfigLoader.load(file, Map.of());
    }

    private RefCredentialStore refs(PublicConfig config) {
        return RefCredentialStore.withEnvAndKeyring(new TenantId("config"), List.of(),
                Map.of("CORP_SECRET", "s3cret"), Platform.current(), new ProcessCommandRunner(),
                BinaryProbe.system(Map.of()), config.secrets().keyringTimeout());
    }

    @Test
    void aConfiguredProviderIsAdaptedWithItsSecretReadAtUse() throws Exception {
        PublicConfig config = config();
        TokenProviders providers = PublicOAuthUpstream.providers(config.oauth().upstreamProviders(), refs(config));

        TokenProvider p = providers.find("corp").orElseThrow();

        assertThat(p.id()).isEqualTo("corp");
        assertThat(p.clientId()).isEqualTo("cid");
        assertThat(p.tokenEndpoint().toString()).isEqualTo("https://login.example.com/token");
        try (Secret s = p.clientSecret().get()) {
            assertThat(s.chars()).isEqualTo("s3cret".toCharArray());
        }
    }

    @Test
    void anIdThatIsNotConfiguredHasNoProvider() throws Exception {
        PublicConfig config = config();
        TokenProviders providers = PublicOAuthUpstream.providers(config.oauth().upstreamProviders(), refs(config));

        assertThat(providers.find("gone")).isEmpty();
    }

    @Test
    void theChainIsBuiltForAGenericProviderAndTheRefreshExecutorStopsOnClose() throws Exception {
        PublicConfig config = config();
        byte[] kek = new byte[32];
        Arrays.fill(kek, (byte) 3);
        byte[] master = new byte[32];
        Arrays.fill(master, (byte) 1);
        SsrfPolicy ssrf = SsrfPolicy.of(config.ssrf().allowedHosts(), false, List.of());
        PublicOAuthUpstream upstream = PublicOAuthUpstream.create(config.oauth().upstreamProviders(), refs(config),
                NO_DB, ssrf, new CredentialCipher(new LocalKeyWrapper("a", Map.of("a", kek)), new SecureRandom()),
                new LogIds(new KeyRing(1, Map.of(1, master))), MetricsRecorder.NOOP, Clock.systemUTC());

        assertThat(upstream.tokens()).isNotNull();
        assertThat(upstream.isClosed()).isFalse();
        upstream.close();
        assertThat(upstream.isClosed()).isTrue();
    }

    private static List<ILoggingEvent> warn(Map<String, Long> stored, Set<String> configured) {
        Logger log = (Logger) LoggerFactory.getLogger("imapmcp.app");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        log.addAppender(appender);
        try {
            PublicOAuthUpstream.warnUnconfigured(stored, configured);
        } finally {
            log.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void theWarnCountsAccountsOfUnconfiguredProvidersAndNamesNoProviderOrAccount() {
        List<ILoggingEvent> warns = warn(Map.of("corp", 4L, "gone", 2L, "old-google", 1L), Set.of("corp"));

        assertThat(warns).hasSize(1);
        String text = warns.get(0).getFormattedMessage();
        assertThat(text).contains("3");
        assertThat(text).doesNotContain("gone").doesNotContain("old-google").doesNotContain("corp");
    }

    @Test
    void noWarnWhenEveryStoredProviderIsConfigured() {
        assertThat(warn(Map.of("corp", 4L), Set.of("corp"))).isEmpty();
        assertThat(warn(Map.of(), Set.of())).isEmpty();
    }
}
