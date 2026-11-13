package de.alexanderlindhorst.imapmcp.it;

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
 * A test CA generated at run time with the JDK's {@code keytool} (no key material in the repository), a server
 * certificate signed by it for one DNS name, and a client context that trusts that CA only (#498, SPEC 5.8).
 */
final class Xoauth2Pki {

    private static final String PASS = "changeit";

    private final Path caPem;
    private final SSLContext server;

    Xoauth2Pki(Path dir, String dnsName) throws Exception {
        Path caStore = dir.resolve("ca.p12");
        caPem = dir.resolve("ca.pem");
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=test-ca",
                "-ext", "bc:c", "-ext", "ku:c=keyCertSign", "-validity", "2", "-keystore", caStore.toString(),
                "-storepass", PASS, "-storetype", "PKCS12");
        keytool("-exportcert", "-alias", "ca", "-rfc", "-file", caPem.toString(), "-keystore", caStore.toString(),
                "-storepass", PASS);
        Path store = dir.resolve("srv.p12");
        Path csr = dir.resolve("srv.csr");
        Path pem = dir.resolve("srv.pem");
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
        server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);
    }

    SSLContext serverContext() {
        return server;
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
