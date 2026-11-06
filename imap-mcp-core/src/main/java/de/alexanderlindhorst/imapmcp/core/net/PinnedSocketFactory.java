package de.alexanderlindhorst.imapmcp.core.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import javax.net.SocketFactory;

/** Resolves, checks and pins every TCP connect (SPEC 5.3). */
public final class PinnedSocketFactory extends SocketFactory {

    /** Name resolution seam. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    public PinnedSocketFactory(SsrfPolicy policy) {
        throw new UnsupportedOperationException();
    }

    /** Test seam: own resolver, block predicate and port predicate. Never fed from config. */
    public PinnedSocketFactory(SsrfPolicy policy, Resolver resolver, Predicate<InetAddress> blocked,
            IntPredicate portAllowed) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(String host, int port) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
        throw new UnsupportedOperationException();
    }
}
