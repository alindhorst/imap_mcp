package de.alexanderlindhorst.imapmcp.app;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Docker HEALTHCHECK entry point (SPEC 5.6): GET {@code http://127.0.0.1:<port>/health}, exit 0 on 200, else 1. Port
 * from env {@code IMAP_MCP_HTTP_PORT} (default 8080). Deliberately free of logging and SLF4J so it starts fast and
 * never initialises Logback; writes nothing to stdout or stderr.
 */
public final class HealthProbe {

    static final int DEFAULT_PORT = 8080;
    static final int TIMEOUT_MILLIS = 3000;

    private HealthProbe() {
    }

    public static void main(String[] args) {
        System.exit(probe(port(System.getenv("IMAP_MCP_HTTP_PORT")), TIMEOUT_MILLIS));
    }

    /** @return 0 if /health answered 200 within the timeout, else 1. */
    static int probe(int port, int timeoutMillis) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/health").toURL().openConnection();
            c.setConnectTimeout(timeoutMillis);
            c.setReadTimeout(timeoutMillis);
            c.setRequestMethod("GET");
            return c.getResponseCode() == 200 ? 0 : 1;
        } catch (Exception e) {
            return 1;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    static int port(String env) {
        if (env == null || env.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int p = Integer.parseInt(env.strip());
            return p >= 1 && p <= 65535 ? p : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }
}
