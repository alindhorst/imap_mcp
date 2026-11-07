package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import de.alexanderlindhorst.imapmcp.core.mcp.McpToolAdapter;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** B2: validated principal to tenant to AccountContext, and the D3 read scope (SPEC 5.3). */
class PrincipalContextFilterTest {

    static final String INSUFFICIENT =
            "Bearer realm=\"imap-mcp\", error=\"insufficient_scope\", resource_metadata=\""
                    + JwtTokenFilterTest.METADATA_URL + "\", scope=\"imap-mcp:read imap-mcp:write\"";
    static final TenantId NO_TENANT = TenantId.NONE;
    static final TenantId TENANT_A = new TenantId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final TenantId TENANT_B = new TenantId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    static final ToolDefinition WHO = new ToolDefinition("who_am_i", "Who am I",
            "Echo the tenant id. Read-only; does not change flags or mark messages as read.",
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
            Map.of("type", "object", "properties", Map.of("tenant", Map.of("type", "string"),
                    "write", Map.of("type", "boolean")), "required", List.of("tenant", "write")),
            ToolHints.READ_ONLY_OPEN_WORLD);

    final TestTokens keys = new TestTokens();
    final HttpClient client = HttpClient.newHttpClient();
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final List<String> lookups = new CopyOnWriteArrayList<>();
    final AtomicInteger toolCalls = new AtomicInteger();
    final Map<String, PrincipalTenants.Tenant> rows = new java.util.concurrent.ConcurrentHashMap<>();
    volatile boolean directoryDown;
    Level oldLevel;
    HttpLocalServer server;

