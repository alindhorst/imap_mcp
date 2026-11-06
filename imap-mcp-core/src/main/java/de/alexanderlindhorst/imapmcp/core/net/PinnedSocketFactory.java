package de.alexanderlindhorst.imapmcp.core.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import javax.net.SocketFactory;

/**
 * Socket factory of {@code http-public} (SPEC 5.3), installed per Session as {@code mail.<protocol>.socketFactory}
 * with {@code socketFactory.fallback=false}. Angus creates an unconnected socket with {@link #createSocket()} and
 * connects it to an address it has already resolved itself; {@link PinnedSocket#connect} ignores that address. It
 * checks the host name, resolves it with our resolver, refuses the connect if any answer is blocked and connects to
 * the first checked address. The check therefore runs on every TCP connection, folder connections included, and the
 * address that was checked is the address that is used (no second lookup). TLS is layered on top by Angus with the
 * host name (SNI, {@code ssl.checkserveridentity}), not with the pinned address.
 *
 * <p>Only the first answer is tried (IPv4 before IPv6): a failed connect closes the socket, so there is no fallback
 * to a later address. Nothing here logs a host or an address.
 */
public final class PinnedSocketFactory extends SocketFactory {

    /** Name resolution seam. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    private final SsrfPolicy policy;
    private final Resolver resolver;
    private final Predicate<InetAddress> blocked;
    private final IntPredicate portAllowed;

    public PinnedSocketFactory(SsrfPolicy policy) {
        this(policy, InetAddress::getAllByName, Objects.requireNonNull(policy, "policy")::isBlocked,
                p -> p == SsrfPolicy.IMAPS_PORT || p == SsrfPolicy.IMAP_PORT);
    }

    /** Test seam: own resolver, block predicate and port predicate. Never fed from config. */
    public PinnedSocketFactory(SsrfPolicy policy, Resolver resolver, Predicate<InetAddress> blocked,
            IntPredicate portAllowed) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.blocked = Objects.requireNonNull(blocked, "blocked");
        this.portAllowed = Objects.requireNonNull(portAllowed, "portAllowed");
    }

    /**
     * Host, port and address check for one connect; returns the address to connect to (carrying the normalized
     * name). Also usable at link time.
     */
    public InetSocketAddress resolveChecked(String host, int port) throws IOException {
        String name;
        try {
            name = policy.normalizeHost(host);
        } catch (HostRejectedException e) {
            throw new AddressBlockedException();
        }
        if (!portAllowed.test(port)) {
            throw new AddressBlockedException();
        }
        InetAddress[] answers = resolver.resolve(name);
        if (answers == null || answers.length == 0) {
            throw new UnknownHostException("no address");
        }
        for (InetAddress a : answers) {
            if (blocked.test(a)) {
                throw new AddressBlockedException();
            }
        }
        InetAddress first = Arrays.stream(answers)
                .min(Comparator.comparingInt((InetAddress a) -> a.getAddress().length)).orElseThrow();
        return new InetSocketAddress(InetAddress.getByAddress(name, first.getAddress()), port);
    }

    @Override
    public Socket createSocket() {
        return new PinnedSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        throw unsupported();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        throw unsupported();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        throw unsupported();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        throw unsupported();
    }

    private static IOException unsupported() {
        return new IOException("connected sockets are not supported; use createSocket()");
    }

    private final class PinnedSocket extends Socket {

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress isa)) {
                throw new IllegalArgumentException("unsupported address type");
            }
            // The address Angus resolved is dropped on purpose: only its name is used.
            super.connect(resolveChecked(isa.getHostString(), isa.getPort()), timeout);
        }
    }
}
