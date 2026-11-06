package de.alexanderlindhorst.imapmcp.store.crypto;

/**
 * A stored credential could not be decrypted (copied or tampered row, wrong KEK). Never falls back; the tool call
 * ends as INTERNAL_ERROR. The message is fixed and carries no id or key material, and there is no cause.
 */
public class CredentialDecryptException extends RuntimeException {

    public CredentialDecryptException() {
        super("credential could not be decrypted");
    }
}
