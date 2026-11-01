package de.alexanderlindhorst.imapmcp.it;

import jakarta.mail.MessagingException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Dovecot 2.4.5 with a test-only TLS certificate (SAN localhost, 127.0.0.1; {@code src/test/resources/dovecot}) and
 * {@code conf.d/99-it.conf}, which adds the one SPECIAL-USE mailbox the image lacks (\Archive). Any user name is
 * accepted (static passdb) with the single password {@link #PASSWORD}; each user gets a separate mail store.
 * Files are copied into the container, not bind-mounted (the container user could not read bind mounts below a
 * private home directory, and Dovecot silently ignores unreadable includes).
 */
public final class DovecotContainer extends GenericContainer<DovecotContainer> {

    public static final int IMAPS_PORT = 31993;
    public static final int STARTTLS_PORT = 31143;
    public static final String PASSWORD = "secret";
    /** Host name of the container on a shared Network (for Toxiproxy upstreams). */
    public static final String NETWORK_ALIAS = "dovecot";

    private final String password;

    public DovecotContainer() {
        this(PASSWORD);
    }

    /** With a non-default password (e.g. a marker string a test asserts never shows up in logs). */
    public DovecotContainer(String password) {
        super(DockerImageName.parse(ItImages.DOVECOT));
        this.password = password;
        withEnv("USER_PASSWORD", "{PLAIN}" + password);
        withExposedPorts(IMAPS_PORT, STARTTLS_PORT);
        withNetworkAliases(NETWORK_ALIAS);
        withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/tls.crt", 0644), "/etc/dovecot/ssl/tls.crt");
        // Test-only key, deliberately world-readable so the unprivileged "vmail" user can read it.
        withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/tls.key", 0644), "/etc/dovecot/ssl/tls.key");
        withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/99-it.conf", 0644),
                "/etc/dovecot/conf.d/99-it.conf");
        waitingFor(Wait.forLogMessage(".*starting up for imap.*", 1));
    }

    public int imapsPort() {
        return getMappedPort(IMAPS_PORT);
    }

    public int startTlsPort() {
        return getMappedPort(STARTTLS_PORT);
    }

    /** A seeder logged in as {@code user} over implicit TLS. */
    public MailSeeder seeder(String user) throws MessagingException {
        return new MailSeeder(getHost(), imapsPort(), user, password);
    }
}
