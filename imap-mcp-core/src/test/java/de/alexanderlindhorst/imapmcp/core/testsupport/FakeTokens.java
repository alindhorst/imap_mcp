package de.alexanderlindhorst.imapmcp.core.testsupport;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.oauth.AccessToken;
import de.alexanderlindhorst.imapmcp.core.oauth.OAuthAccessTokens;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** An {@link OAuthAccessTokens} whose current and renewed tokens the test sets; it counts and records the calls. */
public final class FakeTokens implements OAuthAccessTokens {

    private volatile AccessToken current;
    private volatile AccessToken renewed;
    private final List<TenantId> tenants = new ArrayList<>();
    private final List<Instant> deadlines = new ArrayList<>();
    private int currentCalls;
    private int renewals;

    public FakeTokens(String current, Instant expiresAt) {
        this.current = token(current, expiresAt);
    }

    public static AccessToken token(String value, Instant expiresAt) {
        return new AccessToken(new Secret(value.toCharArray()), expiresAt);
    }

    /** What {@link #current} hands out from now on. */
    public FakeTokens setCurrent(String value, Instant expiresAt) {
        this.current = token(value, expiresAt);
        return this;
    }

    /** What {@link #renewAfterRejection} hands out next. */
    public FakeTokens renewedTo(String value, Instant expiresAt) {
        this.renewed = token(value, expiresAt);
        return this;
    }

    @Override
    public synchronized AccessToken current(TenantId tenantId, AccountId accountId, Instant deadline) {
        currentCalls++;
        tenants.add(tenantId);
        deadlines.add(deadline);
        return current;
    }

    @Override
    public synchronized AccessToken renewAfterRejection(TenantId tenantId, AccountId accountId, AccessToken rejected,
            Instant deadline) {
        renewals++;
        tenants.add(tenantId);
        if (renewed == null) {
            throw new IllegalStateException("no renewed token scripted");
        }
        current = renewed;
        return renewed;
    }

    /** The deadlines passed to {@link #current}, in order. */
    public synchronized List<Instant> deadlinesSeen() {
        return List.copyOf(deadlines);
    }

    public synchronized int currentCalls() {
        return currentCalls;
    }

    public synchronized int renewals() {
        return renewals;
    }

    public synchronized List<TenantId> tenantsSeen() {
        return List.copyOf(tenants);
    }
}
