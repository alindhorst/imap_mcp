package de.alexanderlindhorst.imapmcp.transport.http.web;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.JSONObjectUtils;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.transport.http.InvalidTokenException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link OidcProvider} over HTTPS: endpoints from {@code <issuer>/.well-known/openid-configuration} (whose
 * {@code issuer} must equal the configured one; every endpoint {@code https}), discovered on first use and kept for
 * an hour, a failure remembered for 10 s. The token request authenticates the client with HTTP Basic
 * ({@code client_secret_basic}); redirects are never followed, answers are size-capped and nothing of them is
 * logged or carried in an exception.
 */
public final class HttpOidcProvider implements OidcProvider {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOidcProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_BYTES = 256 * 1024;
    private static final long CACHE_MS = 3_600_000;
    private static final long NEGATIVE_CACHE_MS = 10_000;

    private record Endpoints(URI authorization, URI token) {
    }

    private final WebConfig config;
    private final IdTokenValidator validator;
    private final boolean allowHttp;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT).build();
    private Endpoints endpoints; // guarded by this
    private long endpointsAt; // guarded by this
    private long failedAt = -1; // guarded by this

    private HttpOidcProvider(WebConfig config, JWKSource<SecurityContext> keys, boolean allowHttp) {
        this.config = config;
        this.validator = new IdTokenValidator(config.issuer(), config.clientId(), config.subjectClaim(), keys);
        this.allowHttp = allowHttp;
    }

    public static HttpOidcProvider create(WebConfig config, JWKSource<SecurityContext> keys) {
        return new HttpOidcProvider(config, keys, false);
    }

    /**
     * With {@code allowInsecureHttp} the discovery document may name {@code http} endpoints. For tests against a
     * local identity provider only; the application never passes {@code true}.
     */
    public static HttpOidcProvider create(WebConfig config, JWKSource<SecurityContext> keys,
            boolean allowInsecureHttp) {
        return new HttpOidcProvider(config, keys, allowInsecureHttp);
    }

    static HttpOidcProvider forTest(WebConfig config, JWKSource<SecurityContext> keys, boolean allowHttp) {
        return new HttpOidcProvider(config, keys, allowHttp);
    }

    @Override
    public URI authorizationEndpoint() throws OidcException {
        return endpoints().authorization();
    }

    @Override
    public IdToken exchange(String code, String pkceVerifier, String nonce) throws OidcException {
        Endpoints e = endpoints();
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri="
                + enc(config.redirectUri()) + "&code_verifier=" + enc(pkceVerifier);
        String idToken;
        try (Secret secret = config.clientSecret().get()) {
            String basic = Base64.getEncoder().encodeToString((formEnc(config.clientId()) + ":"
                    + formEnc(new String(secret.chars()))).getBytes(StandardCharsets.UTF_8));
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(e.token()).timeout(TIMEOUT)
                    .header("Authorization", "Basic " + basic).header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = res.body()) {
                body = in.readNBytes(MAX_BYTES + 1);
            }
            if (res.statusCode() != 200 || body.length > MAX_BYTES) {
                LOG.warn("token endpoint answered status {}", res.statusCode());
                throw new OidcException(OidcException.UNAVAILABLE);
            }
            Object t = JSONObjectUtils.parse(new String(body, StandardCharsets.UTF_8)).get("id_token");
            if (!(t instanceof String s) || s.isEmpty()) {
                LOG.warn("token response without id_token");
                throw new OidcException(OidcException.UNAVAILABLE);
            }
            idToken = s;
        } catch (IOException | ParseException | RuntimeException ex) {
            LOG.warn("token request failed ({})", ex.getClass().getSimpleName());
            throw new OidcException(OidcException.UNAVAILABLE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException(OidcException.UNAVAILABLE);
        }
        try {
            return validator.validate(idToken, nonce);
        } catch (InvalidTokenException ex) {
            LOG.debug("id token rejected ({})", ex.category());
            throw new OidcException(InvalidTokenException.KEYS_UNAVAILABLE.equals(ex.category())
                    ? OidcException.UNAVAILABLE : OidcException.INVALID_TOKEN);
        }
    }

    private synchronized Endpoints endpoints() throws OidcException {
        long now = System.currentTimeMillis();
        if (endpoints != null && now - endpointsAt < CACHE_MS) {
            return endpoints;
        }
        if (failedAt >= 0 && now - failedAt < NEGATIVE_CACHE_MS) {
            throw new OidcException(OidcException.UNAVAILABLE);
        }
        try {
            String base = config.issuer().endsWith("/") ? config.issuer().substring(0, config.issuer().length() - 1)
                    : config.issuer();
            HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(base
                    + "/.well-known/openid-configuration")).timeout(TIMEOUT).header("Accept", "application/json")
                    .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = res.body()) {
                body = in.readNBytes(MAX_BYTES + 1);
            }
            if (res.statusCode() != 200 || body.length > MAX_BYTES) {
                throw new IOException("status");
            }
            Map<String, Object> meta = JSONObjectUtils.parse(new String(body, StandardCharsets.UTF_8));
            if (!config.issuer().equals(meta.get("issuer"))) {
                throw new IOException("issuer");
            }
            endpoints = new Endpoints(endpoint(meta, "authorization_endpoint"), endpoint(meta, "token_endpoint"));
            endpointsAt = now;
            failedAt = -1;
            return endpoints;
        } catch (IOException | ParseException | URISyntaxException | RuntimeException ex) {
            failedAt = now;
            LOG.warn("OIDC discovery failed ({})", ex.getClass().getSimpleName());
            throw new OidcException(OidcException.UNAVAILABLE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException(OidcException.UNAVAILABLE);
        }
    }

    private URI endpoint(Map<String, Object> meta, String name) throws URISyntaxException, IOException {
        if (!(meta.get(name) instanceof String s)) {
            throw new IOException("missing");
        }
        URI u = new URI(s);
        boolean scheme = "https".equals(u.getScheme()) || (allowHttp && "http".equals(u.getScheme()));
        if (!scheme || u.getHost() == null || u.getRawFragment() != null || u.getRawUserInfo() != null) {
            throw new IOException("scheme");
        }
        return u;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** RFC 6749 2.3.1: client id and secret are form-encoded before the Basic encoding. */
    private static String formEnc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
