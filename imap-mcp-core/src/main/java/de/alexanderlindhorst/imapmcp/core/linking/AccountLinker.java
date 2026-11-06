package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the linking pages can do for a tenant. Every method takes the tenant of the web session; account handles
 * are looked up within that tenant only. Passwords are wiped by the caller.
 */
public interface AccountLinker {

    List<ResolvedAccount> list(TenantId tenant);

    Optional<ResolvedAccount> find(TenantId tenant, String handle);

    /** {@code authTime}: when the user last signed in (allow_purge needs a recent one). */
    LinkOutcome link(TenantId tenant, LinkRequest request, char[] password, Instant authTime, String requestId);

    LinkOutcome relink(TenantId tenant, String handle, char[] password, String requestId);

    LinkOutcome updateSettings(TenantId tenant, String handle, SettingsRequest request, Instant authTime,
            String requestId);

    LinkOutcome unlink(TenantId tenant, String handle, String requestId);

    /** Whether the deployment offers {@code allow_purge} ({@code purge.enabled}). */
    boolean purgeEnabled();

    int maxAccounts();
}
