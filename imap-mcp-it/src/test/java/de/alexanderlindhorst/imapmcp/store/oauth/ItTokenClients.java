package de.alexanderlindhorst.imapmcp.store.oauth;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import javax.net.ssl.SSLContext;

/**
 * IT-side helper in the package of {@link TokenEndpointClient}, so that the client's package-private test seam
 * (block predicate, resolver, TLS context, the one allowed port) stays unwidened (#498). Test code only; the
 * production client is built with {@code new TokenEndpointClient(policy)}.
 */
public final class ItTokenClients {

    private ItTokenClients() {
    }

    /** A real {@link TokenEndpointClient} that resolves every host to loopback, trusts only {@code ssl}'s CA and talks to {@code port}. */
    public static TokenEndpoint toLoopback(SSLContext ssl, int port) {
        return new TokenEndpointClient(a -> false, host -> new InetAddress[] {InetAddress.getLoopbackAddress()}, ssl,
                port, Duration.ofSeconds(5), Duration.ofSeconds(8), Clock.systemUTC());
    }
}
