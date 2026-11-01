package de.alexanderlindhorst.imapmcp.it;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Reads the untagged greeting of a plain (non-TLS) IMAP port: the pre-login capabilities. */
public final class ImapProbe {

    private ImapProbe() {
    }

    public static String greeting(String host, int port) throws IOException {
        String line = tryGreeting(host, port);
        if (line == null) {
            throw new IOException("no IMAP greeting from " + host + ":" + port);
        }
        return line;
    }

    /** Null when the connection is refused, reset or closed before a greeting arrives. */
    public static String tryGreeting(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(5000);
            return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
