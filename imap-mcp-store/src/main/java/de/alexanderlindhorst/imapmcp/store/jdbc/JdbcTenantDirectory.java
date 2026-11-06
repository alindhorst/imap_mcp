package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.audit.AuditWriteException;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;

/**
 * Tenant row by IdP (issuer, subject) (SPEC 5.3, 5.7). Hits are cached per replica for 5 minutes in a bounded
 * least-recently-used map; misses are never cached, so a tenant created at first login is seen at once. The inputs
 * come from a validated token, but their length is bounded anyway. A disabled tenant stays visible for up to the
 * cache time unless {@link #invalidate} is called.
 */
public final class JdbcTenantDirectory {

    /** A tenant row: its id and whether it is disabled. */
    public record TenantInfo(TenantId tenantId, boolean disabled) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(JdbcTenantDirectory.class);

    public static final Duration CACHE_TTL = Duration.ofMinutes(5);
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    private static final int MAX_ISSUER = 512;
    private static final int MAX_SUBJECT = 255; // OIDC Core: sub is at most 255 ASCII characters

    private record Key(String issuer, String subject) {
    }

    private record Cached(TenantInfo info, Instant expiresAt) {
    }

    private final DataSource dataSource;
    private final Clock clock;
    private final Map<Key, Cached> cache;
    /** Counts invalidations; a load that started before one must not cache its (possibly stale) row. */
    private final AtomicLong invalidations = new AtomicLong();

    public JdbcTenantDirectory(DataSource dataSource) {
        this(dataSource, Clock.systemUTC(), DEFAULT_MAX_ENTRIES);
    }

    public JdbcTenantDirectory(DataSource dataSource, Clock clock, int maxEntries) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public Optional<TenantInfo> find(String issuer, String subject) {
        if (!withinBounds(issuer, subject)) {
            return Optional.empty();
        }
        Key key = new Key(issuer, subject);
        Instant now = clock.instant();
        synchronized (cache) {
            Cached cached = cache.get(key);
            if (cached != null) {
                if (cached.expiresAt().isAfter(now)) {
                    return Optional.of(cached.info());
                }
                cache.remove(key);
            }
        }
        long generation = invalidations.get();
        Optional<TenantInfo> loaded = load(issuer, subject);
        loaded.ifPresent(info -> {
            synchronized (cache) {
                if (invalidations.get() == generation) { // invalidate() runs under this lock too
                    cache.put(key, new Cached(info, now.plus(CACHE_TTL)));
                }
            }
        });
        return loaded;
    }

    /**
     * Creates the tenant row if absent and returns it (first login with the required role, D2). Idempotent and
     * safe against concurrent callers.
     */
    public TenantInfo create(String issuer, String subject) {
        if (!withinBounds(issuer, subject)) {
            throw new IllegalArgumentException("issuer or subject out of bounds");
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO tenant (id, issuer, subject) VALUES (?, ?, ?)"
                                + " ON CONFLICT (issuer, subject) DO NOTHING")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, issuer);
            ps.setString(3, subject);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("tenant insert failed (sqlstate " + e.getSQLState() + ")");
        }
        invalidate(issuer, subject);
        return load(issuer, subject).orElseThrow();
    }

    /**
     * As {@link #create(String, String)}, and when this call inserts the row it also writes the
     * {@code tenant_created} audit row in the same transaction; if that write fails nothing is created and
     * {@link AuditWriteException} is thrown.
     */
    public TenantInfo create(String issuer, String subject, String requestId) {
        if (!withinBounds(issuer, subject)) {
            throw new IllegalArgumentException("issuer or subject out of bounds");
        }
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO tenant (id, issuer, subject)"
                        + " VALUES (?, ?, ?) ON CONFLICT (issuer, subject) DO NOTHING")) {
                    ps.setObject(1, id);
                    ps.setString(2, issuer);
                    ps.setString(3, subject);
                    inserted = ps.executeUpdate();
                }
                if (inserted == 1) {
                    try {
                        JdbcAuditLog.insertEvent(c, new EventInfo("tenant_created", new TenantId(id.toString()), null,
                                requestId, "ok"));
                    } catch (SQLException e) {
                        c.rollback();
                        LOG.error("audit write failed (cause=SQLException, sqlstate={})", e.getSQLState());
                        throw new AuditWriteException();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("tenant insert failed (sqlstate " + e.getSQLState() + ")");
        }
        invalidate(issuer, subject);
        return load(issuer, subject).orElseThrow();
    }

    public void invalidate(String issuer, String subject) {
        synchronized (cache) {
            invalidations.incrementAndGet();
            cache.remove(new Key(issuer, subject));
        }
    }

    /** Number of cached entries (tests and diagnostics). */
    public int cachedEntries() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private Optional<TenantInfo> load(String issuer, String subject) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT id, status FROM tenant WHERE issuer = ? AND subject = ?")) {
            ps.setString(1, issuer);
            ps.setString(2, subject);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TenantInfo(new TenantId(UUID.fromString(rs.getString(1)).toString()),
                        "disabled".equals(rs.getString(2))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("tenant lookup failed (sqlstate " + e.getSQLState() + ")");
        }
    }

    private static boolean withinBounds(String issuer, String subject) {
        return issuer != null && subject != null && !issuer.isEmpty() && !subject.isEmpty()
                && issuer.length() <= MAX_ISSUER && subject.length() <= MAX_SUBJECT
                && issuer.indexOf('\0') < 0 && subject.indexOf('\0') < 0;
    }
}
