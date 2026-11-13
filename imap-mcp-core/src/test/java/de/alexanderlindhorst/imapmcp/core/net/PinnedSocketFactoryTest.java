package de.alexanderlindhorst.imapmcp.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PinnedSocketFactoryTest {

    private static final String NAME = "imap.example.test";

    private GreenMail greenMail;
    private int port;

    @BeforeEach
    void start() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        port = greenMail.getImaps().getPort();
    }

    @AfterEach
    void stop() {
        greenMail.stop();
    }

    private static InetAddress named(String name, int... octets) throws UnknownHostException {
        byte[] b = new byte[octets.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) octets[i];
        }
        return InetAddress.getByAddress(name, b);
    }

    private PinnedSocketFactory factory(PinnedSocketFactory.Resolver resolver, Predicate<InetAddress> blocked) {
        return new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()), resolver, blocked, p -> p == port);
    }

    /** Loopback is the only address the fixture can serve; the real table (loopback blocked) is tested elsewhere. */
    private static final Predicate<InetAddress> ALLOW_LOOPBACK = a -> !a.isLoopbackAddress();

    @Test
    void connectGoesToTheCheckedAddressWhateverAngusPasses() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        PinnedSocketFactory f = factory(h -> {
            lookups.incrementAndGet();
            return new InetAddress[] {named(NAME, 127, 0, 0, 1)};
        }, ALLOW_LOOPBACK);
        try (Socket s = f.createSocket()) {
            // Angus has already resolved the name (possibly to something else): the address in it must be ignored.
            s.connect(new InetSocketAddress(named(NAME, 203, 0, 113, 1), port), 5000);
            assertThat(s.isConnected()).isTrue();
            assertThat(s.getInetAddress().isLoopbackAddress()).isTrue();
            assertThat(s.getPort()).isEqualTo(port);
        }
        assertThat(lookups).hasValue(1);
    }

    @Test
    void aSecondLookupThatReturnsAnotherAddressIsNeverUsed() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        PinnedSocketFactory f = factory(h -> lookups.incrementAndGet() == 1
                ? new InetAddress[] {named(NAME, 127, 0, 0, 1)}
                : new InetAddress[] {named(NAME, 10, 0, 0, 5)}, ALLOW_LOOPBACK);
        try (Socket s = f.createSocket()) {
            s.connect(InetSocketAddress.createUnresolved(NAME, port), 5000);
            assertThat(s.getInetAddress().isLoopbackAddress()).isTrue();
        }
        assertThat(lookups).as("one lookup per connect: checked and used are the same answer").hasValue(1);
    }

    @Test
    void oneBlockedAddressRefusesTheWholeConnect() throws Exception {
        PinnedSocketFactory f = factory(h -> new InetAddress[] {named(NAME, 127, 0, 0, 1), named(NAME, 10, 0, 0, 5)},
                ALLOW_LOOPBACK);
        try (Socket s = f.createSocket()) {
            assertThatThrownBy(() -> s.connect(InetSocketAddress.createUnresolved(NAME, port), 5000))
                    .isInstanceOf(AddressBlockedException.class);
            assertThat(s.isConnected()).isFalse();
        }
    }

    @Test
    void theTestPortMappingChangesOnlyTheTcpPortAfterThePortCheck() throws Exception {
        PinnedSocketFactory f = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()),
                h -> new InetAddress[] {named(NAME, 127, 0, 0, 1)}, ALLOW_LOOPBACK, p -> p == 993, p -> port);
        try (Socket s = f.createSocket()) {
            s.connect(InetSocketAddress.createUnresolved(NAME, 993), 5000);
            assertThat(s.getInetAddress().isLoopbackAddress()).isTrue();
            assertThat(s.getPort()).as("connected to the mapped port").isEqualTo(port);
        }
        try (Socket s = f.createSocket()) {
            assertThatThrownBy(() -> s.connect(InetSocketAddress.createUnresolved(NAME, port), 5000))
                    .as("the check still sees the requested port, not the mapped one")
                    .isInstanceOf(AddressBlockedException.class);
        }
    }

    @Test
    void aMappedPortStillRefusesABlockedAddressBeforeAnyConnect() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        PinnedSocketFactory f = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()), h -> {
            lookups.incrementAndGet();
            return new InetAddress[] {named(NAME, 127, 0, 0, 1), named(NAME, 10, 0, 0, 5)};
        }, ALLOW_LOOPBACK, p -> p == 993, p -> port);
        try (Socket s = f.createSocket()) {
            assertThatThrownBy(() -> s.connect(InetSocketAddress.createUnresolved(NAME, 993), 5000))
                    .isInstanceOf(AddressBlockedException.class);
            assertThat(s.isConnected()).isFalse();
        }
        assertThat(lookups).hasValue(1);
    }

    @Test
    void theProductionConstructorsKeepTheRequestedPort() throws Exception {
        PinnedSocketFactory f = factory(h -> new InetAddress[] {named(NAME, 127, 0, 0, 1)}, ALLOW_LOOPBACK);
        try (Socket s = f.createSocket()) {
            s.connect(InetSocketAddress.createUnresolved(NAME, port), 5000);
            assertThat(s.getPort()).isEqualTo(port);
        }
    }

    @Test
    void theRealTableBlocksLoopbackFixture() throws Exception {
        PinnedSocketFactory f = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()),
                h -> new InetAddress[] {named(NAME, 127, 0, 0, 1)}, new AddressBlocklist(List.of())::isBlocked,
                p -> p == port);
        try (Socket s = f.createSocket()) {
            assertThatThrownBy(() -> s.connect(InetSocketAddress.createUnresolved(NAME, port), 5000))
                    .isInstanceOf(AddressBlockedException.class);
        }
    }

    @Test
    void emptyAnswerIsUnknownHost() throws Exception {
        PinnedSocketFactory f = factory(h -> new InetAddress[0], ALLOW_LOOPBACK);
        try (Socket s = f.createSocket()) {
            assertThatThrownBy(() -> s.connect(InetSocketAddress.createUnresolved(NAME, port), 5000))
                    .isInstanceOf(UnknownHostException.class);
        }
    }

    @Test
    void hostPolicyAndPortAreCheckedOnEveryConnect() throws Exception {
        PinnedSocketFactory f = new PinnedSocketFactory(SsrfPolicy.of(List.of("allowed.example.test"), false, List.of()),
                h -> new InetAddress[] {named(h, 127, 0, 0, 1)}, ALLOW_LOOPBACK, p -> p == port);
        for (InetSocketAddress target : List.of(
                InetSocketAddress.createUnresolved("other.example.test", port),
                InetSocketAddress.createUnresolved("127.0.0.1", port),
                InetSocketAddress.createUnresolved("allowed.example.test", port + 1),
                InetSocketAddress.createUnresolved("allowed.example.test", 22))) {
            try (Socket s = f.createSocket()) {
                assertThatThrownBy(() -> s.connect(target, 5000)).as(target.toString())
                        .isInstanceOf(AddressBlockedException.class);
            }
        }
        try (Socket s = f.createSocket()) {
            s.connect(InetSocketAddress.createUnresolved("allowed.example.test", port), 5000);
            assertThat(s.isConnected()).isTrue();
        }
    }

    @Test
    void productionConstructorAllowsOnly993And143() throws Exception {
        PinnedSocketFactory f = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()),
                h -> new InetAddress[] {named(h, 8, 8, 8, 8)}, a -> false, p -> p == 993 || p == 143);
        try (Socket s = f.createSocket()) {
            assertThatThrownBy(() -> s.connect(InetSocketAddress.createUnresolved(NAME, 25), 1000))
                    .isInstanceOf(AddressBlockedException.class);
        }
        PinnedSocketFactory prod = new PinnedSocketFactory(SsrfPolicy.of(List.of(), true, List.of()));
        try (Socket s = prod.createSocket()) {
            assertThatThrownBy(() -> s.connect(InetSocketAddress.createUnresolved(NAME, 587), 1000))
                    .isInstanceOf(AddressBlockedException.class);
            assertThatThrownBy(() -> s.connect(InetSocketAddress.createUnresolved("10.1.2.3", 993), 1000))
                    .isInstanceOf(AddressBlockedException.class);
        }
    }

    @Test
    void connectWithoutTimeoutIsPinnedToo() throws Exception {
        PinnedSocketFactory f = factory(h -> new InetAddress[] {named(NAME, 127, 0, 0, 1)}, ALLOW_LOOPBACK);
        try (Socket s = f.createSocket()) {
            s.connect(new InetSocketAddress(named(NAME, 203, 0, 113, 1), port));
            assertThat(s.getInetAddress().isLoopbackAddress()).isTrue();
        }
    }

    @Test
    void directCreateSocketVariantsAreUnsupported() {
        PinnedSocketFactory f = factory(h -> new InetAddress[0], ALLOW_LOOPBACK);
        assertThatThrownBy(() -> f.createSocket(NAME, port)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> f.createSocket(InetAddress.getLoopbackAddress(), port)).isInstanceOf(IOException.class);
    }

    @Test
    void resolveCheckedDoesNotMarkTheThread() throws Exception {
        PinnedSocketFactory f = factory(h -> new InetAddress[] {named(NAME, 10, 0, 0, 5)}, ALLOW_LOOPBACK);
        RefusalMarker.takeRecent();
        assertThatThrownBy(() -> f.resolveChecked(NAME, port)).isInstanceOf(AddressBlockedException.class);
        assertThat(RefusalMarker.takeRecent()).as("link-time checks run on pooled threads").isFalse();
    }

    @Test
    void aRefusedConnectMarksTheThread() throws Exception {
        PinnedSocketFactory f = factory(h -> new InetAddress[] {named(NAME, 10, 0, 0, 5)}, ALLOW_LOOPBACK);
        RefusalMarker.takeRecent();
        try (Socket s = f.createSocket()) {
            assertThatThrownBy(() -> s.connect(InetSocketAddress.createUnresolved(NAME, port), 1000))
                    .isInstanceOf(AddressBlockedException.class);
        }
        assertThat(RefusalMarker.takeRecent()).isTrue();
    }
}
