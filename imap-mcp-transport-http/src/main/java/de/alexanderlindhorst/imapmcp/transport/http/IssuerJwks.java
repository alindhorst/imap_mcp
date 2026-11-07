package de.alexanderlindhorst.imapmcp.transport.http;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.JSONObjectUtils;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signing keys of the issuer (SPEC 5.3 "Key availability", D16): {@code jwks_uri} from the OpenID Provider
 * metadata at {@code <issuer>/.well-known/openid-configuration}, whose {@code issuer} member must equal the
 * configured issuer; issuer and {@code jwks_uri} must be {@code https}. This is the key cache itself, not a
 * library cache (the JWT library only matches keys): the last key set fetched successfully, the time of that
 * fetch and whether the last refresh failed. Discovery runs at the first token and, when it fails, is retried at
 * most every 10 s. Refreshes are single-flight and at most one attempt starts every 30 s (also in the stale
 * window); one is due 5 minutes after the last successful fetch (it runs without holding a request up) or when a
 * token names an unknown {@code kid} (the request waits, up to the 5 s fetch timeout, for a refresh that started
 * after it arrived). After a failed refresh the last good set stays usable for 60 minutes after its fetch, then
 * it is dropped. When the key decision cannot be made the source throws {@link KeysUnavailableException}
 * ({@code 503}); a {@code kid} that is still missing after a refresh that started after the request arrived
 * and succeeded yields no key ({@code 401}). Neither fetch follows redirects, both are size-capped, and the URLs
 * come from configuration and the issuer's own metadata, never from a request. Logs name fixed reasons only.
 */
public final class IssuerJwks implements JWKSource<SecurityContext> {

    private static final Logger LOG = LoggerFactory.getLogger(IssuerJwks.class);
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_BYTES = 256 * 1024;
    /** Retry of a failed discovery (v0.18). */
    static final long NEGATIVE_CACHE_MS = 10_000;
    static final long STALE_WINDOW_MS = 60 * 60_000L;
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 30;

    private final String issuer;
    private final long dueAfterMs;
    private final long refreshIntervalMs;
    private final URI discoveryUri;
    private final boolean allowHttp;
    private final LongSupplier clockMillis;
    private final MetricsRecorder metrics;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build();
    private final DefaultResourceRetriever retriever = new NoRedirectRetriever();
    /** Orders request arrivals against refresh starts without clock ties. */
    private final AtomicLong sequence = new AtomicLong();
    private final Object lock = new Object();

    /** The last key set fetched successfully and when; replaced as a whole, never mutated. */
    private record Keys(JWKSet set, long fetchedAt) { }

    /** A refresh in flight; {@code done} completes with whether it succeeded, after the result is published. */
    private record Refresh(long startSequence, CompletableFuture<Boolean> done) { }

    private volatile Keys keys;
    private volatile boolean failed; // the last refresh attempt failed (the stale window runs)
    private volatile URL jwksUrl; // set once discovery succeeded
    // guarded by lock
    private boolean attempted;
    private long lastAttemptStart;
    private Refresh inFlight;

