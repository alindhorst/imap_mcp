package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import io.modelcontextprotocol.json.McpJsonDefaults;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The filter in front of a stub servlet that records what reaches the "SDK" (SPEC 4, D12, body decoding). */
class SessionBindingFilterTest {

    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";

    private final AtomicInteger reached = new AtomicInteger();
    private final AtomicReference<byte[]> seenBody = new AtomicReference<>();
    private final AtomicReference<String> seenEncoding = new AtomicReference<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private SessionRegistry registry;
    private Server server;
    private int port;

    @BeforeEach
    void start() throws Exception {
        registry = new SessionRegistry(10, 100, Duration.ofMinutes(30), Clock.systemUTC(), id -> { });
        registry.tryReserve("alice");
        registry.register("s1", "alice");
        SessionBindingFilter filter = new SessionBindingFilter(registry, r -> r.getHeader("X-P"), null,
                MetricsRecorder.NOOP, McpJsonDefaults.getMapper(), 4096);
        server = new Server();
        ServerConnector c = new ServerConnector(server);
        c.setHost("127.0.0.1");
        server.addConnector(c);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.addFilter(new FilterHolder(filter), "/mcp", EnumSet.of(DispatcherType.REQUEST));
        ctx.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                reached.incrementAndGet();
                seenEncoding.set(req.getCharacterEncoding());
                seenBody.set(req.getInputStream().readAllBytes());
                if ("new-session".equals(req.getHeader("X-Make"))) {
                    resp.setHeader("Mcp-Session-Id", "sdk-created");
                }
                resp.setStatus(200);
            }
        }), "/mcp");
        server.setHandler(ctx);
        server.start();
        port = c.getLocalPort();
    }

    @AfterEach
    void stop() throws Exception {
        server.stop();
    }

    private HttpResponse<String> post(byte[] body, String contentType, String... extra) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", contentType).header("X-P", "alice").header("Mcp-Session-Id", "s1");
        for (int i = 0; i + 1 < extra.length; i += 2) {
            b.header(extra[i], extra[i + 1]);
        }
        return http.send(b.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anInitializeWithAnInvalidUtf8ByteIsRefusedAndNotPassedOn() throws Exception {
        byte[] head = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"x\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + 3];
        System.arraycopy(head, 0, body, 0, head.length);
        body[head.length] = (byte) 0xFF;
        body[head.length + 1] = '"';
        body[head.length + 2] = '}';
        HttpResponse<String> r = post(body, "application/json");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).isEmpty();
        assertThat(reached.get()).isZero();
    }

    @Test
    void aCharsetOtherThanUtf8IsRefusedWith415AndNotPassedOn() throws Exception {
        HttpResponse<String> r = post(INIT.getBytes(StandardCharsets.UTF_8), "application/json; charset=IBM037");
        assertThat(r.statusCode()).isEqualTo(415);
        assertThat(r.body()).isEmpty();
        assertThat(reached.get()).isZero();
    }

    @Test
    void aBodyAboveTheLimitIsRefusedWith413AndNotPassedOn() throws Exception {
        byte[] body = ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"x\":\"" + "a".repeat(5000) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> r = post(body, "application/json");
        assertThat(r.statusCode()).isEqualTo(413);
        assertThat(r.body()).isEmpty();
        assertThat(reached.get()).isZero();
    }

    @Test
    void aForeignSessionIdWithAnotherCharsetIs404BeforeAnyDecoding() throws Exception {
        registry.tryReserve("bob");
        registry.register("s2", "bob");
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json; charset=IBM037").header("X-P", "alice")
                .header("Mcp-Session-Id", "s2")
                .POST(HttpRequest.BodyPublishers.ofByteArray(INIT.getBytes(StandardCharsets.UTF_8))).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(reached.get()).isZero();
    }

    @Test
    void anExplicitUtf8CharsetAndNoCharsetBothPassWithTheBodyIntact() throws Exception {
        byte[] body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{\"n\":\"ä€\"}}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(post(body, "application/json; charset=utf-8").statusCode()).isEqualTo(200);
        assertThat(seenBody.get()).isEqualTo(body);
        assertThat(seenEncoding.get()).isEqualToIgnoringCase("UTF-8");
        assertThat(post(body, "application/json").statusCode()).isEqualTo(200);
        assertThat(seenBody.get()).isEqualTo(body);
        assertThat(seenEncoding.get()).isEqualToIgnoringCase("UTF-8");
        assertThat(reached.get()).isEqualTo(2);
    }

    @Test
    void aSessionTheSdkCreatesForARequestWithASessionIdIsStillRegisteredAgainstTheCaps() throws Exception {
        HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}"
                .getBytes(StandardCharsets.UTF_8), "application/json", "X-Make", "new-session");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(registry.touch("sdk-created", "alice")).isTrue();
        assertThat(registry.touch("sdk-created", "bob")).isFalse();
    }
}
