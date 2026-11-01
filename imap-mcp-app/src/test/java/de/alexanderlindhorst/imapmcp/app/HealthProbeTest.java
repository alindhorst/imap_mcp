package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HealthProbeTest {

    private static HttpServer stub(int status) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/health", ex -> {
            byte[] body = "{}".getBytes();
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        s.start();
        return s;
    }

    @Test
    void exitsZeroOn200() throws Exception {
        HttpServer s = stub(200);
        try {
            assertThat(HealthProbe.probe(s.getAddress().getPort(), 3000)).isZero();
        } finally {
            s.stop(0);
        }
    }

    @Test
    void exitsOneOn503() throws Exception {
        HttpServer s = stub(503);
        try {
            assertThat(HealthProbe.probe(s.getAddress().getPort(), 3000)).isEqualTo(1);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void exitsOneOnConnectionRefusedQuickly() throws Exception {
        HttpServer s = stub(200);
        int port = s.getAddress().getPort();
        s.stop(0);
        long t0 = System.nanoTime();
        assertThat(HealthProbe.probe(port, 3000)).isEqualTo(1);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(3500);
    }

    @Test
    void exitsOneWhenServerHangsAndHonoursTimeout() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        s.createContext("/health", ex -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        s.start();
        try {
            long t0 = System.nanoTime();
            assertThat(HealthProbe.probe(s.getAddress().getPort(), 500)).isEqualTo(1);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(3000);
        } finally {
            release.countDown();
            s.stop(0);
        }
    }

    @Test
    void portDefaultsTo8080AndComesFromEnv() {
        assertThat(HealthProbe.port(null)).isEqualTo(8080);
        assertThat(HealthProbe.port("")).isEqualTo(8080);
        assertThat(HealthProbe.port("9123")).isEqualTo(9123);
        assertThat(HealthProbe.port("garbage")).isEqualTo(8080);
    }
}
