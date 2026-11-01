package de.alexanderlindhorst.imapmcp.transport.http;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.function.Function;

/** Static bearer token check for /mcp (SPEC 2, 5.3); success attributes the request to the fixed tenant. */
public final class BearerTokenFilter implements Filter {

    public static final String ACCOUNT_ATTRIBUTE = "imapmcp.accountContext";
    public static final String CONTEXT_KEY = "imapmcp.accountContext";

    public BearerTokenFilter(char[] expectedToken, AccountContext fixedContext) {
        throw new UnsupportedOperationException();
    }

    static boolean constantTimeEquals(String a, String b) {
        throw new UnsupportedOperationException();
    }

    public static Function<jakarta.servlet.http.HttpServletRequest, McpTransportContext> contextExtractor() {
        throw new UnsupportedOperationException();
    }

    public static Function<McpTransportContext, AccountContext> accountResolver() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        throw new UnsupportedOperationException();
    }
}
