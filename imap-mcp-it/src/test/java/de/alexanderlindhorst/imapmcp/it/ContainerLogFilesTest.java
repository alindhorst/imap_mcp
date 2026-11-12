package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pure parts of the failed-start diagnostics: the defensive tar extraction and the containers.txt line. */
class ContainerLogFilesTest {

    @TempDir
    Path dir;

    private interface Writer {
        void write(TarArchiveOutputStream tar) throws IOException;
    }

    private static byte[] tar(Writer w) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            w.write(tar);
        }
        return out.toByteArray();
    }

    private static void file(TarArchiveOutputStream tar, String name, byte[] content) throws IOException {
        // keeps a leading slash: a hostile container does not normalise its entry names
        TarArchiveEntry e = new TarArchiveEntry(name, true);
        e.setSize(content.length);
        tar.putArchiveEntry(e);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    private static void link(TarArchiveOutputStream tar, String name, String target, byte type) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name, type);
        e.setLinkName(target);
        tar.putArchiveEntry(e);
        tar.closeArchiveEntry();
    }

    private static Path[] listAll(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).toArray(Path[]::new);
        }
    }

    @Test
    void aRegularFileIsWrittenByteExactAndDirectoriesAreCreatedForIt() throws Exception {
        byte[] content = {0, 1, 2, (byte) 0xff, 'a', '\n'};
        byte[] tar = tar(t -> file(t, "logs/sub/app.log", content));
        Path target = dir.resolve("out");
        int n = TarExtractor.extract(new ByteArrayInputStream(tar), target, 1024, 4096);
        assertThat(n).isEqualTo(1);
        assertThat(Files.readAllBytes(target.resolve("logs/sub/app.log"))).isEqualTo(content);
    }

    @Test
    void linksDevicesAndEscapingEntriesAreSkipped() throws Exception {
        Path outside = dir.resolve("outside.txt");
        byte[] tar = tar(t -> {
            link(t, "sym", "/etc/passwd", TarArchiveEntry.LF_SYMLINK);
            link(t, "hard", "ok.txt", TarArchiveEntry.LF_LINK);
            link(t, "dev", "", TarArchiveEntry.LF_CHR);
            file(t, "../outside.txt", "escape".getBytes(StandardCharsets.US_ASCII));
            file(t, "a/../../outside.txt", "escape".getBytes(StandardCharsets.US_ASCII));
            file(t, outside.toString(), "absolute".getBytes(StandardCharsets.US_ASCII));
            file(t, "ok.txt", "fine".getBytes(StandardCharsets.US_ASCII));
        });
        Path target = dir.resolve("out");
        int n = TarExtractor.extract(new ByteArrayInputStream(tar), target, 1024, 4096);
        assertThat(n).isEqualTo(1);
        assertThat(outside).doesNotExist();
        assertThat(listAll(dir)).containsExactly(target.resolve("ok.txt"));
        assertThat(Files.isSymbolicLink(target.resolve("sym"))).isFalse();
        assertThat(target.resolve("hard")).doesNotExist();
    }

    @Test
    void aFileIsTruncatedAtThePerFileCapAndExtractionStopsAtTheTotalCap() throws Exception {
        byte[] ten = "0123456789".getBytes(StandardCharsets.US_ASCII);
        byte[] tar = tar(t -> {
            file(t, "a", ten);
            file(t, "b", ten);
            file(t, "c", ten);
        });
        Path target = dir.resolve("out");
        TarExtractor.extract(new ByteArrayInputStream(tar), target, 6, 8);
        assertThat(Files.readString(target.resolve("a"))).isEqualTo("012345");
        assertThat(Files.readString(target.resolve("b"))).isEqualTo("01");
        assertThat(target.resolve("c")).doesNotExist();
    }

    @Test
    void theContainersLineHasServiceStateStatusHealthAndExitCodeAndNothingElse() {
        ContainerFacts f = new ContainerFacts("proxy", "exited", "Exited (1) 3 seconds ago", "none", 1);
        assertThat(f.line()).isEqualTo("service=proxy state=exited status=\"Exited (1) 3 seconds ago\" health=none exit=1");
    }

    @Test
    void aMissingHealthIsNoneAndLineBreaksInTheStatusAreFlattened() {
        ContainerFacts f = new ContainerFacts("svc", "running", "Up\nnow", null, 0);
        assertThat(f.line()).isEqualTo("service=svc state=running status=\"Up now\" health=none exit=0");
    }
}
