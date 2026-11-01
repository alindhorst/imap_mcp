package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Optional;

/** Source of account secrets, read lazily at connect time. The caller closes the returned {@link Secret}. */
public interface CredentialStore {

    Optional<Secret> resolve(TenantId tenant, AccountId account);
}
