package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RedactingDebugStreamTest {

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
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
        byte[] b = "* \u00e4\n".getBytes(StandardCharsets.UTF_8);
        stream.write(b, 0, 3);
        stream.write(b, 3, b.length - 3);
        assertThat(lines).containsExactly("* \u00e4");
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

    @Test
    void overlongLoginLineIsRedactedInEveryChunk() {
        write("A1 LOGIN user " + "p".repeat(70_000) + "\r\n");
        write("A2 OK\r\n");
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3);
        assertThat(lines.subList(0, lines.size() - 1)).allMatch("<redacted>"::equals);
        assertThat(lines).noneMatch(l -> l.contains("ppp"));
        assertThat(lines.getLast()).isEqualTo("A2 OK");
    }

    @Test
    void overlongClientResponseAfterContinuationIsRedactedInEveryChunk() {
        write("+ \r\n");
        write("p".repeat(70_000) + "\r\n");
        write("A2 OK\r\n");
        assertThat(lines).noneMatch(l -> l.contains("ppp"));
        assertThat(lines.subList(0, lines.size() - 1)).allMatch("<redacted>"::equals);
        assertThat(lines.getLast()).isEqualTo("A2 OK");
    }

    @Test
    void continuationFlagIsPerThread() throws Exception {
        CountDownLatch continuationPrinted = new CountDownLatch(1);
        CountDownLatch otherLinePrinted = new CountDownLatch(1);
        Thread a = new Thread(() -> {
            stream.println("S: + ");
            continuationPrinted.countDown();
            await(otherLinePrinted);
            stream.println("C: AHVzZXIAbWFya2VyLXBhc3M=");
        });
        Thread b = new Thread(() -> {
            await(continuationPrinted);
            stream.println("S: * OK still here");
            otherLinePrinted.countDown();
        });
        a.start();
        b.start();
        a.join(5_000);
        b.join(5_000);
        // Thread B's line is not consumed as the client reply of thread A's continuation, and A's reply is redacted.
        assertThat(lines).containsExactlyInAnyOrder("<redacted>", "S: * OK still here", "<redacted>");
        assertThat(lines).doesNotContain("C: AHVzZXIAbWFya2VyLXBhc3M=");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void printlnLineIsNotGluedToAnotherThreadsPartialBytes() throws Exception {
        Thread other = new Thread(() -> write("* partial fragment of another connection"));
        other.start();
        other.join(5_000);
        stream.println("A1 OK done");
        // The whole logical line is handled in one call and never shares the byte buffer.
        assertThat(lines).containsExactly("A1 OK done");
    }
}
