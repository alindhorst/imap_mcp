package de.alexanderlindhorst.imapmcp.core.account;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Derives the opaque client-facing account handle. Handles are not signed; security comes from the
 * tenant-scoped repository lookup.
 */
public final class AccountHandles {

    public static final int SALT_LENGTH = 16;

    private static final byte[] DOMAIN = "imap-mcp:acct".getBytes(StandardCharsets.UTF_8);
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";
    private static final int ENCODED_LENGTH = 26;
    private static final Pattern FORMAT = Pattern.compile("^acc_[a-z2-7]{26}$");

    private AccountHandles() {
    }

    /** {@code acc_ + base32lower(SHA-256("imap-mcp:acct" || salt || lp(tenant) || lp(account)))[0..26]}. */
    public static String derive(byte[] installationSalt, TenantId tenant, AccountId account) {
        Objects.requireNonNull(installationSalt, "installationSalt");
        if (installationSalt.length != SALT_LENGTH) {
            throw new IllegalArgumentException("installation salt must be " + SALT_LENGTH + " bytes");
        }
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        sha256.update(DOMAIN);
        sha256.update(installationSalt);
        lengthPrefixed(sha256, tenant.value());
        lengthPrefixed(sha256, account.value());
        return "acc_" + base32LowerNoPad(sha256.digest()).substring(0, ENCODED_LENGTH);
    }

    /** Strict format check; call before any lookup. */
    public static boolean isValid(String handle) {
        return handle != null && FORMAT.matcher(handle).matches();
    }

    private static void lengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String base32LowerNoPad(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }
}
