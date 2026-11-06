package de.alexanderlindhorst.imapmcp.core.download;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * In-memory sink of {@code http-public} (SPEC 4): the decoded bytes of one attachment, counted, hashed and capped at
 * {@code tools.download.inlineMaxBytes}. Beyond the cap {@link #write} fails with {@link DownloadTooLargeException}
 * and keeps nothing of the excess. One instance per call, not thread-safe. Never writes a file and never logs.
 */
public final class MemorySink implements DecodedSink {

    private static final int INITIAL = 64 * 1024;

    private final long maxBytes;
    private final MessageDigest digest;
    private byte[] buf;
    private int size;

    public MemorySink(long maxBytes) {
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("maxBytes");
        }
        this.maxBytes = maxBytes;
        this.buf = new byte[(int) Math.min(maxBytes, INITIAL)];
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) {
        if ((long) size + len > maxBytes) {
            throw new DownloadTooLargeException();
        }
        if (size + len > buf.length) {
            buf = Arrays.copyOf(buf, (int) Math.min(maxBytes, Math.max((long) buf.length * 2, (long) size + len)));
        }
        System.arraycopy(b, off, buf, size, len);
        digest.update(b, off, len);
        size += len;
    }

    public long size() {
        return size;
    }

    /** The collected bytes (a copy). */
    public byte[] bytes() {
        return Arrays.copyOf(buf, size);
    }

    /** Lowercase hex SHA-256 of the collected bytes. */
    public String sha256() {
        try {
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }
}
