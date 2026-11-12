package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.time.Duration;

/** Readiness probe of the forward proxy (stub, red). */
final class ProxyReadiness {

    private ProxyReadiness() {
    }

    static String connect(String host, int port, String authority, int connectMs, int readMs) throws IOException {
        throw new UnsupportedOperationException();
    }

    static boolean isReady(String statusLine) {
        throw new UnsupportedOperationException();
    }

    static void await(String host, int port, String authority, Duration deadline, Duration interval, int connectMs,
            int readMs) throws InterruptedException {
        throw new UnsupportedOperationException();
    }
}
