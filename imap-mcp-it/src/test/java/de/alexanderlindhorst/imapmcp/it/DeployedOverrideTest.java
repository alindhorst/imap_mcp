package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeployedOverrideTest {

    private final String yaml = DeployedOverride.render("imap-mcp-it:abc", "squid:digest", "dovecot-it:xyz");

    @Test
    void backendNetworkIsInternalWithoutIpv6AndWithoutIpamOverride() {
        assertThat(yaml).contains("backend:").contains("internal: true").contains("enable_ipv6: false");
        assertThat(yaml).doesNotContain("ipam");
    }

    @Test
    void appUsesTheBuiltImageWithoutBuildOrPull() {
        assertThat(yaml).contains("image: imap-mcp-it:abc").contains("pull_policy: never")
                .contains("build: !reset null");
    }

    @Test
    void publishedPortsOfAppAndCaddyAreDropped() {
        assertThat(yaml.split("ports: !reset \\[\\]", -1)).hasSize(3);
    }

    @Test
    void proxyIsTheOnlyPublishedPortAndSitsOnTwoNetworks() {
        assertThat(yaml).contains("127.0.0.1::3128").contains("image: squid:digest");
        assertThat(yaml).contains("outside:");
    }

    @Test
    void shippedServicesGetNoEnvironmentVolumesCommandEntrypointOrHealthcheckTest() {
        String shipped = yaml.substring(0, yaml.indexOf("  proxy:"));
        for (String forbidden : new String[] {"environment", "volumes:", "command", "entrypoint", "read_only",
                "cap_add", "cap_drop", "mem_limit", "pids_limit", "test:"}) {
            assertThat(shipped).as(forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    void healthcheckTimingIsShortenedForTheSlowStarters() {
        assertThat(yaml).contains("interval: 3s");
    }

    @Test
    void dovecotIsAddedOnTheBackendNetworkOnlyWithItsAliasAndPort993Sysctl() {
        String dovecot = yaml.substring(yaml.indexOf("  dovecot:"), yaml.indexOf("networks:\n  backend:"));
        assertThat(dovecot).contains("image: dovecot-it:xyz").contains("pull_policy: never")
                .contains("net.ipv4.ip_unprivileged_port_start=0").contains("imap.imap-mcp.test");
        assertThat(dovecot).doesNotContain("outside").doesNotContain("ports:").doesNotContain("volumes:")
                .doesNotContain("environment");
    }
}
