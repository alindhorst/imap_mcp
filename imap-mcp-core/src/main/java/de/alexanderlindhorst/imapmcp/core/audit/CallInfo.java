package de.alexanderlindhorst.imapmcp.core.audit;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;

/** Who and what of one audited tool call; {@code account} is null when the call has none. */
public record CallInfo(TenantId tenant, AccountId account, String requestId, String tool) {
}
