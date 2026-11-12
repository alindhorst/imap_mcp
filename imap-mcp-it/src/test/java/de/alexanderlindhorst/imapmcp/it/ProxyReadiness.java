package de.alexanderlindhorst.imapmcp.it;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Readiness probe of the deployed stack's forward proxy: a raw {@code CONNECT} must be answered with status 200.
 * Every other outcome is retried until the deadline: a refused connection (rootless Podman), a connection that a
 * userland proxy accepted and closed before squid listens (native Docker), a reset, silence, and a non-200 status
 * line (503 while Caddy is still starting). The timeout message names the last observation.
 */
final class ProxyReadiness {

    private ProxyReadiness() {
    }

    /** Status line of the proxy's answer to a raw {@code CONNECT}; never null: EOF or an empty line is an IOException. */
    static String connect(String host, int port, String authority, int connectMs, int readMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectMs);
            socket.setSoTimeout(readMs);
            socket.getOutputStream().write(("CONNECT " + authority + " HTTP/1.1\r\nHost: " + authority + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            if (line == null || line.isEmpty()) {
                throw new EOFException("closed without a status line");
            }
            return line;
        }
    }

    /** Ready means a status code token of exactly {@code 200}. */
    static boolean isReady(String statusLine) {
        String[] tokens = statusLine.split(" ");
        return tokens.length >= 2 && tokens[1].equals("200");
    }

    /** One attempt as exactly one observation; null when the proxy is ready. */
    static String probe(String host, int port, String authority, int connectMs, int readMs) {
        try {
            String line = connect(host, port, authority, connectMs, readMs);
            return isReady(line) ? null : "status line: " + line;
        } catch (ConnectException e) {
            return "refused";
        } catch (EOFException e) {
            return "closed without data";
        } catch (SocketTimeoutException e) {
            return "no answer";
        } catch (SocketException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
            return m.contains("reset") || m.contains("broken pipe") ? "reset" : "connection error";
        } catch (IOException e) {
            return "connection error";
        }
    }

    /** Returns when the proxy tunnels; throws IllegalStateException after the deadline; an interrupt propagates. */
    static void await(String host, int port, String authority, Duration deadline, Duration interval, int connectMs,
            int readMs) throws InterruptedException {
        long end = System.nanoTime() + deadline.toNanos();
        int attempts = 0;
        while (true) {
            attempts++;
            String observation = probe(host, port, authority, connectMs, readMs);
            if (observation == null) {
                return;
            }
            if (System.nanoTime() >= end) {
                throw new IllegalStateException("the forward proxy does not tunnel to " + authority + " after "
                        + deadline.toSeconds() + " s (" + attempts + " attempts); last observation: " + observation);
            }
            Thread.sleep(interval.toMillis());
        }
    }
}
