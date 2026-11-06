package de.alexanderlindhorst.imapmcp.store.jdbc;

import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialDecryptException;
import de.alexanderlindhorst.imapmcp.store.crypto.KeyServiceUnavailableException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CredentialStore} on the {@code credential} table (SPEC 5.3, 5.7): decrypts the current password
 * credential of an account at connect time. A row that does not decrypt (copied to another tenant, account or
 * version, tampered, wrong KEK) never falls back: one ERROR line with hashed ids, a metric, and
 * {@link CredentialDecryptException}, which the tool layer reports as INTERNAL_ERROR before any LOGIN is sent.
 */
public final class JdbcCredentialStore implements CredentialStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcCredentialStore.class);

    static final String METRIC_TOOL = "credentials";
    static final String METRIC_OP_DECRYPT_FAILURE = "decrypt_failure";
    static final String METRIC_OP_KEY_SERVICE_UNAVAILABLE = "key_service_unavailable";

    private final DataSource dataSource;
    private final CredentialCipher cipher;
    private final LogIds logIds;
    private final MetricsRecorder metrics;

    public JdbcCredentialStore(DataSource dataSource, CredentialCipher cipher, LogIds logIds,
            MetricsRecorder metrics) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.logIds = Objects.requireNonNull(logIds, "logIds");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public Optional<Secret> resolve(TenantId tenant, AccountId account) {
        UUID tenantUuid = parse(tenant);
        if (tenantUuid == null) {
            return Optional.empty();
        }
        int version;
        CredentialCipher.Sealed sealed;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT c.version, c.kek_id, c.wrapped_dek, c.nonce, c.ciphertext FROM credential c"
                                + " JOIN mailbox_account a ON a.tenant_id = c.tenant_id AND a.id = c.account_id"
                                + " AND a.credential_version = c.version"
                                + " WHERE c.tenant_id = ? AND c.account_id = ? AND c.kind = 'password'")) {
            ps.setObject(1, tenantUuid);
            ps.setString(2, account.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                version = rs.getInt(1);
                sealed = new CredentialCipher.Sealed(rs.getString(2), rs.getBytes(3), rs.getBytes(4), rs.getBytes(5));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("credential lookup failed");
        }
        try {
            return Optional.of(cipher.open(tenantUuid.toString(), account.value(), version, sealed));
        } catch (KeyServiceUnavailableException e) {
            // availability, not integrity: its own WARN line and metric, never the decrypt-failure ones
            LOG.warn("key service unavailable tenant={} account={} provider={} class={}",
                    logIds.tenant(tenant.value()), logIds.account(account.value()), e.provider(),
                    e.failureClass());
            metrics.operation(tenant, METRIC_TOOL, METRIC_OP_KEY_SERVICE_UNAVAILABLE, Duration.ZERO);
            throw e;
        } catch (CredentialDecryptException e) {
            LOG.error("credential decrypt failed tenant={} account={}", logIds.tenant(tenant.value()),
                    logIds.account(account.value()));
            metrics.operation(tenant, METRIC_TOOL, METRIC_OP_DECRYPT_FAILURE, Duration.ZERO);
            throw e;
        }
    }

    /**
     * Encrypts and stores a password credential as {@code version} of an existing account. Does not retain or wipe
     * {@code secret}; choosing the current version is the caller's (account repository's) business.
     */
    public void insert(TenantId tenant, AccountId account, int version, char[] secret) {
        UUID tenantUuid = parse(tenant);
        if (tenantUuid == null) {
            throw new IllegalArgumentException("tenant id is not a UUID");
        }
        CredentialCipher.Sealed sealed = cipher.seal(tenantUuid.toString(), account.value(), version, secret);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO credential (tenant_id, account_id, version, kind, kek_id, wrapped_dek, nonce,"
                                + " ciphertext) VALUES (?, ?, ?, 'password', ?, ?, ?, ?)")) {
            ps.setObject(1, tenantUuid);
            ps.setString(2, account.value());
            ps.setInt(3, version);
            ps.setString(4, sealed.kekId());
            ps.setBytes(5, sealed.wrappedDek());
            ps.setBytes(6, sealed.nonce());
            ps.setBytes(7, sealed.ciphertext());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("credential insert failed");
        }
    }

    /** The AAD uses the canonical (lower-case) UUID text, the same form the row's key is compared in. */
    private static UUID parse(TenantId tenant) {
        try {
            return UUID.fromString(tenant.value());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
