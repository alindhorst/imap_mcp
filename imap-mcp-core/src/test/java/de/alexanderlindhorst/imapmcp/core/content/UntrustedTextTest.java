package de.alexanderlindhorst.imapmcp.core.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UntrustedTextTest {

    @Test
    void singleLineReplacesControlsAndStripsInvisibles() {
        assertThat(UntrustedText.singleLine("a\r\nb\tc", 10)).isEqualTo("a b c"); // runs of controls collapse into one space
        assertThat(UntrustedText.singleLine("a\u202Eb\u200Bc\uFEFFd\u2066e\u2069f", 10)).isEqualTo("abcdef");
    }

    @Test
    void singleLineTruncatesAtCodePointBoundary() {
        String emoji = "\uD83D\uDE00"; // one code point, two chars
        String out = UntrustedText.singleLine(emoji.repeat(10), 5);
        assertThat(out.codePointCount(0, out.length())).isEqualTo(5);
        assertThat(out).isEqualTo(emoji.repeat(4) + "\u2026");
        assertThat(UntrustedText.singleLine("abcdef", 6)).isEqualTo("abcdef");
        assertThat(UntrustedText.singleLine("abcdefg", 6)).isEqualTo("abcde\u2026");
    }

    @Test
    void bodyTextNormalizesLineEndingsAndStripsControls() {
        assertThat(UntrustedText.bodyText("a\r\nb\rc")).isEqualTo("a\nb\nc");
        assertThat(UntrustedText.bodyText("a\tb\nc")).isEqualTo("a\tb\nc");
        assertThat(UntrustedText.bodyText("a\u0007b\u202Ec\u2069d")).isEqualTo("abcd");
    }

    @Test
    void nullAndEmpty() {
        assertThat(UntrustedText.singleLine(null, 5)).isNull();
        assertThat(UntrustedText.singleLine("", 5)).isEmpty();
        assertThat(UntrustedText.bodyText(null)).isNull();
        assertThat(UntrustedText.bodyText("")).isEmpty();
    }

    @Test
    void constants() {
        assertThat(UntrustedText.SUBJECT_MAX).isEqualTo(1000);
        assertThat(UntrustedText.DISPLAY_NAME_MAX).isEqualTo(200);
        assertThat(UntrustedText.ADDRESS_MAX).isEqualTo(320);
        assertThat(UntrustedText.FILENAME_MAX).isEqualTo(255);
        assertThat(UntrustedText.HEADER_VALUE_MAX).isEqualTo(2000);
        assertThat(UntrustedText.UPSTREAM_ERROR_MAX).isEqualTo(200);
        assertThat(UntrustedText.CONTENT_NOTICE).startsWith("Values inside `untrusted` objects");
    }

    // ---- #135 sanitizer: invisible and line-separator code points ----

    private static String cps(int... cps) {
        return new String(cps, 0, cps.length);
    }

    private static List<Integer> invisibles() {
        List<Integer> l = new ArrayList<>(List.of(0x200E, 0x200F, 0x061C, 0x200B, 0x2060, 0xFEFF, 0x0890, 0x0891,
                0x2065, 0x00AD, 0x034F, 0x180E, 0x3164, 0xFFA0, 0xFFF9));
        for (int c = 0x202A; c <= 0x202E; c++) {
            l.add(c);
        }
        for (int c = 0x2066; c <= 0x2069; c++) {
            l.add(c);
        }
        for (int c = 0xE0000; c <= 0xE007F; c++) {
            l.add(c);
        }
        return l;
    }

    @Test
    void singleLineTurnsEveryLineSeparatorIntoOneSpace() {
        assertThat(UntrustedText.singleLine("a\u2028b", 10)).isEqualTo("a b");
        assertThat(UntrustedText.singleLine("a\u2029b", 10)).isEqualTo("a b");
        assertThat(UntrustedText.singleLine("a\u0085b", 10)).isEqualTo("a b");
        assertThat(UntrustedText.singleLine("a\nb\rc", 10)).isEqualTo("a b c");
    }

    @Test
    void singleLineCollapsesRunsOfControlsAndWhiteSpaceAndTrims() {
        assertThat(UntrustedText.singleLine("a \t\r\n b", 10)).isEqualTo("a b");
        assertThat(UntrustedText.singleLine("a\u00A0　 \u0085\u007F\u009Bb", 10)).isEqualTo("a b");
        assertThat(UntrustedText.singleLine("  a  ", 10)).isEqualTo("a");
        assertThat(UntrustedText.singleLine(" \t\n ", 10)).isEmpty();
    }

    @Test
    void bothRemoveInvisibleAndFormatCodePoints() {
        for (int cp : invisibles()) {
            String in = "a" + cps(cp) + "b";
            assertThat(UntrustedText.singleLine(in, 10)).as("singleLine U+%X", cp).isEqualTo("ab");
            assertThat(UntrustedText.bodyText(in)).as("bodyText U+%X", cp).isEqualTo("ab");
        }
    }

    @Test
    void removingSupplementaryCodePointsLeavesNoLoneSurrogate() {
        String smile = "😀";
        String in = smile + cps(0xE0041, 0xE0042) + smile + cps(0xE007F);
        for (String out : List.of(UntrustedText.singleLine(in, 10), UntrustedText.bodyText(in))) {
            assertThat(out).isEqualTo(smile + smile);
            out.codePoints().forEach(c -> assertThat(Character.isSurrogate((char) c) && c < 0x10000).isFalse());
        }
    }

    @Test
    void bodyTextStripsDelAndC1ButKeepsTabAndLf() {
        assertThat(UntrustedText.bodyText("a\u007Fb")).isEqualTo("ab");
        for (int c = 0x80; c <= 0x9F; c++) {
            if (c != 0x85) {
                assertThat(UntrustedText.bodyText("a" + (char) c + "b")).as("U+%X", c).isEqualTo("ab");
            }
        }
        assertThat(UntrustedText.bodyText("a\u009Bb")).isEqualTo("ab");
        assertThat(UntrustedText.bodyText("a\tb\nc")).isEqualTo("a\tb\nc");
    }

    @Test
    void bodyTextNormalizesAllLineBreaksToLf() {
        assertThat(UntrustedText.bodyText("a\u0085b\u2028c\u2029d\r\ne\rf")).isEqualTo("a\nb\nc\nd\ne\nf");
    }

    @Test
    void visibleTextIsUnchanged() {
        String visible = "Grüße 日本語 😀 שלום مرحبا";
        assertThat(UntrustedText.singleLine(visible, 100)).isEqualTo(visible);
        assertThat(UntrustedText.bodyText(visible)).isEqualTo(visible);
    }

    @Test
    void capsHoldAfterStripping() {
        String in = ("a" + cps(0xE0041) + "\u200B").repeat(50);
        String out = UntrustedText.singleLine(in, 10);
        assertThat(out.codePointCount(0, out.length())).isLessThanOrEqualTo(10);
        assertThat(UntrustedText.singleLine("x".repeat(20), 10)).isEqualTo("x".repeat(9) + "…");
        String hidden = ("a" + cps(0xE0041)).repeat(5);
        assertThat(UntrustedText.singleLine(hidden, 5)).isEqualTo("aaaaa"); // stripped text fits exactly
    }

    @Test
    void bidiOverrideInFilenameIsRemoved() {
        assertThat(UntrustedText.singleLine("invoice\u202Efdp.exe", 255)).isEqualTo("invoicefdp.exe");
    }

    @Test
    void zwnjAndZwjSurviveOnlyInContextAtMostOnce() {
        assertThat(UntrustedText.singleLine("a\u200C\u200Cb", 10)).isEqualTo("a\u200Cb");
        assertThat(UntrustedText.singleLine("a\u200D\u200Cb", 10)).isEqualTo("a\u200Db");
        assertThat(UntrustedText.singleLine("\u200Cab", 10)).isEqualTo("ab");
        assertThat(UntrustedText.singleLine("a \u200Db", 10)).isEqualTo("a b");
        assertThat(UntrustedText.bodyText("a\u200C\u200Cb")).isEqualTo("a\u200Cb");
        assertThat(UntrustedText.bodyText("\u200Dab")).isEqualTo("ab");
        // a removed code point is not a base: the joiner after it has none either
        assertThat(UntrustedText.singleLine(" \u200B\u200Db", 10)).isEqualTo("b");
    }

    @Test
    void legitimateJoinerTextRoundTrips() {
        List<String> samples = List.of(
                "می\u200Cخواهم", // Persian with ZWNJ
                "क्\u200Dष", // Devanagari virama + ZWJ
                "ශ්\u200Dරී", // Sinhala
                "👨\u200D👩\u200D👧", // family
                "❤️\u200D🔥", // heart on fire
                "🏳️\u200D🌈", // rainbow flag
                "1️⃣"); // keycap
        for (String sample : samples) {
            assertThat(UntrustedText.singleLine(sample, 100)).isEqualTo(sample);
            assertThat(UntrustedText.bodyText(sample)).isEqualTo(sample);
        }
    }

    @Test
    void variationSelectorsAreLimitedToOneEmojiPresentationSelectorAfterABase() {
        String smile = "😀";
        String ivs = cps(0xE0100).repeat(20);
        String in = smile + ivs + "️".repeat(20);
        assertThat(UntrustedText.singleLine(in, 100)).isEqualTo(smile + "️");
        assertThat(UntrustedText.bodyText(in)).isEqualTo(smile + "️");
        assertThat(UntrustedText.singleLine("a︀᠋᠍᠏b", 10)).isEqualTo("ab");
        assertThat(UntrustedText.singleLine("️a", 10)).isEqualTo("a");
        assertThat(UntrustedText.singleLine("a︎", 10)).isEqualTo("a︎");
    }

    @Test
    void noncharactersAreRemovedAndPrivateUseIsKept() {
        for (int cp : new int[] {0xFFFE, 0x1FFFF, 0x10FFFF, 0xFDD0, 0xFDEF, 0xFFFF}) {
            assertThat(UntrustedText.singleLine("a" + cps(cp) + "b", 10)).as("U+%X", cp).isEqualTo("ab");
            assertThat(UntrustedText.bodyText("a" + cps(cp) + "b")).as("U+%X", cp).isEqualTo("ab");
        }
        for (int cp : new int[] {0xE000, 0xF8FF, 0xF0000}) {
            String in = "a" + cps(cp) + "b";
            assertThat(UntrustedText.singleLine(in, 10)).as("U+%X", cp).isEqualTo(in);
            assertThat(UntrustedText.bodyText(in)).as("U+%X", cp).isEqualTo(in);
        }
    }

    @Test
    void loneSurrogatesBecomeReplacementCharacter() {
        assertThat(UntrustedText.singleLine("a\uD800b", 10)).isEqualTo("a�b");
        assertThat(UntrustedText.singleLine("a\uDC00", 10)).isEqualTo("a�");
        assertThat(UntrustedText.bodyText("a\uD800")).isEqualTo("a�");
        // U+FFFD counts as a base for a following joiner
        assertThat(UntrustedText.singleLine("\uD800\u200D", 10)).isEqualTo("�\u200D");
    }

    @Test
    void capKeepsZwjSequencesWholeOrDropsTheDanglingJoiner() {
        String seq = "x👨\u200D👩y"; // 5 code points
        assertThat(UntrustedText.singleLine(seq, 5)).isEqualTo(seq);
        String cut = UntrustedText.singleLine(seq, 4); // keeps x, man, ZWJ -> joiner dangling -> dropped
        assertThat(cut).isEqualTo("x👨…");
        assertThat(cut.codePointCount(0, cut.length())).isLessThanOrEqualTo(4);
        assertThat(UntrustedText.singleLine(seq, 3)).isEqualTo("x👨…");
    }

    /** The strip table is fixed (Unicode 16); a newer JDK must not introduce Cf code points we do not know. */
    @Test
    void everyJdkFormatCharacterIsStrippedOrAContextualJoiner() {
        List<String> unknown = new ArrayList<>();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (Character.getType(cp) == Character.FORMAT && !UntrustedText.isStripped(cp) && cp != 0x200C
                    && cp != 0x200D) {
                unknown.add(Integer.toHexString(cp));
            }
        }
        assertThat(unknown).isEmpty();
    }
}
