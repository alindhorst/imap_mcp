package de.alexanderlindhorst.imapmcp.core.download;

/** In-memory sink of {@code http-public} (SPEC 4): decoded bytes in, base64 text out. */
public final class MemorySink implements DecodedSink {

    public MemorySink(long maxBytes, long expectedBytes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(byte[] b, int off, int len) {
        throw new UnsupportedOperationException();
    }

    /** Decoded bytes received. */
    public long size() {
        throw new UnsupportedOperationException();
    }

    /** Lowercase hex SHA-256 of the decoded bytes. */
    public String sha256() {
        throw new UnsupportedOperationException();
    }

    /** The base64 text of everything written (built once). */
    public String base64() {
        throw new UnsupportedOperationException();
    }
}
