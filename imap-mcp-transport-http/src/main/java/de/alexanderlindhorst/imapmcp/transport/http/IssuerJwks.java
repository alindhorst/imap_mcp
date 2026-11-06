package de.alexanderlindhorst.imapmcp.transport.http;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signing keys of the issuer (SPEC 5.3): {@code jwks_uri} from the OpenID Provider metadata at
 * {@code <issuer>/.well-known/openid-configuration}, whose {@code issuer} member must equal the configured
 * issuer. The issuer and the {@code jwks_uri} must be {@code https}. Discovery runs on the first token, not at
 * startup, one thread at a time; a failure is remembered for 10 s (negative cache) so that an unreachable
 * identity provider is not hit by every request. The key set is cached (Nimbus: TTL, refresh ahead,
 * rate-limited refresh on an unknown {@code kid}). Neither fetch follows redirects, and the discovery response
 * is size-capped while it is read. The URLs come from configuration and the issuer's own metadata, never from a
 * request.
 */
public final class IssuerJwks implements JWKSource<SecurityContext> {

    private static final Logger LOG = LoggerFactory.getLogger(IssuerJwks.class);
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_BYTES = 256 * 1024;
    static final long NEGATIVE_CACHE_MS = 10_000;

    private final String issuer;
    private final Duration cacheTtl;
    private final Duration minRefreshInterval;
    private final URI discoveryUri;
    private final boolean allowHttp;
    private final LongSupplier clockMillis;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build();
    private volatile JWKSource<SecurityContext> delegate;
    private long failedAt;
    private boolean failed;

    private IssuerJwks(String issuer, Duration cacheTtl, Duration minRefreshInterval, URI discoveryUri,
            boolean allowHttp, LongSupplier clockMillis) {
        checkIssuer(issuer, allowHttp);
        this.issuer = issuer;
        this.cacheTtl = cacheTtl;
        this.minRefreshInterval = minRefreshInterval;
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        this.discoveryUri = discoveryUri != null ? discoveryUri : URI.create(base + "/.well-known/openid-configuration");
        this.allowHttp = allowHttp;
        this.clockMillis = clockMillis;
    }

    public static IssuerJwks create(String issuer) {
        return create(issuer, Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    public static IssuerJwks create(String issuer, Duration cacheTtl, Duration minRefreshInterval) {
        return new IssuerJwks(issuer, cacheTtl, minRefreshInterval, null, false, System::currentTimeMillis);
    }

    /** Test seam: loopback http, a discovery URL override and a clock. */
    static IssuerJwks createForTest(String issuer, Duration cacheTtl, Duration minRefreshInterval,
            URI discoveryUrl, boolean allowHttp, LongSupplier clockMillis) {
        return new IssuerJwks(issuer, cacheTtl, minRefreshInterval, discoveryUrl, allowHttp, clockMillis);
    }

    private static void checkIssuer(String issuer, boolean allowHttp) {
        URI u;
        try {
            u = new URI(issuer == null ? "" : issuer.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("oauth.issuer is not a valid URI");
        }
        boolean scheme = "https".equals(u.getScheme()) || (allowHttp && "http".equals(u.getScheme()));
        if (!scheme || u.getHost() == null || u.getRawUserInfo() != null || u.getRawQuery() != null
                || u.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "oauth.issuer must be an https URI with a host and without user info, query or fragment");
        }
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        JWKSource<SecurityContext> d = delegate;
        if (d == null) {
            d = resolve();
        }
        return d.get(selector, context);
    }

    private synchronized JWKSource<SecurityContext> resolve() throws KeySourceException {
        if (delegate != null) {
            return delegate;
        }
        if (failed && clockMillis.getAsLong() - failedAt < NEGATIVE_CACHE_MS) {
            throw new KeySourceException("issuer metadata unavailable");
        }
        try {
            URI jwksUri = discover();
            DefaultResourceRetriever retriever = new NoRedirectRetriever();
            delegate = JWKSourceBuilder.<SecurityContext>create(jwksUri.toURL(), retriever)
                    .cache(cacheTtl.toMillis(), TIMEOUT_MS).rateLimited(minRefreshInterval.toMillis())
                    .retrying(true).build();
            failed = false;
            return delegate;
        } catch (Failure e) {
            fail(e.reason, e.status);
        } catch (IOException | RuntimeException | ParseException e) {
            fail("io_or_format", -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeySourceException("interrupted");
        }
        throw new KeySourceException("issuer metadata unavailable");
    }

    private void fail(String reason, int status) {
        failed = true;
        failedAt = clockMillis.getAsLong();
        LOG.warn("Issuer key discovery failed (reason={}, status={})", reason, status);
    }

    private URI discover() throws IOException, InterruptedException, ParseException, Failure {
        HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(discoveryUri)
                .timeout(Duration.ofMillis(TIMEOUT_MS)).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream in = res.body()) {
            if (res.statusCode() != 200) {
                throw new Failure("discovery_status", res.statusCode());
            }
            body = in.readNBytes(MAX_BYTES + 1);
        }
        if (body.length > MAX_BYTES) {
            throw new Failure("discovery_too_large", res.statusCode());
        }
        Map<String, Object> meta = JSONObjectUtils.parse(new String(body, StandardCharsets.UTF_8));
        if (!issuer.equals(meta.get("issuer"))) {
            throw new Failure("issuer_mismatch", res.statusCode());
        }
        if (!(meta.get("jwks_uri") instanceof String s)) {
            throw new Failure("jwks_uri_missing", res.statusCode());
        }
        URI jwks;
        try {
            jwks = new URI(s);
        } catch (URISyntaxException e) {
            throw new Failure("jwks_uri_invalid", res.statusCode());
        }
        boolean scheme = "https".equals(jwks.getScheme()) || (allowHttp && "http".equals(jwks.getScheme()));
        if (!scheme || jwks.getHost() == null) {
            throw new Failure("jwks_uri_scheme", res.statusCode());
        }
        return jwks;
    }

    /** A fixed failure reason (never response content) and the HTTP status, for the log line. */
    private static final class Failure extends Exception {
        final String reason;
        final int status;

        Failure(String reason, int status) {
            super(reason, null, false, false);
            this.reason = reason;
            this.status = status;
        }
    }

    /** Nimbus' retriever follows redirects by default (HttpURLConnection); a key fetch must not. */
    private static final class NoRedirectRetriever extends DefaultResourceRetriever {

        NoRedirectRetriever() {
            super(TIMEOUT_MS, TIMEOUT_MS, MAX_BYTES);
        }

        @Override
        protected HttpURLConnection openHTTPConnection(URL url) throws IOException {
            HttpURLConnection c = super.openHTTPConnection(url);
            c.setInstanceFollowRedirects(false);
            return c;
        }
    }
}
