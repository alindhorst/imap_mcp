package de.alexanderlindhorst.imapmcp.core.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.download.DownloadStorageException.Reason;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DownloadSinkTest {

    private static final Pattern TEMP = Pattern.compile("\\.imap-mcp-[0-9a-f]{16}\\.part");
    private static final byte[] DATA = "hello attachment".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;
    Path dir;

    private DownloadDirectory directory(boolean hardLinks, long max) throws IOException {
        dir = Files.createDirectory(tmp.resolve("dl-" + hardLinks + "-" + System.nanoTime()));
        return DownloadDirectory.of(dir.toRealPath(), hardLinks, max, null);
    }

    private List<String> names() throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private DownloadSink.Published save(DownloadDirectory d, String name, byte[] data) {
        try (DownloadSink sink = DownloadSink.open(d)) {
            sink.write(data, 0, data.length);
            return sink.publish(AttachmentFileName.of(name, "1", false));
        }
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    // 2: publication, both strategies

    @ParameterizedTest(name = "hardLinks={0}")
    @ValueSource(booleans = {true, false})
    void publishesTheBytesWithMode0600AndLeavesNoTemporaryFile(boolean hardLinks) throws Exception {
        DownloadDirectory d = directory(hardLinks, 1000);

        DownloadSink.Published p = save(d, "report.pdf", DATA);

        assertThat(p.savedAs()).isEqualTo("report.pdf");
        assertThat(p.bytes()).isEqualTo(DATA.length);
        assertThat(p.sha256()).isEqualTo(sha(DATA));
        assertThat(names()).containsExactly("report.pdf");
        assertThat(Files.readAllBytes(dir.resolve("report.pdf"))).isEqualTo(DATA);
        assertThat(Files.getPosixFilePermissions(dir.resolve("report.pdf")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @ParameterizedTest(name = "hardLinks={0}")
    @ValueSource(booleans = {true, false})
    void aZeroByteDownloadGivesAnEmptyFileWithTheEmptyDigest(boolean hardLinks) throws Exception {
        DownloadDirectory d = directory(hardLinks, 1000);

        DownloadSink.Published p = save(d, "empty.bin", new byte[0]);

        assertThat(p.bytes()).isZero();
        assertThat(p.sha256()).isEqualTo(sha(new byte[0]));
        assertThat(Files.size(dir.resolve("empty.bin"))).isZero();
    }

    @ParameterizedTest(name = "hardLinks={0}")
    @ValueSource(booleans = {true, false})
    void aSecondSaveOfTheSameNameGetsANumberAndTheFirstIsUntouched(boolean hardLinks) throws Exception {
        DownloadDirectory d = directory(hardLinks, 1000);
        save(d, "a.txt", "one".getBytes(StandardCharsets.UTF_8));

        DownloadSink.Published p = save(d, "a.txt", "two".getBytes(StandardCharsets.UTF_8));

        assertThat(p.savedAs()).isEqualTo("a (2).txt");
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("one");
        assertThat(Files.readString(dir.resolve("a (2).txt"))).isEqualTo("two");
        assertThat(names()).containsExactly("a (2).txt", "a.txt");
    }

    @ParameterizedTest(name = "hardLinks={0}")
    @ValueSource(booleans = {true, false})
    void aPlantedFileDirectoryLinkOrDanglingLinkIsNeverFollowedOrReplaced(boolean hardLinks) throws Exception {
        DownloadDirectory d = directory(hardLinks, 1000);
        Path outside = Files.writeString(tmp.resolve("outside-" + hardLinks), "secret");
        Files.writeString(dir.resolve("a.txt"), "planted");
        Files.createDirectory(dir.resolve("a (2).txt"));
        Files.createSymbolicLink(dir.resolve("a (3).txt"), outside);
        Files.createSymbolicLink(dir.resolve("a (4).txt"), tmp.resolve("does-not-exist"));

        DownloadSink.Published p = save(d, "a.txt", DATA);

        assertThat(p.savedAs()).isEqualTo("a (5).txt");
        assertThat(Files.readString(dir.resolve("a.txt"))).isEqualTo("planted");
        assertThat(Files.readString(outside)).isEqualTo("secret");
        assertThat(Files.isDirectory(dir.resolve("a (2).txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(dir.resolve("a (3).txt"))).isTrue();
        assertThat(Files.isSymbolicLink(dir.resolve("a (4).txt"))).isTrue();
        assertThat(tmp.resolve("does-not-exist")).doesNotExist();
    }

    @ParameterizedTest(name = "hardLinks={0}")
    @ValueSource(booleans = {true, false})
    void theClashSequenceEndsAtNinetyNineWithNameExhaustedAndLeavesNothingBehind(boolean hardLinks) throws Exception {
        DownloadDirectory d = directory(hardLinks, 1000);
        AttachmentFileName n = AttachmentFileName.of("a.txt", "1", false);
        for (int i = 1; i <= 99; i++) {
            Files.writeString(dir.resolve(n.candidate(i)), "planted" + i);
        }

        assertThatThrownBy(() -> save(d, "a.txt", DATA)).isInstanceOf(DownloadStorageException.class)
                .extracting(e -> ((DownloadStorageException) e).reason()).isEqualTo(Reason.NAME_EXHAUSTED);

        assertThat(names()).hasSize(99).noneMatch(s -> TEMP.matcher(s).matches());
        assertThat(Files.readString(dir.resolve(n.candidate(99)))).isEqualTo("planted99");
    }

    @Test
    void theNinetyNinthCandidateIsStillUsable() throws Exception {
        DownloadDirectory d = directory(true, 1000);
        AttachmentFileName n = AttachmentFileName.of("a.txt", "1", false);
        for (int i = 1; i <= 98; i++) {
            Files.writeString(dir.resolve(n.candidate(i)), "x");
        }

        assertThat(save(d, "a.txt", DATA).savedAs()).isEqualTo("a (99).txt");
    }

    @Test
    void theReservationFallbackReplacesTheEmptyReservationAtomically() throws Exception {
        DownloadDirectory d = directory(false, 1000);
        Path[] seenAtPublish = new Path[1];
        long[] sizeAtReserve = new long[1];
        DownloadSink.Hooks hooks = new DownloadSink.Hooks() {
            @Override
            public void step(DownloadSink.Step s) throws IOException {
                if (s == DownloadSink.Step.AFTER_RESERVE) {
                    sizeAtReserve[0] = Files.size(dir.resolve("a.txt"));
                    seenAtPublish[0] = dir.resolve("a.txt");
                }
            }
        };
        try (DownloadSink sink = DownloadSink.open(d, hooks)) {
            sink.write(DATA, 0, DATA.length);
            sink.publish(AttachmentFileName.of("a.txt", "1", false));
        }

        assertThat(seenAtPublish[0]).isNotNull();
        assertThat(sizeAtReserve[0]).isZero();
        assertThat(Files.readAllBytes(dir.resolve("a.txt"))).isEqualTo(DATA);
        assertThat(Files.getPosixFilePermissions(dir.resolve("a.txt")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(names()).containsExactly("a.txt");
    }

    // the temporary file and the final name while writing

    @Test
    void whileWritingThereIsOneHiddenTemporaryFileWith0600AndNoFinalName() throws Exception {
        DownloadDirectory d = directory(true, 1000);
        try (DownloadSink sink = DownloadSink.open(d)) {
            sink.write(DATA, 0, 5);

            List<String> during = names();
            assertThat(during).hasSize(1);
            assertThat(TEMP.matcher(during.get(0)).matches()).isTrue();
            assertThat(Files.getPosixFilePermissions(dir.resolve(during.get(0))))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
        assertThat(names()).isEmpty();
    }

    @Test
    void theFinalNameDoesNotExistBeforePublication() throws Exception {
        DownloadDirectory d = directory(true, 1000);
        boolean[] existed = {true};
        DownloadSink.Hooks hooks = new DownloadSink.Hooks() {
            @Override
            public void step(DownloadSink.Step s) {
                if (s == DownloadSink.Step.BEFORE_PUBLISH) {
                    existed[0] = Files.exists(dir.resolve("a.txt"));
                }
            }
        };
        try (DownloadSink sink = DownloadSink.open(d, hooks)) {
            sink.write(DATA, 0, DATA.length);
            sink.publish(AttachmentFileName.of("a.txt", "1", false));
        }

        assertThat(existed[0]).isFalse();
    }

    // size

    @Test
    void writingBeyondMaxBytesFailsAndRemovesThePartialFile() throws Exception {
        DownloadDirectory d = directory(true, 10);

        try (DownloadSink sink = DownloadSink.open(d)) {
            sink.write(DATA, 0, 10);
            assertThatThrownBy(() -> sink.write(DATA, 0, 1)).isInstanceOf(DownloadTooLargeException.class);
        }

        assertThat(names()).isEmpty();
    }

    @Test
    void exactlyMaxBytesIsAccepted() throws Exception {
        DownloadDirectory d = directory(true, DATA.length);

        assertThat(save(d, "a.txt", DATA).bytes()).isEqualTo(DATA.length);
    }

    // 3: failure paths

    private static DownloadSink.Hooks failingAt(DownloadSink.Step at, Throwable t, long usable) {
        return new DownloadSink.Hooks() {
            @Override
            public void step(DownloadSink.Step s) throws IOException {
                if (s == at) {
                    if (t instanceof IOException io) {
                        throw io;
                    }
                    throw (RuntimeException) t;
                }
            }

            @Override
            public long usableSpace(Path p) {
                return usable;
            }
        };
    }

    private void runFailing(boolean hardLinks, DownloadSink.Hooks hooks, Reason expected) throws Exception {
        DownloadDirectory d = directory(hardLinks, 1000);
        assertThatThrownBy(() -> {
            try (DownloadSink sink = DownloadSink.open(d, hooks)) {
                sink.write(DATA, 0, DATA.length);
                sink.publish(AttachmentFileName.of("a.txt", "1", false));
            }
        }).isInstanceOf(DownloadStorageException.class)
                .extracting(e -> ((DownloadStorageException) e).reason()).isEqualTo(expected);
        assertThat(names()).isEmpty();
    }

    @ParameterizedTest(name = "hardLinks={0}")
    @ValueSource(booleans = {true, false})
    void aWriteFailureIsIoErrorAndLeavesNothing(boolean hardLinks) throws Exception {
        runFailing(hardLinks, failingAt(DownloadSink.Step.WRITE, new IOException("canary-path"), 1L << 40),
                Reason.IO_ERROR);
    }

    @Test
    void aWriteFailureWithLittleFreeSpaceIsNoSpace() throws Exception {
        runFailing(true, failingAt(DownloadSink.Step.WRITE, new IOException("x"), 1024 * 1024 - 1), Reason.NO_SPACE);
    }

    @Test
    void aWriteFailureWithOneBlockFreeIsStillIoError() throws Exception {
        runFailing(true, failingAt(DownloadSink.Step.WRITE, new IOException("x"), 1024 * 1024), Reason.IO_ERROR);
    }

    @Test
    void aForceFailureLeavesNothing() throws Exception {
        runFailing(true, failingAt(DownloadSink.Step.FORCE, new IOException("x"), 1L << 40), Reason.IO_ERROR);
    }

    @ParameterizedTest(name = "hardLinks={0}")
    @ValueSource(booleans = {true, false})
    void aFailureRightBeforePublicationLeavesNothing(boolean hardLinks) throws Exception {
        runFailing(hardLinks, failingAt(DownloadSink.Step.BEFORE_PUBLISH, new IOException("x"), 1L << 40),
                Reason.IO_ERROR);
    }

    @Test
    void aFailureAfterTheReservationRemovesTheReservation() throws Exception {
        runFailing(false, failingAt(DownloadSink.Step.AFTER_RESERVE, new IOException("x"), 1L << 40),
                Reason.IO_ERROR);
    }

    @Test
    void anInterruptedWriteLeavesNothing() throws Exception {
        runFailing(true, failingAt(DownloadSink.Step.WRITE, new ClosedByInterruptException(), 1L << 40),
                Reason.IO_ERROR);
    }

    @Test
    void anUncheckedExceptionLeavesNothingBecauseCloseCleansUp() throws Exception {
        DownloadDirectory d = directory(false, 1000);
        DownloadSink.Hooks hooks = failingAt(DownloadSink.Step.AFTER_RESERVE, new IllegalStateException("x"), 1L << 40);

        assertThatThrownBy(() -> {
            try (DownloadSink sink = DownloadSink.open(d, hooks)) {
                sink.write(DATA, 0, DATA.length);
                sink.publish(AttachmentFileName.of("a.txt", "1", false));
            }
        }).isInstanceOf(IllegalStateException.class);
        assertThat(names()).isEmpty();
    }

    @Test
    void closingWithoutPublishingRemovesTheTemporaryFile() throws Exception {
        DownloadDirectory d = directory(true, 1000);
        try (DownloadSink sink = DownloadSink.open(d)) {
            sink.write(DATA, 0, DATA.length);
        }

        assertThat(names()).isEmpty();
    }

    @Test
    void closeAfterPublishKeepsThePublishedFileAndCloseIsIdempotent() throws Exception {
        DownloadDirectory d = directory(true, 1000);
        DownloadSink sink = DownloadSink.open(d);
        sink.write(DATA, 0, DATA.length);
        sink.publish(AttachmentFileName.of("keep.txt", "1", false));
        sink.close();
        sink.close();

        assertThat(names()).containsExactly("keep.txt");
    }

    @Test
    void aFailedPublishLeavesEarlierPublishedFilesUntouched() throws Exception {
        DownloadDirectory d = directory(true, 1000);
        save(d, "first.txt", DATA);
        DownloadSink.Hooks hooks = failingAt(DownloadSink.Step.BEFORE_PUBLISH, new IOException("x"), 1L << 40);

        assertThatThrownBy(() -> {
            try (DownloadSink sink = DownloadSink.open(d, hooks)) {
                sink.write(DATA, 0, 1);
                sink.publish(AttachmentFileName.of("second.txt", "1", false));
            }
        }).isInstanceOf(DownloadStorageException.class);
        assertThat(names()).containsExactly("first.txt");
    }

    @Test
    void aMissingDirectoryIsDirectoryUnavailableAtOpen() throws Exception {
        DownloadDirectory d = directory(true, 1000);
        Files.delete(dir);

        assertThatThrownBy(() -> DownloadSink.open(d)).isInstanceOf(DownloadStorageException.class)
                .extracting(e -> ((DownloadStorageException) e).reason()).isEqualTo(Reason.DIRECTORY_UNAVAILABLE);
    }

    @Test
    void aNameTheFileSystemRejectsAsTooLongIsIoError() throws Exception {
        DownloadDirectory d = directory(true, 1000);
        DownloadSink.Hooks hooks = new DownloadSink.Hooks() {};
        // 255 is the common limit; a 300 byte name cannot come out of the sanitizer, so build the failure by hand
        assertThatThrownBy(() -> {
            try (DownloadSink sink = DownloadSink.open(d, hooks)) {
                sink.write(DATA, 0, DATA.length);
                sink.publish(AttachmentFileName.rawForTest("x".repeat(300), null));
            }
        }).isInstanceOf(DownloadStorageException.class)
                .extracting(e -> ((DownloadStorageException) e).reason()).isEqualTo(Reason.IO_ERROR);
        assertThat(names()).isEmpty();
    }

    @Test
    void usableSpaceOfTheDirectoryIsReadable() throws Exception {
        assertThat(directory(true, 1000).usableSpace()).isPositive();
    }

    // never log names or content

    @Test
    void nothingIsLoggedForTheNameOrTheContent() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger("de.alexanderlindhorst.imapmcp.core.download");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        ch.qos.logback.classic.Level old = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            DownloadDirectory d = directory(true, 1000);
            save(d, "canary-name.txt", "canary-content".getBytes(StandardCharsets.UTF_8));
            save(d, "canary-name.txt", DATA);
            runFailing(false, failingAt(DownloadSink.Step.WRITE, new IOException("canary-name"), 1L << 40),
                    Reason.IO_ERROR);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(old);
        }
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("canary")
                || e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null
                        && e.getThrowableProxy().getMessage().contains("canary"));
    }
}
