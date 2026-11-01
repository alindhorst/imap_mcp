package de.alexanderlindhorst.imapmcp.transport.http;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DNS-rebinding protection, part 2 (SPEC 5.3): an absent Origin passes (CLI clients send none); a present one
 * must equal (case-insensitively) an {@code http.allowedOrigins} entry such as {@code http://app.example:3000}.
 * The default list is empty, so any Origin, including the literal {@code null}, is rejected unless configured.
 * Rejection: 403 with a fixed body, reason only at DEBUG and only the host name. No CORS headers are ever set.
 */
public final class OriginFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(OriginFilter.class);

    private final Set<String> allowed;

    public OriginFilter(List<String> allowedOrigins) {
        this.allowed = allowedOrigins.stream().map(o -> o.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        String origin = ((HttpServletRequest) req).getHeader("Origin");
        if (origin == null || allowed.contains(origin.trim().toLowerCase(Locale.ROOT))) {
            chain.doFilter(req, res);
            return;
        }
        LOG.debug("Rejected request: Origin not allowed (host={})", hostOf(origin));
        Forbidden.send((HttpServletResponse) res);
    }

    private static String hostOf(String origin) {
        try {
            String host = URI.create(origin.trim()).getHost();
            return host == null ? "<none>" : host;
        } catch (IllegalArgumentException e) {
            return "<malformed>";
        }
    }
}
