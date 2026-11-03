package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import java.util.Objects;

/**
 * Configured IMAP account. {@code address} may be null. {@code toString} omits label, address, username and
 * secret reference so accidental logging stays clean.
 * {@code readOnly} (config {@code accounts[].readOnly}) blocks write tools for this account (SPEC 5.3).
 */
public record MailAccount(AccountId id, String label, String address, String host, int port, Security security,
        Flavor flavor, String username, SecretRef secretRef, boolean readOnly) {

    public enum Security { IMPLICIT_TLS, STARTTLS }

    public enum Flavor { GENERIC, GMAIL }

    /** Writable account ({@code readOnly=false}). */
    public MailAccount(AccountId id, String label, String address, String host, int port, Security security,
            Flavor flavor, String username, SecretRef secretRef) {
        this(id, label, address, host, port, security, flavor, username, secretRef, false);
    }

    public MailAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(flavor, "flavor");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(secretRef, "secretRef");
    }

    @Override
    public String toString() {
        return "MailAccount[id=" + id.value() + ", host=" + host + ", port=" + port + ", security=" + security
                + ", flavor=" + flavor + ", readOnly=" + readOnly + "]";
    }
}
