package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

/** Toxiproxy and Dovecot on a shared network: the proxied port reaches Dovecot and a cut connection is observable. */
class ToxiproxySmokeIT {

    private static Network network;
    private static DovecotContainer dovecot;
    private static ToxiproxyFixture toxiproxy;

    @BeforeAll
    static void start() {
        network = Network.newNetwork();
        dovecot = new DovecotContainer().withNetwork(network);
        toxiproxy = new ToxiproxyFixture(network);
        dovecot.start();
        toxiproxy.start();
    }

    @AfterAll
    static void stop() {
        toxiproxy.stop();
        dovecot.stop();
        network.close();
    }

    @Test
    void proxiedStarttlsPortDeliversTheDovecotGreeting() throws Exception {
        ToxiproxyFixture.Proxy proxy = toxiproxy.proxyTo("imap-starttls", DovecotContainer.NETWORK_ALIAS,
                DovecotContainer.STARTTLS_PORT);
        assertThat(ImapProbe.greeting(proxy.host(), proxy.port())).startsWith("* OK").contains("Dovecot");
    }

    @Test
    void disablingTheProxyRefusesOrResetsConnections() throws Exception {
        ToxiproxyFixture.Proxy proxy = toxiproxy.proxyTo("imap-cut", DovecotContainer.NETWORK_ALIAS,
                DovecotContainer.STARTTLS_PORT);
        proxy.disable();
        try {
            assertThat(ImapProbe.tryGreeting(proxy.host(), proxy.port())).isNull();
        } finally {
            proxy.enable();
        }
        assertThat(ImapProbe.tryGreeting(proxy.host(), proxy.port())).startsWith("* OK");
    }
}
