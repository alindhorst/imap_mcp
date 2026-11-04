package de.alexanderlindhorst.imapmcp.it;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * A minimal raw IMAP client over implicit TLS (trust: the committed Dovecot test certificate only), for tests that
 * must see the server without Angus in between: it never sends ENABLE, so it is a connection in the modified UTF-7
 * state, and it shows the capability string exactly as the server advertises it.
 */
final class RawImap implements AutoCloseable {

    private final SSLSocket socket;
    private final BufferedReader in;
    private final OutputStream out;
    private int tag;

    RawImap(String host, int port) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (InputStream cert = RawImap.class.getResourceAsStream("/dovecot/tls.crt")) {
            Certificate c = CertificateFactory.getInstance("X.509").generateCertificate(cert);
            trust.setCertificateEntry("dovecot-it", c);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        socket = (SSLSocket) ctx.getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        socket.setSoTimeout(15_000);
        socket.startHandshake();
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = socket.getOutputStream();
        in.readLine(); // greeting
    }

    /** Sends one command and returns all response lines up to and including the tagged one. */
    List<String> command(String command) throws IOException {
        String t = "R" + (++tag);
        out.write((t + " " + command + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            lines.add(line);
            if (line.startsWith(t + " ")) {
                return lines;
            }
        }
        throw new IOException("connection closed before the tagged response to " + command.split(" ")[0]);
    }

    void login(String user, String password) throws IOException {
        List<String> r = command("LOGIN \"" + user + "\" \"" + password + "\"");
        if (!r.get(r.size() - 1).contains(" OK")) {
            throw new IOException("login failed");
        }
    }

    /** The CAPABILITY atoms the server advertises after login. */
    List<String> capabilities() throws IOException {
        for (String l : command("CAPABILITY")) {
            if (l.startsWith("* CAPABILITY ")) {
                return List.of(l.substring("* CAPABILITY ".length()).split(" "));
            }
        }
        throw new IOException("no CAPABILITY response");
    }

    /** The mailbox names (as the server writes them on this connection) of {@code LIST "" "*"}. */
    List<String> listNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (String l : command("LIST \"\" \"*\"")) {
            if (l.startsWith("* LIST ")) {
                String name = l.substring(l.lastIndexOf(" \"/\" ") + 5);
                names.add(name.startsWith("\"") ? name.substring(1, name.length() - 1) : name);
            }
        }
        return names;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
