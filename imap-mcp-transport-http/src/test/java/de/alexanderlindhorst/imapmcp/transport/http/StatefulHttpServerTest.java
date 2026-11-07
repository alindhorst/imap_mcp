package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.metrics.InMemoryMetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Stateful Streamable HTTP with session binding (SPEC 4 v3b "purge_messages over HTTP"). */
class StatefulHttpServerTest {

    static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
            + "\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
    static final String INITIALIZED = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
    static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";
    static final String TOKEN = "stateful-token-0123456789-abcdefghij";

    final HttpClient http = HttpClient.newHttpClient();
    final List<String> ended = new CopyOnWriteArrayList<>();
    HttpLocalServer server;

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    static McpServerFeatures.SyncToolSpecification echo() {
        McpSchema.Tool tool = McpSchema.Tool.builder().name("echo").title("Echo")
                .description("Echo. Read-only; does not change flags or mark messages as read.")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null)).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                        .addTextContent("session=" + exchange.sessionId()).build())
                .build();
    }

    HttpLocalServer.Builder builder() {
        return HttpLocalServer.builder().port(0).stateful(true).statefulTool(echo())
                .sessionPrincipal(req -> req.getHeader("X-Test-Principal"))
                .sessionEndListener(ended::add);
    }

    void start(HttpLocalServer.Builder b) throws Exception {
        server = b.build();
        server.start();
    }

    HttpResponse<String> send(String method, String body, String principal, String session, String... extra)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18");
        if (principal != null) {
            b.header("X-Test-Principal", principal);
        }
        if (session != null) {
            b.header("Mcp-Session-Id", session);
        }
        for (int i = 0; i + 1 < extra.length; i += 2) {
            b.header(extra[i], extra[i + 1]);
        }
        b.method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String initialize(String principal) throws Exception {
        HttpResponse<String> r = send("POST", INIT, principal, null);
        assertThat(r.statusCode()).isEqualTo(200);
        String id = r.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(send("POST", INITIALIZED, principal, id).statusCode()).isEqualTo(202);
        return id;
    }

    @Test
    void initializeIssuesASessionThatWorksForItsPrincipal() throws Exception {
        start(builder());
        String id = initialize("alice");
        HttpResponse<String> list = send("POST", LIST, "alice", id);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("echo");
    }

    @Test
    void aSessionPresentedByAnotherPrincipalOrUnknownGets404WithTheSameBodyAndNeverReachesTheSdk() throws Exception {
        start(builder());
        String id = initialize("alice");
        HttpResponse<String> foreign = send("POST", LIST, "bob", id);
        HttpResponse<String> unknown = send("POST", LIST, "bob", "no-such-session");
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).isEqualTo(unknown.body());
        for (String m : List.of("GET", "DELETE")) {
            assertThat(send(m, null, "bob", id).statusCode()).as(m).isEqualTo(404);
        }
        // the probe did not end alice's session
        assertThat(send("POST", LIST, "alice", id).statusCode()).isEqualTo(200);
        assertThat(ended).isEmpty();
    }

    @Test
    void deleteEndsTheSessionForGoodAndFiresTheEndListener() throws Exception {
        start(builder());
        String id = initialize("alice");
        assertThat(send("DELETE", null, "alice", id).statusCode()).isIn(200, 204);
        assertThat(ended).containsExactly(id);
        assertThat(send("POST", LIST, "alice", id).statusCode()).isEqualTo(404);
    }

    @Test
    void theEleventhSessionOfAPrincipalEvictsTheLeastRecentlyUsedOne() throws Exception {
        start(builder().sessionLimits(2, 100, Duration.ofMinutes(30)));
        String first = initialize("alice");
        String second = initialize("alice");
        assertThat(send("POST", LIST, "alice", first).statusCode()).isEqualTo(200); // second is now the oldest
        String third = initialize("alice");
        assertThat(ended).containsExactly(second);
        assertThat(send("POST", LIST, "alice", second).statusCode()).isEqualTo(404);
        assertThat(send("POST", LIST, "alice", first).statusCode()).isEqualTo(200);
        assertThat(send("POST", LIST, "alice", third).statusCode()).isEqualTo(200);
    }

    @Test
    void theReplicaCapAnswers503WithRetryAfter() throws Exception {
        start(builder().sessionLimits(10, 2, Duration.ofMinutes(30)));
        initialize("alice");
        initialize("bob");
        HttpResponse<String> r = send("POST", INIT, "carol", null);
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.headers().firstValue("Retry-After")).isPresent();
        assertThat(r.headers().firstValue("Mcp-Session-Id")).isEmpty();
    }

    @Test
    void idleSessionsAreEvictedAfterTheIdleTimeout() throws Exception {
        java.time.Clock fixed = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                return java.time.Instant.parse("2026-11-06T12:00:00Z").plusMillis(offsetMillis.get());
            }
        };
        start(builder().sessionLimits(10, 100, Duration.ofMinutes(30)).sessionClock(fixed));
        String id = initialize("alice");
        offsetMillis.set(Duration.ofMinutes(31).toMillis());
        assertThat(send("POST", LIST, "alice", id).statusCode()).isEqualTo(404);
        assertThat(ended).containsExactly(id);
    }

    final java.util.concurrent.atomic.AtomicLong offsetMillis = new java.util.concurrent.atomic.AtomicLong();

    @Test
    void methodsAreGetPostDeleteAndOthersGet405WithAllow() throws Exception {
        start(builder());
        for (String m : List.of("PUT", "PATCH", "OPTIONS")) {
            HttpResponse<String> r = send(m, null, "alice", null);
            assertThat(r.statusCode()).as(m).isEqualTo(405);
            assertThat(r.headers().firstValue("Allow")).as(m).hasValue("GET, POST, DELETE");
        }
    }

    @Test
    void hostOriginAndTokenChecksCoverGetAndDeleteToo() throws Exception {
        start(builder().hostFilter(new HostFilter(List.of("127.0.0.1:*")))
                .originFilter(new OriginFilter(List.of()))
                .bearerFilter(new BearerTokenFilter(TOKEN.toCharArray(), new AccountContext(new TenantId("t")))));
        String auth = "Bearer " + TOKEN;
        for (String m : List.of("GET", "DELETE", "POST")) {
            String body = m.equals("POST") ? INIT : null;
            assertThat(send(m, body, "alice", "s", "Authorization", "Bearer wrong").statusCode()).as(m + " token")
                    .isEqualTo(401);
            assertThat(send(m, body, "alice", "s").statusCode()).as(m + " no token").isEqualTo(401);
            assertThat(send(m, body, "alice", "s", "Authorization", auth, "Origin", "http://evil.example")
                    .statusCode()).as(m + " origin").isEqualTo(403);
        }
    }

    @Test
    void theHostCheckCoversGetAndDeleteToo() throws Exception {
        start(builder().hostFilter(new HostFilter(List.of("other.example:*"))));
        for (String m : List.of("GET", "DELETE", "POST")) {
            assertThat(send(m, m.equals("POST") ? INIT : null, "alice", null).statusCode()).as(m).isEqualTo(403);
        }
    }

    @Test
    void statelessModeIsUnchangedPostOnly() throws Exception {
        HttpLocalServerTest t = new HttpLocalServerTest();
        try {
            t.started();
            for (String m : List.of("GET", "DELETE")) {
                assertThat(t.send(m, null).statusCode()).isEqualTo(405);
                assertThat(t.send(m, null).headers().firstValue("Allow")).hasValue("POST");
            }
        } finally {
            t.stop();
        }
    }

    @Test
    void elicitationTravelsOverTheCallsStreamAndTheAnswerIsAPostOfTheSameSession() throws Exception {
        AtomicReference<String> sessionSeenByTool = new AtomicReference<>();
        McpSchema.Tool tool = McpSchema.Tool.builder().name("ask").title("Ask")
                .description("Ask. Read-only; does not change flags or mark messages as read.")
                .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null)).build();
        McpServerFeatures.SyncToolSpecification ask = McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> {
                    sessionSeenByTool.set(exchange.sessionId());
                    McpSchema.ElicitResult answer = exchange.createElicitation(McpSchema.ElicitFormRequest
                            .builder("Continue?", Map.of("type", "object", "properties", Map.of())).build());
                    return McpSchema.CallToolResult.builder().addTextContent("answer=" + answer.action()).build();
                }).build();
        start(builder().statefulTool(ask).requestTimeout(Duration.ofSeconds(30)));
        List<String> asked = Collections.synchronizedList(new ArrayList<>());
        try (McpSyncClient client = McpClient
                .sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + server.port())
                        .endpoint("/mcp")
                        .httpRequestCustomizer((b, m, u, body, ctx) -> b.header("X-Test-Principal", "alice")).build())
                .requestTimeout(Duration.ofSeconds(30)).initializationTimeout(Duration.ofSeconds(30))
                .capabilities(McpSchema.ClientCapabilities.builder().elicitation(true, false).build())
                .elicitation(req -> {
                    asked.add(req.message());
                    return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of());
                }).build()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("ask", Map.of()));
            assertThat(result.content().toString()).contains("answer=ACCEPT");
        }
        assertThat(asked).containsExactly("Continue?");
        assertThat(sessionSeenByTool.get()).isNotBlank();
    }

    static final String CALL_ECHO = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"echo\",\"arguments\":{}}}";

    @Test
    void theToolSeesTheSessionIdOfTheMcpSessionIdHeader() throws Exception {
        start(builder());
        String id = initialize("alice");
        HttpResponse<String> r = send("POST", CALL_ECHO, "alice", id);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("session=" + id);
    }

    static final String RATE_LIMITED_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32003,\"message\":\"Too many requests\"},\"id\":null}";
    static final String INVALID_REQUEST_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"},\"id\":null}";

    @Test
    void anInitializeCarryingASessionIdGets400BeforeTheSdkAndRegistersNothing() throws Exception {
        start(builder().sessionLimits(2, 100, Duration.ofMinutes(30)));
        String first = initialize("alice");
        assertThat(server.sessionCount()).isEqualTo(1);
        int sdk = server.sdkRequestCount();
        HttpResponse<String> again = send("POST", INIT, "alice", first);
        assertThat(again.statusCode()).isEqualTo(400);
        assertThat(again.body()).isEqualTo(INVALID_REQUEST_BODY);
        assertThat(again.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(server.sessionCount()).isEqualTo(1);
        assertThat(server.sdkRequestCount()).isEqualTo(sdk); // the SDK never saw it
        assertThat(ended).isEmpty();
        assertThat(send("POST", LIST, "alice", first).statusCode()).isEqualTo(200);
    }

    @Test
    void anInitializeInsideABatchWithASessionIdIsRefusedToo() throws Exception {
        start(builder());
        String id = initialize("alice");
        String batch = "[" + LIST + "," + INIT + "]";
        int sdk = server.sdkRequestCount();
        HttpResponse<String> r = send("POST", batch, "alice", id);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).isEqualTo(INVALID_REQUEST_BODY);
        assertThat(server.sdkRequestCount()).isEqualTo(sdk);
        assertThat(server.sessionCount()).isEqualTo(1);
    }

    @Test
    void anotherMethodWithASessionIdPassesWithItsBodyIntact() throws Exception {
        start(builder());
        String id = initialize("alice");
        HttpResponse<String> r = send("POST", CALL_ECHO, "alice", id);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("session=" + id);
        // text that merely mentions initialize in a string value is not an initialize
        String tricky = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\","
                + "\"arguments\":{\"method\":\"initialize\"}}}";
        assertThat(send("POST", tricky, "alice", id).statusCode()).isEqualTo(200);
    }

    @Test
    void aBodyThatDoesNotParseIsPassedOnToTheSdk() throws Exception {
        start(builder());
        String id = initialize("alice");
        HttpResponse<String> r = send("POST", "{not json", "alice", id);
        assertThat(r.statusCode()).isNotEqualTo(404).isNotEqualTo(200);
        assertThat(r.body()).doesNotContain("-32600").doesNotContain("-32003");
        assertThat(send("POST", LIST, "alice", id).statusCode()).isEqualTo(200);
    }

    @Test
    void aBodyBeyondMaxRequestBytesIsRefusedWith413() throws Exception {
        start(builder().maxRequestBytes(2048));
        String id = initialize("alice");
        String big = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\",\"params\":{\"x\":\""
                + "a".repeat(4096) + "\"}}";
        assertThat(send("POST", big, "alice", id).statusCode()).isEqualTo(413);
    }

    static java.time.Clock clockAt(java.util.concurrent.atomic.AtomicLong offsetMillis) {
        return new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                return java.time.Instant.parse("2026-11-07T12:00:00Z").plusMillis(offsetMillis.get());
            }
        };
    }

    private HttpLocalServer.Builder limited(int ratePerMinute, MetricsRecorder metrics) {
        return builder().initializeRateLimit(ratePerMinute, clockAt(offsetMillis)).metrics(metrics)
                .bearerFilter(new BearerTokenFilter(TOKEN.toCharArray(), new AccountContext(new TenantId("t"))));
    }

    private HttpResponse<String> init(String principal) throws Exception {
        return send("POST", INIT, principal, null, "Authorization", "Bearer " + TOKEN);
    }

    @Test
    void afterTheCapacityTheNextInitializeGets429WithRetryAfterAndNoSdkSession() throws Exception {
        start(limited(1, MetricsRecorder.NOOP)); // 1 per minute, capacity 2
        assertThat(init("alice").statusCode()).isEqualTo(200);
        assertThat(init("alice").statusCode()).isEqualTo(200);
        int sdk = server.sdkRequestCount();
        HttpResponse<String> r = init("alice");
        assertThat(r.statusCode()).isEqualTo(429);
        assertThat(r.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(r.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(r.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(r.body()).isEqualTo(RATE_LIMITED_BODY);
        assertThat(server.sdkRequestCount()).isEqualTo(sdk);
        assertThat(server.sessionCount()).isEqualTo(2);
        offsetMillis.set(30_000);
        assertThat(init("alice").headers().firstValue("Retry-After")).hasValue("30");
    }

    @Test
    void aRefilledTokenAdmitsAgainAndAnotherPrincipalIsUnaffected() throws Exception {
        start(limited(1, MetricsRecorder.NOOP));
        init("alice");
        init("alice");
        assertThat(init("alice").statusCode()).isEqualTo(429);
        assertThat(init("bob").statusCode()).isEqualTo(200);
        offsetMillis.set(60_000);
        assertThat(init("alice").statusCode()).isEqualTo(200);
        assertThat(init("alice").statusCode()).isEqualTo(429);
    }

    @Test
    void aTokenIsConsumedByAnInitializeRefusedAtTheReplicaCapButA429TakesNone() throws Exception {
        start(limited(1, MetricsRecorder.NOOP).sessionLimits(10, 1, Duration.ofMinutes(30)));
        assertThat(init("alice").statusCode()).isEqualTo(200); // fills the replica
        assertThat(init("bob").statusCode()).isEqualTo(503);
        assertThat(init("bob").statusCode()).isEqualTo(503);
        assertThat(init("bob").statusCode()).isEqualTo(429); // both tokens went to the 503s
        offsetMillis.set(60_000);
        assertThat(init("bob").statusCode()).isEqualTo(503); // exactly one token came back; the 429 took none
        assertThat(init("bob").statusCode()).isEqualTo(429);
    }

    @Test
    void requestsWithASessionIdTakeNoToken() throws Exception {
        start(limited(1, MetricsRecorder.NOOP));
        HttpResponse<String> r = init("alice");
        String id = r.headers().firstValue("Mcp-Session-Id").orElseThrow();
        for (int i = 0; i < 10; i++) {
            assertThat(send("POST", LIST, "alice", id, "Authorization", "Bearer " + TOKEN).statusCode())
                    .isEqualTo(200);
        }
        assertThat(init("alice").statusCode()).isEqualTo(200); // second token still there
    }

    @Test
    void theRefusalIsCountedPerTenantAsInitializeRateLimited() throws Exception {
        try (InMemoryMetricsRecorder metrics = new InMemoryMetricsRecorder()) {
            start(limited(1, metrics));
            init("alice");
            init("alice");
            init("alice");
            init("alice");
            var o = metrics.snapshot().operations().stream().filter(x -> x.op().equals("rate_limited"))
                    .findFirst().orElseThrow();
            assertThat(o.tool()).isEqualTo("initialize");
            assertThat(o.count()).isEqualTo(2);
        }
    }

    @Test
    void withoutARateLimitConfiguredInitializeIsNotLimited() throws Exception {
        start(builder());
        for (int i = 0; i < 30; i++) {
            assertThat(send("POST", INIT, "alice", null).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void anEvictedSessionsOpenStreamIsClosed() throws Exception {
        start(builder().sessionLimits(1, 100, Duration.ofMinutes(30)));
        String first = initialize("alice");
        var stream = openStream("alice", first);
        String second = initialize("alice"); // evicts the first session
        assertThat(second).isNotEqualTo(first);
        assertStreamEnds(stream);
    }

    @Test
    void deleteClosesTheSessionsOpenStream() throws Exception {
        start(builder());
        String id = initialize("alice");
        var stream = openStream("alice", id);
        assertThat(send("DELETE", null, "alice", id).statusCode()).isIn(200, 204);
        assertStreamEnds(stream);
    }

    /** Opens the GET stream; the SDK may not flush headers before its first event, so the call is not awaited. */
    private java.util.concurrent.CompletableFuture<HttpResponse<java.io.InputStream>> openStream(String principal,
            String session) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Accept", "text/event-stream").header("MCP-Protocol-Version", "2025-06-18")
                .header("X-Test-Principal", principal).header("Mcp-Session-Id", session).GET().build();
        var future = http.sendAsync(r, HttpResponse.BodyHandlers.ofInputStream());
        Thread.sleep(700); // the server has the stream registered with its session by now
        assertThat(server.sessionCount()).isPositive();
        return future;
    }

    /** The stream ends (normally, or with an I/O error) well before its own timeout, which is none. */
    private static void assertStreamEnds(java.util.concurrent.CompletableFuture<HttpResponse<java.io.InputStream>> f)
            throws Exception {
        var done = f.thenApplyAsync(resp -> {
            try (java.io.InputStream in = resp.body()) {
                while (in.read() != -1) {
                    // drain until the server closes the stream
                }
            } catch (java.io.IOException e) {
                // a reset is a closed stream too
            }
            return true;
        }).exceptionally(t -> true);
        assertThat(done.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void withoutASessionPrincipalConfiguredOnlyAnAuthenticatedRequestHasOne() throws Exception {
        HttpLocalServer.Builder b = HttpLocalServer.builder().port(0).stateful(true).statefulTool(echo())
                .bearerFilter(new BearerTokenFilter(TOKEN.toCharArray(), new AccountContext(new TenantId("t"))));
        start(b);
        HttpResponse<String> init = send("POST", INIT, null, null, "Authorization", "Bearer " + TOKEN);
        assertThat(init.statusCode()).isEqualTo(200);
        String id = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(send("POST", LIST, null, id, "Authorization", "Bearer " + TOKEN).statusCode()).isEqualTo(200);
        server.stop();
        // no bearer filter: nothing sets the account attribute, so there is no principal and nothing is served
        start(HttpLocalServer.builder().port(0).stateful(true).statefulTool(echo()));
        assertThat(send("POST", INIT, null, null).statusCode()).isEqualTo(404);
    }
}
