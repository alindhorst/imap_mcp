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

/**
 * /mcp accepts POST only (stateless), or GET, POST and DELETE (stateful Streamable HTTP); every other method gets
 * 405 with the {@code Allow} header and no CORS headers. It runs before authentication, so a 405 leaks nothing.
 */
final class AllowedMethodsFilter implements Filter {

    static final AllowedMethodsFilter POST_ONLY = new AllowedMethodsFilter(List.of("POST"));
    static final AllowedMethodsFilter STREAMABLE = new AllowedMethodsFilter(List.of("GET", "POST", "DELETE"));

    private final List<String> allowed;
    private final String allowHeader;

    private AllowedMethodsFilter(List<String> allowed) {
        this.allowed = allowed;
        this.allowHeader = String.join(", ", allowed);
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        if (allowed.contains(request.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        HttpServletResponse response = (HttpServletResponse) res;
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        response.setHeader("Allow", allowHeader);
    }
}
