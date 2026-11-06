package de.alexanderlindhorst.imapmcp.core.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MemorySinkTest {

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(3).nextBytes(b);
        return b;
    }

    private static String hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void encodesBase64WhileStreamingAndHashesTheDecodedBytes() throws Exception {
        byte[] data = random(300_000);
        MemorySink sink = new MemorySink(1_000_000, 100);
        for (int off = 0; off < data.length; off += 70_001) {
            sink.write(data, off, Math.min(70_001, data.length - off));
        }
        assertThat(sink.size()).isEqualTo(300_000);
        assertThat(sink.base64()).isEqualTo(Base64.getEncoder().encodeToString(data));
        assertThat(sink.sha256()).isEqualTo(hex(data));
    }

    @Test
    void chunksOfEverySizeKeepTheLeftoverBytesStraight() throws Exception {
        byte[] data = random(1000);
        for (int chunk : new int[] {1, 2, 3, 4, 5, 7, 76, 77, 999, 1000}) {
            MemorySink sink = new MemorySink(5000, 0);
            for (int off = 0; off < data.length; off += chunk) {
                sink.write(data, off, Math.min(chunk, data.length - off));
            }
            assertThat(sink.base64()).as("chunk " + chunk).isEqualTo(Base64.getEncoder().encodeToString(data));
            assertThat(sink.sha256()).isEqualTo(hex(data));
        }
    }

    @Test
    void paddingForEveryRemainder() {
        for (int n = 0; n <= 7; n++) {
            byte[] data = random(n);
            MemorySink sink = new MemorySink(100, n);
            sink.write(data, 0, n);
            assertThat(sink.base64()).as("n " + n).isEqualTo(Base64.getEncoder().encodeToString(data));
        }
    }

    @Test
    void emptyDigestIsTheEmptyDigestAndBase64IsEmpty() {
        MemorySink sink = new MemorySink(10, 0);
        assertThat(sink.sha256()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(sink.base64()).isEmpty();
    }

    @Test
    void base64CanBeReadTwiceAndDigestStaysValid() throws Exception {
        byte[] data = random(10);
        MemorySink sink = new MemorySink(100, 10);
        sink.write(data, 0, 10);
        String first = sink.base64();
        assertThat(sink.base64()).isEqualTo(first);
        assertThat(sink.sha256()).isEqualTo(hex(data));
    }

    @Test
    void theCapCountsDecodedBytesAndIsInclusive() {
        MemorySink sink = new MemorySink(10, 10);
        sink.write(new byte[6], 0, 6);
        sink.write(new byte[4], 0, 4);
        assertThat(sink.size()).isEqualTo(10);
        assertThatThrownBy(() -> sink.write(new byte[1], 0, 1)).isInstanceOf(DownloadTooLargeException.class);
        assertThat(sink.size()).isEqualTo(10);
        assertThatThrownBy(() -> new MemorySink(5, 5).write(new byte[6], 0, 6))
                .isInstanceOf(DownloadTooLargeException.class);
    }

    @Test
    void anExactEstimateNeedsNoGrowthAndAWrongOneStillWorks() {
        byte[] data = random(1000);
        MemorySink exact = new MemorySink(5000, 1000);
        exact.write(data, 0, 1000);
        MemorySink low = new MemorySink(5000, 10);
        low.write(data, 0, 1000);
        MemorySink high = new MemorySink(5000, 99_999); // clamped to the cap
        high.write(data, 0, 1000);
        String expected = Base64.getEncoder().encodeToString(data);
        assertThat(exact.base64()).isEqualTo(expected);
        assertThat(low.base64()).isEqualTo(expected);
        assertThat(high.base64()).isEqualTo(expected);
    }

    @Test
    void aNonPositiveCapIsRefused() {
        assertThatThrownBy(() -> new MemorySink(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bothSinksShareTheInterface() {
        assertThat(DecodedSink.class).isAssignableFrom(MemorySink.class).isAssignableFrom(DownloadSink.class);
    }
}
