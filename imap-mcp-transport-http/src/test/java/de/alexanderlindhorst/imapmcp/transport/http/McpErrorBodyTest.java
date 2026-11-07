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

    private HttpResponse<String> post(String body, String contentType) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", contentType).header("Accept", "application/json, text/event-stream")
                .header("X-Test-Principal", "alice")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertNoLeaks(boolean stateful) throws Exception {
        HttpLocalServer.Builder b = HttpLocalServer.builder().port(0)
                .sessionPrincipal(req -> req.getHeader("X-Test-Principal"));
        if (stateful) {
            b.stateful(true).statefulTool(StatefulHttpServerTest.echo());
        }
        server = b.build();
        server.start();
        int errors = 0;
        for (String[] c : List.of(new String[] {"{not json", "application/json"}, new String[] {"{}", "application/json"},
                new String[] {"[]", "application/json"}, new String[] {StatefulHttpServerTest.INIT, "text/plain"},
                new String[] {"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope/none\"}", "application/json"})) {
            HttpResponse<String> r = post(c[0], c[1]);
            assertThat(r.body()).as(c[0] + " / " + c[1]).doesNotContain("stackTrace", "className", "fileName",
                    "lineNumber", "localizedMessage", "org.eclipse.jetty", System.getProperty("java.version"));
            if (r.statusCode() >= 400) {
                errors++;
            }
        }
        assertThat(errors).isPositive(); // the probes did provoke error paths
    }

    @Test
    void statefulErrorBodiesCarryNoStackTrace() throws Exception {
        assertNoLeaks(true);
    }

    @Test
    void statelessErrorBodiesCarryNoStackTrace() throws Exception {
        assertNoLeaks(false);
    }

    @Test
    void aMalformedBodyGetsABareJsonRpcError() throws Exception {
        server = HttpLocalServer.builder().port(0).build();
        server.start();
        HttpResponse<String> r = post("{not json", "application/json");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).startsWith("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":")
                .endsWith("\"id\":null}");
    }
}
