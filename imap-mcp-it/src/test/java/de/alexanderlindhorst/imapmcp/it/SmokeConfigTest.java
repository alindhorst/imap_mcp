package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The server config of the smoke harness (#258): the secret only travels as an {@code env:} reference. */
class SmokeConfigTest {

    private static final SmokeEnv.Settings GMAIL = new SmokeEnv.Settings(SmokeEnv.Provider.GMAIL,
            "someone@example.org", "app-pw-1234", "imap.gmail.com", 993, null);

    @TempDir
    Path dir;

    @Test
    void passwordIsAnEnvReferenceAndNeverInTheFileOrTheArguments() throws Exception {
        ServerConfig c = SmokeConfig.write(dir, GMAIL, false);
        String yaml = Files.readString(c.configFile());
        assertThat(yaml).contains("password: \"env:IMAP_MCP_IT_PW_SMOKE\"")
                .contains("host: imap.gmail.com").contains("port: 993").contains("security: implicit-tls")
                .doesNotContain("app-pw-1234");
        assertThat(c.env()).containsEntry("IMAP_MCP_IT_PW_SMOKE", "app-pw-1234");
        assertThat(c.jvmArgs()).noneMatch(a -> a.contains("app-pw-1234"));
    }

    @Test
    void readChecksRunOnAReadOnlyAccountAndWriteChecksOnAWritableOne() throws Exception {
        assertThat(Files.readString(SmokeConfig.write(dir.resolve("r"), GMAIL, false).configFile()))
                .contains("readOnly: true");
        assertThat(Files.readString(SmokeConfig.write(dir.resolve("w"), GMAIL, true).configFile()))
                .doesNotContain("readOnly");
    }

    @Test
    void realNetworkTimeoutsAreGenerousAndTheTruststoreOnlyComesFromALocalFixture() throws Exception {
        ServerConfig c = SmokeConfig.write(dir, GMAIL, true);
        assertThat(Files.readString(c.configFile())).contains("deadline: 60s").contains("readTimeout: 60s");
        assertThat(c.jvmArgs()).isEmpty();
        Path ts = TestTrust.writeTruststore(Files.createDirectories(dir.resolve("ts")));
        assertThat(SmokeConfig.write(dir.resolve("l"), GMAIL.withLocalFixture("localhost", 1, ts), true).jvmArgs())
                .anyMatch(a -> a.startsWith("-Djavax.net.ssl.trustStore="));
    }
}
