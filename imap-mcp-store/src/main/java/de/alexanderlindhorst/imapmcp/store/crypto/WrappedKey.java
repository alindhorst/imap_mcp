package de.alexanderlindhorst.imapmcp.store.crypto;

import java.util.Objects;

/** A wrapped DEK and the id of the key-encryption key that wrapped it. */
public record WrappedKey(String kekId, byte[] bytes) {

    public WrappedKey {
        Objects.requireNonNull(kekId, "kekId");
        Objects.requireNonNull(bytes, "bytes");
    }

    @Override
    public String toString() {
        return "WrappedKey[" + kekId + "]";
    }
}
