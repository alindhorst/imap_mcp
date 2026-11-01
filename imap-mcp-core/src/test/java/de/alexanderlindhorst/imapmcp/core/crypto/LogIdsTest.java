package de.alexanderlindhorst.imapmcp.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogIdsTest {

    private static LogIds ids(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return new LogIds(new KeyRing(1, Map.of(1, k)));
    }

    @Test
    void is12LowercaseHexAndDeterministic() {
        String h = ids(1).tenant("t-1");
        assertThat(h).matches("[0-9a-f]{12}");
        assertThat(ids(1).tenant("t-1")).isEqualTo(h);
        assertThat(ids(1).account("a-1")).matches("[0-9a-f]{12}");
        assertThat(ids(1).tenant("t-2")).isNotEqualTo(h);
    }

    @Test
    void differsAcrossKeys() {
        assertThat(ids(1).tenant("t-1")).isNotEqualTo(ids(2).tenant("t-1"));
    }

    @Test
    void tenantAndAccountAreDomainSeparated() {
        assertThat(ids(1).tenant("same")).isNotEqualTo(ids(1).account("same"));
    }

    @Test
    void doesNotContainRawId() {
        assertThat(ids(1).tenant("secret-tenant")).doesNotContain("secret");
    }
}
