package de.alexanderlindhorst.imapmcp.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeyRingTest {

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    @Test
    void subkeysDifferPerPurposeAndAreDeterministic() {
        KeyRing ring = new KeyRing(1, Map.of(1, key(0x11)));
        byte[] handle = ring.subkey(1, KeyRing.MSG_HANDLE);
        assertThat(handle).hasSize(32);
        assertThat(ring.subkey(1, KeyRing.CURSOR)).isNotEqualTo(handle);
        assertThat(ring.subkey(1, KeyRing.LOG)).isNotEqualTo(handle);
        assertThat(ring.subkey(1, KeyRing.LOG)).isNotEqualTo(ring.subkey(1, KeyRing.CURSOR));
        assertThat(ring.subkey(1, KeyRing.MSG_HANDLE)).isEqualTo(handle);
        assertThat(new KeyRing(1, Map.of(1, key(0x11))).subkey(1, KeyRing.MSG_HANDLE)).isEqualTo(handle);
        assertThat(new KeyRing(1, Map.of(1, key(0x22))).subkey(1, KeyRing.MSG_HANDLE)).isNotEqualTo(handle);
        assertThat(KeyRing.PART_REF).isEqualTo("part-ref");
    }

    @Test
    void activeKidSignsAndAllKidsVerify() {
        KeyRing ring = new KeyRing(2, Map.of(1, key(1), 2, key(2)));
        assertThat(ring.activeKid()).isEqualTo(2);
        assertThat(ring.activeSubkey(KeyRing.CURSOR)).isEqualTo(ring.subkey(2, KeyRing.CURSOR));
        assertThat(ring.subkey(1, KeyRing.CURSOR)).isNotEqualTo(ring.subkey(2, KeyRing.CURSOR));
    }

    @Test
    void unknownKidIsReportedDistinctly() {
        KeyRing ring = new KeyRing(1, Map.of(1, key(1)));
        assertThatThrownBy(() -> ring.subkey(7, KeyRing.CURSOR))
                .isInstanceOf(UnknownKidException.class)
                .hasMessageContaining("unknown kid");
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new KeyRing(1, Map.of(2, key(1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyRing(1, Map.of(1, new byte[16]))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeyRing(256, Map.of(256, key(1)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringAndErrorsContainNoKeyMaterial() {
        byte[] k = key(0x5a);
        KeyRing ring = new KeyRing(1, Map.of(1, k));
        String hex = HexFormat.of().formatHex(k);
        assertThat(ring.toString()).doesNotContain(hex).doesNotContain("5a5a").doesNotContain(Arrays.toString(k));
        assertThatThrownBy(() -> new KeyRing(1, Map.of(1, new byte[]{0x5a, 0x5a})))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("5a").doesNotContain("90"));
    }

    @Test
    void constructorCopiesKeys() {
        byte[] k = key(3);
        KeyRing ring = new KeyRing(1, Map.of(1, k));
        byte[] before = ring.subkey(1, KeyRing.LOG);
        Arrays.fill(k, (byte) 9);
        assertThat(ring.subkey(1, KeyRing.LOG)).isEqualTo(before);
    }

    @Test
    void constantTimeEquals() {
        assertThat(Macs.constantTimeEquals(new byte[]{1, 2}, new byte[]{1, 2})).isTrue();
        assertThat(Macs.constantTimeEquals(new byte[]{1, 2}, new byte[]{1, 3})).isFalse();
        assertThat(Macs.hmac(key(1), new byte[]{1})).hasSize(32);
    }
}
