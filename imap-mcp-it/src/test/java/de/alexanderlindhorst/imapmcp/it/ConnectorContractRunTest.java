package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #435: selecting the fixture of a contract run. No stack. */
class ConnectorContractRunTest {

    @Test
    void theDefaultIsTheAssumedFixtureOnTheClasspath() {
        assertThat(ConnectorContractRun.loadFixture(null).header().source()).isEqualTo("assumed");
    }

    @Test
    void aFilePathIsReadWhenThereIsNoSuchClasspathResource(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("f.jsonl");
        Files.writeString(f, "{\"format\":\"imap-mcp-connector-session\",\"version\":1,\"source\":\"recorded 2026-11-12\"}\n");
        assertThat(ConnectorContractRun.loadFixture(f.toString()).header().source()).isEqualTo("recorded 2026-11-12");
    }

    @Test
    void anUnknownFixtureIsAnError() {
        assertThatThrownBy(() -> ConnectorContractRun.loadFixture("connector/nope.jsonl"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
