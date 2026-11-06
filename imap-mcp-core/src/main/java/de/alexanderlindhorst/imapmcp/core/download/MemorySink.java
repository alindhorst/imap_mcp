package de.alexanderlindhorst.imapmcp.core.download;

/** In-memory sink of {@code http-public} (SPEC 4). */
public final class MemorySink implements DecodedSink {

    public MemorySink(long maxBytes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(byte[] b, int off, int len) {
        throw new UnsupportedOperationException();
    }

    public long size() {
        throw new UnsupportedOperationException();
    }

    /** The collected bytes (a copy). */
    public byte[] bytes() {
        throw new UnsupportedOperationException();
    }

    /** Lowercase hex SHA-256 of the collected bytes. */
    public String sha256() {
        throw new UnsupportedOperationException();
    }
}
