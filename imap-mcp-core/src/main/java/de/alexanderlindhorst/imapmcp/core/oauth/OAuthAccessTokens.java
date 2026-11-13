package de.alexanderlindhorst.imapmcp.core.oauth;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Instant;

/**
 * Access tokens for XOAUTH2 accounts (SPEC 5.5). The tenant and account come only from the {@code PoolKey} of an
 * account resolved through an {@code AccountContext}. {@code deadline} is the tool call's deadline (the same
 * {@link Instant} as {@code ToolCallContext#deadline()}); both calls fail with {@link TokenAcquisitionException}.
 */
public interface OAuthAccessTokens {

    /** A token valid for use now; refreshes (single-flight) when none is cached or it is about to expire. */
    AccessToken current(TenantId tenantId, AccountId accountId, Instant deadline);

    /**
     * The IMAP server refused {@code rejected}. Refreshes only if the cached token is still the rejected one;
     * otherwise returns the newer cached token without a refresh.
     */
    AccessToken renewAfterRejection(TenantId tenantId, AccountId accountId, AccessToken rejected, Instant deadline);
}
