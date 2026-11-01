package de.alexanderlindhorst.imapmcp.store.secret;

import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Credential store for {@code env:} and {@code file:} references of the fixed owning tenant. Other schemes
 * (for example {@code keyring:}) plug in through the resolver map; without a resolver they fail validation.
 */
public final class RefCredentialStore implements CredentialStore {

    private static final String KEYRING = "keyring";

    private final TenantId owner;
    private final Map<AccountId, SecretRef> refs = new HashMap<>();
    private final Map<String, SecretResolver> resolvers;

    public RefCredentialStore(TenantId owner, List<MailAccount> accounts, Map<String, SecretResolver> resolvers) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.resolvers = Map.copyOf(resolvers);
        accounts.forEach(a -> refs.put(a.id(), a.secretRef()));
    }

    /** Standard {@code env:} and {@code file:} resolvers with the given environment. */
    public static RefCredentialStore withEnv(TenantId owner, List<MailAccount> accounts, Map<String, String> env) {
        return new RefCredentialStore(owner, accounts,
                Map.of("env", new EnvSecretResolver(env), "file", new FileSecretResolver()));
    }

    /** Startup validation; throws {@link SecretResolutionException} without revealing any value. */
    public void validate(SecretRef ref) {
        Target t = target(ref);
        t.resolver().validate(t.target());
    }

    /** Reads the secret now. The caller closes it. */
    public Secret resolve(SecretRef ref) {
        Target t = target(ref);
        return t.resolver().resolve(t.target());
    }

    @Override
    public Optional<Secret> resolve(TenantId tenant, AccountId account) {
        if (!owner.equals(tenant)) {
            return Optional.empty();
        }
        return Optional.ofNullable(refs.get(account)).map(this::resolve);
    }

    private record Target(SecretResolver resolver, String target) {
    }

    private Target target(SecretRef ref) {
        String value = ref.value();
        int colon = value.indexOf(':');
        String scheme = colon > 0 ? value.substring(0, colon) : "";
        SecretResolver resolver = resolvers.get(scheme);
        if (resolver != null) {
            return new Target(resolver, value.substring(colon + 1));
        }
        if (scheme.equals(KEYRING)) {
            throw new SecretResolutionException("keyring not available here; use env: or file:");
        }
        throw new SecretResolutionException(
                "secret reference must start with env:, file: or keyring:; literal secrets are not allowed");
    }
}
