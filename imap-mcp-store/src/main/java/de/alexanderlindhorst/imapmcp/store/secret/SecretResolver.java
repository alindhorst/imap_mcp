package de.alexanderlindhorst.imapmcp.store.secret;

import de.alexanderlindhorst.imapmcp.core.account.Secret;

/** Strategy for one reference scheme; {@code target} is the part after {@code scheme:}. */
public interface SecretResolver {

    /** Startup check; must not return or log the secret. */
    void validate(String target);

    /** Reads the secret now (lazily, per call). */
    Secret resolve(String target);
}
