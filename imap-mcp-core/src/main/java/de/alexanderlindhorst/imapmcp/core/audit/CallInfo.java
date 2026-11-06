package de.alexanderlindhorst.imapmcp.core.audit;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;

/**
 * Who and what of one audited tool call; {@code account} is null when the call has none (unresolved or foreign
 * account handles never get an account id here). {@code confirmation} (a purge's {@code elicited},
 * {@code session_approval} or the refusal's elicitation value) is known before the rows are written, so it is
 * written with them; null otherwise.
 */
public record CallInfo(TenantId tenant, AccountId account, String requestId, String tool, String confirmation) {

    public CallInfo(TenantId tenant, AccountId account, String requestId, String tool) {
        this(tenant, account, requestId, tool, null);
    }

    public CallInfo withConfirmation(String confirmation) {
        return new CallInfo(tenant, account, requestId, tool, confirmation);
    }
}
