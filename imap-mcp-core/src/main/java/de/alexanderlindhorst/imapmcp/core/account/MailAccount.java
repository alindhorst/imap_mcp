package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import java.util.Objects;

/**
 * Configured IMAP account. {@code address} may be null. {@code toString} omits label, address, username and
 * secret reference so accidental logging stays clean.
 * {@code readOnly} (config {@code accounts[].readOnly}) blocks write tools for this account (SPEC 5.3).
 * {@code allowPurge} (config {@code accounts[].allowPurge}, default false) is the operator's switch for
 * {@code purge_messages}; read per call from this record (SPEC 5.3).
 * {@code auth}, {@code status} and {@code revision} (SPEC 5.5, v3b) default to {@code PASSWORD}, {@code ACTIVE}
 * and 0 for configured accounts; a repository raises {@code revision} whenever a change must make pooled
 * connections obsolete (credentials, host, status).
 * {@code providerId} (v0.29, SPEC 5.7) names the configured upstream OAuth provider of an {@code XOAUTH2} account;
 * null for password accounts.
 */
public record MailAccount(AccountId id, String label, String address, String host, int port, Security security,
        Flavor flavor, String username, SecretRef secretRef, boolean readOnly,
        boolean allowPurge, Auth auth, Status status, long revision, String providerId) {

    public enum Security { IMPLICIT_TLS, STARTTLS }

    public enum Auth { PASSWORD, XOAUTH2 }

    public enum Status { ACTIVE, NEEDS_RELINK }

    /** Account with the defaults {@code PASSWORD}, {@code ACTIVE} and revision 0. */
    public MailAccount(AccountId id, String label, String address, String host, int port, Security security,
            Flavor flavor, String username, SecretRef secretRef, boolean readOnly, boolean allowPurge) {
        this(id, label, address, host, port, security, flavor, username, secretRef, readOnly, allowPurge,
                Auth.PASSWORD, Status.ACTIVE, 0L, null);
    }

    /** Account without provider id (password accounts). */
    public MailAccount(AccountId id, String label, String address, String host, int port, Security security,
            Flavor flavor, String username, SecretRef secretRef, boolean readOnly, boolean allowPurge, Auth auth,
            Status status, long revision) {
        this(id, label, address, host, port, security, flavor, username, secretRef, readOnly, allowPurge, auth,
                status, revision, null);
    }

    public enum Flavor { GENERIC, GMAIL }

    /** Account without purge permission ({@code allowPurge=false}). */
    public MailAccount(AccountId id, String label, String address, String host, int port, Security security,
            Flavor flavor, String username, SecretRef secretRef, boolean readOnly) {
        this(id, label, address, host, port, security, flavor, username, secretRef, readOnly, false);
    }

    /** Writable account ({@code readOnly=false}). */
    public MailAccount(AccountId id, String label, String address, String host, int port, Security security,
            Flavor flavor, String username, SecretRef secretRef) {
        this(id, label, address, host, port, security, flavor, username, secretRef, false, false);
    }

    public MailAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(flavor, "flavor");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(secretRef, "secretRef");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(status, "status");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }

    @Override
    public String toString() {
        return "MailAccount[id=" + id.value() + ", host=" + host + ", port=" + port + ", security=" + security
                + ", flavor=" + flavor + ", readOnly=" + readOnly + ", allowPurge=" + allowPurge + ", auth=" + auth
                + ", status=" + status
                + ", revision=" + revision + ", providerId=" + providerId + "]";
    }
}
