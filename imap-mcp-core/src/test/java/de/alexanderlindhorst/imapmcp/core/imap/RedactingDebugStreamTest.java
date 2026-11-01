package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedactingDebugStreamTest {

    private final List<String> lines = new ArrayList<>();
    private final RedactingDebugStream stream = new RedactingDebugStream(lines::add);

    private void write(String s) {
        stream.write(s.getBytes(StandardCharsets.UTF_8), 0, s.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void loginLineIsRedacted() {
        write("A1 LOGIN user marker-pass\r\n");
        write("C: a2 login user marker-pass\r\n");
        assertThat(lines).containsExactly("<redacted>", "<redacted>");
    }

    @Test
    void authenticateAndContinuationAreRedacted() {
        write("A1 AUTHENTICATE PLAIN\r\n");
        write("+ \r\n");
        write("AHVzZXIAbWFya2VyLXBhc3M=\r\n");
        write("A1 OK done\r\n");
        assertThat(lines).containsExactly("<redacted>", "<redacted>", "<redacted>", "A1 OK done");
    }

    @Test
    void prefixedContinuationAndClientResponseAreRedacted() {
        write("S: + VXNlcm5hbWU6\r\n");
        write("C: dXNlcg==\r\n");
        write("C: A3 NOOP\r\n");
        assertThat(lines).containsExactly("<redacted>", "<redacted>", "C: A3 NOOP");
    }

    @Test
    void normalLinesAreUnchangedAndPartialWritesBuffered() {
        write("A2 O");
        assertThat(lines).isEmpty();
        write("K\r\n");
        assertThat(lines).containsExactly("A2 OK");
        write("* partial");
        assertThat(lines).hasSize(1);
        stream.flush();
        assertThat(lines).containsExactly("A2 OK", "* partial");
    }

    @Test
    void decodesUtf8PerLineEvenWhenSplitMidCharacter() {
        byte[] b = "* ä\n".getBytes(StandardCharsets.UTF_8);
        stream.write(b, 0, 3);
        stream.write(b, 3, b.length - 3);
        assertThat(lines).containsExactly("* ä");
    }

    @Test
    void overlongLineIsEmittedInChunks() {
        write("x".repeat(RedactingDebugStream.MAX_LINE_BYTES + 10) + "\n");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).hasSize(RedactingDebugStream.MAX_LINE_BYTES);
        assertThat(lines.get(1)).isEqualTo("x".repeat(10));
    }

    @Test
    void worksAsPrintStream() {
        PrintStream ps = stream;
        ps.println("A1 LOGIN u p");
        ps.println("A2 OK");
        assertThat(lines).containsExactly("<redacted>", "A2 OK");
    }
}
