package de.alexanderlindhorst.imapmcp.store.crypto;

/**
 * Wraps and unwraps data encryption keys (SPEC 5.3, D6). Implementations: {@link LocalKeyWrapper}, Vault Transit
 * (C3), cloud KMS later. The tenant and account are the wrap context where the backend supports one.
 */
public interface KeyWrapper {

    /** Wraps under the current key; the result names the key (stored as {@code kek_id}). */
    WrappedKey wrap(byte[] dek, String tenantId, String accountId);

    /** Unwraps under the key named {@code kekId}; {@link KeyWrapException} when it cannot (wrong key or context). */
    byte[] unwrap(String kekId, byte[] wrapped, String tenantId, String accountId);
}
