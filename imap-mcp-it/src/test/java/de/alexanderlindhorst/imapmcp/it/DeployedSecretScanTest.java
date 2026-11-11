package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeployedSecretScanTest {

    @TempDir
    Path dir;

    @Test
    void secretValuesAreTheLongValuesOfSecretLookingKeys() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                MCP_HOST=mail.imap-mcp.test
                POSTGRES_PASSWORD=0123456789abcdef0123456789abcdef
                WEB_CLIENT_SECRET=fedcba9876543210fedcba9876543210
                HANDLE_KEY_1=abcdefghijklmnopqrstuvwxyzABCDEFG
                KEK_1=short
                """);
        assertThat(DeployedSecretScan.secretValues(env)).containsExactlyInAnyOrder(
                "0123456789abcdef0123456789abcdef", "fedcba9876543210fedcba9876543210",
                "abcdefghijklmnopqrstuvwxyzABCDEFG");
    }

    @Test
    void leaksReportsOnlyTheKeyNamesNotTheValues() {
        List<String> found = DeployedSecretScan.leaks("log line with s3cr3tvalue-1234567890 inside",
                java.util.Map.of("POSTGRES_PASSWORD", "s3cr3tvalue-1234567890", "OTHER", "not-present-anywhere-123"));
        assertThat(found).containsExactly("POSTGRES_PASSWORD");
    }

    @Test
    void pemBodyOfAKeyIsAlsoACanary() throws Exception {
        Path key = dir.resolve("ca.key");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\nAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\nBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB\n-----END PRIVATE KEY-----\n");
        assertThat(DeployedSecretScan.pemLines(key)).containsExactly("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
    }
}
