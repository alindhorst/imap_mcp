package de.alexanderlindhorst.imapmcp.transport.http;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth 2.1 resource-server check for /mcp in {@code http-public} (SPEC 5.3), in the place of the static
 * {@link BearerTokenFilter}. The access token is read only from the {@code Authorization} header; on success the
 * validated {@link JwtPrincipal} is stored as request attribute {@link #PRINCIPAL_ATTRIBUTE} (the tenant is
 * derived from it later, never from anything the client sends). Failures are {@code 401} with a challenge that
 * names the RFC 9728 metadata URL, built from configuration only. Tokens are never logged.
 */
public final class JwtTokenFilter implements Filter {

    public static final String PRINCIPAL_ATTRIBUTE = "de.alexanderlindhorst.imapmcp.jwtPrincipal";

    private static final Logger LOG = LoggerFactory.getLogger(JwtTokenFilter.class);
    private static final long WARN_INTERVAL_MS = 60_000;
    private static final String SCHEME = "Bearer ";

    private final JwtValidator validator;
    private final String challengeMissing;
    private final String challengeInvalid;
    private final LongSupplier clockMillis;
    private final AtomicLong lastKeysWarn = new AtomicLong(Long.MIN_VALUE);

    public JwtTokenFilter(JwtValidator validator, OAuthResourceConfig config) {
        this(validator, config, System::currentTimeMillis);
    }

    /** Test seam: a clock for the rate limit of the key-unavailable warning. */
    JwtTokenFilter(JwtValidator validator, OAuthResourceConfig config, LongSupplier clockMillis) {
        this.validator = Objects.requireNonNull(validator, "validator");
        String metadata = ", resource_metadata=\"" + config.metadataUrl() + "\"";
        this.challengeMissing = "Bearer realm=\"imap-mcp\"" + metadata;
        this.challengeInvalid = "Bearer realm=\"imap-mcp\", error=\"invalid_token\"" + metadata;
        this.clockMillis = clockMillis;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) {
            reject(response, challengeMissing);
            return;
        }
        String token = headers.size() == 1 ? bearerToken(headers.get(0)) : null;
        if (token == null) {
            reject(response, challengeInvalid);
            return;
        }
        JwtPrincipal principal;
        try {
            principal = validator.validate(token);
        } catch (InvalidTokenException e) {
            if (InvalidTokenException.KEYS_UNAVAILABLE.equals(e.category())) {
                warnKeysUnavailable();
            } else {
                LOG.debug("Token rejected: {}", e.category());
            }
            reject(response, challengeInvalid);
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(req, res);
    }

    /** At most one warning per minute: an unreachable identity provider fails every request. */
    private void warnKeysUnavailable() {
        long now = clockMillis.getAsLong();
        long last = lastKeysWarn.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastKeysWarn.compareAndSet(last, now)) {
            LOG.warn("Token not validated: {}", InvalidTokenException.KEYS_UNAVAILABLE);
        }
    }

    /** The credentials after {@code Bearer }, or null when the scheme is another or the value is not one token. */
    private static String bearerToken(String header) {
        if (header.length() <= SCHEME.length() || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return null;
        }
        String token = header.substring(SCHEME.length());
        return token.chars().anyMatch(Character::isWhitespace) ? null : token;
    }

    private static void reject(HttpServletResponse res, String challenge) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", challenge);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(BearerTokenFilter.UNAUTHORIZED_BODY);
    }
}
