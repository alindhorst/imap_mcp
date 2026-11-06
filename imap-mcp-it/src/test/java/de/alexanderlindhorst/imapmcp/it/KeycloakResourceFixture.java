package de.alexanderlindhorst.imapmcp.it;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Keycloak (the image of {@link ItImages#KEYCLOAK}, development mode) as the identity provider of the
 * {@code http-public} resource server (SPEC 5.8): realm {@value #REALM} with the public test clients that issue real
 * access tokens by the password grant (audience mapper for the resource, optional scopes {@code imap-mcp:read} and
 * {@code imap-mcp:write}, users alice, bob and carol), and realm {@value #OTHER_REALM} (another issuer). The hostname
 * is fixed to {@value #PUBLIC_HOST}, so the tokens carry the https issuer the server requires, while the container
 * is reached over plain http on its mapped port (token endpoint and keys). The linking pages' fixture is
 * {@link KeycloakFixture}; this one is for the resource-server side.
 */
public final class KeycloakResourceFixture extends GenericContainer<KeycloakResourceFixture> {

    public static final String REALM = "imap-public";
    public static final String OTHER_REALM = "imap-other";
    public static final String PUBLIC_HOST = "https://idp.example.com";
    /** The resource every {@code connector} client's tokens are issued for. */
    public static final String RESOURCE = "https://mail.example.com/mcp";
    public static final String WRONG_AUDIENCE = "https://other.example.com/mcp";
    private static final int HTTP_PORT = 8080;

    public KeycloakResourceFixture() {
        super(DockerImageName.parse(ItImages.KEYCLOAK).asCompatibleSubstituteFor("quay.io/keycloak/keycloak"));
        withExposedPorts(HTTP_PORT);
        // a few attempts: a failed create call on a stale engine connection is retried on a fresh one
        withStartupAttempts(3);
        // bounded heap: the JVM default is a quarter of the host memory, which hurts when ITs run in parallel
        withEnv("JAVA_OPTS_KC_HEAP", "-Xms128m -Xmx768m");
        withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
        withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin-for-tests");
        withEnv("KC_HOSTNAME", PUBLIC_HOST);
        withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/imap-public-realm.json"),
                "/opt/keycloak/data/import/imap-public-realm.json");
        withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/imap-other-realm.json"),
                "/opt/keycloak/data/import/imap-other-realm.json");
        withCommand("start-dev", "--import-realm");
        waitingFor(Wait.forHttp("/realms/" + OTHER_REALM + "/.well-known/openid-configuration").forPort(HTTP_PORT)
                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /** {@code http://<host>:<mapped port>}, how the tests reach the container. */
    public String baseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }

    /** The issuer in the tokens of {@code realm}: the fixed https host, never the mapped address. */
    public static String issuer(String realm) {
        return PUBLIC_HOST + "/realms/" + realm;
    }

    public String tokenEndpoint(String realm) {
        return baseUrl() + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String certsUrl(String realm) {
        return baseUrl() + "/realms/" + realm + "/protocol/openid-connect/certs";
    }
}
