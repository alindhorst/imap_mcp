package de.alexanderlindhorst.imapmcp.store.oauth;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.oauth.AccessToken;
import de.alexanderlindhorst.imapmcp.core.oauth.RefreshGrant;
import de.alexanderlindhorst.imapmcp.core.oauth.RefreshResult;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialDecryptException;
import de.alexanderlindhorst.imapmcp.store.crypto.KeyServiceInterruptedException;
import de.alexanderlindhorst.imapmcp.store.crypto.KeyServiceUnavailableException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RefreshGrant} on {@code oauth_grant} and the {@code oauth_refresh} credentials (SPEC 5.5, "Lock"): in one
 * transaction it locks the grant row ({@code FOR UPDATE}, lock timeout 10 s), decrypts the refresh token at
 * {@code refresh_version}, calls the token endpoint, and stores a rotated token as the next credential version,
 * moves the pointer and deletes the old version before it commits. {@code mailbox_account} is touched only by
 * {@code invalid_grant}, which flips the account to {@code needs_relink} (revision bump) and writes the audit event
 * {@code credential_revoked} in the same transaction. A rotation never bumps the revision.
 *
 * <p>Residual risk (stated in 5.5): a failure after the provider rotated the token and before the commit loses the
 * grant; it is counted ({@code oauth_grant_lost}) and logged with hashed ids. Nothing here logs a token, a code or a
 * response text.
 */
