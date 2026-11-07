package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** E19: the SDK's error bodies must not carry a serialized exception (class names, line numbers, JDK version). */
class McpErrorBodyTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private HttpLocalServer server;

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    static final String CANARY = "CANARY-7f3a9c";
    private static final List<String> FORBIDDEN = List.of("stackTrace", "className", "fileName", "lineNumber",
            "localizedMessage", "org.eclipse.jetty", "tools.jackson", "io.modelcontextprotocol", "Exception",
            "McpSchema", CANARY, System.getProperty("java.version"));

    /** body, content type, expected status, expected message (null: any, but still free of internals). */
    record Probe(String body, String contentType, int status, String message) {
    }

    private static final List<Probe> PROBES = List.of(
            new Probe("{not json", "application/json", 400, "Invalid message format"),
            new Probe("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":{}}", "application/json", 400, "Invalid message format"),
            new Probe("{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":\"tools/list\"}", "application/json", 400,
                    "Invalid message format"),
            new Probe("{\"canary\":\"" + CANARY + "\"}", "application/json", 400, "Invalid message format"),
            new Probe("[\"" + CANARY + "\"]", "application/json", 400, "Invalid message format"),
            new Probe(StatefulHttpServerTest.INIT, "text/plain", 415, null));

    private HttpResponse<String> post(String body, String contentType, String session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", contentType).header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18").header("X-Test-Principal", "alice");
        if (session != null) {
            b.header("Mcp-Session-Id", session);
        }
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void assertClean(String what, HttpResponse<String> r) {
        assertThat(r.body()).as(what).doesNotContain(FORBIDDEN.toArray(new String[0]));
    }

    private void start(boolean stateful) throws Exception {
        HttpLocalServer.Builder b = HttpLocalServer.builder().port(0)
                .sessionPrincipal(req -> req.getHeader("X-Test-Principal"));
        if (stateful) {
            b.stateful(true).statefulTool(StatefulHttpServerTest.echo());
        }
        server = b.build();
        server.start();
    }

    private void probe(String session) throws Exception {
        for (Probe p : PROBES) {
            HttpResponse<String> r = post(p.body(), p.contentType(), session);
            String what = p.body() + " / " + p.contentType() + " / session=" + (session != null);
            assertClean(what, r);
            assertThat(r.statusCode()).as(what).isEqualTo(p.status());
            if (p.message() != null) {
                assertThat(r.body()).as(what).contains("\"message\":\"" + p.message() + "\"");
            }
        }
    }

    @Test
    void statefulErrorBodiesCarryNoInternalsWithoutASession() throws Exception {
        start(true);
        probe(null);
    }

    @Test
    void statefulErrorBodiesCarryNoInternalsWithAValidSession() throws Exception {
        start(true);
        HttpResponse<String> init = post(StatefulHttpServerTest.INIT, "application/json", null);
        String id = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        probe(id);
        // an initialize with a type mismatch and a session id is refused by the binding filter before the SDK
        HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":{}}}", "application/json", id);
        assertClean("initialize with session", r);
        assertThat(r.statusCode()).isEqualTo(400);
    }

    @Test
    void statefulInitializeWithATypeMismatchAnswersWithoutInternals() throws Exception {
        start(true);
        HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":{}}}", "application/json", null);
        assertClean("initialize", r);
    }

    @Test
    void statelessErrorBodiesCarryNoInternals() throws Exception {
        start(false);
        probe(null);
    }

    @Test
    void statelessInitializeWithATypeMismatchAnswersWithoutInternals() throws Exception {
        start(false);
        HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":{}}}", "application/json", null);
        assertClean("initialize", r);
        assertThat(r.body()).contains("\"message\":\"Internal error\"");
    }

    @Test
    void aStatelessInternalErrorIs500WithTheFixedMessage() throws Exception {
        start(false);
        HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":{}}}", "application/json", null);
        assertClean("tools/call name {}", r);
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.body()).contains("\"message\":\"Internal error\"");
    }

    @Test
    void aMalformedBodyGetsABareJsonRpcError() throws Exception {
        server = HttpLocalServer.builder().port(0).build();
        server.start();
        HttpResponse<String> r = post("{not json", "application/json", null);
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).startsWith("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":")
                .endsWith("\"id\":null}");
    }
}
