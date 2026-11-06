package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.account.AccountHandles;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared resolution of the {@code account} tool argument (SPEC 4, 5.3). Format check first, then the
 * tenant-scoped lookup with the tenant from the caller's {@code AccountContext}. Malformed, unknown and
 * other-tenant handles all yield the same {@code ACCOUNT_NOT_FOUND} error (identical except request_id), so
 * nothing about other tenants' accounts can be learned. On success the account is bound to the call's log
 * context.
 */
public final class AccountArgument {

    /** Outcome of {@link #resolve}. */
    public sealed interface Result {
        record Ok(ResolvedAccount account, PoolKey key) implements Result {
        }

        /** The account is not usable: unknown to the tenant (ACCOUNT_NOT_FOUND) or needing a re-link (AUTH_FAILED). */
        record NotFound(ToolError error) implements Result {
        }
    }

    private final AccountRepository accounts;
    private final String linkUrl;

    public AccountArgument(AccountRepository accounts) {
        this(accounts, null);
    }

    /** {@code linkUrl}: the linking surface's account page for re-link errors (null in the single-tenant modes). */
    public AccountArgument(AccountRepository accounts, String linkUrl) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.linkUrl = linkUrl;
    }

    public Result resolve(ToolCallContext ctx, String account) {
        Objects.requireNonNull(ctx, "ctx");
        Optional<ResolvedAccount> found = AccountHandles.isValid(account)
                ? accounts.findByTenantAndHandle(ctx.accountContext().tenantId(), account) : Optional.empty();
        if (found.isEmpty()) {
            return new Result.NotFound(ToolError.accountNotFound(ctx.requestId()));
        }
        ResolvedAccount resolved = found.get();
        ctx.bindAccount(resolved.account().id());
        ctx.bindLinkUrl(linkUrl); // for a re-link error that surfaces later, from the pool
        if (resolved.account().status() == MailAccount.Status.NEEDS_RELINK) {
            // SPEC 5.3: no connection is attempted; every account tool fails here, before any I/O
            return new Result.NotFound(ToolError.relinkRequired(linkUrl, ctx.requestId()));
        }
        return new Result.Ok(resolved, PoolKey.of(ctx.accountContext(), resolved));
    }
}
