package de.alexanderlindhorst.imapmcp.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HkdfTest {

    private static final HexFormat HEX = HexFormat.of();

    /** RFC 5869 appendix A.1 (SHA-256). */
    @Test
    void matchesRfc5869TestVector1() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");

        byte[] prk = Hkdf.extract(salt, ikm);
        assertThat(HEX.formatHex(prk))
                .isEqualTo("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        assertThat(HEX.formatHex(Hkdf.expand(prk, info, 42)))
                .isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865");
    }
}
