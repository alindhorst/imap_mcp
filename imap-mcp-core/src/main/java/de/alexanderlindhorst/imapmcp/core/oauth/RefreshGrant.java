package de.alexanderlindhorst.imapmcp.core.oauth;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;

/**
 * The store side of a token refresh (SPEC 5.5): takes the grant row lock, calls the token endpoint, stores the
 * rotated refresh token in the same transaction and returns the new access token or a typed failure. Implemented
 * in the store module; core stays free of database and HTTP. Called only from the refresh executor, never from a
 * tool worker, and always runs to completion. Implementations return failures as {@link RefreshResult}; an
 * exception is an internal error.
 */
public interface RefreshGrant {

    RefreshResult refresh(TenantId tenantId, AccountId accountId);
}
