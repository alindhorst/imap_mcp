package de.alexanderlindhorst.imapmcp.core.mime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeaderFieldsTest {

    static HeaderFields.Result parse(String raw) {
        return HeaderFields.parse(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void keepsMessageOrderAndDuplicates() {
        HeaderFields.Result r = parse("Received: a\r\nSubject: s\r\nReceived: b\r\n\r\n");
        assertThat(r.fields()).extracting(HeaderFields.Field::name).containsExactly("Received", "Subject", "Received");
        assertThat(r.fields()).extracting(HeaderFields.Field::value).containsExactly("a", "s", "b");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    void unfoldsContinuationLinesKeepingTheWhitespace() {
        HeaderFields.Result r = parse("X-Long: one\r\n two\r\n\tthree\r\nNext: n\r\n\r\n");
        assertThat(r.fields().get(0).value()).isEqualTo("one two\tthree");
        assertThat(r.fields().get(1).name()).isEqualTo("Next");
    }

    @Test
    void decodesRfc2047AndKeepsMalformedEncodedWordsRaw() {
        HeaderFields.Result r = parse("Subject: =?UTF-8?Q?Gr=C3=BC=C3=9Fe?=\r\nX-Bad: =?nope?Q?abc?=\r\n\r\n");
        assertThat(r.fields().get(0).value()).isEqualTo("Grüße");
        assertThat(r.fields().get(1).value()).isEqualTo("=?nope?Q?abc?=");
    }

    @Test
    void sanitizesControlCharactersAndBidiInNamesAndValues() {
        HeaderFields.Result r = parse("X-A: a\u0001b‮c\r\n\r\n");
        assertThat(r.fields().get(0).value()).isEqualTo("a bc");
    }

    @Test
    void skipsLinesWithoutAColon() {
        HeaderFields.Result r = parse("garbage line\r\nA: b\r\n\r\n");
        assertThat(r.fields()).extracting(HeaderFields.Field::name).containsExactly("A");
    }

    @Test
    void capsFieldCountAt100() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            sb.append("X-").append(i).append(": v\r\n");
        }
        HeaderFields.Result r = parse(sb.append("\r\n").toString());
        assertThat(r.fields()).hasSize(100);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void exactly100FieldsIsNotTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("X-").append(i).append(": v\r\n");
        }
        assertThat(parse(sb.toString()).truncated()).isFalse();
    }

    @Test
    void capsValueAt2000CodePointsAndFlagsTruncation() {
        HeaderFields.Result r = parse("X-Big: " + "a".repeat(5000) + "\r\n\r\n");
        assertThat(r.fields().get(0).value().codePointCount(0, r.fields().get(0).value().length())).isEqualTo(2000);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void capsTotalAt32000AndStopsAddingFields() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("X-").append(String.format("%02d", i)).append(": ").append("b".repeat(1900)).append("\r\n");
        }
        HeaderFields.Result r = parse(sb.toString());
        int total = r.fields().stream().mapToInt(f -> f.name().length() + f.value().length()).sum();
        assertThat(total).isLessThanOrEqualTo(32000);
        assertThat(r.fields().size()).isBetween(10, 17);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void emptyInputGivesNoFields() {
        assertThat(HeaderFields.parse(new byte[0]).fields()).isEqualTo(List.of());
    }
}
