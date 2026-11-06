package de.alexanderlindhorst.imapmcp.transport.http;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

/** Stub. */
public final class PrincipalContextFilter implements Filter {

    public static final String READ_SCOPE = "imap-mcp:read";
    public static final String WRITE_SCOPE = "imap-mcp:write";

    public PrincipalContextFilter(PrincipalTenants tenants) {
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(req, res);
    }
}
