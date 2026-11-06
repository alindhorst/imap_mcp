package de.alexanderlindhorst.imapmcp.transport.http;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

public final class JwtTokenFilter implements Filter {

    public static final String PRINCIPAL_ATTRIBUTE = "de.alexanderlindhorst.imapmcp.jwtPrincipal";

    public JwtTokenFilter(JwtValidator validator, OAuthResourceConfig config) {
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        throw new UnsupportedOperationException();
    }
}
