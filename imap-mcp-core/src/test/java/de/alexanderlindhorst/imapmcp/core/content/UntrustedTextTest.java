package de.alexanderlindhorst.imapmcp.core.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UntrustedTextTest {

    @Test
    void singleLineReplacesControlsAndStripsInvisibles() {
        assertThat(UntrustedText.singleLine("a\r\nb\tc", 10)).isEqualTo("a  b c");
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
}
