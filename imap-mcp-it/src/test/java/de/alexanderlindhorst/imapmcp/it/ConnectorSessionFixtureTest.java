package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #434: the connector session fixture format (header line, then one JSON object per request). No Docker. */
class ConnectorSessionFixtureTest {

    private static final String HEADER = "{\"format\":\"imap-mcp-connector-session\",\"version\":1,\"source\":\"assumed\"}";

    private static String assumedFixture() throws IOException {
        try (InputStream in = ConnectorSessionFixtureTest.class.getResourceAsStream("/connector/assumed-claude-session.jsonl")) {
            assertThat(in).as("assumed fixture on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theAssumedFixtureRoundTripsExactly() throws IOException {
        String text = assumedFixture();
        ConnectorSessionFixture f = ConnectorSessionFixture.read(text);
        assertThat(f.header().source()).isEqualTo("assumed");
        assertThat(f.header().version()).isEqualTo(ConnectorSessionFixture.VERSION);
        assertThat(f.requests()).isNotEmpty();
        StringWriter out = new StringWriter();
        f.write(out);
        assertThat(ConnectorSessionFixture.read(out.toString())).isEqualTo(f);
        assertThat(out.toString()).isEqualTo(text);
    }

    @Test
    void theAssumedFixtureCoversBothHostsTheChallengeAndTheRefresh() throws IOException {
        ConnectorSessionFixture f = ConnectorSessionFixture.read(assumedFixture());
        assertThat(f.requests()).extracting(ConnectorSessionFixture.Request::host).contains("mcp", "idp");
        assertThat(f.requests()).anyMatch(r -> r.status() == 401 && r.wwwAuthenticate());
        assertThat(f.requests()).extracting(ConnectorSessionFixture.Request::seq)
                .isEqualTo(java.util.stream.IntStream.rangeClosed(1, f.requests().size()).boxed().toList());
        assertThat(f.requests().getLast().relativeMs()).isGreaterThan(5 * 60 * 1000L);
        assertThat(assumedFixture()).doesNotContain("Bearer ").doesNotContain("eyJ");
    }

    @Test
    void aMissingHeaderIsRejected() {
        String request = "{\"seq\":1,\"relativeMs\":0,\"host\":\"mcp\",\"method\":\"POST\",\"path\":\"/mcp\","
                + "\"query\":{},\"headers\":{},\"status\":401,\"wwwAuthenticate\":true}";
        assertThatThrownBy(() -> ConnectorSessionFixture.read(request + "\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header");
        assertThatThrownBy(() -> ConnectorSessionFixture.read(""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("header");
    }

    @Test
    void aHeaderWithoutSourceOrWithAnUnknownVersionOrSourceIsRejected() {
        assertThatThrownBy(() -> ConnectorSessionFixture.read("{\"format\":\"imap-mcp-connector-session\",\"version\":1}\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectorSessionFixture.read(HEADER.replace("\"version\":1", "\"version\":2") + "\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> ConnectorSessionFixture.read(HEADER.replace("assumed", "guessed") + "\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
    }

    @Test
    void aRecordedSourceCarriesTheDate() {
        var f = ConnectorSessionFixture.read(HEADER.replace("assumed", "recorded 2026-11-11") + "\n");
        assertThat(f.header().source()).isEqualTo("recorded 2026-11-11");
        assertThat(f.requests()).isEmpty();
    }

    @Test
    void aRequestWithMissingFieldsIsRejected() {
        assertThatThrownBy(() -> ConnectorSessionFixture.read(HEADER + "\n{\"seq\":1}\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectorSessionFixture.read(HEADER + "\nnot json\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWrittenRequestKeepsAllFields() throws IOException {
        var r = new ConnectorSessionFixture.Request(3, 1500L, "idp", "GET", "/realms/imap-mcp/x",
                Map.of("state", "<redacted>", "scope", "imap-mcp:read"), Map.of("Authorization", "present"), 302, false);
        var f = new ConnectorSessionFixture(new ConnectorSessionFixture.Header(ConnectorSessionFixture.FORMAT,
                ConnectorSessionFixture.VERSION, "assumed"), List.of(r));
        StringWriter out = new StringWriter();
        f.write(out);
        assertThat(ConnectorSessionFixture.read(out.toString()).requests()).containsExactly(r);
    }
}
