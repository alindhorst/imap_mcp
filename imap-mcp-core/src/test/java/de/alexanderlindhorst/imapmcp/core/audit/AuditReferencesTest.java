package de.alexanderlindhorst.imapmcp.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.Macs;
import de.alexanderlindhorst.imapmcp.core.crypto.UnknownKidException;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditReferencesTest {

    static byte[] key(int seed) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) seed);
        return k;
    }

    final KeyRing ring = new KeyRing(2, Map.of(1, key(1), 2, key(2)));
    final AuditReferences refs = new AuditReferences(ring);
    final TenantId tenant = new TenantId("t1");
    final AccountId account = new AccountId("acc-1");
    final ItemRef item = new ItemRef("INBOX", 7, 42);

    @Test
    void hashIs16BytesDeterministicAndCarriesTheSigningKeyId() {
        AuditReferences.Ref a = refs.ref(tenant, account, item);
        AuditReferences.Ref b = refs.ref(tenant, account, item);
        assertThat(a.hash()).hasSize(16).isEqualTo(b.hash());
        assertThat(a.keyId()).isEqualTo(2);
    }

    @Test
    void everyInputChangesTheHash() {
        byte[] base = refs.ref(tenant, account, item).hash();
        assertThat(refs.ref(new TenantId("t2"), account, item).hash()).isNotEqualTo(base);
        assertThat(refs.ref(tenant, new AccountId("acc-2"), item).hash()).isNotEqualTo(base);
        assertThat(refs.ref(tenant, account, new ItemRef("Sent", 7, 42)).hash()).isNotEqualTo(base);
        assertThat(refs.ref(tenant, account, new ItemRef("INBOX", 8, 42)).hash()).isNotEqualTo(base);
        assertThat(refs.ref(tenant, account, new ItemRef("INBOX", 7, 43)).hash()).isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesAreUnambiguous() {
        byte[] a = refs.ref(new TenantId("ab"), new AccountId("c"), item).hash();
        byte[] b = refs.ref(new TenantId("a"), new AccountId("bc"), item).hash();
        assertThat(a).isNotEqualTo(b);
        byte[] f1 = refs.ref(tenant, account, new ItemRef("a", 1, 1)).hash();
        byte[] f2 = refs.ref(tenant, new AccountId("acc-1"), new ItemRef("a", 1, 1)).hash();
        assertThat(f1).isEqualTo(f2);
    }

    @Test
    void usesTheAuditSubkeyAndNotTheHandleOrLogSubkey() {
        byte[] audit = ring.subkey(2, KeyRing.AUDIT);
        assertThat(audit).isNotEqualTo(ring.subkey(2, KeyRing.MSG_HANDLE)).isNotEqualTo(ring.subkey(2, KeyRing.LOG));
        // independent reconstruction of the documented encoding
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        writeBytes(out, "imap-mcp:audit:v1".getBytes(StandardCharsets.UTF_8));
        writeBytes(out, "t1".getBytes(StandardCharsets.UTF_8));
        writeBytes(out, "acc-1".getBytes(StandardCharsets.UTF_8));
        writeBytes(out, "INBOX".getBytes(StandardCharsets.UTF_8));
        out.writeBytes(java.nio.ByteBuffer.allocate(16).putLong(7).putLong(42).array());
        byte[] expected = Arrays.copyOf(Macs.hmac(audit, out.toByteArray()), 16);
        assertThat(refs.ref(tenant, account, item).hash()).isEqualTo(expected);
    }

    private static void writeBytes(java.io.ByteArrayOutputStream out, byte[] b) {
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());
        out.writeBytes(b);
    }

    @Test
    void oldRowsStayMatchableUnderTheirKeyUntilTheKeyIsRemoved() {
        byte[] underOne = refs.refUnder(1, tenant, account, item).hash();
        assertThat(underOne).isNotEqualTo(refs.ref(tenant, account, item).hash());
        assertThat(refs.refUnder(1, tenant, account, item).keyId()).isEqualTo(1);
        AuditReferences rotated = new AuditReferences(new KeyRing(2, Map.of(2, key(2))));
        assertThatThrownBy(() -> rotated.refUnder(1, tenant, account, item)).isInstanceOf(UnknownKidException.class);
    }

    @Test
    void hashesNeverContainTheInputs() {
        byte[] h = refs.ref(tenant, account, new ItemRef("canary-folder", 7, 42)).hash();
        assertThat(new String(h, StandardCharsets.ISO_8859_1)).doesNotContain("canary");
    }
}
