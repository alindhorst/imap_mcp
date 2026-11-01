package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shaded jar started exactly as a client would (stdio), against the GreenMail fixture. */
class JarLauncherIT {

    private static GreenMailFixture greenMail;

    @TempDir
    Path dir;

    @BeforeAll
    static void start() {
        greenMail = GreenMailFixture.start();
        greenMail.createUser("alice@localhost", "alice", "pw");
    }

    @AfterAll
    static void stop() {
        greenMail.close();
    }

    @Test
    void stdioModeAnswersInitializeOnStdoutAndKeepsStderrSeparate() throws Exception {
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", "127.0.0.1", greenMail.imapsPort(), MailAccount.Security.IMPLICIT_TLS, "alice", "pw")
                .trustStore(TestTrust.writeGreenMailTruststore(dir)).write();

        try (RunningServer server = JarLauncher.start(cfg, "stdio")) {
            server.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                    + "\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}");
            BufferedReader out = new BufferedReader(
                    new InputStreamReader(server.stdout(), StandardCharsets.UTF_8));
            String line = out.readLine();
            assertThat(line).contains("\"jsonrpc\":\"2.0\"").contains("\"result\"");

            server.closeStdin();
            assertThat(server.waitFor(15)).isTrue();
            assertThat(server.exitCode()).isZero();
            assertThat(server.stderr()).doesNotContain("\"jsonrpc\"");
        }
    }

    @Test
    void noModeExitsTwoWithUsageOnStderrAndNothingOnStdout() throws Exception {
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", "127.0.0.1", greenMail.imapsPort(), MailAccount.Security.IMPLICIT_TLS, "alice", "pw")
                .write();

        try (RunningServer server = JarLauncher.start(cfg, null)) {
            assertThat(server.waitFor(30)).isTrue();
            assertThat(server.exitCode()).isEqualTo(2);
            assertThat(server.stderr()).contains("Usage");
            assertThat(server.stdout().readAllBytes()).isEmpty();
        }
    }
}
