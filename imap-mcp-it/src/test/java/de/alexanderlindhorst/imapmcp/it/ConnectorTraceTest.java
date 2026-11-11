package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** #430: the connector's own trace in the #434 fixture format: allowlists, placeholders, no secrets. No Docker. */
class ConnectorTraceTest {

    private static ConnectorTrace trace() {
        return new ConnectorTrace("mcp.imap-mcp.test:8443", "idp.imap-mcp.test:8443");
    }

    private static Map<String, List<String>> headers(String... kv) {
        java.util.Map<String, List<String>> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], List.of(kv[i + 1]));
        }
        return m;
    }

    @Test
    void hostsBecomePlaceholdersAndTheHostKeyIsMcpOrIdp() {
        ConnectorTrace t = trace();
        t.record("GET", URI.create("https://idp.imap-mcp.test:8443/realms/imap-mcp/.well-known/openid-configuration"),
                headers("Accept", "application/json"), 200, false, 0L);
        ConnectorSessionFixture.Request r = t.fixture().requests().getFirst();
        assertThat(r.host()).isEqualTo("idp");
        assertThat(r.path()).isEqualTo("/realms/imap-mcp/.well-known/openid-configuration");
        assertThat(r.seq()).isEqualTo(1);
        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    void queryValuesOutsideTheAllowlistAreRedactedAndTheOrderIsKept() {
        ConnectorTrace t = trace();
        t.record("GET", URI.create("https://idp.imap-mcp.test:8443/realms/imap-mcp/protocol/openid-connect/auth"
                + "?response_type=code&client_id=claude-connector&redirect_uri=https%3A%2F%2Fclaude.ai%2Fapi%2Fmcp%2Fauth_callback"
                + "&scope=imap-mcp%3Aread%20imap-mcp%3Awrite&state=STATECANARY&code_challenge=CHALLENGECANARY"
                + "&code_challenge_method=S256&resource=https%3A%2F%2Fmcp.imap-mcp.test%3A8443%2Fmcp"),
                headers("Accept", "text/html"), 302, false, 0L);
        List<ConnectorSessionFixture.Param> q = t.fixture().requests().getFirst().query();
        assertThat(q).extracting(ConnectorSessionFixture.Param::name).containsExactly("response_type", "client_id",
                "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method", "resource");
        assertThat(q).contains(new ConnectorSessionFixture.Param("state", "<redacted>"),
                new ConnectorSessionFixture.Param("code_challenge", "<redacted>"),
                new ConnectorSessionFixture.Param("scope", "imap-mcp:read imap-mcp:write"),
                new ConnectorSessionFixture.Param("redirect_uri", "https://claude.ai/api/mcp/auth_callback"),
                new ConnectorSessionFixture.Param("resource", "https://<MCP_HOST>/mcp"));
        assertThat(t.text()).doesNotContain("STATECANARY").doesNotContain("CHALLENGECANARY");
    }

    @Test
    void headersAreReducedAuthorizationIsOnlyPresentAndCookiesNeverAppear() {
        ConnectorTrace t = trace();
        t.record("POST", URI.create("https://mcp.imap-mcp.test:8443/mcp"),
                headers("Authorization", "Bearer TOKENCANARY", "Content-Type", "application/json",
                        "Accept", "application/json, text/event-stream", "Cookie", "KC_RESTART=COOKIECANARY",
                        "User-Agent", "Claude-User", "MCP-Protocol-Version", "2025-06-18",
                        "Origin", "https://idp.imap-mcp.test:8443", "X-Other", "OTHERCANARY"),
                200, false, 0L);
        Map<String, String> h = t.fixture().requests().getFirst().headers();
        assertThat(h).containsEntry("Authorization", "present").containsEntry("Content-Type", "application/json")
                .containsEntry("User-Agent", "Claude-User").containsEntry("MCP-Protocol-Version", "2025-06-18")
                .containsEntry("Origin", "https://<IDP_HOST>").doesNotContainKeys("Cookie", "X-Other");
        assertThat(t.text()).doesNotContain("TOKENCANARY").doesNotContain("COOKIECANARY").doesNotContain("OTHERCANARY");
    }

    @Test
    void aBrowserUserAgentIsRecordedAsBrowser() {
        ConnectorTrace t = trace();
        t.record("GET", URI.create("https://idp.imap-mcp.test:8443/x"),
                headers("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Gecko/20100101 Firefox/130.0"), 200, false, 0L);
        assertThat(t.fixture().requests().getFirst().headers()).containsEntry("User-Agent", "browser");
    }

    @Test
    void relativeTimeStartsAtTheFirstRequestAndTheWwwAuthenticateFlagIsKept() {
        ConnectorTrace t = trace();
        t.record("POST", URI.create("https://mcp.imap-mcp.test:8443/mcp"), headers(), 401, true, 5_000_000_000L);
        t.record("GET", URI.create("https://mcp.imap-mcp.test:8443/x"), headers(), 200, false, 5_250_000_000L);
        List<ConnectorSessionFixture.Request> rs = t.fixture().requests();
        assertThat(rs).extracting(ConnectorSessionFixture.Request::relativeMs).containsExactly(0L, 250L);
        assertThat(rs.getFirst().wwwAuthenticate()).isTrue();
        assertThat(rs.get(1).seq()).isEqualTo(2);
    }

    @Test
    void theTextIsTheFixtureFormatAndReadsBack() throws IOException {
        ConnectorTrace t = trace();
        t.record("POST", URI.create("https://mcp.imap-mcp.test:8443/mcp"), headers("Accept", "application/json"),
                401, true, 1L);
        ConnectorSessionFixture back = ConnectorSessionFixture.read(t.text());
        assertThat(back.header().source()).isEqualTo("assumed");
        StringWriter w = new StringWriter();
        back.write(w);
        assertThat(w.toString()).isEqualTo(t.text());
    }

    @Test
    void aHostThatIsNeitherOfTheTwoIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> trace().record("GET",
                URI.create("https://claude.ai/api/mcp/auth_callback"), headers(), 200, false, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
