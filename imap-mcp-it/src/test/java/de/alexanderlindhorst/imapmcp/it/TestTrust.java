package de.alexanderlindhorst.imapmcp.it;

import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collections;

/**
 * Test-only trust material. The server under test never gets a trust-all switch; the child JVM is started with a
 * PKCS12 truststore ({@code -Djavax.net.ssl.trustStore}) holding exactly the certificate of the fixture.
 */
public final class TestTrust {

    public static final String PASSWORD = "changeit";

    private TestTrust() {
    }

    /** Truststore with the committed Dovecot test certificate ({@code dovecot/tls.crt}, SAN localhost, 127.0.0.1). */
    public static Path writeTruststore(Path dir) throws Exception {
        KeyStore trust = emptyStore();
        try (InputStream in = TestTrust.class.getResourceAsStream("/dovecot/tls.crt")) {
            Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
            trust.setCertificateEntry("dovecot-it", cert);
        }
        return store(trust, dir.resolve("dovecot-truststore.p12"));
    }

    /** Truststore with the certificate GreenMail's IMAPS server presents. */
    public static Path writeGreenMailTruststore(Path dir) throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        try (InputStream in = DummySSLServerSocketFactory.class.getResourceAsStream("/greenmail.p12")) {
            source.load(in, PASSWORD.toCharArray());
        }
        KeyStore trust = emptyStore();
        for (String alias : Collections.list(source.aliases())) {
            Certificate c = source.getCertificate(alias);
            if (c != null) {
                trust.setCertificateEntry(alias, c);
            }
        }
        return store(trust, dir.resolve("greenmail-truststore.p12"));
    }

    private static KeyStore emptyStore() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        return trust;
    }

    private static Path store(KeyStore trust, Path file) throws Exception {
        try (OutputStream out = Files.newOutputStream(file)) {
            trust.store(out, PASSWORD.toCharArray());
        }
        return file;
    }
}
