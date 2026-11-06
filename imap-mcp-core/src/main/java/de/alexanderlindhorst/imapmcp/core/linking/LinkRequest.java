package de.alexanderlindhorst.imapmcp.core.linking;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;

/**
 * The fields of the link form except the password, which travels separately and is wiped by the caller. Values
 * are as submitted; {@link AccountLinking} validates them. {@code toString} shows no field.
 */
public record LinkRequest(String label, String host, int port, Security security, String username,
        boolean readOnly, boolean allowPurge) {

    @Override
    public String toString() {
        return "LinkRequest[port=" + port + ", readOnly=" + readOnly + ", allowPurge=" + allowPurge + "]";
    }
}
