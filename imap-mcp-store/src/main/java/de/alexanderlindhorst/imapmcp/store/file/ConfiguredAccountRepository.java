package de.alexanderlindhorst.imapmcp.store.file;

import de.alexanderlindhorst.imapmcp.core.account.AccountHandles;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
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
    private final List<ResolvedAccount> accounts;
    private final Map<String, ResolvedAccount> byHandle;
    private final Map<AccountId, ResolvedAccount> byId;

    public ConfiguredAccountRepository(TenantId owner, List<MailAccount> accounts, byte[] installationSalt) {
        this.owner = Objects.requireNonNull(owner, "owner");
        List<MailAccount> configured = List.copyOf(accounts);
        List<ResolvedAccount> resolved = new java.util.ArrayList<>();
        Map<String, ResolvedAccount> handles = new HashMap<>();
        Map<AccountId, ResolvedAccount> ids2 = new HashMap<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (MailAccount account : configured) {
            if (!ids.add(account.id().value())) {
                throw new IllegalArgumentException("duplicate account id in configuration: " + account.id().value());
            }
            String handle = AccountHandles.derive(installationSalt, owner, account.id());
            ResolvedAccount r = new ResolvedAccount(owner, account, handle);
            handles.put(handle, r);
            ids2.put(account.id(), r);
            resolved.add(r);
        }
        this.accounts = List.copyOf(resolved);
        this.byHandle = Map.copyOf(handles);
        this.byId = Map.copyOf(ids2);
    }

    @Override
    public List<ResolvedAccount> findByTenant(TenantId tenant) {
        return owner.equals(tenant) ? accounts : List.of();
    }

    @Override
    public Optional<ResolvedAccount> findByTenantAndHandle(TenantId tenant, String handle) {
        if (!owner.equals(tenant) || !AccountHandles.isValid(handle)) {
            return Optional.empty();
        }
        return Optional.ofNullable(byHandle.get(handle));
    }

    @Override
    public Optional<ResolvedAccount> findByTenantAndId(TenantId tenant, AccountId id) {
        if (!owner.equals(tenant) || id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id));
    }
}