    @BeforeEach
    void setUp() {
        Logger root = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp");
        oldLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        logs.start();
        root.addAppender(logs);
        rows.put(TestTokens.ISSUER + "|alice", new PrincipalTenants.Tenant(TENANT_A, false));
        rows.put(TestTokens.ISSUER + "|bob", new PrincipalTenants.Tenant(TENANT_B, false));
        rows.put(TestTokens.ISSUER + "|mallory", new PrincipalTenants.Tenant(new TenantId(
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc"), true));
    }

    @AfterEach
    void tearDown() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp");
        root.detachAppender(logs);
        root.setLevel(oldLevel);
        if (server != null) {
            server.stop();
        }
    }

    void start() throws Exception {
        OAuthResourceConfig cfg = TestTokens.config();
        PrincipalTenants tenants = (iss, sub) -> {
            lookups.add(iss + "|" + sub);
            if (directoryDown) {
                throw new IllegalStateException("db down (sqlstate 08006)");
            }
            return Optional.ofNullable(rows.get(iss + "|" + sub));
        };
        McpToolAdapter adapter = new McpToolAdapter(BearerTokenFilter.accountResolver());
        server = HttpLocalServer.builder().port(0)
                .tool(adapter.stateless(WHO, (c, a) -> {
                    toolCalls.incrementAndGet();
                    return new ToolOutcome.Success(Map.of("tenant", c.accountContext().tenantId().value(),
                            "write", c.accountContext().hasScope(PrincipalContextFilter.WRITE_SCOPE)));
                }))
                .contextExtractor(BearerTokenFilter.contextExtractor())
                .bearerFilter(new JwtTokenFilter(new JwtValidator(cfg, new ImmutableJWKSet<>(keys.publicJwks())), cfg))
                .principalFilter(new PrincipalContextFilter(tenants, cfg)).build();
        server.start();
    }

    String token(String subject, String scope) {
        return keys.rs256(b -> {
            b.subject(subject);
            if (scope != null) {
                b.claim("scope", scope);
            }
        });
    }

    String token(Consumer<JWTClaimsSet.Builder> c) {
        return keys.rs256(c);
    }

    HttpResponse<String> post(String bearer, String... extraHeaders) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp?tenant=" + TENANT_B.value()))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        for (int i = 0; i < extraHeaders.length; i += 2) {
            b.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"who_am_i\","
                + "\"arguments\":{}}}";
        return client.send(b.POST(BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theTenantComesFromTheDirectoryRowNotFromTheTokenOrTheRequest() throws Exception {
        start();
        HttpResponse<String> r = post(token("alice", "imap-mcp:read"), "X-Tenant", TENANT_B.value());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"tenant\":\"" + TENANT_A.value() + "\"");
        assertThat(lookups).containsExactly(TestTokens.ISSUER + "|alice");
    }

    @Test
    void twoPrincipalsNeverShareAContextNotEvenConcurrently() throws Exception {
        start();
        String alice = token("alice", "imap-mcp:read");
        String bob = token("bob", "imap-mcp:read");
        List<Thread> threads = new ArrayList<>();
        List<String> wrong = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 40; i++) {
            boolean a = i % 2 == 0;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    String body = post(a ? alice : bob).body();
                    String want = (a ? TENANT_A : TENANT_B).value();
                    String other = (a ? TENANT_B : TENANT_A).value();
                    if (!body.contains(want) || body.contains(other)) {
                        wrong.add(body);
                    }
                } catch (Exception e) {
                    wrong.add(e.toString());
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void aPrincipalWithoutARowGetsAContextThatOwnsNothingAndNothingIsCreated() throws Exception {
        start();
        HttpResponse<String> r = post(token("stranger", "imap-mcp:read"));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"tenant\":\"" + NO_TENANT.value() + "\"");
        assertThat(rows).doesNotContainKey(TestTokens.ISSUER + "|stranger");
        assertThat(lookups).containsExactly(TestTokens.ISSUER + "|stranger");
    }

    @Test
    void aDisabledTenantGets403WithTheFixedBodyAndNoChallengeAndNoToolCall() throws Exception {
        start();
        HttpResponse<String> r = post(token("mallory", "imap-mcp:read imap-mcp:write"));
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).isEqualTo(Forbidden.BODY);
        assertThat(r.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(toolCalls.get()).isZero();
    }

    @Test
    void aMissingReadScopeIs403InsufficientScopeVerbatimBeforeAnyLookup() throws Exception {
        start();
        for (String scope : new String[] {null, "", "imap-mcp:write", "openid profile", "imap-mcp:readx",
                "ximap-mcp:read", "IMAP-MCP:READ"}) {
            HttpResponse<String> r = post(token("alice", scope));
            assertThat(r.statusCode()).as(String.valueOf(scope)).isEqualTo(403);
            assertThat(r.headers().allValues("WWW-Authenticate")).containsExactly(INSUFFICIENT);
            assertThat(r.body()).isEqualTo(Forbidden.BODY);
        }
        assertThat(lookups).isEmpty();
        assertThat(toolCalls.get()).isZero();
    }

    @Test
    void theScopeCheckPrecedesTheDisabledCheck() throws Exception {
        start();
        HttpResponse<String> r = post(token("mallory", "openid"));
        assertThat(r.headers().allValues("WWW-Authenticate")).containsExactly(INSUFFICIENT);
    }

    @Test
    void scopesAreReadFromTheScopeStringAndTheScpClaimAndPassedThrough() throws Exception {
        start();
        assertThat(post(token("alice", "openid imap-mcp:read")).body()).contains("\"write\":false");
        assertThat(post(token("alice", "imap-mcp:read   imap-mcp:write")).body()).contains("\"write\":true");
        String scp = token(b -> b.subject("alice").claim("scp", List.of("imap-mcp:read", "imap-mcp:write")));
        assertThat(post(scp).body()).contains("\"write\":true");
        String scpString = token(b -> b.subject("alice").claim("scp", "imap-mcp:read"));
        assertThat(post(scpString).body()).contains("\"write\":false");
    }

    @Test
    void scpArrayElementsAreMatchedWholeAndNotSplit() throws Exception {
        start();
        String spaced = token(b -> b.subject("alice").claim("scp", List.of("imap-mcp:read imap-mcp:write")));
        assertThat(post(spaced).statusCode()).isEqualTo(403);
        String ok = token(b -> b.subject("alice").claim("scp", List.of("imap-mcp:read", "other thing")));
        assertThat(post(ok).statusCode()).isEqualTo(200);
    }

    @Test
    void aNonStringScopeClaimGrantsNothing() throws Exception {
        start();
        String t = token(b -> b.subject("alice").claim("scope", List.of("imap-mcp:read")));
        assertThat(post(t).statusCode()).isEqualTo(403);
        String n = token(b -> b.subject("alice").claim("scope", 42));
        assertThat(post(n).statusCode()).isEqualTo(403);
    }

    @Test
    void aDirectoryFailureIs503WithAFixedBodyAndNoToolCall() throws Exception {
        start();
        directoryDown = true;
        HttpResponse<String> r = post(token("alice", "imap-mcp:read"));
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).doesNotContain("sqlstate").doesNotContain("db down");
        assertThat(toolCalls.get()).isZero();
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void withoutAValidatedPrincipalTheMisconfigurationIs500NotAChallenge() throws Exception {
        PrincipalTenants tenants = (i, s) -> Optional.of(new PrincipalTenants.Tenant(TENANT_A, false));
        server = HttpLocalServer.builder().port(0).bearerFilter((rq, rs, ch) -> ch.doFilter(rq, rs))
                .principalFilter(new PrincipalContextFilter(tenants, TestTokens.config())).build();
        server.start();
        HttpResponse<String> r = post(null);
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(toolCalls.get()).isZero();
    }

    @Test
    void aTenantRowWithTheNilIdIsRefusedFailClosedWithAClassOnlyWarning() throws Exception {
        start();
        rows.put(TestTokens.ISSUER + "|nil", new PrincipalTenants.Tenant(TenantId.NONE, false));
        HttpResponse<String> r = post(token("nil", "imap-mcp:read"));
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(toolCalls.get()).isZero();
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).doesNotContain("nil").doesNotContain(TestTokens.ISSUER);
        });
    }

