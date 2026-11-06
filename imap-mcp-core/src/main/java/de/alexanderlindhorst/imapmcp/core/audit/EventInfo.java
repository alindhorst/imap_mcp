package de.alexanderlindhorst.imapmcp.core.audit;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;

/**
 * A linking event (SPEC 5.3): {@code tenant_created}, {@code link}, {@code relink}, {@code settings},
 * {@code unlink}, {@code session_approval}. Event type, tenant, account, request id and result only; never a
 * host, username or address.
 */
public record EventInfo(String type, TenantId tenant, AccountId account, String requestId, String result) {
}
