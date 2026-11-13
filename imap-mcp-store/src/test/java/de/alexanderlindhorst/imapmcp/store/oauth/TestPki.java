package de.alexanderlindhorst.imapmcp.store.oauth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A test CA generated at run time with the JDK's {@code keytool} (no key material in the repository), and server
 * certificates signed by it. The client side trusts only this CA.
 */
final class TestPki {

    private static final String PASS = "changeit";

    private final Path dir;
    private final Path caStore;
    private final Path caPem;

    TestPki(Path dir) throws Exception {
        this.dir = dir;
        this.caStore = dir.resolve("ca.p12");
        this.caPem = dir.resolve("ca.pem");
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=test-ca",
                "-ext", "bc:c", "-ext", "ku:c=keyCertSign", "-validity", "2", "-keystore", caStore.toString(),
                "-storepass", PASS, "-storetype", "PKCS12");
        keytool("-exportcert", "-alias", "ca", "-rfc", "-file", caPem.toString(), "-keystore", caStore.toString(),
                "-storepass", PASS);
    }

    /** A server SSL context whose certificate (signed by the test CA) is valid for {@code dnsName} only. */
    SSLContext serverContext(String dnsName) throws Exception {
        Path store = dir.resolve("srv-" + dnsName + ".p12");
        Path csr = dir.resolve("srv-" + dnsName + ".csr");
        Path pem = dir.resolve("srv-" + dnsName + ".pem");
        keytool("-genkeypair", "-alias", "srv", "-keyalg", "EC", "-groupname", "secp256r1", "-dname",
                "CN=" + dnsName, "-validity", "2", "-keystore", store.toString(), "-storepass", PASS,
                "-storetype", "PKCS12");
        keytool("-certreq", "-alias", "srv", "-file", csr.toString(), "-keystore", store.toString(),
                "-storepass", PASS);
        keytool("-gencert", "-alias", "ca", "-infile", csr.toString(), "-outfile", pem.toString(), "-rfc",
                "-ext", "san=dns:" + dnsName, "-ext", "eku=serverAuth", "-validity", "2", "-keystore",
                caStore.toString(), "-storepass", PASS);
        keytool("-importcert", "-alias", "ca", "-file", caPem.toString(), "-noprompt", "-keystore",
                store.toString(), "-storepass", PASS);
        keytool("-importcert", "-alias", "srv", "-file", pem.toString(), "-keystore", store.toString(),
                "-storepass", PASS);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, PASS.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** A client SSL context that trusts the test CA only. */
    SSLContext clientContext() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (InputStream in = Files.newInputStream(caPem)) {
            Certificate ca = CertificateFactory.getInstance("X.509").generateCertificate(in);
            trust.setCertificateEntry("ca", ca);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static void keytool(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IllegalStateException("keytool failed: " + args[0]);
        }
    }
}
