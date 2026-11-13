package de.alexanderlindhorst.imapmcp.it;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
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
 * A minimal raw-socket IMAP client over implicit TLS for fixture checks that must see the wire exchange itself
 * (capabilities, SASL challenges), which Jakarta Mail hides. Trusts the committed test certificate only; the host name
 * is verified. Test code only; never used against real servers.
 */
public final class RawImapClient implements Closeable {

    private final SSLSocket socket;
    private final BufferedReader in;
    private final OutputStream out;
    private int tag;

    /** The untagged greeting line (with the pre-login capabilities). */
    public final String greeting;

    public RawImapClient(String host, int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (InputStream crt = RawImapClient.class.getResourceAsStream("/dovecot/tls.crt")) {
            Certificate c = CertificateFactory.getInstance("X.509").generateCertificate(
                    new ByteArrayInputStream(crt.readAllBytes()));
            ks.setCertificateEntry("dovecot", c);
        }
        tmf.init(ks);
        ctx.init(null, tmf.getTrustManagers(), null);
        socket = (SSLSocket) ctx.getSocketFactory().createSocket();
        var params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(params);
        socket.connect(new InetSocketAddress(host, port), 10_000);
        socket.setSoTimeout(15_000);
        socket.startHandshake();
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        out = socket.getOutputStream();
        greeting = in.readLine();
    }

    /** Sends {@code command} under a fresh tag and returns all lines up to and including the tagged response. */
    public List<String> command(String command) throws IOException {
        String t = "t" + (++tag);
        send(t + " " + command);
        return readUntilTagged(t);
    }

    /**
     * {@code AUTHENTICATE XOAUTH2} with the initial response (SASL-IR). On success returns the lines up to the tagged
     * OK. On failure the server sends an error challenge (a {@code +} line); the empty line the RFC requires is sent
     * and the tagged NO is returned together with the challenge.
     */
    public List<String> authenticateXoauth2(String user, String token) throws IOException {
        String ir = java.util.Base64.getEncoder().encodeToString(
                ("user=" + user + "\u0001auth=Bearer " + token + "\u0001\u0001").getBytes(StandardCharsets.US_ASCII));
        String t = "t" + (++tag);
        send(t + " AUTHENTICATE XOAUTH2 " + ir);
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = in.readLine();
            if (line == null) {
                throw new IOException("connection closed during AUTHENTICATE");
            }
            lines.add(line);
            if (line.startsWith("+")) {
                send("");
            } else if (line.startsWith(t + " ")) {
                return lines;
            }
        }
    }

    private void send(String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private List<String> readUntilTagged(String t) throws IOException {
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = in.readLine();
            if (line == null) {
                throw new IOException("connection closed");
            }
            lines.add(line);
            if (line.startsWith(t + " ")) {
                return lines;
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
