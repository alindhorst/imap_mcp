package de.alexanderlindhorst.imapmcp.core.download;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadDirectoryTest {

    @TempDir
    Path tmp;

    private DownloadDirectory open(Path dir, Path display) throws IOException {
        return DownloadDirectory.of(dir.toRealPath(), true, 1024, display);
    }

    @Test
    void reportedDirectoryIsTheRealPathWithoutDisplayDir() throws IOException {
        Path d = Files.createDirectory(tmp.resolve("dl"));
        DownloadDirectory dd = open(d, null);

        assertThat(dd.path()).isEqualTo(d.toRealPath());
        assertThat(dd.reportedDirectory()).isEqualTo(d.toRealPath().toString());
        assertThat(dd.maxBytes()).isEqualTo(1024);
        assertThat(dd.hardLinks()).isTrue();
    }

    @Test
    void displayDirReplacesTheReportedDirectoryOnly() throws IOException {
        Path d = Files.createDirectory(tmp.resolve("dl"));
        DownloadDirectory dd = open(d, Path.of("/home/me/Downloads/imap-mcp"));

        assertThat(dd.reportedDirectory()).isEqualTo("/home/me/Downloads/imap-mcp");
        assertThat(dd.path()).isEqualTo(d.toRealPath());
    }

    @Test
    void stillAvailableWhileTheSameDirectoryExists() throws IOException {
        DownloadDirectory dd = open(Files.createDirectory(tmp.resolve("dl")), null);

        assertThat(dd.stillAvailable()).isTrue();
    }

    @Test
    void notAvailableAfterRemoval() throws IOException {
        Path d = Files.createDirectory(tmp.resolve("dl"));
        DownloadDirectory dd = open(d, null);
        Files.delete(d);

        assertThat(dd.stillAvailable()).isFalse();
    }

    @Test
    void notAvailableWhenReplacedByAnotherDirectory() throws IOException {
        Path d = Files.createDirectory(tmp.resolve("dl"));
        DownloadDirectory dd = open(d, null);
        Files.move(d, tmp.resolve("old"));
        Files.createDirectory(d);

        assertThat(dd.stillAvailable()).isFalse();
    }

    @Test
    void notAvailableWhenReplacedByALinkToTheSameDirectory() throws IOException {
        Path d = Files.createDirectory(tmp.resolve("dl"));
        DownloadDirectory dd = open(d, null);
        Path moved = Files.move(d, tmp.resolve("moved"));
        Files.createSymbolicLink(d, moved);

        assertThat(dd.stillAvailable()).isFalse();
    }

    @Test
    void notAvailableWhenReplacedByAFile() throws IOException {
        Path d = Files.createDirectory(tmp.resolve("dl"));
        DownloadDirectory dd = open(d, null);
        Files.delete(d);
        Files.createFile(d);

        assertThat(dd.stillAvailable()).isFalse();
    }
}
