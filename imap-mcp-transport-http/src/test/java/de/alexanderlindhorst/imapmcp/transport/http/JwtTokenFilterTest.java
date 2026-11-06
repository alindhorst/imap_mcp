package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import de.alexanderlindhorst.imapmcp.core.mcp.McpToolAdapter;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class JwtTokenFilterTest {

    static final String METADATA_URL = "https://mail.example.com/.well-known/oauth-protected-resource/mcp";
    static final String MISSING = "Bearer realm=\"imap-mcp\", resource_metadata=\"" + METADATA_URL + "\"";
    static final String INVALID =
            "Bearer realm=\"imap-mcp\", error=\"invalid_token\", resource_metadata=\"" + METADATA_URL + "\"";
    static final String UNAUTHORIZED = BearerTokenFilter.UNAUTHORIZED_BODY;
    static final ToolDefinition WHO = new ToolDefinition("who_am_i", "Who am I",
            "Echo the tenant id. Read-only; does not change flags or mark messages as read.",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            Map.of("type", "object", "properties", Map.of("tenant", Map.of("type", "string")),
                    "required", List.of("tenant")),
            ToolHints.READ_ONLY_OPEN_WORLD);

    final TestTokens keys = new TestTokens();
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

    void start(JWKSource<SecurityContext> source) throws Exception {
        OAuthResourceConfig cfg = TestTokens.config();
        McpToolAdapter adapter = new McpToolAdapter(BearerTokenFilter.accountResolver());
        Function<HttpServletRequest, McpTransportContext> extractor = req -> {
            Object p = req.getAttribute(JwtTokenFilter.PRINCIPAL_ATTRIBUTE);
            return p instanceof JwtPrincipal jp
                    ? McpTransportContext.create(Map.of(BearerTokenFilter.CONTEXT_KEY,
                            new AccountContext(new TenantId(jp.subject()))))
                    : McpTransportContext.EMPTY;
        };
        server = HttpLocalServer.builder().port(0)
                .tool(adapter.stateless(WHO, (c, a) ->
                        new ToolOutcome.Success(Map.of("tenant", c.accountContext().tenantId().value()))))
                .contextExtractor(extractor)
                .hostFilter(new HostFilter(List.of("localhost:*", "127.0.0.1:*")))
                .originFilter(new OriginFilter(List.of()))
                .bearerFilter(new JwtTokenFilter(new JwtValidator(cfg, source), cfg))
                .principalFilter((rq, rs, ch) -> ch.doFilter(rq, rs)) // B2's filter is tested in PrincipalContextFilterTest
                .openServlet(cfg.metadataPath(), new ProtectedResourceMetadataServlet(cfg))
                .openServlet(OAuthResourceConfig.METADATA_ROOT, new ProtectedResourceMetadataServlet(cfg))
                .build();
        server.start();
    }

    void start() throws Exception {
        start(new ImmutableJWKSet<>(keys.publicJwks()));
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

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    void assertUnauthorized(HttpResponse<String> r, String wwwAuthenticate) {
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().allValues("WWW-Authenticate")).containsExactly(wwwAuthenticate);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(r.body()).isEqualTo(UNAUTHORIZED);
    }

    @Test
    void validTokenReachesToolWithPrincipalFromTheToken() throws Exception {
        start();
        HttpResponse<String> r = post("", "Bearer " + keys.rs256(b -> b.subject("alice")));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"tenant\":\"alice\"");
    }

    @Test
    void schemeIsCaseInsensitive() throws Exception {
        start();
        assertThat(post("", "bearer " + keys.rs256(b -> { })).statusCode()).isEqualTo(200);
    }

    @Test
    void missingHeaderIs401WithResourceMetadataVerbatim() throws Exception {
        start();
        assertUnauthorized(post(""), MISSING);
    }

    @Test
    void invalidTokensAre401InvalidTokenWithResourceMetadataVerbatim() throws Exception {
        start();
        assertUnauthorized(post("", "Bearer garbage"), INVALID);
        assertUnauthorized(post("", "Bearer " + keys.rs256(b -> b.audience("https://other.example.com/mcp"))), INVALID);
        assertUnauthorized(post("", "Bearer " + TestTokens.none(b -> { })), INVALID);
        assertUnauthorized(post("", "Bearer " + keys.rs256(b -> b.expirationTime(TestTokens.in(-3600)))), INVALID);
    }

    @Test
    void wrongSchemeAndMalformedHeadersAre401InvalidToken() throws Exception {
        start();
        String t = keys.rs256(b -> { });
        assertUnauthorized(post("", "Basic dXNlcjpwYXNz"), INVALID);
        assertUnauthorized(post("", t), INVALID);
        assertUnauthorized(post("", "Bearer"), INVALID);
        assertUnauthorized(post("", "Bearer "), INVALID);
        assertUnauthorized(post("", "Bearer  " + t), INVALID);
    }

    @Test
    void multipleAuthorizationHeadersAre401InvalidToken() throws Exception {
        start();
        String t = keys.rs256(b -> { });
        assertUnauthorized(post("", "Bearer " + t, "Bearer " + t), INVALID);
    }

    @Test
    void queryStringTokenIsNeverAccepted() throws Exception {
        start();
        String t = keys.rs256(b -> { });
        assertUnauthorized(post("?access_token=" + t), MISSING);
        assertUnauthorized(post("?access_token=" + t, "Bearer garbage"), INVALID);
    }

    @Test
    void unavailableSigningKeysAre401AndLoggedWithoutTheToken() throws Exception {
        start(new JWKSource<>() {
            @Override
            public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
                throw new KeySourceException("down");
            }
        });
        assertUnauthorized(post("", "Bearer " + keys.rs256(b -> { })), INVALID);
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("keys_unavailable");
        });
    }

    @Test
    void formBodyAccessTokenIsNeverAccepted() throws Exception {
        start();
        String t = keys.rs256(b -> { });
        HttpResponse<String> r = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(BodyPublishers.ofString("access_token=" + t)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertUnauthorized(r, MISSING);
    }

    @Test
    void keysUnavailableWarningIsRateLimited() throws Exception {
        OAuthResourceConfig cfg = TestTokens.config();
        JwtValidator down = new JwtValidator(cfg, (selector, ctx) -> {
            throw new KeySourceException("down");
        });
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        server = HttpLocalServer.builder().port(0).bearerFilter(new JwtTokenFilter(down, cfg, now::get))
                .principalFilter((rq, rs, ch) -> ch.doFilter(rq, rs)).build();
        server.start();
        String t = keys.rs256(b -> { });
        for (int i = 0; i < 5; i++) {
            assertUnauthorized(post("", "Bearer " + t), INVALID);
        }
        assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(1);
        now.addAndGet(61_000);
        post("", "Bearer " + t);
        assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(2);
    }

    @Test
    void openServletIsRestrictedToWellKnownPaths() {
        for (String bad : List.of("/mcp", "/health", "/accounts", "/", "/.well-known", "/foo/.well-known/x")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> HttpLocalServer.builder().openServlet(bad,
                    new ProtectedResourceMetadataServlet(TestTokens.config())))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void metadataSupportsHeadAndIsCacheable() throws Exception {
        start();
        HttpResponse<String> head = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/.well-known/oauth-protected-resource"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        HttpResponse<String> get = get("/.well-known/oauth-protected-resource");
        assertThat(get.headers().firstValue("Cache-Control")).contains("public, max-age=300");
    }

    @Test
    void emptyScopesSupportedIsOmitted() throws Exception {
        OAuthResourceConfig cfg = new OAuthResourceConfig(TestTokens.ISSUER, TestTokens.RESOURCE, "sub", List.of());
        server = HttpLocalServer.builder().port(0)
                .openServlet(OAuthResourceConfig.METADATA_ROOT, new ProtectedResourceMetadataServlet(cfg)).build();
        server.start();
        assertThat(get("/.well-known/oauth-protected-resource").body()).isEqualTo("{\"resource\":\""
                + TestTokens.RESOURCE + "\",\"authorization_servers\":[\"" + TestTokens.ISSUER
                + "\"],\"bearer_methods_supported\":[\"header\"]}");
    }

    @Test
    void hostAndOriginAreCheckedBeforeTheToken() throws Exception {
        start();
        // java.net.http forbids setting Host; use a raw socket for that case
        try (java.net.Socket s = new java.net.Socket("127.0.0.1", server.port())) {
            s.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: evil.example\r\nContent-Length: 2\r\n\r\n{}")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream())).readLine();
            assertThat(status).contains("403");
        }
        HttpResponse<String> origin = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                        .header("Origin", "https://evil.example").POST(BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(origin.statusCode()).isEqualTo(403);
        assertThat(origin.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void nonPostIs405BeforeTheToken() throws Exception {
        start();
        HttpResponse<String> r = get("/mcp");
        assertThat(r.statusCode()).isEqualTo(405);
        assertThat(r.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void metadataIsServedUnauthenticatedAtBothWellKnownPaths() throws Exception {
        start();
        for (String path : List.of("/.well-known/oauth-protected-resource",
                "/.well-known/oauth-protected-resource/mcp")) {
            HttpResponse<String> r = get(path);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            assertThat(r.body()).isEqualTo("{\"resource\":\"https://mail.example.com/mcp\","
                    + "\"authorization_servers\":[\"https://idp.example.com/realms/test\"],"
                    + "\"scopes_supported\":[\"mcp:read\",\"mcp:write\"],\"bearer_methods_supported\":[\"header\"]}");
        }
    }

    @Test
    void metadataIgnoresRequestHeadersAndRefusesOtherMethods() throws Exception {
        start();
        HttpResponse<String> post = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/.well-known/oauth-protected-resource"))
                .header("X-Forwarded-Host", "evil.example").POST(BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(405);
        assertThat(get("/.well-known/oauth-protected-resource/other").statusCode()).isEqualTo(404);
    }

    @Test
    void tokensNeverAppearInLogs() throws Exception {
        start();
        String good = keys.rs256(b -> { });
        String bad = keys.rs256(b -> b.audience("https://leak-marker.example/mcp"));
        post("", "Bearer " + good);
        post("", "Bearer " + bad);
        post("?access_token=" + good);
        post("", "Basic " + good);
        server.stop();
        server = null;
        for (ILoggingEvent e : logs.list) {
            String all = e.getFormattedMessage() + e.getMDCPropertyMap() + e.getThrowableProxy();
            assertThat(all).doesNotContain(good).doesNotContain(bad).doesNotContain("leak-marker");
            assertThat(all).doesNotContain(good.substring(good.indexOf('.') + 1, good.lastIndexOf('.')));
        }
    }
}
