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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signing keys of the issuer (SPEC 5.3): {@code jwks_uri} from the OpenID Provider metadata at
 * {@code <issuer>/.well-known/openid-configuration}, whose {@code issuer} member must equal the configured
 * issuer. Discovery runs on the first token, not at startup, and a failure is not remembered. The key set is
 * cached (Nimbus: TTL, refresh ahead, rate-limited refresh on an unknown {@code kid}). Redirects are not
 * followed and responses are size-capped; the {@code jwks_uri} scheme must be that of the issuer.
 */
public final class IssuerJwks implements JWKSource<SecurityContext> {

    private static final Logger LOG = LoggerFactory.getLogger(IssuerJwks.class);
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_BYTES = 256 * 1024;

    private final String issuer;
    private final Duration cacheTtl;
    private final Duration minRefreshInterval;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build();
    private volatile JWKSource<SecurityContext> delegate;

    private IssuerJwks(String issuer, Duration cacheTtl, Duration minRefreshInterval) {
        this.issuer = issuer;
        this.cacheTtl = cacheTtl;
        this.minRefreshInterval = minRefreshInterval;
    }

    public static IssuerJwks create(String issuer) {
        return create(issuer, Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    public static IssuerJwks create(String issuer, Duration cacheTtl, Duration minRefreshInterval) {
        return new IssuerJwks(issuer, cacheTtl, minRefreshInterval);
    }

    /** Test seam: loopback http, a discovery URL override and a clock. */
    static IssuerJwks createForTest(String issuer, Duration cacheTtl, Duration minRefreshInterval,
            URI discoveryUrl, boolean allowHttp, java.util.function.LongSupplier clockMillis) {
        return new IssuerJwks(issuer, cacheTtl, minRefreshInterval);
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
        try {
            URI jwksUri = discover();
            DefaultResourceRetriever retriever = new DefaultResourceRetriever(TIMEOUT_MS, TIMEOUT_MS, MAX_BYTES);
            delegate = JWKSourceBuilder.<SecurityContext>create(jwksUri.toURL(), retriever)
                    .cache(cacheTtl.toMillis(), TIMEOUT_MS).rateLimited(minRefreshInterval.toMillis())
                    .retrying(true).build();
            return delegate;
        } catch (IOException | RuntimeException | ParseException e) {
            LOG.warn("Issuer key discovery failed ({})", e.getClass().getSimpleName());
            throw new KeySourceException("issuer metadata unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeySourceException("interrupted");
        }
    }

    private URI discover() throws IOException, InterruptedException, ParseException {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        URI uri = URI.create(base + "/.well-known/openid-configuration");
        HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(TIMEOUT_MS))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200 || res.body().length > MAX_BYTES) {
            throw new IOException("discovery status");
        }
        Map<String, Object> meta = JSONObjectUtils.parse(new String(res.body(), StandardCharsets.UTF_8));
        if (!issuer.equals(meta.get("issuer"))) {
            throw new IOException("issuer mismatch");
        }
        URI jwks = URI.create(String.valueOf(meta.get("jwks_uri")));
        if (jwks.getScheme() == null || !jwks.getScheme().equals(uri.getScheme()) || jwks.getHost() == null) {
            throw new IOException("jwks_uri scheme");
        }
        return jwks;
    }
}
