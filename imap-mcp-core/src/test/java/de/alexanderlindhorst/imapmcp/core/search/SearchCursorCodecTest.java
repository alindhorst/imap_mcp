package de.alexanderlindhorst.imapmcp.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.search.SearchCursorCodec.CriteriaMismatch;
import de.alexanderlindhorst.imapmcp.core.search.SearchCursorCodec.Decoded;
import de.alexanderlindhorst.imapmcp.core.search.SearchCursorCodec.Invalid;
import de.alexanderlindhorst.imapmcp.core.search.SearchCursorCodec.Ok;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchCursorCodecTest {

    private static final TenantId T = new TenantId("t1");
    private static final AccountId A = new AccountId("acc");
    private static final String F = "INBOX";
    private static final SearchCriteria C = SearchCriteria.empty().withSubject("hello");

    private static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    private final KeyRing ring = new KeyRing(1, Map.of(1, key(1), 2, key(2)));
    private final SearchCursorCodec codec = new SearchCursorCodec(ring);

    private String cursor() {
        return codec.encode(T, A, F, C, 0xFFFFFFFFL, 4_000_000_000L);
    }

    @Test
    void roundTrip() {
        String c = cursor();
        assertThat(c).startsWith("c1.").hasSize(47).doesNotContain("=");
        assertThat(codec.decode(T, A, F, C, c)).isEqualTo(new Ok(0xFFFFFFFFL, 4_000_000_000L));
        assertThat(codec.decode(T, A, F, C, codec.encode(T, A, F, C, 1, 0))).isEqualTo(new Ok(1, 0));
    }

    @Test
    void tamperedByteIsInvalidForEveryPosition() {
        byte[] raw = Base64.getUrlDecoder().decode(cursor().substring(3));
        for (int i = 0; i < raw.length; i++) {
            byte[] copy = raw.clone();
            copy[i] ^= 0x01;
            String tampered = "c1." + Base64.getUrlEncoder().withoutPadding().encodeToString(copy);
            assertThat(codec.decode(T, A, F, C, tampered)).as("byte " + i).isEqualTo(new Invalid());
        }
    }

    @Test
    void otherTenantAccountFolderAreInvalid() {
        String c = cursor();
        assertThat(codec.decode(new TenantId("t2"), A, F, C, c)).isEqualTo(new Invalid());
        assertThat(codec.decode(T, new AccountId("other"), F, C, c)).isEqualTo(new Invalid());
        assertThat(codec.decode(T, A, "Sent", C, c)).isEqualTo(new Invalid());
    }

    @Test
    void lengthPrefixPreventsFieldBoundaryShifting() {
        String c = codec.encode(new TenantId("ab"), new AccountId("c"), F, C, 1, 1);
        assertThat(codec.decode(new TenantId("a"), new AccountId("bc"), F, C, c)).isEqualTo(new Invalid());
    }

    @Test
    void unknownKidIsInvalid() {
        String c = new SearchCursorCodec(new KeyRing(2, Map.of(2, key(2)))).encode(T, A, F, C, 1, 1);
        assertThat(new SearchCursorCodec(new KeyRing(1, Map.of(1, key(1)))).decode(T, A, F, C, c))
                .isEqualTo(new Invalid());
    }

    @Test
    void oldKidStillVerifiesAfterRotation() {
        String c = new SearchCursorCodec(new KeyRing(1, Map.of(1, key(1)))).encode(T, A, F, C, 5, 6);
        assertThat(codec.decode(T, A, F, C, c)).isEqualTo(new Ok(5, 6));
    }

    @Test
    void malformedInputIsInvalid() {
        String c = cursor();
        String body = c.substring(3);
        for (String bad : new String[] {null, "", "c1.", "c2." + body, "C1." + body, body, c + "=", c + "A",
                c.substring(0, c.length() - 1), "c1." + body + "==", "c1.+" + body.substring(1),
                "c1." + body.replace('-', '+').replace('_', '/') + "!", " " + c, c + " ", "c1." + "A".repeat(300),
                "x".repeat(257)}) {
            assertThat(codec.decode(T, A, F, C, bad)).as(String.valueOf(bad)).isEqualTo(new Invalid());
        }
    }

    @Test
    void nonCanonicalBase64IsRejected() {
        // 33 bytes = 44 chars with 2 spare bits... 33 % 3 == 0, so no spare bits; use a 34-byte payload (46 chars).
        byte[] raw = Base64.getUrlDecoder().decode(cursor().substring(3));
        byte[] longer = Arrays.copyOf(raw, raw.length + 1);
        String enc = Base64.getUrlEncoder().withoutPadding().encodeToString(longer);
        assertThat(codec.decode(T, A, F, C, "c1." + enc)).isEqualTo(new Invalid());
        // flip the unused trailing bits of a 32-byte payload: decodes leniently, must still be rejected
        byte[] shorter = Arrays.copyOf(raw, raw.length - 1);
        String s = Base64.getUrlEncoder().withoutPadding().encodeToString(shorter);
        char last = s.charAt(s.length() - 1);
        String alt = s.substring(0, s.length() - 1) + (char) (last == 'A' ? 'B' : 'A');
        assertThat(codec.decode(T, A, F, C, "c1." + alt)).isEqualTo(new Invalid());
    }

    @Test
    void invalidResultsMapToIdenticalPayloadsWithoutDetails() {
        String c = cursor();
        Map<String, Object> expected = ToolError.invalidCursor("r").toMap();
        for (String bad : new String[] {c.substring(0, 20), "x".repeat(300), "c1.AAAA"}) {
            assertThat(codec.decode(T, A, F, C, bad)).isInstanceOf(Invalid.class);
            assertThat(((Invalid) codec.decode(T, A, F, C, bad)).toError("r").toMap()).isEqualTo(expected);
        }
        assertThat(expected.toString()).doesNotContain("details");
    }

    @Test
    void changedCriteriaIsCriteriaMismatchOnlyAfterMacVerified() {
        String c = cursor();
        Decoded d = codec.decode(T, A, F, SearchCriteria.empty().withSubject("other"), c);
        assertThat(d).isEqualTo(new CriteriaMismatch());
        assertThat(((CriteriaMismatch) d).toError("r").toMap().toString()).contains("criteria_mismatch");
        // different tenant AND different criteria: MAC fails first, so no mismatch signal
        assertThat(codec.decode(new TenantId("t2"), A, F, SearchCriteria.empty(), c)).isEqualTo(new Invalid());
    }

    @Test
    void equivalentCriteriaKeepCursorValid() {
        String c = codec.encode(T, A, F, SearchCriteria.empty().withSubject("hello").withKeywords(java.util.List.of("b", "a")), 3, 4);
        SearchCriteria same = SearchCriteria.empty().withSubject("hello").withKeywords(java.util.List.of("a", "b", "a"));
        assertThat(codec.decode(T, A, F, same, c)).isEqualTo(new Ok(3, 4));
    }

    @Test
    void pageSizeIsNotPartOfTheCriteria() {
        // SearchCriteria has no page_size/cursor component, so changing page_size cannot change the hash
        assertThat(Arrays.stream(SearchCriteria.class.getRecordComponents()).map(rc -> rc.getName()))
                .doesNotContain("pageSize", "cursor", "limit", "folder");
        assertThat(codec.decode(T, A, F, C, cursor())).isInstanceOf(Ok.class);
    }
}
