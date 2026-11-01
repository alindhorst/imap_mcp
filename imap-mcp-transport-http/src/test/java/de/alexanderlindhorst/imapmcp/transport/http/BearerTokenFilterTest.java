package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.mcp.McpToolAdapter;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class BearerTokenFilterTest {

    static final String TOKEN = "s3cr3t-MARKER-token-0123456789";
    static final String UNAUTHORIZED =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Unauthorized\"},\"id\":null}";
    static final String MISSING = "Bearer realm=\"imap-mcp\"";
    static final String INVALID = "Bearer realm=\"imap-mcp\", error=\"invalid_token\"";
    static final ToolDefinition WHO = new ToolDefinition("who_am_i", "Who am I",
            "Echo the tenant id. Read-only; does not change flags or mark messages as read.",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            Map.of("type", "object", "properties", Map.of("tenant", Map.of("type", "string")),
                    "required", List.of("tenant")),
            ToolHints.READ_ONLY_OPEN_WORLD);

    final HttpClient client = HttpClient.newHttpClient();
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    Level oldLevel;
    HttpLocalServer server;

    @BeforeEach
    void captureLogs() {
        Logger root = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp");
        oldLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void stop() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp");
        root.detachAppender(logs);
        root.setLevel(oldLevel);
        if (server != null) {
            server.stop();
        }
    }

    void start(String token, String tenant) throws Exception {
        McpToolAdapter adapter = new McpToolAdapter(BearerTokenFilter.accountResolver());
        server = HttpLocalServer.builder().port(0)
                .tool(adapter.stateless(WHO, (c, a) ->
                        new ToolOutcome.Success(Map.of("tenant", c.accountContext().tenantId().value()))))
                .contextExtractor(BearerTokenFilter.contextExtractor())
                .bearerFilter(new BearerTokenFilter(token.toCharArray(), new AccountContext(new TenantId(tenant))))
                .healthServlet(new jakarta.servlet.http.HttpServlet() {
                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest rq,
                            jakarta.servlet.http.HttpServletResponse rs) {
                        rs.setStatus(200);
                    }
                }).build();
        server.start();
    }

    HttpResponse<String> post(String query, String... authHeaders) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp" + query))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        for (String h : authHeaders) {
            b.header("Authorization", h);
        }
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"who_am_i\",\"arguments\":{}}}";
        return client.send(b.POST(BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    void assertUnauthorized(HttpResponse<String> r, String wwwAuthenticate) {
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().allValues("WWW-Authenticate")).containsExactly(wwwAuthenticate);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(r.body()).isEqualTo(UNAUTHORIZED);
    }

    @Test
    void validTokenReachesToolWithFixedAccountContext() throws Exception {
        start(TOKEN, "fixed-tenant");
        HttpResponse<String> r = post("", "Bearer " + TOKEN);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"tenant\":\"fixed-tenant\"");
    }

    @Test
    void schemeIsCaseInsensitive() throws Exception {
        start(TOKEN, "t");
        assertThat(post("", "bearer " + TOKEN).statusCode()).isEqualTo(200);
        assertThat(post("", "BEARER " + TOKEN).statusCode()).isEqualTo(200);
    }

    @Test
    void missingHeaderIs401WithRealmOnly() throws Exception {
        start(TOKEN, "t");
        assertUnauthorized(post(""), MISSING);
    }

    @Test
    void wrongTokenIs401InvalidToken() throws Exception {
        start(TOKEN, "t");
        assertUnauthorized(post("", "Bearer wrong"), INVALID);
        assertUnauthorized(post("", "Bearer " + TOKEN + "x"), INVALID);
    }

    @Test
    void wrongSchemeAndMalformedHeadersAre401InvalidToken() throws Exception {
        start(TOKEN, "t");
        assertUnauthorized(post("", "Basic dXNlcjpwYXNz"), INVALID);
        assertUnauthorized(post("", TOKEN), INVALID);
        assertUnauthorized(post("", "Bearer"), INVALID);
        assertUnauthorized(post("", "Bearer "), INVALID);
        assertUnauthorized(post("", "Bearer  " + TOKEN), INVALID);
    }

    @Test
    void multipleAuthorizationHeadersAre401InvalidToken() throws Exception {
        start(TOKEN, "t");
        assertUnauthorized(post("", "Bearer " + TOKEN, "Bearer " + TOKEN), INVALID);
    }

    @Test
    void queryStringTokenIsNeverAccepted() throws Exception {
        start(TOKEN, "t");
        assertUnauthorized(post("?access_token=" + TOKEN), MISSING);
    }

    @Test
    void comparisonHelperHandlesDifferentLengths() {
        assertThat(BearerTokenFilter.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(BearerTokenFilter.constantTimeEquals("abc", "abcd")).isFalse();
        assertThat(BearerTokenFilter.constantTimeEquals("", "abc")).isFalse();
        assertThat(BearerTokenFilter.constantTimeEquals("a".repeat(10_000), "a")).isFalse();
        assertThat(BearerTokenFilter.constantTimeEquals("abd", "abc")).isFalse();
    }

    @Test
    void resolverFailsWhenContextAbsent() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> BearerTokenFilter.accountResolver().apply(McpTransportContext.EMPTY))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void healthIsNotAffected() throws Exception {
        start(TOKEN, "t");
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health")).GET().build();
        assertThat(client.send(r, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    void tokensNeverAppearInLogs() throws Exception {
        start(TOKEN, "t");
        post("", "Bearer " + TOKEN);
        post("", "Bearer " + TOKEN + "-BAD-MARKER");
        post("?access_token=" + TOKEN);
        post("", "Basic " + TOKEN);
        stopQuietly();
        for (ILoggingEvent e : logs.list) {
            String all = e.getFormattedMessage() + e.getMDCPropertyMap() + e.getThrowableProxy();
            assertThat(all).doesNotContain(TOKEN).doesNotContain("BAD-MARKER");
        }
    }

    void stopQuietly() throws Exception {
        server.stop();
        server = null;
    }
}
