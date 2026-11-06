package de.alexanderlindhorst.imapmcp.core.download;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * In-memory sink of {@code http-public} (SPEC 4): the decoded bytes of one attachment go in, counted against the
 * cap and hashed, and are base64-encoded while streaming (0 to 2 bytes are carried between writes), so a call holds
 * the base64 buffer and, once, the finished {@link #base64() String}, never the decoded bytes as well. The buffer is
 * pre-sized from the expected size (rounded up to whole base64 groups, clamped to the cap) and only grows by what
 * is needed, never by doubling. Beyond {@code maxBytes} decoded bytes {@link #write} fails with
 * {@link DownloadTooLargeException}. One instance per call, not thread-safe. Never writes a file and never logs.
 */
public final class MemorySink implements DecodedSink {

    private static final byte[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes(StandardCharsets.ISO_8859_1);
    private static final int GROW_STEP = 64 * 1024;

    private final long maxBytes;
    private final long maxEncoded;
    private final MessageDigest digest;
    private final byte[] carry = new byte[3];
    private int carryLen;
    private byte[] out;
    private int outLen;
    private long decoded;
    private String finished;

    /** @param expectedBytes the decoded size expected (an estimate; a wrong one only costs a growth step) */
    public MemorySink(long maxBytes, long expectedBytes) {
        if (maxBytes < 1 || maxBytes > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("maxBytes");
        }
        this.maxBytes = maxBytes;
        this.maxEncoded = encodedLength(maxBytes);
        this.out = new byte[(int) encodedLength(Math.min(Math.max(expectedBytes, 0), maxBytes))];
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long encodedLength(long decodedBytes) {
        return 4 * ((decodedBytes + 2) / 3);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        if (finished != null) {
            throw new IllegalStateException("already finished");
        }
        if (decoded + len > maxBytes) {
            throw new DownloadTooLargeException();
        }
        digest.update(b, off, len);
        decoded += len;
        int i = off;
        int end = off + len;
        while (carryLen > 0 && carryLen < 3 && i < end) {
            carry[carryLen++] = b[i++];
        }
        if (carryLen == 3) {
            ensure(4);
            encode(carry, 0);
            carryLen = 0;
        }
        int groups = (end - i) / 3;
        if (groups > 0) {
            ensure(groups * 4);
            for (int g = 0; g < groups; g++, i += 3) {
                encode(b, i);
            }
        }
        while (i < end) {
            carry[carryLen++] = b[i++];
        }
    }

    private void encode(byte[] src, int p) {
        int v = ((src[p] & 0xff) << 16) | ((src[p + 1] & 0xff) << 8) | (src[p + 2] & 0xff);
        out[outLen++] = ALPHABET[v >>> 18];
        out[outLen++] = ALPHABET[(v >>> 12) & 63];
        out[outLen++] = ALPHABET[(v >>> 6) & 63];
        out[outLen++] = ALPHABET[v & 63];
    }

    private void ensure(int extra) {
        long need = (long) outLen + extra;
        if (need > out.length) {
            long target = Math.max(need, Math.min(maxEncoded, need + GROW_STEP));
            out = Arrays.copyOf(out, (int) target);
        }
    }

    /** Decoded bytes received. */
    public long size() {
        return decoded;
    }

    /** Lowercase hex SHA-256 of the decoded bytes. */
    public String sha256() {
        try {
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The base64 text (padded) of everything written; built once, after which the sink accepts no more bytes. */
    public String base64() {
        if (finished == null) {
            if (carryLen > 0) {
                ensure(4);
                for (int k = carryLen; k < 3; k++) {
                    carry[k] = 0;
                }
                int v = ((carry[0] & 0xff) << 16) | ((carry[1] & 0xff) << 8) | (carry[2] & 0xff);
                out[outLen++] = ALPHABET[v >>> 18];
                out[outLen++] = ALPHABET[(v >>> 12) & 63];
                out[outLen++] = carryLen == 2 ? ALPHABET[(v >>> 6) & 63] : (byte) '=';
                out[outLen++] = (byte) '=';
                carryLen = 0;
            }
            finished = new String(out, 0, outLen, StandardCharsets.ISO_8859_1);
            out = null;
        }
        return finished;
    }
}
