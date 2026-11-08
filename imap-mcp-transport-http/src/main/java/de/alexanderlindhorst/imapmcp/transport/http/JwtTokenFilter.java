package de.alexanderlindhorst.imapmcp.transport.http;

import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
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
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth 2.1 resource-server check for /mcp in {@code http-public} (SPEC 5.3), in the place of the static
 * {@link BearerTokenFilter}. The access token is read only from the {@code Authorization} header; on success the
 * validated {@link JwtPrincipal} is stored as request attribute {@link #PRINCIPAL_ATTRIBUTE} (the tenant is
 * derived from it later, never from anything the client sends). Failures are {@code 401} with a challenge that
 * names the RFC 9728 metadata URL, built from configuration only; when the signing keys cannot be had (D16) the
 * answer is {@code 503} with {@code Retry-After} and no challenge. Tokens are never logged.
 */
public final class JwtTokenFilter implements Filter {

    public static final String PRINCIPAL_ATTRIBUTE = "de.alexanderlindhorst.imapmcp.jwtPrincipal";

    private static final Logger LOG = LoggerFactory.getLogger(JwtTokenFilter.class);
    private static final long WARN_INTERVAL_MS = 60_000;
    private static final String SCHEME = "Bearer ";

    private final JwtValidator validator;
    private final String challengeMissing;
    private final String challengeInvalid;
    private final KeysUnavailableReporter keysUnavailable;

    public JwtTokenFilter(JwtValidator validator, OAuthResourceConfig config) {
        this(validator, config, KeysUnavailableReporter.withoutMetrics());
    }

    /** With the replica-wide counter {@code oauth_keys_unavailable} (SPEC 5.6). */
    public JwtTokenFilter(JwtValidator validator, OAuthResourceConfig config, MetricsRecorder metrics) {
        this(validator, config, new KeysUnavailableReporter(metrics, System::currentTimeMillis));
    }

    /** Test seam: a clock for the rate limit of the key-unavailable warning. */
    JwtTokenFilter(JwtValidator validator, OAuthResourceConfig config, LongSupplier clockMillis) {
        this(validator, config, new KeysUnavailableReporter(MetricsRecorder.NOOP, clockMillis));
    }

    /** With a reporter shared with the linking surface's callback. */
    public JwtTokenFilter(JwtValidator validator, OAuthResourceConfig config, KeysUnavailableReporter reporter) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.challengeMissing = config.challenge(null);
        this.challengeInvalid = config.challenge("invalid_token");
        this.keysUnavailable = Objects.requireNonNull(reporter, "reporter");
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
                // D16: not an invalid token, so no challenge (a client would re-authorize instead of retrying)
                keysUnavailable.report();
                unavailable(response, e.retryAfterSeconds());
            } else {
                LOG.debug("Token rejected: {}", e.category());
                reject(response, challengeInvalid);
            }
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(req, res);
    }

    /** The credentials after {@code Bearer }, or null when the scheme is another or the value is not one token. */
    private static String bearerToken(String header) {
        if (header.length() <= SCHEME.length() || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return null;
        }
        String token = header.substring(SCHEME.length());
        return token.chars().anyMatch(Character::isWhitespace) ? null : token;
    }

    private static void unavailable(HttpServletResponse res, int retryAfterSeconds) throws IOException {
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        res.setHeader("Retry-After", Integer.toString(Math.max(1, retryAfterSeconds)));
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(PrincipalContextFilter.UNAVAILABLE_BODY);
    }

    private static void reject(HttpServletResponse res, String challenge) throws IOException {
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", challenge);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(BearerTokenFilter.UNAUTHORIZED_BODY);
    }
}
