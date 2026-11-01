package de.alexanderlindhorst.imapmcp.transport.http;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DNS-rebinding protection, part 1 (SPEC 5.3): the Host header must match an {@code http.allowedHosts} entry.
 * Entries are {@code host:port} or {@code host:*} (any port; Docker may publish another host port); host names
 * compare case-insensitively; IPv6 literals are written in brackets. A Host header without a port
 * ({@code localhost}) is accepted by a {@code host:*} entry (clients omit default ports) but not by an entry
 * with an exact port. A missing Host header is rejected (Jetty already answers 400 for HTTP/1.1 without Host;
 * a Host-less HTTP/1.0 request reaches this filter). Rejection: 403 with a fixed body, reason only at DEBUG
 * and only the host name.
 */
public final class HostFilter implements Filter {

    public static final List<String> DEFAULT_ALLOWED_HOSTS = List.of("localhost:*", "127.0.0.1:*", "[::1]:*");

    private static final Logger LOG = LoggerFactory.getLogger(HostFilter.class);

    private final List<HostPort> allowed;

    public HostFilter(List<String> allowedHosts) {
        this.allowed = allowedHosts.stream().map(HostPort::parse).map(hp -> {
            if (hp == null) {
                throw new IllegalArgumentException("invalid http.allowedHosts entry");
            }
            return hp;
        }).toList();
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        String header = ((HttpServletRequest) req).getHeader("Host");
        HostPort hp = header == null ? null : HostPort.parse(header);
        if (hp != null && allowed.stream().anyMatch(a -> a.admits(hp))) {
            chain.doFilter(req, res);
            return;
        }
        LOG.debug("Rejected request: Host not allowed (host={})", hp == null ? "<missing or malformed>" : hp.host());
        Forbidden.send((HttpServletResponse) res);
    }

    /** Lower-cased host (brackets kept for IPv6) and port: null = absent, -1 = wildcard (patterns only). */
    private record HostPort(String host, Integer port) {

        static HostPort parse(String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            String host;
            String portPart = null;
            if (v.startsWith("[")) {
                int end = v.indexOf(']');
                if (end < 0) {
                    return null;
                }
                host = v.substring(0, end + 1);
                String rest = v.substring(end + 1);
                if (!rest.isEmpty()) {
                    if (!rest.startsWith(":")) {
                        return null;
                    }
                    portPart = rest.substring(1);
                }
            } else {
                int colon = v.indexOf(':');
                if (colon >= 0) {
                    host = v.substring(0, colon);
                    portPart = v.substring(colon + 1);
                } else {
                    host = v;
                }
            }
            if (host.isEmpty() || host.contains("/") || host.contains("@") || host.contains(" ")) {
                return null;
            }
            if (portPart == null) {
                return new HostPort(host, null);
            }
            if (portPart.equals("*")) {
                return new HostPort(host, -1);
            }
            if (portPart.isEmpty() || portPart.length() > 5 || !portPart.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return null;
            }
            int p = Integer.parseInt(portPart);
            return p > 65535 ? null : new HostPort(host, p);
        }

        /** {@code this} is the configured pattern, {@code actual} the parsed request header. */
        boolean admits(HostPort actual) {
            if (!host.equals(actual.host)) {
                return false;
            }
            if (port == null) {
                return actual.port == null;
            }
            return port == -1 || port.equals(actual.port);
        }
    }
}
