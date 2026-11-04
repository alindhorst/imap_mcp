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

    public enum Mode { NONE, REJECT_WITH_NO, DROP_CONNECTION }

    private final ServerSocket server;
    private final int targetPort;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.NONE;
    private volatile String needle = "BODYSTRUCTURE";
    private volatile String noText = "rejected";

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
                if (mode != Mode.NONE && cmd.contains(needle)) {
                    if (mode == Mode.DROP_CONNECTION) {
                        return;
                    }
                    String tag = cmd.substring(0, cmd.indexOf(' '));
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
