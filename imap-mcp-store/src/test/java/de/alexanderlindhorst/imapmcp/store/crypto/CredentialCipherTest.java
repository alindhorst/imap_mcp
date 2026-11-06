package de.alexanderlindhorst.imapmcp.store.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String ACCOUNT = "acct-one";

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    private static CredentialCipher cipher() {
        return new CredentialCipher(new LocalKeyWrapper("local-1", Map.of("local-1", key(7))), new SecureRandom());
    }

    @Test
    void aadMatchesTheTestVector() {
        // "imap-mcp:cred:v1" || len("t1") "t1" || len("a1") "a1" || len("1") "1", lengths as 4-byte big endian
        String expected = "696d61702d6d63703a637265643a7631" + "00000002" + "7431" + "00000002" + "6131"
                + "00000001" + "31";

        assertThat(HexFormat.of().formatHex(CredentialCipher.aad("t1", "a1", 1))).isEqualTo(expected);
    }

    @Test
    void aadEncodesNonAsciiIdsAsUtf8WithByteLengths() {
        // "ä" is two bytes in UTF-8, so the prefix is 00000002, not 00000001
        String expected = "696d61702d6d63703a637265643a7631" + "00000002" + "c3a4" + "00000001" + "62"
                + "00000002" + "3132";

        assertThat(HexFormat.of().formatHex(CredentialCipher.aad("ä", "b", 12))).isEqualTo(expected);
    }

    @Test
    void sealedValuesHaveTheSpecifiedSizesAndDoNotContainThePlaintext() {
        CredentialCipher.Sealed sealed = cipher().seal(TENANT, ACCOUNT, 1, "hunter2-password".toCharArray());

        assertThat(sealed.kekId()).isEqualTo("local-1");
        assertThat(sealed.nonce()).hasSize(12);
        assertThat(sealed.ciphertext()).hasSize("hunter2-password".length() + 16); // 128-bit tag
        assertThat(new String(sealed.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("hunter2");
    }

    @Test
    void everySealUsesAFreshNonceAndDek() {
        CredentialCipher c = cipher();
        CredentialCipher.Sealed a = c.seal(TENANT, ACCOUNT, 1, "same".toCharArray());
        CredentialCipher.Sealed b = c.seal(TENANT, ACCOUNT, 1, "same".toCharArray());

        assertThat(a.nonce()).isNotEqualTo(b.nonce());
        assertThat(a.wrappedDek()).isNotEqualTo(b.wrappedDek());
        assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
    }

    @Test
    void roundTripReturnsAWipeableSecret() {
        CredentialCipher c = cipher();
        CredentialCipher.Sealed sealed = c.seal(TENANT, ACCOUNT, 3, "pässw0rd".toCharArray());

        Secret secret = c.open(TENANT, ACCOUNT, 3, sealed);
        char[] live = secret.chars();
        assertThat(new String(live)).isEqualTo("pässw0rd");
        secret.close();

        assertThat(live).containsOnly('\0');
    }

    @Test
    void sealDoesNotKeepOrChangeTheCallersArray() {
        char[] plain = "keep-me".toCharArray();
        cipher().seal(TENANT, ACCOUNT, 1, plain);

        assertThat(new String(plain)).isEqualTo("keep-me"); // the caller owns and wipes it
    }

    @Test
    void aRowCopiedToAnotherTenantOrAccountOrVersionDoesNotDecrypt() {
        CredentialCipher c = cipher();
        CredentialCipher.Sealed sealed = c.seal(TENANT, ACCOUNT, 1, "pw".toCharArray());

        assertThatThrownBy(() -> c.open(OTHER_TENANT, ACCOUNT, 1, sealed))
                .isInstanceOf(CredentialDecryptException.class);
        assertThatThrownBy(() -> c.open(TENANT, "acct-two", 1, sealed)).isInstanceOf(CredentialDecryptException.class);
        assertThatThrownBy(() -> c.open(TENANT, ACCOUNT, 2, sealed)).isInstanceOf(CredentialDecryptException.class);
    }

    @Test
    void aTamperedCiphertextNonceOrWrappedDekDoesNotDecrypt() {
        CredentialCipher c = cipher();
        CredentialCipher.Sealed s = c.seal(TENANT, ACCOUNT, 1, "pw".toCharArray());

        assertThatThrownBy(() -> c.open(TENANT, ACCOUNT, 1,
                new CredentialCipher.Sealed(s.kekId(), s.wrappedDek(), s.nonce(), flip(s.ciphertext()))))
                .isInstanceOf(CredentialDecryptException.class);
        assertThatThrownBy(() -> c.open(TENANT, ACCOUNT, 1,
                new CredentialCipher.Sealed(s.kekId(), s.wrappedDek(), flip(s.nonce()), s.ciphertext())))
                .isInstanceOf(CredentialDecryptException.class);
        assertThatThrownBy(() -> c.open(TENANT, ACCOUNT, 1,
                new CredentialCipher.Sealed(s.kekId(), flip(s.wrappedDek()), s.nonce(), s.ciphertext())))
                .isInstanceOf(CredentialDecryptException.class);
    }

    @Test
    void aWrongOrUnknownKekDoesNotDecryptAndFailureTextHasNoSecretOrKey() {
        CredentialCipher.Sealed s = cipher().seal(TENANT, ACCOUNT, 1, "pw-canary".toCharArray());
        CredentialCipher wrongKek = new CredentialCipher(
                new LocalKeyWrapper("local-1", Map.of("local-1", key(9))), new SecureRandom());
        CredentialCipher otherId = new CredentialCipher(
                new LocalKeyWrapper("local-2", Map.of("local-2", key(7))), new SecureRandom());

        assertThatThrownBy(() -> wrongKek.open(TENANT, ACCOUNT, 1, s)).isInstanceOf(CredentialDecryptException.class)
                .hasMessageNotContaining("pw-canary").hasMessageNotContaining(TENANT);
        assertThatThrownBy(() -> otherId.open(TENANT, ACCOUNT, 1, s)).isInstanceOf(CredentialDecryptException.class);
    }

    private static byte[] flip(byte[] in) {
        byte[] out = in.clone();
        out[0] ^= 1;
        return out;
    }

    @Test
    void anUnavailableKeyServiceIsNotReportedAsTampering() {
        CredentialCipher.Sealed sealed = cipher().seal(TENANT, ACCOUNT, 1, "pw".toCharArray());
        KeyWrapper down = new KeyWrapper() {
            @Override
            public WrappedKey wrap(byte[] dek, String tenantId, String accountId) {
                throw new KeyServiceUnavailableException();
            }

            @Override
            public byte[] unwrap(String kekId, byte[] wrapped, String tenantId, String accountId) {
                throw new KeyServiceUnavailableException();
            }
        };
        CredentialCipher c = new CredentialCipher(down, new SecureRandom());

        assertThatThrownBy(() -> c.open(TENANT, ACCOUNT, 1, sealed)).isInstanceOf(KeyServiceUnavailableException.class)
                .isNotInstanceOf(CredentialDecryptException.class);
    }

    @Test
    void anUnknownKekIdInTheRowDoesNotDecrypt() {
        CredentialCipher c = cipher();
        CredentialCipher.Sealed s = c.seal(TENANT, ACCOUNT, 1, "pw".toCharArray());

        assertThatThrownBy(() -> c.open(TENANT, ACCOUNT, 1,
                new CredentialCipher.Sealed("gone", s.wrappedDek(), s.nonce(), s.ciphertext())))
                .isInstanceOf(CredentialDecryptException.class);
    }
}
