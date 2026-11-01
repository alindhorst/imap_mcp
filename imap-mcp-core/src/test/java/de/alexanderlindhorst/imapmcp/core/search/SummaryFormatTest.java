package de.alexanderlindhorst.imapmcp.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Flags;
import jakarta.mail.internet.InternetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummaryFormatTest {

    @Test
    void sentAtKeepsOriginalOffset() {
        assertThat(SummaryFormat.sentAt("Date: Thu, 5 Mar 2026 10:00:07 +0100\r\n\r\n"))
                .isEqualTo("2026-03-05T10:00:07+01:00");
        assertThat(SummaryFormat.sentAt("Date: Thu, 5 Mar 2026 10:00:07 -0530\r\n\r\n"))
                .isEqualTo("2026-03-05T10:00:07-05:30");
        assertThat(SummaryFormat.sentAt("Date: Thu, 5 Mar 2026 10:00:07 GMT\r\n\r\n"))
                .isEqualTo("2026-03-05T10:00:07Z");
    }

    @Test
    void sentAtIgnoresTrailingCommentFoldingAndHeaderCase() {
        assertThat(SummaryFormat.sentAt("DATE: Thu, 5 Mar 2026 10:00:07 +0000 (UTC)\r\n\r\n"))
                .isEqualTo("2026-03-05T10:00:07Z");
        assertThat(SummaryFormat.sentAt("Date: Thu, 5 Mar 2026\r\n 10:00:07 +0200\r\n\r\n"))
                .isEqualTo("2026-03-05T10:00:07+02:00");
    }

    @Test
    void sentAtIsNullWhenAbsentOrUnparseable() {
        assertThat(SummaryFormat.sentAt(null)).isNull();
        assertThat(SummaryFormat.sentAt("")).isNull();
        assertThat(SummaryFormat.sentAt("Subject: x\r\n\r\n")).isNull();
        assertThat(SummaryFormat.sentAt("Date: yesterday-ish\r\n\r\n")).isNull();
        assertThat(SummaryFormat.sentAt("Date: " + "9".repeat(500) + "\r\n\r\n")).isNull();
    }

    @Test
    void flagsUseImapSpellingSystemFirstThenSortedKeywordsSanitized() {
        Flags f = new Flags();
        f.add(Flags.Flag.SEEN);
        f.add(Flags.Flag.FLAGGED);
        f.add("zeta");
        f.add("alpha");
        f.add("evil\u202eflag");
        assertThat(SummaryFormat.flags(f)).containsExactly("\\Seen", "\\Flagged", "alpha", "evilflag", "zeta");
        assertThat(SummaryFormat.flags(new Flags())).isEmpty();
    }

    @Test
    void addressSplitsNameAndAddressAndSanitizes() throws Exception {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "Al  Ice"); // control character becomes a space
        expected.put("address", "al@example.org");
        assertThat(SummaryFormat.address(new InternetAddress("al@example.org", "Al\u0007 Ice"))).isEqualTo(expected);
        expected.put("name", null);
        assertThat(SummaryFormat.address(new InternetAddress("al@example.org"))).isEqualTo(expected);
    }

    @Test
    void addressCapsNameAndAddress() throws Exception {
        Map<String, Object> m = SummaryFormat.address(new InternetAddress("a@example.org", "n".repeat(500)));
        assertThat(((String) m.get("name")).codePointCount(0, ((String) m.get("name")).length())).isEqualTo(200);
    }

    @Test
    void addressListIsLimitedAndReportsTruncation() throws Exception {
        InternetAddress[] twelve = new InternetAddress[12];
        for (int i = 0; i < 12; i++) {
            twelve[i] = new InternetAddress("u" + i + "@example.org");
        }
        SummaryFormat.AddressList l = SummaryFormat.addresses(twelve);
        assertThat(l.entries()).hasSize(10);
        assertThat(l.truncated()).isTrue();
        assertThat(SummaryFormat.addresses(null).entries()).isEqualTo(List.of());
        assertThat(SummaryFormat.addresses(null).truncated()).isFalse();
    }
}
