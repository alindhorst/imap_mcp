package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
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
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder().addTextContent("ok").build())
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
        start(builder().hostFilter(new HostFilter(List.of("127.0.0.1")))
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
            assertThat(send(m, body, "alice", "s", "Authorization", auth, "Host", "evil.example")
                    .statusCode()).as(m + " host").isNotEqualTo(200);
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
                        .customizeRequest(b -> b.header("X-Test-Principal", "alice")).build())
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
}
