package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** The proxy readiness probe against fake proxies on loopback (dynamic ports, no Docker). */
class ProxyReadinessTest {

    private static final String AUTH = "mail.imap-mcp.test:8443";
    private static final String OK = "HTTP/1.1 200 Connection established\r\n\r\n";

    /** A fake proxy: the handler gets the zero-based connection number and the accepted socket. */
    private static final class Fake implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger connections = new AtomicInteger();
        final Thread thread;

        Fake(BiConsumer<Integer, Socket> handler) throws IOException {
            server = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        int n = connections.getAndIncrement();
                        handler.accept(n, s);
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "fake-proxy");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // test fixture
        }
    }

    private static void answer(Socket s, String text) {
        try {
            s.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
        } catch (IOException e) {
            // the client may have given up
        }
        closeQuietly(s);
    }

    private static void reset(Socket s) {
        try {
            s.setSoLinger(true, 0);
        } catch (IOException e) {
            // test fixture
        }
        closeQuietly(s);
    }

    private static void await(Fake f, Duration deadline) throws InterruptedException {
        ProxyReadiness.await("127.0.0.1", f.port(), AUTH, deadline, Duration.ofMillis(20), 500, 300);
    }

    @Test
    void anAcceptedThenClosedConnectionIsRetriedUntilTheProxyAnswers() throws Exception {
        try (Fake f = new Fake((n, s) -> {
            if (n < 3) {
                closeQuietly(s);
            } else {
                answer(s, OK);
            }
        })) {
            await(f, Duration.ofSeconds(5));
            assertThat(f.connections.get()).isEqualTo(4);
        }
    }

    @Test
    void aResetConnectionIsRetried() throws Exception {
        try (Fake f = new Fake((n, s) -> {
            if (n < 2) {
                reset(s);
            } else {
                answer(s, OK);
            }
        })) {
            await(f, Duration.ofSeconds(5));
            assertThat(f.connections.get()).isEqualTo(3);
        }
    }

    @Test
    void aServiceUnavailableStatusIsRetried() throws Exception {
        try (Fake f = new Fake((n, s) -> answer(s, n < 2 ? "HTTP/1.1 503 Service Unavailable\r\n\r\n" : OK))) {
            await(f, Duration.ofSeconds(5));
            assertThat(f.connections.get()).isEqualTo(3);
        }
    }

    @Test
    void aProxyThatAlwaysClosesWithoutDataTimesOutNamingTheObservation() throws Exception {
        try (Fake f = new Fake((n, s) -> closeQuietly(s))) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> await(f, Duration.ofMillis(500))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed without data").hasMessageContaining(AUTH)
                    .hasMessageContaining("attempts").hasMessageContaining("last observation");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            assertThat(f.connections.get()).isGreaterThan(2);
        }
    }

    @Test
    void aProxyThatNeverAnswersIsReportedAsNoAnswer() throws Exception {
        try (Fake f = new Fake((n, s) -> {
            // holds the connection open without a reply; the client's read timeout ends the probe
            try {
                s.getInputStream().read(new byte[1024]);
                Thread.sleep(1500);
            } catch (IOException | InterruptedException e) {
                // done
            }
            closeQuietly(s);
        })) {
            assertThatThrownBy(() -> ProxyReadiness.await("127.0.0.1", f.port(), AUTH, Duration.ofMillis(400),
                    Duration.ofMillis(20), 500, 200)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no answer");
        }
    }

    @Test
    void aForbiddenStatusLineIsReportedVerbatim() throws Exception {
        try (Fake f = new Fake((n, s) -> answer(s, "HTTP/1.1 403 Forbidden\r\n\r\n"))) {
            assertThatThrownBy(() -> await(f, Duration.ofMillis(300))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("status line: HTTP/1.1 403 Forbidden");
        }
    }

    @Test
    void noListenerIsReportedAsRefused() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        assertThatThrownBy(() -> ProxyReadiness.await("127.0.0.1", port, AUTH, Duration.ofMillis(300),
                Duration.ofMillis(20), 500, 300)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused");
    }

    @Test
    void theStatusCodeTokenMustBeExactly200() {
        assertThat(ProxyReadiness.isReady("HTTP/1.1 200 Connection established")).isTrue();
        assertThat(ProxyReadiness.isReady("HTTP/1.1 200")).isTrue();
        assertThat(ProxyReadiness.isReady("HTTP/1.1 2000 X")).isFalse();
        assertThat(ProxyReadiness.isReady("HTTP/1.1 503 200")).isFalse();
        assertThat(ProxyReadiness.isReady("")).isFalse();
    }

    @Test
    void aSingleAttemptAgainstAnAcceptAndCloseServerThrowsAnIoExceptionNeverNull() throws Exception {
        try (Fake f = new Fake((n, s) -> closeQuietly(s))) {
            assertThatThrownBy(() -> ProxyReadiness.connect("127.0.0.1", f.port(), AUTH, 500, 300))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void aSingleAttemptReturnsTheFirstLineVerbatim() throws Exception {
        try (Fake f = new Fake((n, s) -> answer(s, "HTTP/1.1 403 Forbidden\r\nX: y\r\n\r\n"))) {
            assertThat(ProxyReadiness.connect("127.0.0.1", f.port(), AUTH, 500, 300))
                    .isEqualTo("HTTP/1.1 403 Forbidden");
        }
    }
}
