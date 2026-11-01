package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class HttpLocalServerTest {

    static final String DESC = "Count things in the account. Read-only; does not change flags or mark messages as read.";
    static final Map<String, Object> INPUT = Map.of("type", "object",
            "properties", Map.of("n", Map.of("type", "integer", "minimum", 1, "maximum", 10)), "additionalProperties", false);
    static final Map<String, Object> OUTPUT = Map.of("type", "object",
            "properties", Map.of("count", Map.of("type", "integer")), "required", List.of("count"));
    static final ToolDefinition SAMPLE =
            new ToolDefinition("count_things", "Count things", DESC, INPUT, OUTPUT, ToolHints.READ_ONLY_OPEN_WORLD);

    final HttpClient client = HttpClient.newHttpClient();
    final AtomicBoolean extractorOnVirtual = new AtomicBoolean();
    HttpLocalServer server;

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    HttpLocalServer.Builder builder() {
        McpToolAdapter adapter = new McpToolAdapter(ctx -> new AccountContext(new TenantId("t1")));
        return HttpLocalServer.builder().port(0)
                .tool(adapter.stateless(SAMPLE, (c, a) -> new ToolOutcome.Success(Map.of("count", 3))))
                .contextExtractor(req -> {
                    extractorOnVirtual.set(Thread.currentThread().isVirtual());
                    return McpTransportContext.EMPTY;
                });
    }

    HttpLocalServer started() throws Exception {
        server = builder().build();
        server.start();
        return server;
    }

    HttpResponse<String> post(String body) throws Exception {
        return send("POST", body);
    }

    HttpResponse<String> send(String method, String body) throws Exception {
        return sendTo("/mcp", method, body);
    }

    HttpResponse<String> sendTo(String path, String method, String body) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build();
        return client.send(r, HttpResponse.BodyHandlers.ofString());
    }

    static String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    @Test
    void initializeAndToolsList() throws Exception {
        started();
        HttpResponse<String> init = post(rpc("initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}"));
        assertThat(init.statusCode()).isEqualTo(200);
        assertThat(init.body()).contains("\"result\"").contains("\"serverInfo\"");
        HttpResponse<String> list = post(rpc("tools/list", "{}"));
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("count_things").contains("\"readOnlyHint\":true").contains("\"outputSchema\"");
    }

    @Test
    void unknownPropertyYieldsOurInvalidArgumentPayload() throws Exception {
        started();
        HttpResponse<String> r = post(rpc("tools/call", "{\"name\":\"count_things\",\"arguments\":{\"bogus\":1}}"));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"isError\":true").contains("INVALID_ARGUMENT").contains("unknown_property")
                .contains("structuredContent");
    }

    @Test
    void validToolCallWorks() throws Exception {
        started();
        HttpResponse<String> r = post(rpc("tools/call", "{\"name\":\"count_things\",\"arguments\":{\"n\":2}}"));
        assertThat(r.body()).contains("\"count\":3");
    }

    @Test
    void otherMethodsOnMcpAre405WithAllowPostAndNoCors() throws Exception {
        started();
        for (String m : List.of("GET", "DELETE", "OPTIONS")) {
            HttpResponse<String> r = send(m, null);
            assertThat(r.statusCode()).as(m).isEqualTo(405);
            assertThat(r.headers().firstValue("Allow")).as(m).hasValue("POST");
            assertThat(r.headers().map().keySet()).as(m).noneMatch(h -> h.toLowerCase().startsWith("access-control-"));
        }
    }

    @Test
    void otherPathsAre404() throws Exception {
        started();
        assertThat(sendTo("/mcp/", "POST", "{}").statusCode()).isEqualTo(404);
        assertThat(sendTo("/other", "POST", "{}").statusCode()).isEqualTo(404);
        assertThat(sendTo("/other", "GET", null).statusCode()).isEqualTo(404);
    }

    @Test
    void configuredMaxRequestBytesRejectsLargerBodies() throws Exception {
        server = builder().maxRequestBytes(2048).build();
        server.start();
        String big = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"pad\":\""
                + "x".repeat(4096) + "\"}}";
        assertThat(post(big).statusCode()).isBetween(400, 499);
        assertThat(post(rpc("tools/list", "{}")).statusCode()).isEqualTo(200);
    }

    @Test
    void serverInfoCarriesConfiguredNameAndVersion() throws Exception {
        server = builder().serverInfo("imap-mcp", "9.8.7").build();
        server.start();
        HttpResponse<String> init = post(rpc("initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}"));
        assertThat(init.body()).contains("\"version\":\"9.8.7\"");
    }

    @Test
    void oversizedBodyRejectedAndServerStaysHealthy() throws Exception {
        started();
        String big = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"pad\":\""
                + "x".repeat(2 * 1024 * 1024) + "\"}}";
        HttpResponse<String> r = post(big);
        assertThat(r.statusCode()).isBetween(400, 499);
        assertThat(post(rpc("tools/list", "{}")).statusCode()).isEqualTo(200);
    }

    @Test
    void requestRunsOnVirtualThread() throws Exception {
        started();
        post(rpc("tools/list", "{}"));
        assertThat(extractorOnVirtual).isTrue();
    }

    @Test
    void defaultBindIsLoopback() throws Exception {
        started();
        assertThat(server.host()).isEqualTo("127.0.0.1");
        assertThat(server.port()).isPositive();
    }

    @Test
    void nonLoopbackBindRefusedWithoutFlag() {
        server = builder().bind("0.0.0.0").build();
        assertThatThrownBy(server::start).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http.allowNonLoopbackBind=true");
        server = null;
    }

    @Test
    void nonLoopbackBindWithFlagStartsAndWarnsOnce() throws Exception {
        Logger log = (Logger) LoggerFactory.getLogger(HttpLocalServer.class);
        ListAppender<ILoggingEvent> app = new ListAppender<>();
        app.start();
        log.addAppender(app);
        try {
            server = builder().bind("0.0.0.0").allowNonLoopbackBind(true).build();
            server.start();
            assertThat(app.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(1);
        } finally {
            log.detachAppender(app);
        }
    }

    @Test
    void filterChainRunsHostOriginBearerInOrder() throws Exception {
        java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        server = builder().hostFilter(recording("host", order)).originFilter(recording("origin", order))
                .bearerFilter(recording("bearer", order)).build();
        server.start();
        post(rpc("tools/list", "{}"));
        assertThat(order).containsExactly("host", "origin", "bearer");
    }

    @Test
    void methodCheckRunsBeforeBearerAndHostOriginSkipHealth() throws Exception {
        java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        jakarta.servlet.Filter reject = (req, res, chain) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401);
        server = builder().hostFilter(recording("host", order)).originFilter(recording("origin", order))
                .bearerFilter(reject).healthServlet(new jakarta.servlet.http.HttpServlet() {
                    @Override
                    protected void doGet(jakarta.servlet.http.HttpServletRequest rq,
                            jakarta.servlet.http.HttpServletResponse rs) {
                        rs.setStatus(200);
                    }
                }).build();
        server.start();
        HttpResponse<String> get = send("GET", null);
        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(get.headers().firstValue("Allow")).hasValue("POST");
        assertThat(post(rpc("tools/list", "{}")).statusCode()).isEqualTo(401);
        order.clear();
        assertThat(sendTo("/health", "GET", null).statusCode()).isEqualTo(200);
        assertThat(order).isEmpty();
    }

    static jakarta.servlet.Filter recording(String name, List<String> order) {
        return (req, res, chain) -> {
            order.add(name);
            chain.doFilter(req, res);
        };
    }
}
