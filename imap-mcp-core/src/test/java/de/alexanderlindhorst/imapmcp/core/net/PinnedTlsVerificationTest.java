package de.alexanderlindhorst.imapmcp.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TLS layered over the pinned socket the way Angus does it ({@code SSLSocketFactory.createSocket(socket, host, port,
 * true)} with endpoint identification): the host name, not the pinned address, is what SNI and verification see.
 */
class PinnedTlsVerificationTest {

    private static final String NAME = "imap.example.test";

    @TempDir
    Path dir;

    private SSLContext context;
    private SSLServerSocket server;
    private volatile String sniSeen;

    @BeforeEach
    void start() throws Exception {
        Path p12 = dir.resolve("k.p12");
        Process pr = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=unrelated", "-ext", "san=dns:" + NAME, "-storetype", "PKCS12", "-keystore", p12.toString(),
                "-storepass", "changeit").redirectErrorStream(true).start();
        pr.getInputStream().readAllBytes();
        assertThat(pr.waitFor()).isZero();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks); // the self-signed fixture certificate is the only trust anchor
        context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (SSLSocket c = (SSLSocket) server.accept()) {
                    c.startHandshake();
                    var names = ((javax.net.ssl.ExtendedSSLSession) c.getSession()).getRequestedServerNames();
                    if (!names.isEmpty()) {
                        sniSeen = ((SNIHostName) names.get(0)).getAsciiName();
                    }
                } catch (Exception ignored) {
                    // failed handshakes are the point of the negative test
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
    }

    private void handshake(String hostForTls) throws Exception {
        int port = server.getLocalPort();
        PinnedSocketFactory f = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()),
                h -> new InetAddress[] {InetAddress.getByAddress(h, new byte[] {127, 0, 0, 1})}, a -> false, p -> p == port);
        try (Socket plain = f.createSocket()) {
            plain.connect(InetSocketAddress.createUnresolved(NAME, port), 5000);
            SSLSocketFactory ssf = context.getSocketFactory();
            try (SSLSocket tls = (SSLSocket) ssf.createSocket(plain, hostForTls, port, true)) {
                SSLParameters params = tls.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS"); // what ssl.checkserveridentity does
                tls.setSSLParameters(params);
                tls.startHandshake();
            }
        }
    }

    @Test
    void certificateForTheLinkedNameVerifiesAndSniCarriesTheName() throws Exception {
        handshake(NAME);
        for (int i = 0; i < 100 && sniSeen == null; i++) {
            Thread.sleep(20); // the server thread records the SNI after its own handshake returns
        }
        assertThat(sniSeen).isEqualTo(NAME);
    }

    @Test
    void certificateForAnotherNameIsRefused() {
        assertThatThrownBy(() -> handshake("other.example.test")).isInstanceOf(SSLException.class);
    }
}
