package de.alexanderlindhorst.imapmcp.store.crypto;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope encryption of one credential (SPEC 5.3): a random 256-bit DEK per credential version, a random 96-bit
 * nonce, AES-256-GCM with a 128-bit tag. The AAD is {@code "imap-mcp:cred:v1"} followed by the length-prefixed
 * (4-byte big endian) UTF-8 tenant id, account id and decimal credential version, so a row copied to another
 * tenant, account or version does not decrypt. The DEK is wrapped by a {@link KeyWrapper}.
 */
public final class CredentialCipher {

    /** The stored form: columns {@code kek_id}, {@code wrapped_dek}, {@code nonce}, {@code ciphertext}. */
    public record Sealed(String kekId, byte[] wrappedDek, byte[] nonce, byte[] ciphertext) {

        public Sealed {
            Objects.requireNonNull(kekId, "kekId");
            Objects.requireNonNull(wrappedDek, "wrappedDek");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(ciphertext, "ciphertext");
        }

        @Override
        public String toString() {
            return "Sealed[" + kekId + "]";
        }
    }

    private static final byte[] AAD_PREFIX = "imap-mcp:cred:v1".getBytes(StandardCharsets.US_ASCII);
    private static final int DEK_LEN = 32;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;

    private final KeyWrapper wrapper;
    private final SecureRandom random;

    public CredentialCipher(KeyWrapper wrapper, SecureRandom random) {
        this.wrapper = Objects.requireNonNull(wrapper, "wrapper");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** The exact AAD bytes (public for the test vector). */
    public static byte[] aad(String tenantId, String accountId, int version) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(AAD_PREFIX);
        for (String part : new String[] {tenantId, accountId, Integer.toString(version)}) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(new byte[] {(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16),
                    (byte) (bytes.length >>> 8), (byte) bytes.length});
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    /** Encrypts; does not retain or modify {@code plaintext} (the caller wipes it). */
    public Sealed seal(String tenantId, String accountId, int version, char[] plaintext) {
        byte[] dek = new byte[DEK_LEN];
        byte[] nonce = new byte[NONCE_LEN];
        byte[] plain = null;
        try {
            random.nextBytes(dek);
            random.nextBytes(nonce);
            plain = toBytes(plaintext);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(tenantId, accountId, version));
            byte[] ciphertext = c.doFinal(plain);
            WrappedKey wrapped = wrapper.wrap(dek, tenantId, accountId);
            return new Sealed(wrapped.kekId(), wrapped.bytes(), nonce, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("credential encryption failed");
        } finally {
            Arrays.fill(dek, (byte) 0);
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    /** Decrypts into a {@link Secret} that the caller closes (wipes); {@link CredentialDecryptException} on any failure. */
    public Secret open(String tenantId, String accountId, int version, Sealed sealed) {
        byte[] dek = null;
        byte[] plain = null;
        try {
            dek = wrapper.unwrap(sealed.kekId(), sealed.wrappedDek(), tenantId, accountId);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            c.updateAAD(aad(tenantId, accountId, version));
            plain = c.doFinal(sealed.ciphertext());
            return new Secret(toChars(plain));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new CredentialDecryptException();
        } finally {
            if (dek != null) {
                Arrays.fill(dek, (byte) 0);
            }
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    private static byte[] toBytes(char[] chars) {
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        Arrays.fill(bb.array(), (byte) 0); // the encoder's own buffer
        return out;
    }

    private static char[] toChars(byte[] bytes) {
        CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        char[] out = new char[cb.remaining()];
        cb.get(out);
        Arrays.fill(cb.array(), '\0');
        return out;
    }
}
