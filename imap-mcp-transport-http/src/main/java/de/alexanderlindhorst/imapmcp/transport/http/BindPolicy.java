package de.alexanderlindhorst.imapmcp.transport.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Loopback-only bind policy of http-local (SPEC 5.3). Pure function, no logging. */
public final class BindPolicy {

    private BindPolicy() {
    }

    /**
     * Resolves the bind host and refuses non-loopback addresses unless explicitly allowed.
     *
     * @return the resolved address; {@code isLoopbackAddress()} tells the caller whether to WARN
     * @throws IllegalStateException with an actionable message when the bind is refused or unresolvable
     */
    public static InetAddress check(String bind, boolean allowNonLoopback) {
        InetAddress addr;
        try {
            addr = InetAddress.getByName(bind);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("http.bind '" + bind + "' cannot be resolved", e);
        }
        if (!addr.isLoopbackAddress() && !allowNonLoopback) {
            throw new IllegalStateException("http.bind '" + bind + "' is not a loopback address; refusing to start. "
                    + "Bind to 127.0.0.1 or set http.allowNonLoopbackBind=true (e.g. in the Docker image, "
                    + "with the host-side publish limited to 127.0.0.1).");
        }
        return addr;
    }
}
