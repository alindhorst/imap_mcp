package de.alexanderlindhorst.imapmcp.core.testsupport;

import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import com.icegreen.greenmail.util.DummySSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Test stub: an IMAPS relay in front of GreenMail that can answer or break the first matching client command
 * instead of forwarding it, to exercise error paths a well-behaved server never produces. A command matches
 * when its line contains {@code needle}; {@link Mode#NONE} relays everything.
 */
public final class FaultingImapProxy implements AutoCloseable {

    public enum Mode { NONE, REJECT_WITH_NO, DROP_CONNECTION, ANSWER_NIL_BODY, SERVE_BODY }

    private final ServerSocket server;
    private final int targetPort;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.NONE;
    private volatile String needle = "BODYSTRUCTURE";
    private volatile String noText = "rejected";
    private volatile byte[] served = new byte[0];
    private final java.util.concurrent.atomic.AtomicInteger skip = new java.util.concurrent.atomic.AtomicInteger();

    public FaultingImapProxy(int targetPort) throws IOException {
        this.targetPort = targetPort;
        this.server = DummySSLServerSocketFactory.getDefault().createServerSocket(0);
        Thread.ofVirtual().name("faulting-proxy-accept").start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    public void rejectWithNo(String needle, String text) {
        this.needle = needle;
        this.noText = text;
        this.mode = Mode.REJECT_WITH_NO;
    }

    public void dropConnectionOn(String needle) {
        this.needle = needle;
        this.mode = Mode.DROP_CONNECTION;
    }

    /** Like {@link #rejectWithNo(String, String)}, after {@code skip} matching commands were relayed. */
    public void rejectWithNoAfter(String needle, String text, int skip) {
        this.skip.set(skip);
        rejectWithNo(needle, text);
    }

    /** Like {@link #dropConnectionOn(String)}, after {@code skip} matching commands were relayed. */
    public void dropConnectionAfter(String needle, int skip) {
        this.skip.set(skip);
        dropConnectionOn(needle);
    }

    /** Answers a matching {@code FETCH n (BODY.PEEK[...])} with a NIL body item (after {@code skip} relayed ones). */
    public void answerNilBodyAfter(String needle, int skip) {
        this.needle = needle;
        this.skip.set(skip);
        this.mode = Mode.ANSWER_NIL_BODY;
    }

    /**
     * Answers every matching {@code FETCH n (BODY.PEEK[s]<o.l>)} itself, with the {@code o..o+l} slice of
     * {@code content} (an empty string past its end), whatever the real message holds.
     */
    public void serveBody(String needle, byte[] content) {
        this.needle = needle;
        this.served = content.clone();
        this.skip.set(0);
        this.mode = Mode.SERVE_BODY;
    }

    public void relay() {
        this.mode = Mode.NONE;
    }

    private void acceptLoop() {
        try {
            while (!server.isClosed()) {
                Socket client = server.accept();
                Socket backend = DummySSLSocketFactory.getDefault().createSocket("127.0.0.1", targetPort);
                sockets.add(client);
                sockets.add(backend);
                Thread.ofVirtual().start(() -> clientToServer(client, backend));
                Thread.ofVirtual().start(() -> copy(backend, client));
            }
        } catch (IOException e) {
            // closed
        }
    }

    private void copy(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // peer gone
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private void clientToServer(Socket client, Socket backend) {
        try (InputStream in = client.getInputStream(); OutputStream toServer = backend.getOutputStream();
                OutputStream toClient = client.getOutputStream()) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                line.write(b);
                if (b != '\n') {
                    continue;
                }
                String cmd = line.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
                line.reset();
                if (mode != Mode.NONE && cmd.contains(needle) && skip.getAndDecrement() <= 0) {
                    if (mode == Mode.DROP_CONNECTION) {
                        return;
                    }
                    String tag = cmd.substring(0, cmd.indexOf(' '));
                    if (mode == Mode.SERVE_BODY) {
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("FETCH (\\d+) .*BODY\\.PEEK\\[([^\\]]*)\\]<(\\d+)\\.(\\d+)>").matcher(cmd);
                        if (m.find()) {
                            int from = (int) Math.min(Long.parseLong(m.group(3)), served.length);
                            int to = (int) Math.min((long) from + Long.parseLong(m.group(4)), served.length);
                            ByteArrayOutputStream resp = new ByteArrayOutputStream();
                            resp.write(("* " + m.group(1) + " FETCH (BODY[" + m.group(2) + "]<" + m.group(3) + "> {"
                                    + (to - from) + "}\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                            resp.write(served, from, to - from);
                            resp.write((")\r\n" + tag + " OK FETCH completed\r\n")
                                    .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                            toClient.write(resp.toByteArray());
                            toClient.flush();
                            continue;
                        }
                    }
                    if (mode == Mode.ANSWER_NIL_BODY) {
                        String num = cmd.split(" ")[2];
                        toClient.write(("* " + num + " FETCH (BODY[1]<0> NIL)\r\n" + tag + " OK FETCH completed\r\n")
                                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                        toClient.flush();
                        continue;
                    }
                    toClient.write((tag + " NO " + noText + "\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    toClient.flush();
                    continue;
                }
                toServer.write(cmd.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                toServer.flush();
            }
        } catch (IOException e) {
            // peer gone
        } finally {
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // ignore
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        sockets.forEach(FaultingImapProxy::closeQuietly);
    }
}
