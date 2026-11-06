package de.alexanderlindhorst.imapmcp.core.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MemorySinkTest {

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(3).nextBytes(b);
        return b;
    }

    @Test
    void collectsBytesInChunksWithTheirDigest() throws Exception {
        byte[] data = random(300_000);
        MemorySink sink = new MemorySink(1_000_000);
        for (int off = 0; off < data.length; off += 70_001) {
            sink.write(data, off, Math.min(70_001, data.length - off));
        }
        assertThat(sink.size()).isEqualTo(300_000);
        assertThat(sink.bytes()).isEqualTo(data);
        assertThat(sink.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
    }

    @Test
    void emptyDigestIsTheEmptyDigest() {
        assertThat(new MemorySink(10).sha256())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(new MemorySink(10).bytes()).isEmpty();
    }

    @Test
    void theCapIsInclusiveAndBeyondItFailsWithoutKeepingTheExcess() {
        MemorySink sink = new MemorySink(10);
        sink.write(new byte[6], 0, 6);
        sink.write(new byte[4], 0, 4);
        assertThat(sink.size()).isEqualTo(10);
        assertThatThrownBy(() -> sink.write(new byte[1], 0, 1)).isInstanceOf(DownloadTooLargeException.class);
        assertThat(sink.size()).isEqualTo(10);
        assertThatThrownBy(() -> new MemorySink(5).write(new byte[6], 0, 6)).isInstanceOf(DownloadTooLargeException.class);
    }

    @Test
    void bytesAreACopy() {
        MemorySink sink = new MemorySink(10);
        sink.write(new byte[] {1, 2, 3}, 0, 3);
        sink.bytes()[0] = 9;
        assertThat(sink.bytes()[0]).isEqualTo((byte) 1);
    }

    @Test
    void aNonPositiveCapIsRefused() {
        assertThatThrownBy(() -> new MemorySink(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bothSinksShareTheInterface() {
        assertThat(DecodedSink.class).isAssignableFrom(MemorySink.class).isAssignableFrom(DownloadSink.class);
    }
}
