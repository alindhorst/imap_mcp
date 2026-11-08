package de.alexanderlindhorst.imapmcp.transport.http;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.JSONObjectUtils;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    /** A refresh is bounded by one deadline per fetch (discovery, then keys); a waiter outlasts that, not the reverse. */
    private static final long WAIT_MS = 2L * TIMEOUT_MS + 1000;
    private static final int MAX_BYTES = 256 * 1024;
    /** Retry of a failed discovery (v0.18). */
    static final long NEGATIVE_CACHE_MS = 10_000;
    static final long AGE_CEILING_MS = 60 * 60_000L;
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 30;
    /** Monotonic: a wall-clock step must neither shorten the stale window nor open the refresh gate. */
    private static final LongSupplier MONOTONIC_MILLIS = () -> System.nanoTime() / 1_000_000;

    private final String issuer;
    private final long dueAfterMs;
    private final long refreshIntervalMs;
    private final URI discoveryUri;
    private final boolean allowHttp;
    private final LongSupplier clockMillis;
    private final MetricsRecorder metrics;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build();
    /** Orders request arrivals against refresh starts without clock ties. */
    private final AtomicLong sequence = new AtomicLong();
    private final Object lock = new Object();

    /**
     * One immutable snapshot of the key state, replaced as a whole under the lock: the last key set fetched
     * successfully (null before the first success and after the stale set was dropped), the time of that fetch and
     * whether the last refresh attempt failed (the stale window runs).
     */
    private record State(JWKSet set, long fetchedAt, boolean failed) { }

    /** A refresh in flight; {@code done} completes with whether it succeeded, after the result is published. */
    private record Refresh(long startSequence, CompletableFuture<Boolean> done) { }

    /** Test seams. */
    volatile Runnable afterStateReadForTest;
    volatile long waitMsForTest = WAIT_MS;

    private volatile State state = new State(null, 0, false);
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
        return new IssuerJwks(issuer, cacheTtl, minRefreshInterval, null, false, MONOTONIC_MILLIS, metrics);
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
        for (;;) {
            List<JWK> keys = decideOnce(selector, arrival);
            if (keys != RETRY) {
                return keys;
            }
        }
    }

    /** Marks that the key state changed under the request, so that the decision is made again on the new state. */
    private static final List<JWK> RETRY = new java.util.ArrayList<>();

    private List<JWK> decideOnce(JWKSelector selector, long arrival) throws KeySourceException {
        State st = state;
        Runnable seam = afterStateReadForTest;
        if (seam != null) {
            seam.run();
        }
        if (st.set == null) {
            return awaitRefresh(selector, arrival, null, false);
        }
        if (clockMillis.getAsLong() - st.fetchedAt >= AGE_CEILING_MS) {
            // v0.24: a set at the ceiling is never used, also without a failed refresh
            if (st.failed) {
                dropAtCeiling(st.set); // as in v0.23: after a failed refresh the request finds it and drops it at once
                return RETRY;
            }
            // only a token the old set could decide joins a refresh that started before it arrived
            return awaitRefresh(selector, arrival, st.set, decide(selector, st.set) != null);
        }
        List<JWK> hit = decide(selector, st.set);
        if (hit != null) {
            if (st.failed) {
                metrics.oauthKeysStale();
            }
            if (clockMillis.getAsLong() - st.fetchedAt >= dueAfterMs) {
                refreshInBackground();
            }
            return hit;
        }
        return awaitRefresh(selector, arrival, st.set, false);
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

    /** Drops the set that reached the ceiling (identity), never one published since. */
    private void dropAtCeiling(JWKSet reached) {
        synchronized (lock) {
            if (state.set == reached) {
                state = new State(null, 0, true);
                LOG.error("Issuer keys dropped: the key set reached the age ceiling of {} minutes; every token gets "
                        + "503 until a fetch succeeds", AGE_CEILING_MS / 60_000);
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

    /**
     * Waits for a refresh, or says 503. {@code seen} is the set this request decided on (null: none); under the lock
     * the state is read again and, when another set was published meanwhile, the decision is made anew. A request
     * for an unknown {@code kid} waits for a refresh that started after it arrived; with {@code joinOlder} (a token
     * the set at the age ceiling could decide) any refresh in flight will do.
     */
    private List<JWK> awaitRefresh(JWKSelector selector, long arrival, JWKSet seen, boolean joinOlder)
            throws KeySourceException {
        Refresh refresh;
        synchronized (lock) {
            if (state.set != seen) {
                return RETRY;
            }
            long now = clockMillis.getAsLong();
            if (inFlight != null) {
                refresh = joinOlder || inFlight.startSequence > arrival ? inFlight : null;
            } else {
                refresh = mayStart(now) ? startRefresh() : null;
            }
            if (refresh == null) {
                throw new KeysUnavailableException(restOfInterval(now));
            }
        }
        boolean ok;
        try {
            ok = refresh.done.get(waitMsForTest, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            ok = false; // the refresh runs on and decides alone; nothing is dropped here
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeysUnavailableException(DEFAULT_RETRY_AFTER_SECONDS);
        }
        State st = state;
        if (!ok || st.set == null) {
            throw new KeysUnavailableException(DEFAULT_RETRY_AFTER_SECONDS);
        }
        List<JWK> hit = decide(selector, st.set);
        return hit != null ? hit : List.of(); // still missing after the refresh: 401
    }

    /** Caller holds the lock. Before discovery has succeeded the interval is the 10 s of the discovery retry. */
    private boolean mayStart(long now) {
        return !attempted || now - lastAttemptStart >= (jwksUrl == null ? NEGATIVE_CACHE_MS : refreshIntervalMs);
    }

    /** Caller holds the lock. Whole seconds in [1, interval]; 30 while discovery has not succeeded. */
    private int restOfInterval(long now) {
        if (jwksUrl == null || !attempted) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        long elapsed = now - lastAttemptStart;
        long rest = elapsed < 0 ? refreshIntervalMs : refreshIntervalMs - Math.min(elapsed, refreshIntervalMs);
        long max = Math.max(1, (refreshIntervalMs + 999) / 1000);
        return (int) Math.min(max, Math.max(1, (rest + 999) / 1000));
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
            JWKSet set = JWKSet.parse(new String(fetch(url.toURI(), "jwks"), StandardCharsets.UTF_8));
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
            boolean wasFailed = state.failed;
            state = new State(set, clockMillis.getAsLong(), false);
            if (wasFailed) {
                LOG.info("Issuer keys recovered: a fetch succeeded again");
            }
        }
    }

    private void failedRefresh(String reason, int status) {
        synchronized (lock) {
            State st = state;
            if (st.set != null && clockMillis.getAsLong() - st.fetchedAt >= AGE_CEILING_MS) {
                // a failed refresh of a set at the ceiling: dropped, the ERROR line only (no stale-window WARN)
                state = new State(null, 0, true);
                LOG.error("Issuer keys dropped: the key set reached the age ceiling of {} minutes and the refresh "
                        + "failed (reason={}, status={}); every token gets 503 until a fetch succeeds",
                        AGE_CEILING_MS / 60_000, reason, status);
                return;
            }
            state = new State(st.set, st.fetchedAt, true);
            if (!st.failed) {
                if (st.set != null) {
                    LOG.warn("Issuer keys unavailable (reason={}, status={}); the last good set stays usable for {} "
                            + "minutes after its fetch", reason, status, AGE_CEILING_MS / 60_000);
                } else {
                    LOG.warn("Issuer keys unavailable (reason={}, status={}); no key set is held, every token gets "
                            + "503 until a fetch succeeds", reason, status);
                }
            } else {
                LOG.debug("Issuer key refresh failed again (reason={}, status={})", reason, status);
            }
        }
    }

    /**
     * One GET of {@code uri} under an overall deadline of 5 s for headers and body together: status exactly
     * {@code 200}, no redirect (the client never follows), at most 256 KiB. A stalled body is cut by closing the
     * stream. Reasons are fixed words prefixed with {@code what}; nothing of the response is kept.
     */
    private byte[] fetch(URI uri, String what) throws Failure, InterruptedException {
        AtomicReference<InputStream> open = new AtomicReference<>();
        AtomicBoolean timedOut = new AtomicBoolean();
        CompletableFuture<HttpResponse<InputStream>> sent = http.sendAsync(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(TIMEOUT_MS)).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<byte[]> read = sent.thenApply(res -> {
            open.set(res.body());
            try (InputStream in = res.body()) {
                if (timedOut.get()) { // the deadline fired before the stream was registered: nobody closes it but us
                    throw new CompletionException(new Failure(what + "_timeout", -1));
                }
                if (res.statusCode() != 200) {
                    throw new CompletionException(new Failure(what + "_status", res.statusCode()));
                }
                byte[] body = in.readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) {
                    throw new CompletionException(new Failure(what + "_too_large", res.statusCode()));
                }
                return body;
            } catch (IOException e) {
                throw new CompletionException(new Failure(what + "_io", res.statusCode()));
            }
        });
        try {
            return read.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            timedOut.set(true); // before the close, so a late-registered stream is closed by the reader
            read.cancel(true);
            sent.cancel(true);
            closeQuietly(open.get());
            throw new Failure(what + "_timeout", -1);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Failure f) {
                throw f;
            }
            throw new Failure(what + "_io", -1);
        } catch (InterruptedException e) {
            read.cancel(true);
            sent.cancel(true);
            closeQuietly(open.get());
            throw e;
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to do: the stream is abandoned
            }
        }
    }

    private URI discover() throws InterruptedException, ParseException, Failure {
        byte[] body = fetch(discoveryUri, "discovery");
        Map<String, Object> meta = JSONObjectUtils.parse(new String(body, StandardCharsets.UTF_8));
        if (!issuer.equals(meta.get("issuer"))) {
            throw new Failure("issuer_mismatch", 200);
        }
        if (!(meta.get("jwks_uri") instanceof String s)) {
            throw new Failure("jwks_uri_missing", 200);
        }
        URI jwks;
        try {
            jwks = new URI(s);
        } catch (URISyntaxException e) {
            throw new Failure("jwks_uri_invalid", 200);
        }
        boolean scheme = "https".equals(jwks.getScheme()) || (allowHttp && "http".equals(jwks.getScheme()));
        if (!scheme || jwks.getHost() == null) {
            throw new Failure("jwks_uri_scheme", 200);
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
}
