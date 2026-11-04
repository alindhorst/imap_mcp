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

    /**
     * The advertised capability set (SPEC 5.8, v2 write tests), set by an {@code imap_capability} fixture copied to
     * {@code conf.d/98-*.conf}. SPECIAL-USE stays advertised in all of them.
     */
    public enum Profile {
        FULL(null, true, true, true),
        NO_MOVE("dovecot/98-no-move.conf", false, true, true),
        NO_MOVE_NO_UIDPLUS("dovecot/98-no-move-no-uidplus.conf", false, false, true),
        /** Everything but UTF8=ACCEPT: Angus does not ENABLE it, names travel in modified UTF-7 (#179). */
        NO_UTF8_ACCEPT("dovecot/98-no-utf8-accept.conf", true, true, false);

        private final String fixture;
        private final boolean move;
        private final boolean uidplus;
        private final boolean utf8Accept;

        Profile(String fixture, boolean move, boolean uidplus, boolean utf8Accept) {
            this.fixture = fixture;
            this.move = move;
            this.uidplus = uidplus;
            this.utf8Accept = utf8Accept;
        }

        public boolean move() {
            return move;
        }

        public boolean uidplus() {
            return uidplus;
        }

        public boolean utf8Accept() {
            return utf8Accept;
        }
    }

    private final String password;
    private final Profile profile;

    public DovecotContainer() {
        this(PASSWORD);
    }

    /** With a non-default password (e.g. a marker string a test asserts never shows up in logs). */
    public DovecotContainer(String password) {
        this(password, Profile.FULL);
    }

    public DovecotContainer(Profile profile) {
        this(PASSWORD, profile);
    }

    public DovecotContainer(String password, Profile profile) {
        super(DockerImageName.parse(ItImages.DOVECOT));
        this.password = password;
        this.profile = profile;
        withEnv("USER_PASSWORD", "{PLAIN}" + password);
        withExposedPorts(IMAPS_PORT, STARTTLS_PORT);
        withNetworkAliases(NETWORK_ALIAS);
        withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/tls.crt", 0644), "/etc/dovecot/ssl/tls.crt");
        // Test-only key, deliberately world-readable so the unprivileged "vmail" user can read it.
        withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/tls.key", 0644), "/etc/dovecot/ssl/tls.key");
        withCopyFileToContainer(MountableFile.forClasspathResource("dovecot/99-it.conf", 0644),
                "/etc/dovecot/conf.d/99-it.conf");
        if (profile.fixture != null) {
            withCopyFileToContainer(MountableFile.forClasspathResource(profile.fixture, 0644),
                    "/etc/dovecot/conf.d/" + profile.fixture.substring("dovecot/".length()));
        }
        waitingFor(Wait.forLogMessage(".*starting up for imap.*", 1));
    }

    public Profile profile() {
        return profile;
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
