package de.alexanderlindhorst.imapmcp.app;

import de.alexanderlindhorst.imapmcp.app.config.UpstreamProvider;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.oauth.CachingOAuthAccessTokens;
import de.alexanderlindhorst.imapmcp.core.oauth.OAuthAccessTokens;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.oauth.JdbcOAuthGrantStore;
import de.alexanderlindhorst.imapmcp.store.oauth.TokenEndpointClient;
import de.alexanderlindhorst.imapmcp.store.oauth.TokenProvider;
import de.alexanderlindhorst.imapmcp.store.oauth.TokenProviders;
import de.alexanderlindhorst.imapmcp.store.secret.RefCredentialStore;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The XOAUTH2 refresh chain of {@code http-public} (SPEC 5.5, 5.7): the configured upstream providers as the store's
 * {@link TokenProviders}, the token-endpoint client under the SSRF policy, the grant store and the bounded token cache
 * with its refresh executor. Built only by {@link PublicComponents}; {@code stdio} and {@code http-local} never see
 * it. The cache is handed to the pool, and {@link #close()} stops the refresh executor.
 */
final class PublicOAuthUpstream implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("imapmcp.app");

    /** Cached access tokens per replica; the least recently used key goes first. */
    static final int MAX_CACHED_TOKENS = 10_000;
    /** Refreshes running at once (each holds a token-endpoint call of at most 8 s and a row lock). */
    static final int MAX_CONCURRENT_REFRESHES = 8;
    /** Refreshes waiting for a thread; beyond it a call fails with SERVER_BUSY. */
    static final int REFRESH_QUEUE_CAPACITY = 64;

    private final CachingOAuthAccessTokens tokens;
    private boolean closed;

    private PublicOAuthUpstream(CachingOAuthAccessTokens tokens) {
        this.tokens = tokens;
    }

    static PublicOAuthUpstream create(List<UpstreamProvider> providers, RefCredentialStore refs, DataSource ds,
            SsrfPolicy ssrf, CredentialCipher cipher, LogIds logIds, MetricsRecorder metrics, Clock clock) {
        TokenEndpointClient client = new TokenEndpointClient(ssrf);
        JdbcOAuthGrantStore grants = new JdbcOAuthGrantStore(ds, cipher, client, providers(providers, refs), logIds,
                metrics);
        return new PublicOAuthUpstream(new CachingOAuthAccessTokens(grants, clock, MAX_CACHED_TOKENS,
                MAX_CONCURRENT_REFRESHES, REFRESH_QUEUE_CAPACITY));
    }

    /** The token source handed to the pool. */
    OAuthAccessTokens tokens() {
        return tokens;
    }

    /** Each provider with its secret read at use (an {@code env:} or {@code file:} reference); empty for other ids. */
    static TokenProviders providers(List<UpstreamProvider> configured, RefCredentialStore refs) {
        Map<String, TokenProvider> byId = new HashMap<>();
        for (UpstreamProvider p : configured) {
            byId.put(p.id(), new TokenProvider(p.id(), URI.create(p.tokenEndpoint()), p.clientId(),
                    () -> refs.resolve(p.clientSecret())));
        }
        return id -> java.util.Optional.ofNullable(byId.get(id));
    }

    /**
     * SPEC 5.6: stored XOAUTH2 accounts whose provider is no longer configured are answered with
     * {@code provider_unconfigured}; startup says how many there are. The count only: no provider id, no account.
     */
    static void warnUnconfigured(Map<String, Long> storedByProvider, Set<String> configuredIds) {
        long count = storedByProvider.entrySet().stream().filter(e -> !configuredIds.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
        if (count > 0) {
            LOG.warn("{} stored XOAUTH2 account(s) use an upstream provider that is not configured; calls on them "
                    + "fail with provider_unconfigured until the provider is configured again", count);
        }
    }

    /** Startup check; a database failure here must not stop the server, so only the failure class is logged. */
    static void checkStoredProviders(DataSource ds, List<UpstreamProvider> configured) {
        try {
            warnUnconfigured(storedByProvider(ds),
                    configured.stream().map(UpstreamProvider::id).collect(Collectors.toSet()));
        } catch (SQLException | RuntimeException e) {
            LOG.warn("could not check stored XOAUTH2 accounts against the configured providers: {}",
                    e.getClass().getSimpleName());
        }
    }

    /** Number of stored XOAUTH2 accounts per provider id, over all tenants (a startup statistic, no content). */
    static Map<String, Long> storedByProvider(DataSource ds) throws SQLException {
        Map<String, Long> out = new HashMap<>();
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT provider_id, count(*) FROM mailbox_account"
                        + " WHERE auth = 'xoauth2' GROUP BY provider_id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        closed = true;
        tokens.close();
    }
}
