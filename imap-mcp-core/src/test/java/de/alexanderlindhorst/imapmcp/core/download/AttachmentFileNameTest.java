package de.alexanderlindhorst.imapmcp.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC 5.4 "File name": the fixed sanitizer table. Non-ASCII and control inputs are written as escapes. */
class AttachmentFileNameTest {

    private static String rep(String s, int n) {
        return s.repeat(n);
    }

    static Stream<Arguments> table() {
        return Stream.of(
                Arguments.of("plain name", "report.pdf", "report.pdf"),
                Arguments.of("slash", "a/b.txt", "a_b.txt"),
                Arguments.of("backslash", "a\\b.txt", "a_b.txt"),
                Arguments.of("NUL", "a\0b.txt", "a_b.txt"),
                Arguments.of("tab and DEL and C1", "a\tb\u007fc\u0085d", "a_b_c_d"),
                Arguments.of("single dot", ".", "attachment-1"),
                Arguments.of("double dot", "..", "attachment-1"),
                Arguments.of("dots and spaces only", " . . ", "attachment-1"),
                Arguments.of("empty", "", "attachment-1"),
                Arguments.of("leading dot", ".bashrc", "bashrc"),
                Arguments.of("leading dots", "..hidden.txt", "hidden.txt"),
                Arguments.of("trailing dot", "file.", "file"),
                Arguments.of("space runs and trimming", "  my   file .txt ", "my file .txt"),
                Arguments.of("leading dash", "-rf", "_rf"),
                Arguments.of("two dashes", "--x", "_-x"),
                Arguments.of("reserved CON", "CON", "_CON"),
                Arguments.of("reserved con.txt", "con.txt", "_con.txt"),
                Arguments.of("reserved Com1.tar.gz", "Com1.tar.gz", "_Com1.tar.gz"),
                Arguments.of("reserved LPT9", "LPT9.x", "_LPT9.x"),
                Arguments.of("reserved NUL", "nul", "_nul"),
                Arguments.of("COM0 is not reserved", "COM0", "COM0"),
                Arguments.of("CONSOLE is not reserved", "CONSOLE.txt", "CONSOLE.txt"),
                Arguments.of("NFC composes", "naïve.txt", "naïve.txt"),
                Arguments.of("letters and numbers kept", "résumé 中文 ٣.pdf",
                        "résumé 中文 ٣.pdf"),
                Arguments.of("bidi override", "‮exe.txt", "_exe.txt"),
                Arguments.of("zero width space", "a​b", "a_b"),
                Arguments.of("no-break space is not a space", "a b", "a_b"),
                Arguments.of("emoji is one underscore", "x😀.txt", "x_.txt"),
                Arguments.of("lone surrogate", "a\ud800b", "a_b"),
                Arguments.of("punctuation outside the set", "a:b*c?d;e&f'g\"h<i>j|k.txt", "a_b_c_d_e_f_g_h_i_j_k.txt"),
                Arguments.of("allowed punctuation", "a+b(1)[2],@c_d-e.txt", "a+b(1)[2],@c_d-e.txt"),
                Arguments.of("extension case kept", "A.PDF", "A.PDF"),
                Arguments.of("long extension is no extension", "name.abcdefghijklmnopq", "name.abcdefghijklmnopq"),
                Arguments.of("non-ASCII extension", "file.téxt", "file.téxt"),
                Arguments.of("300 bytes with extension", rep("a", 300) + ".pdf", rep("a", 196) + ".pdf"),
                Arguments.of("multi-byte cut at a code point", rep("é", 150) + ".txt", rep("é", 98) + ".txt"),
                Arguments.of("cut leaves no trailing space", rep("a", 195) + " bbbb.txt", rep("a", 195) + ".txt"),
                Arguments.of("no extension is cut too", rep("b", 250), rep("b", 200)),
                Arguments.of("exactly 200 bytes is kept", rep("c", 196) + ".txt", rep("c", 196) + ".txt"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    void sanitizesByTheFixedTable(String name, String input, String expected) {
        AttachmentFileName n = AttachmentFileName.of(input, "1", false);

        assertThat(n.fileName()).isEqualTo(expected);
        assertThat(n.fileName().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(200);
    }

    @Test
    void nullFilenameGivesTheFallbackWithThePartDotsReplaced() {
        assertThat(AttachmentFileName.of(null, "2.1.3", false).fileName()).isEqualTo("attachment-2-1-3");
    }

    @Test
    void fallbackForMessageRfc822GetsEml() {
        AttachmentFileName n = AttachmentFileName.of(null, "2", true);

        assertThat(n.fileName()).isEqualTo("attachment-2.eml");
        assertThat(n.candidate(2)).isEqualTo("attachment-2 (2).eml");
        assertThat(AttachmentFileName.of("..", "4.1", true).fileName()).isEqualTo("attachment-4-1.eml");
    }

    @Test
    void anExistingNameOnAnRfc822PartIsNotGivenEml() {
        assertThat(AttachmentFileName.of("forward", "3", true).fileName()).isEqualTo("forward");
    }

    @Test
    void clashCandidatesNumberTheStemAndKeepTheExtension() {
        AttachmentFileName n = AttachmentFileName.of("archive.tar.gz", "1", false);

        assertThat(n.candidate(1)).isEqualTo("archive.tar.gz");
        assertThat(n.candidate(2)).isEqualTo("archive.tar (2).gz");
        assertThat(n.candidate(99)).isEqualTo("archive.tar (99).gz");
        assertThat(AttachmentFileName.of("readme", "1", false).candidate(3)).isEqualTo("readme (3)");
    }

    @Test
    void theNumberedCandidatesStayWithinTheFileSystemLimit() {
        AttachmentFileName n = AttachmentFileName.of(rep("a", 300) + ".pdf", "1", false);

        assertThat(n.candidate(99).getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(255);
    }
}
