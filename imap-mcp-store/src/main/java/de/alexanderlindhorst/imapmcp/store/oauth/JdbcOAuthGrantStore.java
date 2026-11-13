package de.alexanderlindhorst.imapmcp.store.oauth;

import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.oauth.RefreshGrant;
import de.alexanderlindhorst.imapmcp.core.oauth.RefreshResult;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;

/** Skeleton (red commit). */
public final class JdbcOAuthGrantStore implements RefreshGrant {

    JdbcOAuthGrantStore(GrantDb db, CredentialCipher cipher, TokenEndpoint client, TokenProviders providers,
            LogIds logIds, MetricsRecorder metrics) {
    }

    @Override
    public RefreshResult refresh(TenantId tenantId, AccountId accountId) {
        throw new UnsupportedOperationException();
    }
}
