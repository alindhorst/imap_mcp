package de.alexanderlindhorst.imapmcp.it;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The one Keycloak of the integration tests (development mode, dynamic port, image pinned by digest in
 * {@link ItImages}). Start it together with other containers with
 * {@code Startables.deepStart(keycloak, postgres).join()}: Keycloak takes long to start, and a container created
 * after that wait used to hit a Docker socket connection that the engine had closed meanwhile. It retries the start
 * ({@code withStartupAttempts(3)}) and runs with a bounded heap, which matters when ITs run in parallel.
 *
 * <p>Three test realms are imported (see {@code keycloak/README.md}):
 * <ul>
 *   <li>{@value #REALM}, the linking surface (C1): confidential client {@value #CLIENT_ID} with PKCE S256, users alice
 *       and carol with the role {@code imap-user} and bob without. Its issuer is derived from the request host, so
 *       {@link #issuer()} is the issuer for clients that use {@link #baseUrl()}.
 *   <li>{@value #PUBLIC_REALM} and {@value #OTHER_REALM}, the resource server of {@code http-public} (B6): public
 *       test clients that issue real access tokens by the password grant (audience mapper for {@value #RESOURCE},
 *       optional scopes {@code imap-mcp:read} and {@code imap-mcp:write}). Their realm attribute {@code frontendUrl}
 *       is {@value #PUBLIC_HOST}, so the tokens carry the https issuer the server requires
 *       ({@link #publicIssuer(String)}), while the container is reached over plain http on its mapped port.
 * </ul>
 */
public final class KeycloakFixture extends GenericContainer<KeycloakFixture> {

    // linking surface (C1)
    public static final String REALM = "imap";
    public static final String CLIENT_ID = "imap-mcp-web";
    public static final String CLIENT_SECRET = "web-secret-for-tests";
    public static final String ALICE = "alice";
    public static final String ALICE_PASSWORD = "alice-pw";
    public static final String CAROL = "carol";
    public static final String CAROL_PASSWORD = "carol-pw";
    public static final String BOB = "bob";
    public static final String BOB_PASSWORD = "bob-pw";

    // resource server (B6)
    public static final String PUBLIC_REALM = "imap-public";
    public static final String OTHER_REALM = "imap-other";
    public static final String PUBLIC_HOST = "https://idp.example.com";
    /** The resource every {@code connector} client's tokens are issued for. */
    public static final String RESOURCE = "https://mail.example.com/mcp";
    public static final String WRONG_AUDIENCE = "https://other.example.com/mcp";

    private static final int HTTP_PORT = 8080;

    public KeycloakFixture() {
        super(DockerImageName.parse(ItImages.KEYCLOAK).asCompatibleSubstituteFor("quay.io/keycloak/keycloak"));
        withExposedPorts(HTTP_PORT);
        // a few attempts: a failed create call on a stale engine connection is retried on a fresh one
        withStartupAttempts(3);
        // bounded heap: the JVM default is a quarter of the host memory, which hurts when ITs run in parallel
        withEnv("JAVA_OPTS_KC_HEAP", "-Xms128m -Xmx768m");
        withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
        withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin-for-tests");
        for (String realm : new String[] {"imap-realm", "imap-public-realm", "imap-other-realm"}) {
            withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/" + realm + ".json"),
                    "/opt/keycloak/data/import/" + realm + ".json");
        }
        withCommand("start-dev", "--import-realm");
        // the last realm of the import to be up means all of them are
        waitingFor(Wait.forHttp("/realms/" + OTHER_REALM + "/.well-known/openid-configuration").forPort(HTTP_PORT)
                .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /** {@code http://<host>:<mapped port>}, how the tests reach the container. */
    public String baseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }

    /** Issuer of the linking-surface realm, derived from the request host. */
    public String issuer() {
        return baseUrl() + "/realms/" + REALM;
    }

    public String certsUrl() {
        return issuer() + "/protocol/openid-connect/certs";
    }

    /** The issuer in the tokens of a resource-server realm: the fixed https host, never the mapped address. */
    public static String publicIssuer(String realm) {
        return PUBLIC_HOST + "/realms/" + realm;
    }

    public String tokenEndpoint(String realm) {
        return baseUrl() + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String certsUrl(String realm) {
        return baseUrl() + "/realms/" + realm + "/protocol/openid-connect/certs";
    }
}
