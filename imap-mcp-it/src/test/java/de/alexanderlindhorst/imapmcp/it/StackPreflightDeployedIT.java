package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Network;
import de.alexanderlindhorst.imapmcp.core.net.AddressBlocklist;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What must hold before any flow runs on the deployed stack (#427): an isolated internal network inside the test
 * subnet that the production SSRF policy admits, a copy that really is the project directory, a forward proxy that
 * refuses everything but CONNECT to *.imap-mcp.test:8443, and no secret in any captured output.
 */
class StackPreflightDeployedIT {

    private static DeployedStack stack;

    @BeforeAll
    static void start() {
        stack = DeployedStack.instance();
    }

    @Test
    void backendNetworkIsInternalWithoutIpv6() {
        Network net = stack.backendNetwork();
        assertThat(net.getInternal()).isTrue();
        assertThat(net.getEnableIPv6()).isFalse();
    }

    @Test
    void everyBackendContainerIsInsideTheSubnetAndNotBlockedByTheProductionBlocklist() throws Exception {
        AddressBlocklist blocklist = new AddressBlocklist(List.of());
        List<String> ips = stack.backendContainerIps();
        assertThat(ips).hasSizeGreaterThanOrEqualTo(5);
        de.alexanderlindhorst.imapmcp.core.net.Cidr subnet =
                de.alexanderlindhorst.imapmcp.core.net.Cidr.parse(stack.backendSubnet());
        for (String ip : ips) {
            InetAddress address = InetAddress.getByName(ip);
            assertThat(subnet.contains(address)).as("inside subnet: " + ip).isTrue();
            assertThat(blocklist.isBlocked(address)).as("not blocked: " + ip).isFalse();
        }
    }

    @Test
    void theProjectDirectoryIsTheCopyAndTheSourceTreeStaysUntouched() throws Exception {
        Map<String, String> env = DeployedSecretScan.secrets(stack.runDir().resolve(".env"));
        // the container got the value from the copy's .env (compared as a boolean, never printed)
        assertThat(stack.containerEnv("app", "HANDLE_KEY_1").equals(env.get("HANDLE_KEY_1"))).isTrue();
        assertThat(stack.runDir()).isNotEqualTo(stack.sourceDir());
        assertThat(Files.exists(stack.sourceDir().resolve(".env"))).isFalse();
        assertThat(DeployedRunCopy.snapshot(stack.sourceDir())).isEqualTo(stack.sourceSnapshotAtStart());
    }

    @Test
    void theProxyAllowsOnlyConnectToTheTestDomainOnPort8443() throws Exception {
        assertThat(stack.proxyConnect("mail.imap-mcp.test:8443")).contains(" 200");
        assertThat(stack.proxyConnect("idp.imap-mcp.test:8443")).contains(" 200");
        assertThat(stack.proxyConnect("example.com:8443")).contains(" 403");
        assertThat(stack.proxyConnect("mail.imap-mcp.test:22")).contains(" 403");
        assertThat(stack.proxyConnect("192.31.196.2:8443")).contains(" 403");
    }

    @Test
    void theBuildContextCarriesOnlyTheDockerfileAndTheJar() {
        assertThat(stack.buildContextEntries()).containsExactlyInAnyOrder("Dockerfile", "jar/imap-mcp.jar");
    }

    @Test
    void noGeneratedSecretAppearsInAnyCapturedOutput() throws Exception {
        stack.saveLogs();
        Map<String, String> canaries = new java.util.TreeMap<>(
                DeployedSecretScan.secrets(stack.runDir().resolve(".env")));
        assertThat(canaries).isNotEmpty();
        int i = 0;
        for (String line : DeployedSecretScan.pemLines(stack.runDir().resolve("local-ca/ca.key"))) {
            canaries.put("CA_KEY_LINE_" + i++, line);
        }
        StringBuilder scanned = new StringBuilder(stack.capturedOutput());
        try (var logs = Files.list(stack.runDir().resolve("logs"))) {
            for (var f : (Iterable<java.nio.file.Path>) logs::iterator) {
                scanned.append(Files.readString(f));
            }
        }
        for (String file : List.of("it-compose.override.yaml", "squid.conf")) {
            scanned.append(Files.readString(stack.runDir().resolve(file)));
        }
        assertThat(DeployedSecretScan.leaks(scanned.toString(), canaries)).isEmpty();
    }
}