    @Test
    void aJwtFilterWithoutAPrincipalFilterDoesNotBuild() {
        OAuthResourceConfig cfg = TestTokens.config();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> HttpLocalServer.builder().port(0)
                .bearerFilter(new JwtTokenFilter(new JwtValidator(cfg, new ImmutableJWKSet<>(keys.publicJwks())), cfg))
                .build()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPrincipalFilterWithoutAnyTokenFilterDoesNotBuild() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> HttpLocalServer.builder().port(0)
                .principalFilter(new PrincipalContextFilter((i, s) -> Optional.empty(), TestTokens.config())).build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyHttpPublicContextIsScopeLimited() throws Exception {
        java.util.concurrent.atomic.AtomicReference<de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        OAuthResourceConfig cfg = TestTokens.config();
        McpToolAdapter adapter = new McpToolAdapter(ctx -> {
            var c = BearerTokenFilter.accountResolver().apply(ctx);
            seen.set(c);
            return c;
        });
        server = HttpLocalServer.builder().port(0)
                .tool(adapter.stateless(WHO, (c, a) -> new ToolOutcome.Success(Map.of("tenant",
                        c.accountContext().tenantId().value(), "write", false))))
                .contextExtractor(BearerTokenFilter.contextExtractor())
                .bearerFilter(new JwtTokenFilter(new JwtValidator(cfg, new ImmutableJWKSet<>(keys.publicJwks())), cfg))
                .principalFilter(new PrincipalContextFilter((i, s) -> Optional.empty(), TestTokens.config())).build();
        server.start();
        post(token("stranger", "imap-mcp:read"));
        assertThat(seen.get().scopeLimited()).isTrue(); // also for a principal without a row
        assertThat(seen.get().hasScope("imap-mcp:write")).isFalse();
    }

    @Test
    void neitherTokenNorIdentifiersAreLogged() throws Exception {
        start();
        String t = token("alice", "imap-mcp:read");
        post(t);
        post(token("stranger", null));
        directoryDown = true;
        post(t);
        for (ILoggingEvent e : logs.list) {
            String all = e.getFormattedMessage() + e.getMDCPropertyMap() + e.getThrowableProxy();
            assertThat(all).doesNotContain(t).doesNotContain("alice").doesNotContain("stranger")
                    .doesNotContain(TestTokens.ISSUER).doesNotContain(TENANT_A.value());
        }
    }
}