    private IssuerJwks(String issuer, Duration cacheTtl, Duration minRefreshInterval, URI discoveryUri,
            boolean allowHttp, LongSupplier clockMillis, MetricsRecorder metrics) {
        checkIssuer(issuer, allowHttp);
        this.issuer = issuer;
        this.dueAfterMs = cacheTtl.toMillis();
        this.refreshIntervalMs = minRefreshInterval.toMillis();
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        this.discoveryUri = discoveryUri != null ? discoveryUri : URI.create(base + "/.well-known/openid-configuration");
        this.allowHttp = allowHttp;
        this.clockMillis = clockMillis;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public static IssuerJwks create(String issuer) {
        return create(issuer, MetricsRecorder.NOOP);
    }

    /** With the replica-wide counter {@code oauth_keys_stale} (SPEC 5.6). */
    public static IssuerJwks create(String issuer, MetricsRecorder metrics) {
        return create(issuer, Duration.ofMinutes(5), Duration.ofSeconds(30), metrics);
    }

    public static IssuerJwks create(String issuer, Duration cacheTtl, Duration minRefreshInterval) {
        return create(issuer, cacheTtl, minRefreshInterval, MetricsRecorder.NOOP);
    }

    private static IssuerJwks create(String issuer, Duration cacheTtl, Duration minRefreshInterval,
            MetricsRecorder metrics) {
        return new IssuerJwks(issuer, cacheTtl, minRefreshInterval, null, false, System::currentTimeMillis, metrics);
    }

    /** Test seam: loopback http, a discovery URL override and a clock. */
    static IssuerJwks createForTest(String issuer, Duration cacheTtl, Duration minRefreshInterval,
            URI discoveryUrl, boolean allowHttp, LongSupplier clockMillis) {
        return createForTest(issuer, cacheTtl, minRefreshInterval, discoveryUrl, allowHttp, clockMillis,
                MetricsRecorder.NOOP);
    }

    static IssuerJwks createForTest(String issuer, Duration cacheTtl, Duration minRefreshInterval,
            URI discoveryUrl, boolean allowHttp, LongSupplier clockMillis, MetricsRecorder metrics) {
        return new IssuerJwks(issuer, cacheTtl, minRefreshInterval, discoveryUrl, allowHttp, clockMillis, metrics);
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
        long arrival = sequence.incrementAndGet();
        Keys k = keys;
        boolean stale = failed;
        if (k != null && stale && clockMillis.getAsLong() - k.fetchedAt >= STALE_WINDOW_MS) {
            dropStale(k);
            k = null;
        }
        if (k != null) {
            List<JWK> hit = decide(selector, k.set);
            if (hit != null) {
                if (stale) {
                    metrics.oauthKeysStale();
                }
                if (clockMillis.getAsLong() - k.fetchedAt >= dueAfterMs) {
                    refreshInBackground();
                }
                return hit;
            }
        }
        return awaitRefresh(selector, arrival);
    }

    /**
     * The keys for the token, or null when its {@code kid} is unknown. A {@code kid} is known when a key with it is
     * in the set, by presence alone (a known {@code kid} whose key does not fit gives an empty list: {@code 401}).
     * A token without {@code kid} uses the keys that fit; none counts as unknown.
     */
    private static List<JWK> decide(JWKSelector selector, JWKSet set) {
        java.util.Set<String> ids = selector.getMatcher().getKeyIDs();
        String kid = ids == null || ids.isEmpty() ? null : ids.iterator().next();
        if (kid != null) {
            return set.getKeyByKeyId(kid) != null ? selector.select(set) : null;
        }
        List<JWK> fit = selector.select(set);
        return fit.isEmpty() ? null : fit;
    }

    private void dropStale(Keys expired) {
        synchronized (lock) {
            if (keys == expired) {
                keys = null;
                LOG.error("Issuer keys dropped: the stale window of {} minutes ended; every token gets 503 until a "
                        + "fetch succeeds", STALE_WINDOW_MS / 60_000);
            }
        }
    }

    private void refreshInBackground() {
        synchronized (lock) {
            if (inFlight == null && mayStart(clockMillis.getAsLong())) {
                startRefresh();
            }
        }
    }

    /** An unknown {@code kid}: wait for a refresh that started after this request arrived, or say 503. */
    private List<JWK> awaitRefresh(JWKSelector selector, long arrival) throws KeySourceException {
        Refresh refresh;
        synchronized (lock) {
            long now = clockMillis.getAsLong();
            if (inFlight != null) {
                refresh = inFlight.startSequence > arrival ? inFlight : null;
            } else {
                refresh = mayStart(now) ? startRefresh() : null;
            }
            if (refresh == null) {
                throw new KeysUnavailableException(restOfInterval(now));
            }
        }
        boolean ok;
        try {
            ok = refresh.done.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            ok = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeysUnavailableException(DEFAULT_RETRY_AFTER_SECONDS);
        }
        Keys k = keys;
        if (!ok || k == null) {
            throw new KeysUnavailableException(DEFAULT_RETRY_AFTER_SECONDS);
        }
        List<JWK> hit = decide(selector, k.set);
        return hit != null ? hit : List.of(); // still missing after a refresh that started after arrival: 401
    }

    /** Caller holds the lock. Before discovery has succeeded the interval is the 10 s of the discovery retry. */
    private boolean mayStart(long now) {
        return !attempted || now - lastAttemptStart >= (jwksUrl == null ? NEGATIVE_CACHE_MS : refreshIntervalMs);
    }

    /** Caller holds the lock. Whole seconds, at least 1; 30 while discovery has not succeeded. */
    private int restOfInterval(long now) {
        if (jwksUrl == null || !attempted) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        long rest = refreshIntervalMs - (now - lastAttemptStart);
        return (int) Math.max(1, (rest + 999) / 1000);
    }

    /** Caller holds the lock. */
    private Refresh startRefresh() {
        Refresh r = new Refresh(sequence.incrementAndGet(), new CompletableFuture<>());
        inFlight = r;
        attempted = true;
        lastAttemptStart = clockMillis.getAsLong();
        Thread.ofVirtual().name("issuer-jwks-refresh").start(() -> run(r));
        return r;
    }

    private void run(Refresh r) {
        boolean ok = false;
        try {
            URL url = jwksUrl;
            if (url == null) {
                url = discover().toURL();
                jwksUrl = url;
            }
            JWKSet set = JWKSet.parse(retriever.retrieveResource(url).getContent());
            published(set);
            ok = true;
        } catch (Failure e) {
            failedRefresh(e.reason, e.status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failedRefresh("interrupted", -1);
        } catch (Exception | Error e) {
            failedRefresh("io_or_format", -1);
        } finally {
            synchronized (lock) {
                inFlight = null;
            }
            r.done.complete(ok);
        }
    }

    private void published(JWKSet set) {
        synchronized (lock) {
            keys = new Keys(set, clockMillis.getAsLong());
            if (failed) {
                failed = false;
                LOG.info("Issuer keys recovered: a fetch succeeded again");
            }
        }
    }

    private void failedRefresh(String reason, int status) {
        synchronized (lock) {
            if (!failed) {
                failed = true;
                LOG.warn("Issuer keys unavailable (reason={}, status={}); the last good set stays usable for {} "
                        + "minutes after its fetch", reason, status, STALE_WINDOW_MS / 60_000);
            } else {
                LOG.debug("Issuer key refresh failed again (reason={}, status={})", reason, status);
            }
        }
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
