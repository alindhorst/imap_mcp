package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.linking.LinkOutcome;
import de.alexanderlindhorst.imapmcp.core.linking.LinkRequest;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.LocalKeyWrapper;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAuditLog;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcCredentialStore;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantDirectory;
import de.alexanderlindhorst.imapmcp.store.secret.BinaryProbe;
import de.alexanderlindhorst.imapmcp.store.secret.Platform;
import de.alexanderlindhorst.imapmcp.store.secret.ProcessCommandRunner;
import de.alexanderlindhorst.imapmcp.store.secret.RefCredentialStore;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The linking service as {@code PublicComponents} wires it, without a database (the data source refuses every
 * connection, which the gate checks never reach): the deployment switch {@code purge.enabled} is the
 * {@code allow_purge} gate of the linking surface (SPEC 4, 5.3).
 */
class PublicLinkingWiringTest {

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
            public:
              baseUrl: https://mail.example.com
            security:
              handleKeys:
                - id: 1
                  key: env:HK1
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
            %s""";

    @TempDir
    Path dir;

    private static final DataSource NO_DB = (DataSource) Proxy.newProxyInstance(
            PublicLinkingWiringTest.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
                throw new SQLException("no database in this test");
            });

    private LinkOutcome link(String extraYaml, boolean allowPurge, String host) throws Exception {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, YAML.formatted(extraYaml));
        PublicConfig config = PublicConfigLoader.load(file, Map.of());
        byte[] kek = new byte[32];
        Arrays.fill(kek, (byte) 3);
        byte[] master = new byte[32];
        Arrays.fill(master, (byte) 1);
        KeyRing ring = new KeyRing(1, Map.of(1, master));
        Clock clock = Clock.systemUTC();
        SsrfPolicy ssrf = SsrfPolicy.of(config.ssrf().allowedHosts(), false, List.of());
        JdbcAccountRepository repository = new JdbcAccountRepository(NO_DB);
        JdbcCredentialStore credentials = new JdbcCredentialStore(NO_DB,
                new CredentialCipher(new LocalKeyWrapper("a", Map.of("a", kek)), new SecureRandom()),
                new LogIds(ring), MetricsRecorder.NOOP);
        RefCredentialStore refs = RefCredentialStore.withEnvAndKeyring(new TenantId("config"), List.of(),
                Map.of("WEB_SECRET", "s"), Platform.current(), new ProcessCommandRunner(), BinaryProbe.system(Map.of()),
                config.secrets().keyringTimeout());
        try (ImapSessionFactory sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> p, false,
                new PinnedSocketFactory(ssrf))) {
            var linking = PublicComponents.buildLinking(config, refs, NO_DB, repository, credentials,
                    new JdbcAuditLog(NO_DB, ring), new JdbcTenantDirectory(NO_DB), sessions, ssrf, clock);
            return linking.linker().link(new TenantId("11111111-1111-1111-1111-111111111111"),
                    new LinkRequest("Work", host, 993, MailAccount.Security.IMPLICIT_TLS, "user", false, allowPurge),
                    "pw".toCharArray(), Instant.now(), "req-1");
        }
    }

    @Test
    void allowPurgeIsRefusedByTheWiredSurfaceWhilePurgeIsNotEnabled() throws Exception {
        assertThat(link("", true, "imap.example.com")).isEqualTo(LinkOutcome.PURGE_DISABLED);
    }

    @Test
    void withPurgeEnabledAndAFreshLoginTheSameRequestPassesTheGateAndMeetsTheNextCheck() throws Exception {
        assertThat(link("purge:\n  enabled: true\nhttp:\n  stateful: true\n", true, "evil.example.org"))
                .isEqualTo(LinkOutcome.HOST_REFUSED);
    }
}
