package de.alexanderlindhorst.imapmcp.it;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Dovecot 2.4.5 fixture for XOAUTH2 (#492, SPEC 5.8). Profile {@link Mode#XOAUTH2} advertises {@code AUTH=XOAUTH2} and
 * validates JWTs locally (oauth2 passdb, {@code oauth2_introspection_mode = local}) against the public key of the
 * {@link Xoauth2Jwt} given to the constructor; profile {@link Mode#NONE} advertises no XOAUTH2 and accepts only the
 * static password {@link #PASSWORD} (so a JWT sent as the LOGIN password is rejected). Config and key are copied into
 * the container, not bind-mounted. Independent of {@link DovecotContainer}, whose profiles stay unchanged.
 */
public final class DovecotOauth2Container extends GenericContainer<DovecotOauth2Container> {

    public static final int IMAPS_PORT = 31993;
    public static final String PASSWORD = "secret";

    public enum Mode {
        XOAUTH2("dovecot/98-xoauth2.conf"),
        NONE("dovecot/98-no-xoauth2.conf");

        private final String fixture;

        Mode(String fixture) {
            this.fixture = fixture;
        }
    }

    public DovecotOauth2Container(Mode mode, Xoauth2Jwt jwt) {
        super(DockerImageName.parse(ItImages.DOVECOT));
        withEnv("USER_PASSWORD", "{PLAIN}" + PASSWORD);
        withExposedPorts(IMAPS_PORT);
        withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/tls.crt", 0644), "/etc/dovecot/ssl/tls.crt");
        // Test-only key, deliberately world-readable so the unprivileged "vmail" user can read it.
        withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/tls.key", 0644), "/etc/dovecot/ssl/tls.key");
        withCopyFileToContainer(MountableFile.forClasspathResource(mode.fixture, 0644),
                "/etc/dovecot/conf.d/98-oauth2-fixture.conf");
        if (mode == Mode.XOAUTH2) {
            // Looked up as <azp|default>/RS256/<kid|default> below the dict prefix of the fixture config.
            withCopyToContainer(Transferable.of(jwt.publicKeyPem(), 0644), "/etc/dovecot/keys/default/RS256/default");
        }
        waitingFor(Wait.forLogMessage(".*starting up for imap.*", 1));
    }

    /**
     * Profile for access tokens issued by Keycloak (#500): the issuer string Keycloak puts into its tokens, the user
     * taken from the e-mail claim and no key yet; the realm's key is added with {@link #trustKey} after start.
     */
    public DovecotOauth2Container(String issuer) {
        super(DockerImageName.parse(ItImages.DOVECOT));
        withEnv("USER_PASSWORD", "{PLAIN}" + PASSWORD);
        withExposedPorts(IMAPS_PORT);
        withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/tls.crt", 0644), "/etc/dovecot/ssl/tls.crt");
        withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/tls.key", 0644), "/etc/dovecot/ssl/tls.key");
        String conf;
        try (var in = DovecotOauth2Container.class.getClassLoader()
                .getResourceAsStream("dovecot/98-xoauth2-keycloak.conf")) {
            conf = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("@ISSUER@", issuer);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        withCopyToContainer(Transferable.of(conf, 0644), "/etc/dovecot/conf.d/98-oauth2-fixture.conf");
        waitingFor(Wait.forLogMessage(".*starting up for imap.*", 1));
    }

    /** Adds a token signing key (PEM) for tokens of client {@code azp} with key id {@code kid}; read per lookup. */
    public void trustKey(String azp, String kid, String publicKeyPem) {
        copyFileToContainer(Transferable.of(publicKeyPem, 0644), "/etc/dovecot/keys/" + azp + "/RS256/" + kid);
    }

    public int imapsPort() {
        return getMappedPort(IMAPS_PORT);
    }
}
