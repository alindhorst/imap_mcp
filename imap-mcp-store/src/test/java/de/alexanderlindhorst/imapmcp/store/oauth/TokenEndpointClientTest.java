package de.alexanderlindhorst.imapmcp.store.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class TokenEndpointClientTest {

    private static final String HOST = "token.example.test";
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CANARY_DESC = "CANARY-DESCRIPTION-7f3a";
    private static final String CANARY_ACCESS = "CANARY-ACCESS-91bc";
    private static final String CANARY_REFRESH = "CANARY-REFRESH-5d02";
    private static final String CANARY_CODE = "CANARY-CODE-c4e8";
    private static final String CANARY_VERIFIER = "CANARY-VERIFIER-aa17";

    @TempDir
    static Path dir;
    static TestPki pki;
    static SSLContext clientCtx;
    static StubTokenServer server;

    ListAppender<ILoggingEvent> logs;
    Logger rootLogger;

    @BeforeAll
    static void startServer() throws Exception {
        pki = new TestPki(dir);
        clientCtx = pki.clientContext();
        server = new StubTokenServer(pki.serverContext(HOST));
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void captureLogs() {
        server.requests.clear();
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.TRACE);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        rootLogger.detachAppender(logs);
    }

    private static InetAddress[] loopback() {
        return new InetAddress[] {InetAddress.getLoopbackAddress()};
    }

    private TokenEndpointClient client(PinnedSocketFactory.Resolver resolver, java.util.function.Predicate<InetAddress> blocked,
            int port, Duration total) {
        return new TokenEndpointClient(blocked, resolver, clientCtx, port, Duration.ofSeconds(5), total, CLOCK);
    }

    private TokenEndpointClient client() {
        return client(h -> loopback(), a -> false, server.port(), Duration.ofSeconds(8));
    }

    private TokenProvider provider() {
        return provider(server.port());
    }

    private static TokenProvider provider(int port) {
        return new TokenProvider("generic", URI.create("https://" + HOST + ":" + port + "/oauth/token"), "client-1",
                () -> new Secret("s3cret&=".toCharArray()));
    }

    private TokenResponse refresh(TokenEndpointClient c) {
        return c.refresh(provider(), CANARY_REFRESH.toCharArray());
    }

    private static String ok(String extra) {
        return "{\"access_token\":\"" + CANARY_ACCESS + "\",\"token_type\":\"Bearer\",\"expires_in\":3600" + extra + "}";
    }

    private void assertNoCanaryLogged() {
        for (ILoggingEvent e : logs.list) {
            String text = e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage());
            assertThat(text).doesNotContain(CANARY_DESC, CANARY_ACCESS, CANARY_REFRESH, CANARY_CODE, CANARY_VERIFIER,
                    "s3cret");
        }
    }

    @Test
    void refreshWithoutRotationReturnsAccessTokenAndExpiry() {
        server.respond(r -> StubTokenServer.Reply.json(200, ok(",\"scope\":\"mail\"")));

        TokenResponse res = refresh(client());

        assertThat(res).isInstanceOfSatisfying(TokenResponse.Granted.class, g -> {
            assertThat(new String(g.accessToken().chars())).isEqualTo(CANARY_ACCESS);
            assertThat(g.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
            assertThat(g.rotatedRefreshToken()).isNull();
            assertThat(g.scope()).isEqualTo("mail");
        });
        StubTokenServer.Request req = server.requests.getFirst();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/oauth/token");
        assertThat(req.contentType()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(req.body()).contains("grant_type=refresh_token", "refresh_token=" + CANARY_REFRESH,
                "client_id=client-1", "client_secret=s3cret%26%3D");
        assertNoCanaryLogged();
    }

    @Test
    void refreshWithRotationReturnsTheNewRefreshToken() {
        server.respond(r -> StubTokenServer.Reply.json(200, ok(",\"refresh_token\":\"rotated-1\"")));

        assertThat(refresh(client())).isInstanceOfSatisfying(TokenResponse.Granted.class,
                g -> assertThat(new String(g.rotatedRefreshToken().chars())).isEqualTo("rotated-1"));
    }

    @Test
    void chunkedResponseIsDecoded() {
        server.respond(r -> new StubTokenServer.Reply(200, java.util.Map.of("Content-Type", "application/json"),
                ok("").getBytes(StandardCharsets.UTF_8), true));

        assertThat(refresh(client())).isInstanceOf(TokenResponse.Granted.class);
    }

    @Test
    void codeExchangeSendsCodeVerifierAndRedirectUri() {
        server.respond(r -> StubTokenServer.Reply.json(200, ok(",\"refresh_token\":\"first\"")));

        TokenResponse res = client().exchangeCode(provider(), CANARY_CODE.toCharArray(),
                CANARY_VERIFIER.toCharArray(), URI.create("https://app.example.test/cb?x=1"));

        assertThat(res).isInstanceOf(TokenResponse.Granted.class);
        assertThat(server.requests.getFirst().body()).contains("grant_type=authorization_code",
                "code=" + CANARY_CODE, "code_verifier=" + CANARY_VERIFIER,
                "redirect_uri=https%3A%2F%2Fapp.example.test%2Fcb%3Fx%3D1", "client_id=client-1");
        assertNoCanaryLogged();
    }

    @Test
    void invalidGrantAndInvalidScopeAreInvalidGrant() {
        server.respond(r -> StubTokenServer.Reply.json(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"" + CANARY_DESC + "\"}"));
        assertThat(refresh(client())).isInstanceOf(TokenResponse.InvalidGrant.class);

        server.respond(r -> StubTokenServer.Reply.json(400, "{\"error\":\"invalid_scope\"}"));
        assertThat(refresh(client())).isInstanceOf(TokenResponse.InvalidGrant.class);
        assertNoCanaryLogged();
    }

    @Test
    void invalidClientAndUnauthorizedClientAreDistinguished() {
        server.respond(r -> StubTokenServer.Reply.json(401, "{\"error\":\"invalid_client\"}"));
        assertThat(refresh(client())).isInstanceOf(TokenResponse.InvalidClient.class);

        server.respond(r -> StubTokenServer.Reply.json(400, "{\"error\":\"unauthorized_client\"}"));
        assertThat(refresh(client())).isInstanceOf(TokenResponse.UnauthorizedClient.class);
    }

    @Test
    void unknownErrorCodeIsMalformedAndNeverLogged() {
        server.respond(r -> StubTokenServer.Reply.json(400, "{\"error\":\"" + CANARY_DESC + "\"}"));

        assertThat(refresh(client())).isInstanceOf(TokenResponse.Malformed.class);
        assertNoCanaryLogged();
    }

    @Test
    void serverErrorIsUnavailableWithRetryAfterSeconds() {
        server.respond(r -> StubTokenServer.Reply.json(503, "{}").with("Retry-After", "7"));

        assertThat(refresh(client())).isEqualTo(new TokenResponse.Unavailable(Duration.ofSeconds(7)));
    }

    @Test
    void retryAfterHttpDateIsRelativeToNow() {
        server.respond(r -> StubTokenServer.Reply.json(503, "{}").with("Retry-After", "Fri, 13 Nov 2026 12:00:30 GMT"));

        assertThat(refresh(client())).isEqualTo(new TokenResponse.Unavailable(Duration.ofSeconds(30)));
    }

    @Test
    void tooManyRequestsIsUnavailable() {
        server.respond(r -> StubTokenServer.Reply.json(429, "{}"));

        assertThat(refresh(client())).isEqualTo(new TokenResponse.Unavailable(null));
    }

    @Test
    void slowServerIsUnavailableAfterTheTotalDeadline() {
        server.respond(r -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return StubTokenServer.Reply.json(200, ok(""));
        });
        long start = System.nanoTime();

        TokenResponse res = refresh(client(h -> loopback(), a -> false, server.port(), Duration.ofSeconds(1)));

        assertThat(res).isEqualTo(new TokenResponse.Unavailable(null));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void oversizedBodyIsMalformed() {
        String big = "{\"access_token\":\"" + "a".repeat(70 * 1024) + "\",\"expires_in\":60}";
        server.respond(r -> StubTokenServer.Reply.json(200, big));

        assertThat(refresh(client())).isInstanceOf(TokenResponse.Malformed.class);
    }

    @Test
    void redirectIsNotFollowed() {
        server.respond(r -> StubTokenServer.Reply.json(302, "{}").with("Location", "/elsewhere"));

        assertThat(refresh(client())).isInstanceOf(TokenResponse.Malformed.class);
        assertThat(server.requests).hasSize(1);
    }

    @Test
    void wrongContentTypeIsMalformed() {
        server.respond(r -> new StubTokenServer.Reply(200, java.util.Map.of("Content-Type", "text/html"),
                ok("").getBytes(StandardCharsets.UTF_8), false));

        assertThat(refresh(client())).isInstanceOf(TokenResponse.Malformed.class);
    }

    @Test
    void missingAccessTokenOrExpiryIsMalformed() {
        server.respond(r -> StubTokenServer.Reply.json(200, "{\"expires_in\":60}"));
        assertThat(refresh(client())).isInstanceOf(TokenResponse.Malformed.class);

        server.respond(r -> StubTokenServer.Reply.json(200, "{\"access_token\":\"x\"}"));
        assertThat(refresh(client())).isInstanceOf(TokenResponse.Malformed.class);
    }

    @Test
    void certificateForAnotherHostIsRefusedByTheHostnameCheck() throws Exception {
        try (StubTokenServer other = new StubTokenServer(pki.serverContext("other.example.test"))) {
            other.respond(r -> StubTokenServer.Reply.json(200, ok("")));

            TokenResponse res = client(h -> loopback(), a -> false, other.port(), Duration.ofSeconds(8))
                    .refresh(provider(other.port()), CANARY_REFRESH.toCharArray());

            assertThat(res).isEqualTo(new TokenResponse.Unavailable(null));
            assertThat(other.requests).isEmpty();
        }
    }

    @Test
    void hostResolvingIntoABlockedRangeIsRefusedBeforeConnect() {
        SsrfPolicy policy = SsrfPolicy.of(List.of(), true, List.of());
        server.respond(r -> StubTokenServer.Reply.json(200, ok("")));

        // the stub listens on loopback, which the real block list refuses
        TokenResponse res = client(h -> loopback(), policy::isBlocked, server.port(), Duration.ofSeconds(8))
                .refresh(provider(), CANARY_REFRESH.toCharArray());

        assertThat(res).isInstanceOf(TokenResponse.Refused.class);
        assertThat(server.requests).isEmpty();
    }

    @Test
    void oneBlockedAnswerAmongPublicOnesRefusesTheCall() throws Exception {
        SsrfPolicy policy = SsrfPolicy.of(List.of(), true, List.of());
        InetAddress pub = InetAddress.getByAddress(new byte[] {(byte) 93, (byte) 184, (byte) 216, 34});
        InetAddress priv = InetAddress.getByAddress(new byte[] {10, 0, 0, 1});

        TokenResponse res = client(h -> new InetAddress[] {pub, priv}, policy::isBlocked, server.port(),
                Duration.ofSeconds(8)).refresh(provider(), CANARY_REFRESH.toCharArray());

        assertThat(res).isInstanceOf(TokenResponse.Refused.class);
    }

    @Test
    void anyPortButTheConfiguredOneAndNonHttpsAreRefused() {
        TokenEndpointClient c = client(h -> loopback(), a -> false, 443, Duration.ofSeconds(8));
        assertThat(c.refresh(provider(), CANARY_REFRESH.toCharArray())).isInstanceOf(TokenResponse.Refused.class);

        TokenProvider http = new TokenProvider("generic", URI.create("http://" + HOST + "/t"), "c", () -> new Secret(new char[] {'x'}));
        assertThat(client().refresh(http, CANARY_REFRESH.toCharArray())).isInstanceOf(TokenResponse.Refused.class);
        assertThat(server.requests).isEmpty();
    }

    @Test
    void ipLiteralTokenHostIsRefused() {
        TokenProvider ip = new TokenProvider("generic", URI.create("https://127.0.0.1:" + server.port() + "/t"), "c",
                () -> new Secret(new char[] {'x'}));

        assertThat(client().refresh(ip, CANARY_REFRESH.toCharArray())).isInstanceOf(TokenResponse.Refused.class);
    }

    @Test
    void oversizedHeadersAreMalformed() throws Exception {
        String huge = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nX-Pad: " + "a".repeat(9 * 1024)
                + "\r\nContent-Length: 2\r\n\r\n{}";
        try (RawServer raw = new RawServer(huge)) {
            assertThat(againstRaw(raw)).isInstanceOf(TokenResponse.Malformed.class);
        }
    }

    @Test
    void badStatusLineAndUnsupportedTransferAreMalformed() throws Exception {
        try (RawServer raw = new RawServer("HTTP/2 200 OK\r\nContent-Length: 2\r\n\r\n{}")) {
            assertThat(againstRaw(raw)).isInstanceOf(TokenResponse.Malformed.class);
        }
        try (RawServer raw = new RawServer(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: gzip\r\n\r\n{}")) {
            assertThat(againstRaw(raw)).isInstanceOf(TokenResponse.Malformed.class);
        }
        try (RawServer raw = new RawServer("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + ok(""))) {
            assertThat(againstRaw(raw)).isInstanceOf(TokenResponse.Malformed.class); // no length, not chunked
        }
    }

    @Test
    void slowDripPastTheDeadlineIsUnavailable() throws Exception {
        try (RawServer raw = new RawServer("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 400\r\n\r\n"
                + "{".repeat(400)).dripEveryMillis(100)) {
            TokenEndpointClient c = client(h -> loopback(), a -> false, raw.port(), Duration.ofSeconds(1));
            long start = System.nanoTime();
            assertThat(c.refresh(provider(raw.port()), CANARY_REFRESH.toCharArray()))
                    .isEqualTo(new TokenResponse.Unavailable(null));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
    }

    private TokenResponse againstRaw(RawServer raw) {
        return client(h -> loopback(), a -> false, raw.port(), Duration.ofSeconds(8))
                .refresh(provider(raw.port()), CANARY_REFRESH.toCharArray());
    }

    /** A bare TLS server that answers every connection with fixed bytes. */
    private static final class RawServer implements AutoCloseable {
        private final SSLServerSocket socket;
        private final Thread thread;
        private volatile int dripMillis;
        final AtomicInteger connections = new AtomicInteger();

        RawServer(String response) throws Exception {
            socket = (SSLServerSocket) pki.serverContext(HOST + "").getServerSocketFactory()
                    .createServerSocket(0, 5, InetAddress.getLoopbackAddress());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            thread = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        SSLSocket s = (SSLSocket) socket.accept();
                        connections.incrementAndGet();
                        Thread.ofVirtual().start(() -> serve(s, bytes));
                    }
                } catch (IOException e) {
                    // closed
                }
            });
        }

        RawServer dripEveryMillis(int ms) {
            this.dripMillis = ms;
            return this;
        }

        private void serve(SSLSocket s, byte[] bytes) {
            try (s) {
                InputStream in = s.getInputStream();
                int state = 0;
                while (state < 4) { // read the request head
                    int b = in.read();
                    if (b < 0) {
                        return;
                    }
                    state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))
                            ? state + 1 : (b == '\r' ? 1 : 0);
                }
                OutputStream out = s.getOutputStream();
                if (dripMillis == 0) {
                    out.write(bytes);
                } else {
                    for (byte b : bytes) {
                        out.write(b);
                        out.flush();
                        Thread.sleep(dripMillis);
                    }
                }
                out.flush();
            } catch (IOException | InterruptedException e) {
                // the client closed
            }
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
