package de.alexanderlindhorst.imapmcp.store.crypto;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code local} key wrapper (D6): AES-256-GCM under a KEK read by reference. For self-hosting and tests; a WARN at
 * startup says so. Wrapped form: 96-bit nonce followed by ciphertext and tag, bound to tenant and account as AAD.
 * Older KEKs stay in the map so rows wrapped before a rotation still unwrap.
 */
public final class LocalKeyWrapper implements KeyWrapper {

    private static final Logger LOG = LoggerFactory.getLogger(LocalKeyWrapper.class);
    private static final byte[] AAD_PREFIX = "imap-mcp:kek:v1".getBytes(StandardCharsets.US_ASCII);
    private static final int KEK_LEN = 32;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;

    private final String currentKekId;
    private final Map<String, byte[]> keks;
    private final SecureRandom random = new SecureRandom();

    public LocalKeyWrapper(String currentKekId, Map<String, byte[]> keks) {
        Objects.requireNonNull(currentKekId, "currentKekId");
        Map<String, byte[]> copy = new HashMap<>();
        keks.forEach((id, key) -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("local KEK id must not be blank");
            }
            if (key == null || key.length != KEK_LEN) {
                throw new IllegalArgumentException("local KEK must be " + KEK_LEN + " bytes");
            }
            copy.forEach((otherId, other) -> {
                if (Arrays.equals(other, key)) {
                    throw new IllegalArgumentException("the same local KEK is configured under two ids");
                }
            });
            copy.put(id, key.clone());
        });
        if (!copy.containsKey(currentKekId)) {
            throw new IllegalArgumentException("current KEK id is not among the configured KEKs");
        }
        this.currentKekId = currentKekId;
        this.keks = Map.copyOf(copy);
    }

    /** One KEK by reference; see {@link #fromReferences}. */
    public static LocalKeyWrapper fromReference(String kekId, SecretRef ref, Function<SecretRef, Secret> resolver) {
        return fromReferences(kekId, Map.of(kekId, ref), resolver);
    }

    /**
     * Reads every KEK (base64url without padding, 32 bytes) through {@code resolver}, so a rotation also works by
     * reference, and logs one startup WARN. Resolver failures propagate unchanged; format errors say only that the
     * KEK is malformed, never the reference or the value.
     */
    public static LocalKeyWrapper fromReferences(String currentKekId, Map<String, SecretRef> refs,
            Function<SecretRef, Secret> resolver) {
        Map<String, byte[]> keys = new HashMap<>();
        try {
            refs.forEach((id, ref) -> keys.put(id, readKek(ref, resolver)));
            LOG.warn("Credentials are wrapped with a local KEK read from a reference. Use it for self-hosting and"
                    + " tests; a KMS or Vault Transit is the production choice.");
            return new LocalKeyWrapper(currentKekId, keys);
        } finally {
            keys.values().forEach(k -> Arrays.fill(k, (byte) 0));
        }
    }

    private static byte[] readKek(SecretRef ref, Function<SecretRef, Secret> resolver) {
        byte[] ascii;
        try (Secret secret = resolver.apply(ref)) {
            char[] chars = secret.chars();
            ascii = new byte[chars.length];
            for (int i = 0; i < chars.length; i++) {
                if (chars[i] > 0x7e || chars[i] == '=') { // no padding, ASCII only
                    Arrays.fill(ascii, (byte) 0);
                    throw new IllegalArgumentException("local KEK is not base64url without padding");
                }
                ascii[i] = (byte) chars[i];
            }
        }
        try {
            return Base64.getUrlDecoder().decode(ascii);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("local KEK is not base64url without padding");
        } finally {
            Arrays.fill(ascii, (byte) 0);
        }
    }

    @Override
    public WrappedKey wrap(byte[] dek, String tenantId, String accountId) {
        byte[] nonce = new byte[NONCE_LEN];
        random.nextBytes(nonce);
        try {
            Cipher c = cipher(Cipher.ENCRYPT_MODE, keks.get(currentKekId), nonce, tenantId, accountId);
            byte[] sealed = c.doFinal(dek);
            byte[] out = new byte[NONCE_LEN + sealed.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
            System.arraycopy(sealed, 0, out, NONCE_LEN, sealed.length);
            return new WrappedKey(currentKekId, out);
        } catch (GeneralSecurityException e) {
            throw new KeyWrapException("key wrap failed");
        }
    }

    @Override
    public byte[] unwrap(String kekId, byte[] wrapped, String tenantId, String accountId) {
        byte[] kek = keks.get(kekId);
        if (kek == null || wrapped.length <= NONCE_LEN) {
            throw new KeyWrapException("key unwrap failed");
        }
        try {
            byte[] nonce = Arrays.copyOfRange(wrapped, 0, NONCE_LEN);
            Cipher c = cipher(Cipher.DECRYPT_MODE, kek, nonce, tenantId, accountId);
            return c.doFinal(wrapped, NONCE_LEN, wrapped.length - NONCE_LEN);
        } catch (GeneralSecurityException e) {
            throw new KeyWrapException("key unwrap failed");
        }
    }

    private static Cipher cipher(int mode, byte[] kek, byte[] nonce, String tenantId, String accountId)
            throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        c.updateAAD(AAD_PREFIX);
        updateLengthPrefixed(c, tenantId);
        updateLengthPrefixed(c, accountId);
        return c;
    }

    private static void updateLengthPrefixed(Cipher c, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        c.updateAAD(new byte[] {(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16),
                (byte) (bytes.length >>> 8), (byte) bytes.length});
        c.updateAAD(bytes);
    }

    @Override
    public String toString() {
        return "LocalKeyWrapper[" + currentKekId + "]";
    }
}
