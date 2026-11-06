package de.alexanderlindhorst.imapmcp.it;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Keycloak in development mode on a dynamic port with the test realm {@code imap} imported
 * ({@code keycloak/imap-realm.json}: confidential client {@value #CLIENT_ID} with PKCE S256, users alice with the
 * role {@code imap-user} and bob without). The image is pinned by digest in {@link ItImages}. The issuer is
 * derived from the request host, so it is {@link #issuer()} for clients that use {@link #baseUrl()}.
 */
public final class KeycloakFixture extends GenericContainer<KeycloakFixture> {

    public static final String REALM = "imap";
    public static final String CLIENT_ID = "imap-mcp-web";
    public static final String CLIENT_SECRET = "web-secret-for-tests";
    public static final String ALICE = "alice";
    public static final String ALICE_PASSWORD = "alice-pw";
    public static final String BOB = "bob";
    public static final String BOB_PASSWORD = "bob-pw";
    private static final int HTTP_PORT = 8080;

    public KeycloakFixture() {
        super(DockerImageName.parse(ItImages.KEYCLOAK).asCompatibleSubstituteFor("quay.io/keycloak/keycloak"));
        withExposedPorts(HTTP_PORT);
        withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
        withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin-for-tests");
        withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/imap-realm.json"),
                "/opt/keycloak/data/import/imap-realm.json");
        withCommand("start-dev", "--import-realm");
        waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration").forPort(HTTP_PORT)
                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /** {@code http://<host>:<mapped port>}. */
    public String baseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }

    public String issuer() {
        return baseUrl() + "/realms/" + REALM;
    }

    public String certsUrl() {
        return issuer() + "/protocol/openid-connect/certs";
    }
}
