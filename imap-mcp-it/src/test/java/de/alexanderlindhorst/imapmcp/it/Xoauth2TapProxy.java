package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A TLS-terminating relay in front of a Dovecot fixture that records the IMAP exchange in clear text (#498, SPEC 5.8
 * "the wire shows no LOGIN carrying the token"). The application under test is pointed at the relay's loopback port
 * (it trusts any certificate in these ITs, like the other ITs); the relay talks to the fixture over TLS and trusts
 * its test certificate blindly. Test code only; it logs nothing.
 */
final class Xoauth2TapProxy implements AutoCloseable {

    private final ServerSocket listener;
    private final String upstreamHost;
    private final int upstreamPort;
    private final SSLContext upstreamTls;
    private final boolean plainUpstream;
    private volatile String hostHeader;
    private final StringBuilder client = new StringBuilder();
    private final StringBuilder server = new StringBuilder();
    private final java.util.Set<Socket> open = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    Xoauth2TapProxy(SSLContext serverTls, String upstreamHost, int upstreamPort) throws Exception {
        this(serverTls, upstreamHost, upstreamPort, false);
    }

    /** {@code plainUpstream}: the relay terminates TLS only and talks plain TCP to the target (an http Keycloak). */
    Xoauth2TapProxy(SSLContext serverTls, String upstreamHost, int upstreamPort, boolean plainUpstream)
            throws Exception {
        this.plainUpstream = plainUpstream;
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] c, String t) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String t) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        upstreamTls = SSLContext.getInstance("TLS");
        upstreamTls.init(null, new TrustManager[] {trustAll}, null);
        listener = serverTls.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        ((SSLServerSocket) listener).setUseClientMode(false);
        Thread acceptor = new Thread(this::acceptLoop, "xoauth2-tap-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * Replaces the {@code Host} header of the first request line block of each connection (an HTTP target that derives
     * its issuer from the request host, #500). Null: bytes pass unchanged.
     */
    Xoauth2TapProxy rewriteHost(String host) {
        this.hostHeader = host;
        return this;
    }

    /** Closes every relayed connection now (the client's pooled connections die; new ones are accepted as usual). */
    void dropConnections() {
        for (Socket s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        open.clear();
    }

    int port() {
        return listener.getLocalPort();
    }

    /** Everything the client sent so far (all connections), as text. */
    String clientWire() {
        synchronized (client) {
            return client.toString();
        }
    }

    /** Everything the server sent so far (all connections), as text. */
    String serverWire() {
        synchronized (server) {
            return server.toString();
        }
    }

    /** Number of connections accepted so far. */
    int connections() {
        return connections;
    }

    private volatile int connections;

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket in = listener.accept();
                connections++;
                Thread t = new Thread(() -> relay(in), "xoauth2-tap-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void relay(Socket in) {
        try (Socket down = in; Socket up = plainUpstream ? new Socket(upstreamHost, upstreamPort)
                : upstreamTls.getSocketFactory().createSocket(upstreamHost, upstreamPort)) {
            open.add(down);
            open.add(up);
            if (up instanceof SSLSocket tls) {
                tls.startHandshake();
            }
            Thread back = new Thread(() -> pump(up, down, server, null), "xoauth2-tap-back");
            back.setDaemon(true);
            back.start();
            pump(down, up, client, hostHeader);
        } catch (IOException e) {
            // the peer went away; nothing to record
        }
    }

    private static void pump(Socket from, Socket to, StringBuilder record, String rewriteHost) {
        boolean first = true;
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (first && rewriteHost != null) {
                    String head = new String(buf, 0, n, StandardCharsets.ISO_8859_1)
                            .replaceFirst("(?im)^Host:[^\\r\\n]*", "Host: " + rewriteHost);
                    buf = head.getBytes(StandardCharsets.ISO_8859_1);
                    n = buf.length;
                }
                first = false;
                synchronized (record) {
                    record.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                }
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // closed
        } finally {
            try {
                to.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        listener.close();
    }
}
