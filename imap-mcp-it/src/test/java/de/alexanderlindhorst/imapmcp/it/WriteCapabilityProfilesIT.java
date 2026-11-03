package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The three Dovecot capability sets of SPEC 5.8 (v2 write tests): for each, {@code list_accounts} through the shaded
 * jar (stdio) reports the reduced capabilities. The write ITs that build on these profiles live in their own classes.
 */
class WriteCapabilityProfilesIT {

    private static final String USER = "caps";

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(DovecotContainer.Profile.class)
    @SuppressWarnings("unchecked")
    void listAccountsShowsTheProfilesCapabilities(DovecotContainer.Profile profile) throws Exception {
        try (DovecotContainer dovecot = new DovecotContainer(profile)) {
            dovecot.start();
            ServerConfig config = new ServerConfigWriter(dir)
                    .account("caps", dovecot.getHost(), dovecot.imapsPort(), MailAccount.Security.IMPLICIT_TLS, USER,
                            DovecotContainer.PASSWORD)
                    .trustStore(TestTrust.writeTruststore(dir)).write();
            List<String> cmd = JarLauncher.builder(config, "stdio").command();
            ServerParameters params = ServerParameters.builder(cmd.get(0))
                    .args(cmd.subList(1, cmd.size())).env(config.env()).build();
            StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
            transport.setStdErrorHandler(new CopyOnWriteArrayList<String>()::add);
            try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(60))
                    .initializationTimeout(Duration.ofSeconds(60)).build()) {
                client.initialize();
                McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest("list_accounts", Map.of()));
                assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
                Map<String, Object> structured = (Map<String, Object>) r.structuredContent();
                List<Map<String, Object>> accounts = (List<Map<String, Object>>) structured.get("accounts");
                assertThat(accounts).hasSize(1);
                assertThat(accounts.get(0).get("status")).isEqualTo("ok");
                Map<String, Object> caps = (Map<String, Object>) accounts.get(0).get("capabilities");
                assertThat(caps).containsEntry("move", profile.move())
                        .containsEntry("uidplus", profile.uidplus())
                        .containsEntry("special_use", true);
            }

            // The snapshot helper against the real server: looking changes nothing (no flags, no UIDs).
            try (MailSeeder seeder = dovecot.seeder(USER)) {
                seeder.appendPlain("INBOX", "snapshot-probe", "body");
                MailboxSnapshot before = MailboxSnapshot.of(seeder.store(), "INBOX");
                assertThat(before.folder("INBOX").flags()).hasSize(1);
                assertThat(MailboxSnapshot.diff(before, MailboxSnapshot.of(seeder.store(), "INBOX")).isEmpty())
                        .isTrue();
            }
        }
    }

    @org.junit.jupiter.api.Test
    void defaultConstructionIsTheFullProfile() {
        assertThat(DovecotContainer.Profile.FULL.move()).isTrue();
        assertThat(DovecotContainer.Profile.FULL.uidplus()).isTrue();
        assertThat(DovecotContainer.Profile.NO_MOVE.move()).isFalse();
        assertThat(DovecotContainer.Profile.NO_MOVE.uidplus()).isTrue();
        assertThat(DovecotContainer.Profile.NO_MOVE_NO_UIDPLUS.move()).isFalse();
        assertThat(DovecotContainer.Profile.NO_MOVE_NO_UIDPLUS.uidplus()).isFalse();
        assertThat(new DovecotContainer().profile()).isEqualTo(DovecotContainer.Profile.FULL);
    }
}
