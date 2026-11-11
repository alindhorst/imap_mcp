package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeployedSquidConfTest {

    private final List<String> lines = DeployedSquidConf.render().lines().map(String::strip).toList();

    @Test
    void onlyConnectToTheTestDomainOnPort8443IsAllowed() {
        assertThat(lines).contains("acl allowed_ports port 8443", "acl allowed_hosts dstdomain .imap-mcp.test",
                "http_access allow CONNECT allowed_hosts allowed_ports", "http_access deny all");
        assertThat(lines.stream().filter(l -> l.startsWith("http_access allow"))).hasSize(1);
    }

    @Test
    void nothingIsCached() {
        assertThat(lines).contains("cache deny all");
    }
}
