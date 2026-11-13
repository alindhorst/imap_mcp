package de.alexanderlindhorst.imapmcp.core.testsupport;

import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny implicit-TLS IMAP server on a dynamic port that records every client line and answers
 * {@code AUTHENTICATE XOAUTH2} the way the test scripts it. It exists for the cases GreenMail cannot produce: a server
 * without {@code AUTH=XOAUTH2}, the error-challenge exchange and {@code [UNAVAILABLE]}.
 */
public final class ScriptedImapsServer implements AutoCloseable {

    /** What the server answers to {@code AUTHENTICATE XOAUTH2}. */
    public enum AuthAnswer {
        /** Error challenge ({@code + <base64 json>}), then {@code NO [AUTHENTICATIONFAILED]} after the empty client line. */
        CHALLENGE_THEN_NO,
        /** A plain {@code NO [UNAVAILABLE]}. */
        UNAVAILABLE,
        /** {@code OK}. */
        OK
    }

    private final ServerSocket server;
    private final String capabilities;
    private final AuthAnswer answer;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final Thread acceptor;
    private volatile boolean closed;

    /** {@code capabilities} is the capability list of the greeting, for example {@code IMAP4rev1 SASL-IR AUTH=XOAUTH2}. */
    public ScriptedImapsServer(String capabilities, AuthAnswer answer) throws IOException {
        this.capabilities = capabilities;
        this.answer = answer;
        this.server = DummySSLServerSocketFactory.getDefault().createServerSocket(0);
        this.acceptor = Thread.ofPlatform().daemon().name("scripted-imaps").start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Every line the client sent, in order, over all connections. */
    public List<String> received() {
        return List.copyOf(received);
    }

    private void acceptLoop() {
        while (!closed) {
            try (Socket s = server.accept()) {
                serve(s);
            } catch (IOException e) {
                if (closed) {
                    return;
                }
            }
        }
    }

    private void serve(Socket s) throws IOException {
        s.setSoTimeout(10_000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
        OutputStream out = s.getOutputStream();
        send(out, "* OK [CAPABILITY " + capabilities + "] scripted ready");
        String line;
        while ((line = in.readLine()) != null) {
            received.add(line);
            String[] p = line.split(" ", 4);
            String tag = p[0];
            String cmd = p.length > 1 ? p[1].toUpperCase(java.util.Locale.ROOT) : "";
            switch (cmd) {
                case "CAPABILITY" -> {
                    send(out, "* CAPABILITY " + capabilities);
                    send(out, tag + " OK done");
                }
                case "AUTHENTICATE" -> {
                    if (p.length < 4) { // no initial response: ask for it
                        send(out, "+ ");
                        received.add(in.readLine());
                    }
                    switch (answer) {
                        case CHALLENGE_THEN_NO -> {
                            send(out, "+ eyJzdGF0dXMiOiI0MDAifQ=="); // {"status":"400"}
                            received.add(in.readLine()); // the client's empty line
                            send(out, tag + " NO [AUTHENTICATIONFAILED] Invalid credentials");
                        }
                        case UNAVAILABLE -> send(out, tag + " NO [UNAVAILABLE] try later");
                        case OK -> send(out, tag + " OK authenticated");
                    }
                }
                case "LOGOUT" -> {
                    send(out, "* BYE bye");
                    send(out, tag + " OK done");
                    return;
                }
                case "LOGIN" -> send(out, tag + " NO no plain login here");
                default -> send(out, tag + " OK done");
            }
        }
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
    }
}
