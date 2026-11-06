package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.account.AccountHandles;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
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

        record NotFound(ToolError error) implements Result {
        }
    }

    private final AccountRepository accounts;

    public AccountArgument(AccountRepository accounts) {
        this(accounts, null);
    }

    /** {@code linkUrl}: the linking surface's account page for re-link errors (null in the single-tenant modes). */
    public AccountArgument(AccountRepository accounts, String linkUrl) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
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
        return new Result.Ok(resolved, PoolKey.of(ctx.accountContext(), resolved));
    }
}
