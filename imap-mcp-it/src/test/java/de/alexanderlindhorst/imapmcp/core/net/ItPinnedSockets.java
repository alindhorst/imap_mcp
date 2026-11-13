package de.alexanderlindhorst.imapmcp.core.net;

import java.net.InetAddress;
import java.util.function.IntUnaryOperator;

/**
 * IT-side helper in the package of {@link PinnedSocketFactory}, so that its package-private connect-port seam stays
 * unwidened (#500), like {@code ItTokenClients}. Test code only.
 */
public final class ItPinnedSockets {

    private ItPinnedSockets() {
    }

    /**
     * A pinned factory that passes every check on the configured port (any port allowed, no address blocked) and
     * resolves through {@code resolver}, but connects to {@code connectPort}'s answer: the configured 993 reaches a
     * fixture on a dynamic port.
     */
    public static PinnedSocketFactory remapped(SsrfPolicy policy, PinnedSocketFactory.Resolver resolver,
            IntUnaryOperator connectPort) {
        return new PinnedSocketFactory(policy, resolver, (InetAddress a) -> false, p -> true, connectPort);
    }
}
