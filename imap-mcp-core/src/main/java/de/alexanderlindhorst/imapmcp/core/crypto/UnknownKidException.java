package de.alexanderlindhorst.imapmcp.core.crypto;

/** A signed value names a key id the ring does not hold. Callers map this to INVALID_HANDLE / INVALID_CURSOR. */
public final class UnknownKidException extends RuntimeException {

    private final int kid;

    public UnknownKidException(int kid) {
        super("unknown kid " + kid);
        this.kid = kid;
    }

    public int kid() {
        return kid;
    }
}
