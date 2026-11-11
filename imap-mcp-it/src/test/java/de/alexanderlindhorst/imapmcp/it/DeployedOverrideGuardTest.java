package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The deployed IT is evidence for the shipped compose.yaml only while the override cannot replace what it tests. */
class DeployedOverrideGuardTest {

    private static final String SHIPPED = shipped();

    private static String shipped() {
        try {
            return Files.readString(Path.of("..", "dist", "docker", "compose.yaml"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> check(String override) {
        return DeployedOverrideGuard.violations(SHIPPED, override);
    }

    @Test
    void theRealOverrideOfTheFixturePasses() {
        assertThat(check(DeployedOverride.render("imap-mcp-it:abc", "squid:digest"))).isEmpty();
    }

    @Test
    void environmentOnAppIsRejected() {
        assertThat(check("""
                services:
                  app:
                    environment:
                      X: y
                """)).containsExactly("services.app.environment");
    }

    @Test
    void changedImageOfAShippedServiceOtherThanAppIsRejected() {
        assertThat(check("""
                services:
                  keycloak:
                    image: evil/keycloak:1
                """)).containsExactly("services.keycloak.image");
    }

    @Test
    void healthcheckTestIsRejectedOnAnyService() {
        assertThat(check("""
                services:
                  postgres:
                    healthcheck:
                      test: ["CMD", "true"]
                  app:
                    healthcheck:
                      interval: 3s
                      test: ["CMD", "true"]
                """)).containsExactlyInAnyOrder("services.postgres.healthcheck.test",
                "services.app.healthcheck.test");
    }

    @Test
    void volumesOnCaddyAreRejected() {
        assertThat(check("""
                services:
                  caddy:
                    volumes:
                      - ./x:/etc/caddy
                """)).containsExactly("services.caddy.volumes");
    }

    @Test
    void readOnlyFalseIsRejected() {
        assertThat(check("""
                services:
                  app:
                    read_only: false
                """)).containsExactly("services.app.read_only");
    }

    @Test
    void capAddOnAppIsRejected() {
        assertThat(check("""
                services:
                  app:
                    cap_add: [SYS_ADMIN]
                """)).containsExactly("services.app.cap_add");
    }

    @Test
    void otherForbiddenKeysAreRejected() {
        assertThat(check("""
                services:
                  app:
                    entrypoint: ["sh"]
                    command: ["x"]
                    mem_limit: 8g
                    cap_drop: []
                """)).containsExactlyInAnyOrder("services.app.entrypoint", "services.app.command",
                "services.app.mem_limit", "services.app.cap_drop");
    }

    @Test
    void aNewServiceOtherThanProxyAndDovecotIsRejected() {
        assertThat(check("""
                services:
                  sidecar:
                    image: x
                """)).containsExactly("services.sidecar");
    }

    @Test
    void proxyAndDovecotMayCarryTheirOwnKeys() {
        assertThat(check("""
                services:
                  proxy:
                    image: squid
                    environment:
                      A: b
                    volumes: [./squid.conf:/etc/squid/squid.conf:ro]
                  dovecot:
                    image: dovecot
                    volumes: [./d:/etc/dovecot]
                """)).isEmpty();
    }

    @Test
    void anAllowedShippedServiceKeyOnAnotherServiceIsRejected() {
        assertThat(check("""
                services:
                  postgres:
                    image: other
                    pull_policy: never
                    build: !reset null
                    ports: !reset []
                  keycloak:
                    ports: !reset []
                """)).containsExactlyInAnyOrder("services.postgres.image", "services.postgres.pull_policy",
                "services.postgres.build", "services.postgres.ports", "services.keycloak.ports");
    }

    @Test
    void portsMayOnlyBeResetNotReplacedOnAppAndCaddy() {
        assertThat(check("""
                services:
                  app:
                    ports: ["0.0.0.0:8080:8080"]
                """)).containsExactly("services.app.ports");
    }

    @Test
    void allowedAppAndHealthcheckTimingKeysPass() {
        assertThat(check("""
                services:
                  app:
                    image: x:1
                    pull_policy: never
                    build: !reset null
                    ports: !reset []
                    healthcheck:
                      interval: 3s
                      start_period: 1s
                      retries: 9
                      timeout: 2s
                  caddy:
                    ports: !reset []
                """)).isEmpty();
    }

    @Test
    void aBuildThatIsNotResetIsRejected() {
        assertThat(check("""
                services:
                  app:
                    build:
                      context: /evil
                """)).containsExactly("services.app.build");
    }

    @Test
    void healthcheckKeysOutsideTheTimingSetAreRejected() {
        assertThat(check("""
                services:
                  app:
                    healthcheck:
                      disable: true
                """)).containsExactly("services.app.healthcheck.disable");
    }

    @Test
    void backendNetworkMayOnlyBecomeInternalWithoutIpv6() {
        assertThat(check("""
                networks:
                  backend:
                    internal: true
                    enable_ipv6: false
                """)).isEmpty();
        assertThat(check("""
                networks:
                  backend:
                    internal: false
                    ipam:
                      config:
                        - subnet: 10.0.0.0/8
                    driver: macvlan
                """)).containsExactlyInAnyOrder("networks.backend.internal", "networks.backend.ipam",
                "networks.backend.driver");
    }

    @Test
    void aNewPlainNetworkIsAllowedButNotOneWithKeys() {
        assertThat(check("""
                networks:
                  outside: {}
                """)).isEmpty();
        assertThat(check("""
                networks:
                  outside:
                    internal: true
                """)).containsExactly("networks.outside");
    }

    @Test
    void shippedServicesMayNotJoinOrChangeNetworks() {
        assertThat(check("""
                services:
                  app:
                    networks: [backend, outside]
                networks:
                  outside: {}
                """)).containsExactly("services.app.networks");
    }

    @Test
    void otherTopLevelSectionsAreRejected() {
        assertThat(check("""
                volumes:
                  postgres-data:
                    driver: local
                secrets:
                  s: {}
                """)).containsExactlyInAnyOrder("volumes", "secrets");
    }

    @Test
    void aliasHijackOnAnAddedServiceIsRejectedInBothNetworkForms() {
        assertThat(check("""
                services:
                  proxy:
                    networks:
                      backend:
                        aliases: [idp.imap-mcp.test]
                  dovecot:
                    networks:
                      backend:
                        aliases: [imap.imap-mcp.test, keycloak]
                """)).containsExactlyInAnyOrder("services.proxy.networks.backend.aliases",
                "services.dovecot.networks.backend.aliases");
    }

    @Test
    void dovecotMayHaveItsOwnAliasAndListFormNetworksPass() {
        assertThat(check("""
                services:
                  dovecot:
                    networks:
                      backend:
                        aliases: [imap.imap-mcp.test]
                  proxy:
                    networks: [backend, outside]
                """)).isEmpty();
    }

    @Test
    void networkModeVolumesFromPrivilegedAndCapAddOnAnAddedServiceAreRejected() {
        assertThat(check("""
                services:
                  proxy:
                    network_mode: service:app
                    volumes_from: [app]
                    privileged: true
                    cap_add: [SYS_ADMIN]
                """)).containsExactlyInAnyOrder("services.proxy.network_mode", "services.proxy.volumes_from",
                "services.proxy.privileged", "services.proxy.cap_add");
    }

    @Test
    void namedVolumesAndNonRelativeBindsOnAnAddedServiceAreRejected() {
        assertThat(check("""
                services:
                  dovecot:
                    volumes:
                      - caddy-data:/data
                      - /etc:/host-etc
                      - ./ok:/ok:ro
                """)).containsExactlyInAnyOrder("services.dovecot.volumes");
    }

    @Test
    void publishedPortsAreForTheProxyOnlyAndOnlyOnLoopback() {
        assertThat(check("""
                services:
                  proxy:
                    ports: ["127.0.0.1::3128"]
                """)).isEmpty();
        assertThat(check("""
                services:
                  proxy:
                    ports: ["3128:3128"]
                  dovecot:
                    ports: ["127.0.0.1::993"]
                """)).containsExactlyInAnyOrder("services.proxy.ports", "services.dovecot.ports");
    }

    @Test
    void theAllowedKeysOfAnAddedServicePass() {
        assertThat(check("""
                services:
                  dovecot:
                    image: d
                    pull_policy: never
                    depends_on: [proxy]
                    healthcheck:
                      test: ["CMD", "true"]
                    sysctls: {net.ipv6.conf.all.disable_ipv6: 1}
                    environment: {A: b}
                    command: ["x"]
                    entrypoint: ["y"]
                    user: "1000"
                """)).isEmpty();
    }
}
