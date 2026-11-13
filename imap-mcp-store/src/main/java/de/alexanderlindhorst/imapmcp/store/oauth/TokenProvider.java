package de.alexanderlindhorst.imapmcp.store.oauth;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.net.URI;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * What the token client needs of a configured upstream provider (SPEC 5.7): the token endpoint, the client id and
 * the client secret, read at use (an {@code env:} or {@code file:} reference); the caller of the supplier owns and
 * wipes the returned secret.
 */
public record TokenProvider(String id, URI tokenEndpoint, String clientId, Supplier<Secret> clientSecret) {

    public TokenProvider {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
    }

    @Override
    public String toString() {
        return "TokenProvider[" + id + "]";
    }
}
