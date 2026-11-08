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

    /** #262 / SPEC 5.6: hashed ids use the signing key's log subkey, not that of any other key in the ring. */
    @Test
    void usesTheSigningKeysLogSubkeyOfARingWithSeveralKeys() {
        byte[] k1 = new byte[32];
        byte[] k2 = new byte[32];
        Arrays.fill(k1, (byte) 1);
        Arrays.fill(k2, (byte) 2);
        LogIds signing2 = new LogIds(new KeyRing(2, Map.of(1, k1, 2, k2)));
        LogIds signing1 = new LogIds(new KeyRing(1, Map.of(1, k1, 2, k2)));
        assertThat(signing2.tenant("t-1")).isEqualTo(ids(2).tenant("t-1"));
        assertThat(signing1.tenant("t-1")).isEqualTo(ids(1).tenant("t-1")).isNotEqualTo(signing2.tenant("t-1"));
    }

    @Test
    void tenantAndAccountAreDomainSeparated() {
        assertThat(ids(1).tenant("same")).isNotEqualTo(ids(1).account("same"));
    }

    @Test
    void doesNotContainRawId() {
        assertThat(ids(1).tenant("secret-tenant")).doesNotContain("secret");
    }

    private static byte[] be32(String s) {
        byte[] u = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.nio.ByteBuffer.allocate(4 + u.length).putInt(u.length).put(u).array();
    }

    /** Computed independently of LogIds: HMAC-SHA256 under the log subkey over "principal:" and two length-prefixed parts. */
    private static String expectedPrincipal(KeyRing ring, String issuer, String subject) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(ring.activeSubkey(KeyRing.LOG), "HmacSHA256"));
        mac.update("principal:".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        mac.update(be32(issuer));
        mac.update(be32(subject));
        return java.util.HexFormat.of().formatHex(mac.doFinal()).substring(0, 12);
    }

    @Test
    void thePrincipalHashFollowsTheSpecEncodingAndIsDomainSeparated() throws Exception {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) 7);
        KeyRing ring = new KeyRing(1, Map.of(1, k));
        LogIds ids = new LogIds(ring);
        for (String[] p : new String[][] {{"https://idp.example/realms/x", "sub-123"}, {"i", "s"},
                {"https://idp.example/\u00e4\u00f6", "\u00fcser:1"}}) {
            assertThat(ids.principal(p[0], p[1])).matches("[0-9a-f]{12}").isEqualTo(expectedPrincipal(ring, p[0], p[1]));
        }
        assertThat(ids.principal("ab", "c")).isNotEqualTo(ids.principal("a", "bc")); // length prefixes
        assertThat(ids.principal("t", "")).isNotEqualTo(ids.tenant("t")); // domain label
    }
}
