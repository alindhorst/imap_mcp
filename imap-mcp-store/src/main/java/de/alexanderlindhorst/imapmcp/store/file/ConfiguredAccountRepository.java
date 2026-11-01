package de.alexanderlindhorst.imapmcp.store.file;

import de.alexanderlindhorst.imapmcp.core.account.AccountHandles;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Account repository for the fixed single tenant, backed by a list supplied by the app (parsed from config
 * elsewhere). Immutable after construction; all other tenants see nothing.
 */
public final class ConfiguredAccountRepository implements AccountRepository {

    private final TenantId owner;
    private final List<MailAccount> accounts;
    private final Map<String, ResolvedAccount> byHandle;

    public ConfiguredAccountRepository(TenantId owner, List<MailAccount> accounts, byte[] installationSalt) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.accounts = List.copyOf(accounts);
        Map<String, ResolvedAccount> handles = new HashMap<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (MailAccount account : this.accounts) {
            if (!ids.add(account.id().value())) {
                throw new IllegalArgumentException("duplicate account id in configuration: " + account.id().value());
            }
            handles.put(AccountHandles.derive(installationSalt, owner, account.id()),
                    new ResolvedAccount(owner, account));
        }
        this.byHandle = Map.copyOf(handles);
    }

    @Override
    public List<MailAccount> findByTenant(TenantId tenant) {
        return owner.equals(tenant) ? accounts : List.of();
    }

    @Override
    public Optional<ResolvedAccount> findByTenantAndHandle(TenantId tenant, String handle) {
        if (!owner.equals(tenant) || !AccountHandles.isValid(handle)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byHandle.get(handle));
    }
}
