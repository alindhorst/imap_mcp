package de.alexanderlindhorst.imapmcp.store.oauth;

import java.util.Optional;

/** The configured upstream providers by id; empty when the id is no longer configured. */
@FunctionalInterface
public interface TokenProviders {

    Optional<TokenProvider> find(String providerId);
}
