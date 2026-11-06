package de.alexanderlindhorst.imapmcp.store.crypto;

import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import java.util.Objects;
import java.util.regex.Pattern;

/** How the Vault client authenticates (SPEC 5.7, {@code kms.vault.auth.*}). The references are never resolved here. */
public sealed interface VaultAuth {

    /** A Vault token read from a secret reference at first use and re-read once after a {@code 401}/{@code 403}. */
    record Token(SecretRef token) implements VaultAuth {

        public Token {
            Objects.requireNonNull(token, "token");
        }
    }

    /** AppRole login at {@code auth/<path>/login}, again before the client token expires. */
    record AppRole(String path, SecretRef roleId, SecretRef secretId) implements VaultAuth {

        private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_-]{1,64}(/[A-Za-z0-9_-]{1,64})*");

        public AppRole {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(roleId, "roleId");
            Objects.requireNonNull(secretId, "secretId");
            if (!PATH.matcher(path).matches()) {
                throw new IllegalArgumentException("kms.vault.auth.path is malformed");
            }
        }
    }
}
