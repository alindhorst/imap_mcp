package de.alexanderlindhorst.imapmcp.transport.http;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Static bearer token check for /mcp (SPEC 2, 5.3). Only the SHA-256 of the expected token is kept; the
 * comparison is {@link MessageDigest#isEqual} on two fixed-length digests, so length does not leak. On success
 * the request is attributed to the fixed {@link AccountContext}; nothing from the request selects the tenant.
 * Neither token is ever logged.
 */
public final class BearerTokenFilter implements Filter {

    public static final String ACCOUNT_ATTRIBUTE = "de.alexanderlindhorst.imapmcp.accountContext";
    public static final String CONTEXT_KEY = "de.alexanderlindhorst.imapmcp.accountContext";

    static final String UNAUTHORIZED_BODY =
            "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,\"message\":\"Unauthorized\"},\"id\":null}";
    private static final String CHALLENGE_MISSING = "Bearer realm=\"imap-mcp\"";
    private static final String CHALLENGE_INVALID = "Bearer realm=\"imap-mcp\", error=\"invalid_token\"";
    private static final String SCHEME = "Bearer ";

    private final byte[] expectedDigest;
    private final AccountContext fixedContext;

    /** The array is not retained; the caller may wipe it afterwards. */
    public BearerTokenFilter(char[] expectedToken, AccountContext fixedContext) {
        Objects.requireNonNull(expectedToken, "expectedToken");
        if (expectedToken.length == 0) {
            throw new IllegalArgumentException("expected token must not be empty");
        }
        this.fixedContext = Objects.requireNonNull(fixedContext, "fixedContext");
        this.expectedDigest = sha256(new String(expectedToken));
    }

    /** Hashes both sides to fixed length, then compares in constant time. */
    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(sha256(a), sha256(b));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Copies the account context set by this filter into the SDK's transport context. */
    public static Function<HttpServletRequest, McpTransportContext> contextExtractor() {
        return req -> {
            Object ctx = req.getAttribute(ACCOUNT_ATTRIBUTE);
            return ctx instanceof AccountContext
                    ? McpTransportContext.create(Map.of(CONTEXT_KEY, ctx)) : McpTransportContext.EMPTY;
        };
    }

    /** For {@code McpToolAdapter}; fails (-> INTERNAL_ERROR) when no authenticated context is present. */
    public static Function<McpTransportContext, AccountContext> accountResolver() {
        return ctx -> {
            Object v = ctx == null ? null : ctx.get(CONTEXT_KEY);
            if (v instanceof AccountContext ac) {
                return ac;
            }
            throw new IllegalStateException("no authenticated account context");
        };
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            reject((HttpServletResponse) res, CHALLENGE_MISSING);
            return;
        }
        if (headers.size() > 1 || !authorized(headers.get(0))) {
            reject((HttpServletResponse) res, CHALLENGE_INVALID);
            return;
        }
        request.setAttribute(ACCOUNT_ATTRIBUTE, fixedContext);
        chain.doFilter(req, res);
    }

    private boolean authorized(String header) {
        if (header.length() <= SCHEME.length() || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return false;
        }
        String token = header.substring(SCHEME.length());
        return MessageDigest.isEqual(sha256(token), expectedDigest);
    }

    private static void reject(HttpServletResponse res, String challenge) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", challenge);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(UNAUTHORIZED_BODY);
    }
}