public final class JdbcOAuthGrantStore implements RefreshGrant {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcOAuthGrantStore.class);
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private final GrantDb db;
    private final CredentialCipher cipher;
    private final TokenEndpoint client;
    private final TokenProviders providers;
    private final LogIds logIds;
    private final MetricsRecorder metrics;

    public JdbcOAuthGrantStore(DataSource dataSource, CredentialCipher cipher, TokenEndpoint client,
            TokenProviders providers, LogIds logIds, MetricsRecorder metrics) {
        this(new JdbcGrantDb(Objects.requireNonNull(dataSource, "dataSource")), cipher, client, providers, logIds,
                metrics);
    }

    JdbcOAuthGrantStore(GrantDb db, CredentialCipher cipher, TokenEndpoint client, TokenProviders providers,
            LogIds logIds, MetricsRecorder metrics) {
        this.db = Objects.requireNonNull(db, "db");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.client = Objects.requireNonNull(client, "client");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.logIds = Objects.requireNonNull(logIds, "logIds");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** What happened inside the transaction that matters when its commit fails. */
    private static final class Progress {
        boolean rotated;
        AccessToken token;
    }

    @Override
    public RefreshResult refresh(TenantId tenantId, AccountId accountId) {
        UUID tenant = parse(tenantId);
        if (tenant == null) {
            return new RefreshResult.InvalidGrant();
        }
        Progress progress = new Progress();
        RefreshResult result;
        try {
            result = db.inTransaction(tx -> refreshIn(tx, tenant, tenantId, accountId, progress));
        } catch (SQLException e) {
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLState()) && !progress.rotated) {
                metrics.oauthRefresh(tenantId, "lock_timeout");
                return new RefreshResult.LockTimeout();
            }
            failed(tenantId, accountId, progress);
            throw new IllegalStateException("grant refresh failed (sqlstate " + e.getSQLState() + ")");
        } catch (RuntimeException e) {
            failed(tenantId, accountId, progress);
            throw e;
        }
        String label = label(result);
        if (label != null) {
            metrics.oauthRefresh(tenantId, label);
        }
        return result;
    }

    private static String label(RefreshResult r) {
        return switch (r) {
            case RefreshResult.Refreshed x -> "ok";
            case RefreshResult.InvalidGrant x -> "invalid_grant";
            case RefreshResult.ClientRejected x -> "client_rejected";
            case RefreshResult.Unavailable x -> "unavailable";
            case RefreshResult.LockTimeout x -> "lock_timeout";
            case RefreshResult.ProviderUnconfigured x -> null;
        };
    }

    /** The transaction failed after the exchange: with a rotated token the provider already invalidated the old one. */
    private void failed(TenantId tenantId, AccountId accountId, Progress progress) {
        if (progress.token != null) {
            progress.token.close();
        }
        if (progress.rotated) {
            metrics.oauthGrantLost(tenantId);
            LOG.error("oauth grant lost: refresh token rotated but not stored tenant={} account={}",
                    logIds.tenant(tenantId.value()), logIds.account(accountId.value()));
        }
    }

    private RefreshResult refreshIn(GrantDb.Tx tx, UUID tenant, TenantId tenantId, AccountId accountId,
            Progress progress) throws SQLException {
        String account = accountId.value();
        Optional<GrantDb.Grant> grant = tx.lockGrant(tenant, account);
        if (grant.isEmpty()) {
            return new RefreshResult.InvalidGrant(); // no grant row: nothing to refresh, nothing to write
        }
        Optional<TokenProvider> provider = providers.find(grant.get().providerId());
        if (provider.isEmpty()) {
            return new RefreshResult.ProviderUnconfigured();
        }
        int version = grant.get().refreshVersion();
        CredentialCipher.Sealed sealed = tx.readRefresh(tenant, account, version)
                .orElseThrow(() -> new IllegalStateException("refresh credential missing"));
        TokenResponse response;
        try (Secret refreshToken = open(tenantId, tenant, account, version, sealed)) {
            response = client.refresh(provider.get(), refreshToken.chars());
        }
        return switch (response) {
            case TokenResponse.Granted g -> store(tx, tenant, account, version, g, progress);
            case TokenResponse.InvalidGrant x -> {
                tx.markNeedsRelink(tenant, account);
                tx.audit(new EventInfo("credential_revoked", tenantId, accountId, UUID.randomUUID().toString(),
                        "invalid_grant"));
                yield new RefreshResult.InvalidGrant();
            }
            case TokenResponse.InvalidClient x -> clientRejected(tenantId, accountId, provider.get());
            case TokenResponse.UnauthorizedClient x -> clientRejected(tenantId, accountId, provider.get());
            case TokenResponse.Unavailable u -> new RefreshResult.Unavailable(u.retryAfter());
            case TokenResponse.Malformed x -> new RefreshResult.Unavailable(null);
            case TokenResponse.Refused x -> new RefreshResult.Unavailable(null);
        };
    }

    private RefreshResult store(GrantDb.Tx tx, UUID tenant, String account, int version, TokenResponse.Granted g,
            Progress progress) throws SQLException {
        AccessToken token = new AccessToken(g.accessToken(), g.expiresAt());
        progress.token = token;
        Secret rotated = g.rotatedRefreshToken();
        if (rotated == null || rotated.chars().length == 0) {
            tx.pointTo(tenant, account, version);
            return new RefreshResult.Refreshed(token);
        }
        progress.rotated = true; // from here on, a failure before the commit loses the grant
        try (rotated) {
            int next = version + 1;
            tx.insertRefresh(tenant, account, next, cipher.seal(tenant.toString(), account, next, rotated.chars()));
            tx.pointTo(tenant, account, next);
            tx.deleteRefresh(tenant, account, version);
        }
        return new RefreshResult.Refreshed(token);
    }

    private RefreshResult clientRejected(TenantId tenantId, AccountId accountId, TokenProvider provider) {
        LOG.error("oauth client rejected by provider={} tenant={} account={}", provider.id(),
                logIds.tenant(tenantId.value()), logIds.account(accountId.value()));
        return new RefreshResult.ClientRejected();
    }

    private Secret open(TenantId tenantId, UUID tenant, String account, int version, CredentialCipher.Sealed sealed) {
        try {
            return cipher.open(tenant.toString(), account, version, sealed);
        } catch (KeyServiceInterruptedException e) {
            throw e;
        } catch (KeyServiceUnavailableException e) {
            LOG.warn("key service unavailable tenant={} account={} provider={} class={}",
                    logIds.tenant(tenantId.value()), logIds.account(account), e.provider(), e.failureClass());
            metrics.keyServiceUnavailable(tenantId);
            throw e;
        } catch (CredentialDecryptException e) {
            LOG.error("credential decrypt failed tenant={} account={}", logIds.tenant(tenantId.value()),
                    logIds.account(account));
            metrics.decryptFailure(tenantId);
            throw e;
        }
    }

    private static UUID parse(TenantId tenant) {
        try {
            return UUID.fromString(tenant.value());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
