package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Guards the committed Dovecot test fixture (certificate and private key, #147 item 8). */
class TestTrustTest {

    /** Fails a year before the fixture certificate expires, so ITs do not start failing without warning. */
    @Test
    void dovecotFixtureCertificateIsValidForAtLeastAnotherYear() throws Exception {
        X509Certificate cert;
        try (InputStream in = TestTrust.class.getResourceAsStream("/dovecot/tls.crt")) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        assertThat(cert.getNotAfter().toInstant())
                .as("regenerate dovecot/tls.crt and tls.key (CN=localhost, SAN dovecot, localhost and 127.0.0.1; see dovecot/README.md)")
                .isAfter(Instant.now().plus(Duration.ofDays(365)));
    }
}
