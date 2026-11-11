package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** #430: every request that goes through the tracing client is recorded, on a dynamic port. No Docker. */
class TracingHttpClientTest {

    private HttpServer server;
    private ConnectorTrace trace;
    private HttpClient client;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/mcp", ex -> {
            ex.getResponseHeaders().add("WWW-Authenticate", "Bearer realm=\"x\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        server.createContext("/other", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        String authority = "127.0.0.1:" + server.getAddress().getPort();
        base = "http://" + authority;
        trace = new ConnectorTrace(authority, "idp.invalid:1");
        client = new TracingHttpClient(HttpClient.newHttpClient(), trace);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aSynchronousSendIsRecordedWithStatusChallengeAndReducedHeaders() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                .header("Authorization", "Bearer TOKENCANARY").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(401);
        ConnectorSessionFixture.Request rec = trace.fixture().requests().getFirst();
        assertThat(rec.method()).isEqualTo("POST");
        assertThat(rec.path()).isEqualTo("/mcp");
        assertThat(rec.status()).isEqualTo(401);
        assertThat(rec.wwwAuthenticate()).isTrue();
        assertThat(rec.headers()).containsEntry("Authorization", "present");
        assertThat(trace.text()).doesNotContain("TOKENCANARY");
    }

    @Test
    void anAsynchronousSendIsRecordedWhenTheResponseArrives() throws Exception {
        client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/other?x=1")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).get();
        ConnectorSessionFixture.Request rec = trace.fixture().requests().getFirst();
        assertThat(rec.method()).isEqualTo("GET");
        assertThat(rec.status()).isEqualTo(200);
        assertThat(rec.wwwAuthenticate()).isFalse();
        assertThat(rec.query()).containsExactly(new ConnectorSessionFixture.Param("x", "<redacted>"));
    }
}
