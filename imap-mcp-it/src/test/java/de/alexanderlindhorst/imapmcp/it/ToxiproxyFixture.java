package de.alexanderlindhorst.imapmcp.it;

import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import org.testcontainers.containers.Network;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Toxiproxy on a shared {@link Network} (so it reaches the other containers by network alias). Each
 * {@link #proxyTo} opens one of a small pool of pre-exposed listener ports; tests then point the server at
 * {@link Proxy#host()}:{@link Proxy#port()} and add toxics through {@link Proxy#toxics()}.
 */
public final class ToxiproxyFixture {

    private static final int FIRST_PROXY_PORT = 8666;
    private static final int PROXY_PORTS = 16;

    /** A proxy as seen from the test JVM. */
    public static final class Proxy {
        private final String host;
        private final int port;
        private final eu.rekawek.toxiproxy.Proxy proxy;

        Proxy(String host, int port, eu.rekawek.toxiproxy.Proxy proxy) {
            this.host = host;
            this.port = port;
            this.proxy = proxy;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        /** The Toxiproxy proxy for adding latency, timeouts, reset_peer and so on. */
        public eu.rekawek.toxiproxy.Proxy toxics() {
            return proxy;
        }

        public void disable() throws IOException {
            proxy.disable();
        }

        public void enable() throws IOException {
            proxy.enable();
        }
    }

    private final ToxiproxyContainer container;
    private ToxiproxyClient client;
    private int nextPort = FIRST_PROXY_PORT;

    public ToxiproxyFixture(Network network) {
        container = new ToxiproxyContainer(DockerImageName.parse(ItImages.TOXIPROXY)).withNetwork(network)
                .withNetworkAliases("toxiproxy");
        for (int i = 0; i < PROXY_PORTS; i++) {
            container.addExposedPort(FIRST_PROXY_PORT + i);
        }
    }

    public void start() {
        container.start();
        client = new ToxiproxyClient(container.getHost(), container.getControlPort());
    }

    public void stop() {
        container.stop();
    }

    /** @param upstreamHost network alias of the upstream container; the upstream port is its internal port. */
    public Proxy proxyTo(String name, String upstreamHost, int upstreamPort) throws IOException {
        if (nextPort >= FIRST_PROXY_PORT + PROXY_PORTS) {
            throw new IllegalStateException("no free Toxiproxy listener port left");
        }
        int listen = nextPort++;
        eu.rekawek.toxiproxy.Proxy proxy = client.createProxy(name, "0.0.0.0:" + listen, upstreamHost + ":" + upstreamPort);
        return new Proxy(container.getHost(), container.getMappedPort(listen), proxy);
    }
}
