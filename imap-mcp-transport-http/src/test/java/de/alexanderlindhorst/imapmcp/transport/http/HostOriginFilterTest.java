package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Host/Origin filters wired into a real HttpLocalServer; raw sockets because HttpClient restricts Host/Origin. */
class HostOriginFilterTest {

    static final String FORBIDDEN = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32000,\"message\":\"Forbidden\"},\"id\":null}";

    record Raw(int status, List<String> headers, String body) {
        boolean hasHeaderPrefix(String prefix) {
            return headers.stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).startsWith(prefix));
        }

        String header(String name) {
            return headers.stream().filter(h -> h.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + ":"))
                    .map(h -> h.substring(h.indexOf(':') + 1).trim()).findFirst().orElse(null);
        }
    }

    final List<String> reached = new CopyOnWriteArrayList<>();
    HttpLocalServer server;

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    /** The bearer slot: records that the chain got through Host/Origin and answers 204. */
    Filter passMarker() {
        return (req, res, chain) -> {
            reached.add("bearer");
            ((HttpServletResponse) res).setStatus(204);
        };
    }

    void start(List<String> hosts, List<String> origins, Filter bearer) throws Exception {
        server = HttpLocalServer.builder().port(0).hostFilter(new HostFilter(hosts))
                .originFilter(new OriginFilter(origins)).bearerFilter(bearer)
                .healthServlet(new HttpServlet() {
                    @Override
                    protected void doGet(HttpServletRequest rq, HttpServletResponse rs) {
                        rs.setStatus(200);
                    }
                }).build();
        server.start();
    }

    void startDefaults() throws Exception {
        start(HostFilter.DEFAULT_ALLOWED_HOSTS, List.of(), passMarker());
    }

    Raw raw(String requestLine, String... headers) throws Exception {
        StringBuilder sb = new StringBuilder(requestLine).append("\r\n");
        for (String h : headers) {
            sb.append(h).append("\r\n");
        }
        sb.append("Content-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            s.getInputStream().transferTo(bos);
            String all = bos.toString(StandardCharsets.UTF_8);
            int split = all.indexOf("\r\n\r\n");
            String head = split < 0 ? all : all.substring(0, split);
            String body = split < 0 ? "" : all.substring(split + 4);
            String[] lines = head.split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            List<String> hs = new ArrayList<>(List.of(lines).subList(1, lines.length));
            return new Raw(status, hs, body);
        }
    }

    Raw post11(String... headers) throws Exception {
        return raw("POST /mcp HTTP/1.1", headers);
    }

    // AC1

    @Test
    void allowedHostsPass() throws Exception {
        startDefaults();
        for (String h : List.of("127.0.0.1:9999", "localhost:1", "[::1]:8080", "LocalHost:80")) {
            assertThat(post11("Host: " + h).status()).as(h).isEqualTo(204);
        }
    }

    @Test
    void hostWithoutPortMatchesWildcardPortEntry() throws Exception {
        startDefaults();
        assertThat(post11("Host: localhost").status()).isEqualTo(204);
        assertThat(post11("Host: [::1]").status()).isEqualTo(204);
    }

    @Test
    void foreignOrMalformedHostIs403() throws Exception {
        startDefaults();
        for (String h : List.of("evil.example:8080", "evil.example", "localhost.evil.example:80", "localhost:abc",
                "localhost:", "127.0.0.1.evil:80", "")) {
            Raw r = post11("Host: " + h);
            assertThat(r.status()).as(h).isEqualTo(403);
        }
        assertThat(reached).isEmpty();
    }

    @Test
    void exactPortEntryOnlyMatchesThatPort() throws Exception {
        start(List.of("example.test:8080"), List.of(), passMarker());
        assertThat(post11("Host: example.test:8080").status()).isEqualTo(204);
        assertThat(post11("Host: EXAMPLE.test:8080").status()).isEqualTo(204);
        assertThat(post11("Host: example.test:8081").status()).isEqualTo(403);
        assertThat(post11("Host: example.test").status()).isEqualTo(403);
        assertThat(post11("Host: localhost:8080").status()).isEqualTo(403);
    }

    @Test
    void missingHostOnHttp10Is403() throws Exception {
        startDefaults();
        Raw r = raw("POST /mcp HTTP/1.0");
        // Jetty 12 lets a Host-less HTTP/1.0 request through to the servlet filters; the filter rejects it.
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body()).isEqualTo(FORBIDDEN);
    }

    // AC2

    @Test
    void noOriginPasses() throws Exception {
        startDefaults();
        assertThat(post11("Host: localhost:1").status()).isEqualTo(204);
    }

    @Test
    void foreignAndNullOriginAre403ByDefault() throws Exception {
        startDefaults();
        assertThat(post11("Host: localhost:1", "Origin: http://evil.example").status()).isEqualTo(403);
        assertThat(post11("Host: localhost:1", "Origin: null").status()).isEqualTo(403);
        assertThat(post11("Host: localhost:1", "Origin: http://localhost:1").status()).isEqualTo(403);
        assertThat(reached).isEmpty();
    }

    @Test
    void configuredOriginPassesOthersStill403() throws Exception {
        start(HostFilter.DEFAULT_ALLOWED_HOSTS, List.of("http://app.example:3000"), passMarker());
        assertThat(post11("Host: localhost:1", "Origin: http://app.example:3000").status()).isEqualTo(204);
        assertThat(post11("Host: localhost:1", "Origin: HTTP://APP.example:3000").status()).isEqualTo(204);
        assertThat(post11("Host: localhost:1", "Origin: http://app.example:3001").status()).isEqualTo(403);
        assertThat(post11("Host: localhost:1", "Origin: null").status()).isEqualTo(403);
    }

    @Test
    void literalNullOriginPassesOnlyWhenExplicitlyConfigured() throws Exception {
        start(HostFilter.DEFAULT_ALLOWED_HOSTS, List.of("null"), passMarker());
        assertThat(post11("Host: localhost:1", "Origin: null").status()).isEqualTo(204);
    }

    @Test
    void badHostWithAllowedOriginIsStill403() throws Exception {
        start(HostFilter.DEFAULT_ALLOWED_HOSTS, List.of("http://app.example"), passMarker());
        assertThat(post11("Host: evil.example", "Origin: http://app.example").status()).isEqualTo(403);
    }

    // AC3

    @Test
    void badHostWithoutAuthorizationIs403Not401() throws Exception {
        start(HostFilter.DEFAULT_ALLOWED_HOSTS, List.of(), (req, res, chain) -> ((HttpServletResponse) res).setStatus(401));
        assertThat(post11("Host: evil.example").status()).isEqualTo(403);
        assertThat(post11("Host: localhost:1", "Origin: http://evil.example").status()).isEqualTo(403);
        assertThat(post11("Host: localhost:1").status()).isEqualTo(401);
    }

    @Test
    void badHostWithValidLookingTokenIsStill403() throws Exception {
        startDefaults();
        assertThat(post11("Host: evil.example", "Authorization: Bearer abc").status()).isEqualTo(403);
        assertThat(reached).isEmpty();
    }

    // AC4

    @Test
    void healthIsNotAffected() throws Exception {
        startDefaults();
        assertThat(raw("GET /health HTTP/1.1", "Host: evil.example", "Origin: http://evil.example").status())
                .isEqualTo(200);
    }

    // AC5

    @Test
    void bodyIsExactJsonWithJsonContentTypeAndNoCors() throws Exception {
        startDefaults();
        for (Raw r : List.of(post11("Host: evil.example"),
                post11("Host: localhost:1", "Origin: http://evil.example"))) {
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.body()).isEqualTo(FORBIDDEN);
            assertThat(r.header("Content-Type")).startsWith("application/json");
            assertThat(r.hasHeaderPrefix("access-control-")).isFalse();
        }
    }

    // Logging

    @Test
    void rejectionIsLoggedAtDebugWithHostNameOnly() throws Exception {
        Logger log = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp.transport.http");
        Level old = log.getLevel();
        ListAppender<ILoggingEvent> app = new ListAppender<>();
        app.start();
        log.setLevel(Level.DEBUG);
        log.addAppender(app);
        try {
            startDefaults();
            post11("Host: evil.example:8080");
            post11("Host: localhost:1", "Origin: http://attacker.example:4242/secret?token=abc");
            List<String> msgs = app.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(app.list).anyMatch(e -> e.getLevel() == Level.DEBUG);
            assertThat(msgs).anyMatch(m -> m.contains("evil.example"));
            assertThat(msgs).anyMatch(m -> m.contains("attacker.example"));
            assertThat(msgs).noneMatch(m -> m.contains("8080") || m.contains("4242") || m.contains("secret")
                    || m.contains("token") || m.contains("http://"));
        } finally {
            log.detachAppender(app);
            log.setLevel(old);
        }
    }
}
