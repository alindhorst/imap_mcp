package de.alexanderlindhorst.imapmcp.core.handle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles.Decoded;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MessageHandlesTest {

    private static final TenantId T = new TenantId("owner");
    private static final AccountId A = new AccountId("acc1");
    private static final Decoded INVALID = new Decoded.Invalid();

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    private static final KeyRing RING = new KeyRing(2, Map.of(1, key(0x11), 2, key(0x22)));
    private static final MessageHandles HANDLES = new MessageHandles(RING);
    private static final MessageRef REF = new MessageRef("INBOX", 12345L, 678L);

    private static String cp(int codePoint) {
        return Character.toString(codePoint);
    }

    private static byte[] raw(String handle) {
        return Base64.getUrlDecoder().decode(handle.substring(3));
    }

    private static String wrap(byte[] raw) {
        return "m1." + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    @Test
    void roundTripIncludingNonAsciiFolder() {
        for (String folder : new String[] {"INBOX", "Entw" + cp(0xFC) + "rfe/" + cp(0xC4) + "rger", cp(0x65E5) + cp(0x672C) + cp(0x8A9E), "a b/c.d"}) {
            MessageRef ref = new MessageRef(folder, 4294967295L, 4294967295L);
            String h = HANDLES.encode(T, A, ref);
            assertThat(h).startsWith("m1.").doesNotContain("=");
            assertThat(HANDLES.decode(T, A, h)).isEqualTo(new Decoded.Ok(ref));
        }
    }

    @Test
    void encodingIsDeterministicAndUsesActiveKid() {
        String h = HANDLES.encode(T, A, REF);
        assertThat(HANDLES.encode(T, A, REF)).isEqualTo(h);
        assertThat(raw(h)[0] & 0xFF).isEqualTo(2);
    }

    @Test
    void wireLayoutMatchesSpec() {
        byte[] r = raw(HANDLES.encode(T, A, REF));
        assertThat(r).hasSize(1 + 4 + 4 + 2 + 5 + 16);
        assertThat(java.nio.ByteBuffer.wrap(r, 1, 4).getInt()).isEqualTo(12345);
        assertThat(java.nio.ByteBuffer.wrap(r, 5, 4).getInt()).isEqualTo(678);
        assertThat(java.nio.ByteBuffer.wrap(r, 9, 2).getShort()).isEqualTo((short) 5);
        assertThat(new String(r, 11, 5, StandardCharsets.UTF_8)).isEqualTo("INBOX");
    }

    @Test
    void oldConfiguredKidStillVerifiesAndNewHandlesUseActiveKid() {
        MessageHandles old = new MessageHandles(new KeyRing(1, Map.of(1, key(0x11))));
        String h = old.encode(T, A, REF);
        assertThat(raw(h)[0]).isEqualTo((byte) 1);
        assertThat(HANDLES.decode(T, A, h)).isEqualTo(new Decoded.Ok(REF));
    }

    static Stream<Arguments> tampered() {
        String good = HANDLES.encode(T, A, REF);
        byte[] r = raw(good);
        byte[] payloadFlip = r.clone();
        payloadFlip[6] ^= 0x01;
        byte[] tagFlip = r.clone();
        tagFlip[r.length - 1] ^= 0x01;
        byte[] unknownKid = r.clone();
        unknownKid[0] = 9;
        byte[] extra = Arrays.copyOf(r, r.length + 1);
        byte[] bigFolderRaw = new byte[1 + 4 + 4 + 2 + 1001 + 16];
        bigFolderRaw[10] = (byte) (1001 & 0xFF);
        bigFolderRaw[9] = (byte) (1001 >> 8);
        Arrays.fill(bigFolderRaw, 11, 11 + 1001, (byte) 'a');
        String padded = good + "=";
        // non-canonical: last char changed so the unused trailing bits are non-zero but data bits stay identical
        String body = good.substring(3);
        char last = body.charAt(body.length() - 1);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int idx = alphabet.indexOf(last);
        String nonCanonical = null;
        if (body.length() % 4 != 0) {
            String cand = "m1." + body.substring(0, body.length() - 1) + alphabet.charAt(idx ^ 1);
            nonCanonical = cand;
        }
        Stream.Builder<Arguments> b = Stream.builder();
        b.add(Arguments.of("wrong prefix", "m2." + good.substring(3), T, A));
        b.add(Arguments.of("no prefix", good.substring(3), T, A));
        b.add(Arguments.of("bad base64", "m1.!!!not-base64!!!", T, A));
        b.add(Arguments.of("truncated", good.substring(0, good.length() - 8), T, A));
        b.add(Arguments.of("empty body", "m1.", T, A));
        b.add(Arguments.of("flipped payload bit", wrap(payloadFlip), T, A));
        b.add(Arguments.of("flipped tag bit", wrap(tagFlip), T, A));
        b.add(Arguments.of("unknown kid", wrap(unknownKid), T, A));
        b.add(Arguments.of("other tenant", good, new TenantId("other"), A));
        b.add(Arguments.of("other account", good, T, new AccountId("acc2")));
        b.add(Arguments.of("trailing byte", wrap(extra), T, A));
        b.add(Arguments.of("folder over 1000 bytes", wrap(bigFolderRaw), T, A));
        b.add(Arguments.of("over 1400 chars", "m1." + "A".repeat(1400), T, A));
        b.add(Arguments.of("padding", padded, T, A));
        b.add(Arguments.of("null", null, T, A));
        if (nonCanonical != null) {
            b.add(Arguments.of("non-canonical base64", nonCanonical, T, A));
        }
        return b.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tampered")
    void everyFailureIsTheSameInvalidResultWithIdenticalPayload(String name, String handle, TenantId t, AccountId a) {
        Decoded result = HANDLES.decode(t, a, handle);
        assertThat(result).isEqualTo(INVALID);
        assertThat(ToolError.invalidHandle("rid").toMap()).isEqualTo(ToolError.invalidHandle("rid").toMap());
    }

    @Test
    void folderLimitIs1000Bytes() {
        String ok = cp(0xE4).repeat(500);
        assertThat(ok.getBytes(StandardCharsets.UTF_8)).hasSize(1000);
        MessageRef ref = new MessageRef(ok, 1, 1);
        assertThat(HANDLES.decode(T, A, HANDLES.encode(T, A, ref))).isEqualTo(new Decoded.Ok(ref));
        MessageRef tooBig = new MessageRef(ok + "a", 1, 1);
        assertThatThrownBy(() -> HANDLES.encode(T, A, tooBig)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageRefRejectsOutOfRangeNumbers() {
        assertThatThrownBy(() -> new MessageRef("INBOX", 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageRef("INBOX", 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageRef("INBOX", 1, 4294967296L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageRef("INBOX", -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageRef("", 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handleDoesNotContainTenantOrAccountOnTheWire() {
        String text = new String(raw(HANDLES.encode(T, A, REF)), StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("owner").doesNotContain("acc1");
    }
}
