package de.alexanderlindhorst.imapmcp.core.oauth;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.util.ExpiringLruMap;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded in-memory token cache per replica (SPEC 5.5), keyed by {@code (TenantId, AccountId)}, with a single
 * refresh in flight per key. Refreshes run on a dedicated bounded executor, never on the calling tool worker: a
 * waiter waits within its own deadline and is never interrupted into the refresh, and a refresh in flight always
 * runs to completion and updates the cache, even when every waiter has given up. Access tokens are never logged.
 */
public final class CachingOAuthAccessTokens implements OAuthAccessTokens, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CachingOAuthAccessTokens.class);

    /** A refresh starts this long before the token expires. */
    static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(5);

    /** A cached token not used for this long is dropped; far above a token's life, so the bound is the size. */
    private static final Duration IDLE_EXPIRY = Duration.ofHours(2);

    private record Key(TenantId tenantId, AccountId accountId) {
    }

    private final RefreshGrant grant;
    private final Clock clock;
    private final ExpiringLruMap<Key, AccessToken> cache;
    private final Map<Key, CompletableFuture<AccessToken>> inFlight = new HashMap<>();
    private final Object lock = new Object();
    private final ThreadPoolExecutor executor;

    /**
     * @param maxEntries cache size bound (least recently used key out)
     * @param maxConcurrentRefreshes cap on refreshes running at once
     * @param queueCapacity refreshes waiting for a free thread; beyond it a call fails with SERVER_BUSY
     */
    public CachingOAuthAccessTokens(RefreshGrant grant, Clock clock, int maxEntries, int maxConcurrentRefreshes,
            int queueCapacity) {
        this.grant = Objects.requireNonNull(grant, "grant");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = new ExpiringLruMap<>(maxEntries, IDLE_EXPIRY, clock);
        if (maxConcurrentRefreshes < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("maxConcurrentRefreshes and queueCapacity must be positive");
        }
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "oauth-refresh-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(maxConcurrentRefreshes, maxConcurrentRefreshes, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), tf);
        this.executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public AccessToken current(TenantId tenantId, AccountId accountId, Instant deadline) {
        Key key = new Key(Objects.requireNonNull(tenantId, "tenantId"), Objects.requireNonNull(accountId, "accountId"));
        Objects.requireNonNull(deadline, "deadline");
        return obtain(key, deadline, t -> t.expiresAt().minus(REFRESH_BEFORE_EXPIRY).isAfter(clock.instant()), true);
    }

    @Override
    public AccessToken renewAfterRejection(TenantId tenantId, AccountId accountId, AccessToken rejected,
            Instant deadline) {
        Key key = new Key(Objects.requireNonNull(tenantId, "tenantId"), Objects.requireNonNull(accountId, "accountId"));
        Objects.requireNonNull(rejected, "rejected");
        Objects.requireNonNull(deadline, "deadline");
        return obtain(key, deadline, t -> !isSame(t, rejected) && t.expiresAt().isAfter(clock.instant()), false);
    }

    /** Number of cached tokens (tests). */
    int cachedEntries() {
        return cache.size();
    }

    /** Lets refreshes in flight finish, accepts no new ones. */
    @Override
    public void close() {
        executor.shutdown();
    }

    private static boolean isSame(AccessToken cached, AccessToken rejected) {
        return cached == rejected || cached.expiresAt().equals(rejected.expiresAt());
    }

    private AccessToken obtain(Key key, Instant deadline, Predicate<AccessToken> usable, boolean mayFallBack) {
        CompletableFuture<AccessToken> flight;
        AccessToken stale;
        synchronized (lock) {
            stale = cache.get(key);
            if (stale != null && usable.test(stale)) {
                return stale;
            }
            flight = inFlight.get(key);
            if (flight == null) {
                flight = startRefresh(key);
            }
        }
        long waitMs = Math.max(0, Duration.between(clock.instant(), deadline).toMillis());
        try {
            return flight.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw TokenAcquisitionException.waitTimeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the tool deadline; the refresh itself is not touched
            throw TokenAcquisitionException.waitTimeout();
        } catch (ExecutionException e) {
            TokenAcquisitionException failure = (TokenAcquisitionException) e.getCause();
            if (mayFallBack && failure.endpointUnavailable() && stale != null && stale.expiresAt().isAfter(deadline)) {
                return stale; // best effort: the token is still good for this call
            }
            throw failure;
        }
    }

    /** Called with {@code lock} held. */
    private CompletableFuture<AccessToken> startRefresh(Key key) {
        CompletableFuture<AccessToken> flight = new CompletableFuture<>();
        inFlight.put(key, flight);
        try {
            executor.execute(() -> runRefresh(key, flight));
        } catch (RejectedExecutionException e) {
            inFlight.remove(key, flight);
            flight.completeExceptionally(TokenAcquisitionException.busy());
        }
        return flight;
    }

    private void runRefresh(Key key, CompletableFuture<AccessToken> flight) {
        RefreshResult result;
        try {
            result = Objects.requireNonNull(grant.refresh(key.tenantId(), key.accountId()), "result");
        } catch (Throwable e) {
            LOG.warn("Token refresh failed unexpectedly ({})", e.getClass().getName()); // never the message
            result = null;
        }
        synchronized (lock) {
            if (result instanceof RefreshResult.Refreshed ok) {
                cache.put(key, ok.token()); // before leaving inFlight, so a new caller sees one or the other
            }
            inFlight.remove(key, flight);
        }
        if (result instanceof RefreshResult.Refreshed ok) {
            flight.complete(ok.token());
        } else if (result == null) {
            flight.completeExceptionally(TokenAcquisitionException.internal());
        } else {
            flight.completeExceptionally(TokenAcquisitionException.of(result));
        }
    }
}
