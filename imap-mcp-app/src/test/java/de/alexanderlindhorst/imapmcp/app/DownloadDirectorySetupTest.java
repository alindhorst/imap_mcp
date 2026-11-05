package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.core.download.DownloadDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadDirectorySetupTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final DownloadDirectorySetup.Probe OK = dir -> new DownloadDirectorySetup.ProbeResult(7, 7, true, true);

    @TempDir
    Path tmp;
    Path dl;
    Path state;
    Path logs;
    Path configDir;
    AppPaths paths;

    @BeforeEach
    void layout() throws IOException {
        dl = mkdir(tmp.resolve("dl"), "rwx------");
        state = mkdir(tmp.resolve("state"), "rwx------");
        logs = mkdir(tmp.resolve("logs"), "rwx------");
        configDir = mkdir(tmp.resolve("cfg"), "rwx------");
        paths = new AppPaths(configDir.resolve("config.yaml"), state, logs);
    }

    private static Path mkdir(Path p, String mode) throws IOException {
        Files.createDirectory(p);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode));
        return p;
    }

    private DownloadDirectory open(Path configured) throws IOException {
        return open(configured, Set.of(), OK);
    }

    private DownloadDirectory open(Path configured, Set<Path> defaults, DownloadDirectorySetup.Probe probe)
            throws IOException {
        return DownloadDirectorySetup.open(new AppConfig.Download(configured, 1000, null), paths, defaults, probe, NOW);
    }

    private void assertRejected(Path configured, Set<Path> defaults, String reasonFragment) {
        assertThatThrownBy(() -> open(configured, defaults, OK)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining("tools.download.dir").hasMessageContaining(reasonFragment)
                .hasMessageContaining(paths.configFile().toString());
    }

    @Test
    void acceptsAnOwnDirectoryAndReportsItsRealPath() throws IOException {
        DownloadDirectory d = open(dl);

        assertThat(d.path()).isEqualTo(dl.toRealPath());
        assertThat(d.hardLinks()).isTrue();
        assertThat(d.maxBytes()).isEqualTo(1000);
    }

    @Test
    void aConfiguredLinkToADirectoryIsFollowedOnce() throws IOException {
        Path link = Files.createSymbolicLink(tmp.resolve("link"), dl);

        assertThat(open(link).path()).isEqualTo(dl.toRealPath());
    }

    @Test
    void missingDirectoryFailsAndIsNeverCreated() {
        Path missing = tmp.resolve("nope");

        assertRejected(missing, Set.of(), "does not exist");
        assertThat(missing).doesNotExist();
    }

    @Test
    void aRegularFileFails() throws IOException {
        Path f = Files.createFile(tmp.resolve("file"));

        assertRejected(f, Set.of(), "not a directory");
    }

    @Test
    void aDirectoryWritableByOthersFails() throws IOException {
        Files.setPosixFilePermissions(dl, PosixFilePermissions.fromString("rwx---rwx"));

        assertRejected(dl, Set.of(), "writable by others");
    }

    @Test
    void aGroupWritableDirectoryOnlyWarns() throws IOException {
        Files.setPosixFilePermissions(dl, PosixFilePermissions.fromString("rwxrwx---"));

        assertThat(open(dl).path()).isEqualTo(dl.toRealPath());
    }

    @Test
    void theStateLogAndConfigDirectoriesAreRejected() {
        assertRejected(state, Set.of(), "the state directory");
        assertRejected(logs, Set.of(), "the log directory");
        assertRejected(configDir, Set.of(), "the directory of the config file");
    }

    @Test
    void aLinkToTheStateDirectoryIsTheSameFile() throws IOException {
        Path link = Files.createSymbolicLink(tmp.resolve("alias"), state);

        assertRejected(link, Set.of(), "the state directory");
    }

    @Test
    void defaultConfigAndStateLocationsAreRejectedWhenTheyExist() throws IOException {
        Path defaultCfg = mkdir(tmp.resolve("xdg-config"), "rwx------");

        assertRejected(defaultCfg, Set.of(defaultCfg, tmp.resolve("absent")), "a default config or state location");
    }

    @Test
    void defaultLocationsThatDoNotExistAreSkipped() throws IOException {
        assertThat(open(dl, Set.of(tmp.resolve("absent"), tmp.resolve("absent2")), OK).path())
                .isEqualTo(dl.toRealPath());
    }

    @Test
    void defaultLocationsFollowXdgAndHome() {
        Path home = Path.of("/home/u");
        assertThat(DownloadDirectorySetup.defaultLocations(Map.of(), home)).containsExactlyInAnyOrder(
                Path.of("/home/u/.config/imap-mcp"), Path.of("/home/u/.local/state/imap-mcp"));
        assertThat(DownloadDirectorySetup.defaultLocations(
                Map.of("XDG_CONFIG_HOME", "/x/c", "XDG_STATE_HOME", "/x/s", "IMAP_MCP_STATE_DIR", "/ignored"), home))
                .containsExactlyInAnyOrder(Path.of("/x/c/imap-mcp"), Path.of("/x/s/imap-mcp"),
                        Path.of("/home/u/.config/imap-mcp"), Path.of("/home/u/.local/state/imap-mcp"));
        assertThat(DownloadDirectorySetup.defaultLocations(Map.of("XDG_CONFIG_HOME", "relative"), home))
                .containsExactlyInAnyOrder(Path.of("/home/u/.config/imap-mcp"),
                        Path.of("/home/u/.local/state/imap-mcp"));
    }

    @Test
    void aForeignOwnerFoundByTheProbeFails() {
        assertThatThrownBy(() -> open(dl, Set.of(),
                d -> new DownloadDirectorySetup.ProbeResult(1000, 0, true, true)))
                .isInstanceOf(AppStartupException.class).hasMessageContaining("tools.download.dir")
                .hasMessageContaining("owned by");
    }

    @Test
    void aProbeWithoutOwnerInformationSkipsTheOwnerCheck() throws IOException {
        assertThat(open(dl, Set.of(), d -> new DownloadDirectorySetup.ProbeResult(null, null, true, true)).path())
                .isEqualTo(dl.toRealPath());
    }

    @Test
    void aProbeModeOtherThan0600StillOpens() throws IOException {
        assertThat(open(dl, Set.of(), d -> new DownloadDirectorySetup.ProbeResult(7, 7, false, true)).path())
                .isEqualTo(dl.toRealPath());
    }

    @Test
    void theProbeDecidesTheHardLinkStrategy() throws IOException {
        assertThat(open(dl, Set.of(), d -> new DownloadDirectorySetup.ProbeResult(7, 7, true, false)).hardLinks())
                .isFalse();
    }

    @Test
    void anUnwritableDirectoryFailsWithAClearMessage() {
        assertThatThrownBy(() -> open(dl, Set.of(), d -> {
            throw new java.nio.file.AccessDeniedException("/secret/canary");
        })).isInstanceOf(AppStartupException.class).hasMessageContaining("tools.download.dir")
                .hasMessageContaining("cannot create files").message().doesNotContain("canary");
    }

    // the real probe

    @Test
    void theRealProbeSeesOwnerModeAndHardLinksAndLeavesNothingBehind() throws IOException {
        DownloadDirectorySetup.ProbeResult r = DownloadDirectorySetup.PROBE.run(dl);

        assertThat(r.probeUid()).isNotNull().isEqualTo(r.dirUid());
        assertThat(r.mode0600()).isTrue();
        assertThat(r.hardLinks()).isTrue();
        try (Stream<Path> s = Files.list(dl)) {
            assertThat(s.toList()).isEmpty();
        }
    }

    // the startup sweep

    private Path leftover(String name, Instant mtime) throws IOException {
        Path f = Files.createFile(dl.resolve(name));
        Files.setLastModifiedTime(f, FileTime.from(mtime));
        return f;
    }

    @Test
    void sweepRemovesOnlyOldRegularTemporaryFiles() throws IOException {
        Instant old = NOW.minusSeconds(21 * 60);
        Instant recent = NOW.minusSeconds(19 * 60);
        Path oldTmp = leftover(".imap-mcp-0123456789abcdef.part", old);
        Path recentTmp = leftover(".imap-mcp-fedcba9876543210.part", recent);
        Path wrongName = leftover(".imap-mcp-0123456789ABCDEF.part", old);
        Path wrongLength = leftover(".imap-mcp-0123456789abcde.part", old);
        Path published = leftover("report.pdf", old);
        Path target = leftover("target", old);
        Path link = Files.createSymbolicLink(dl.resolve(".imap-mcp-aaaaaaaaaaaaaaaa.part"), target);
        Path dirNamed = Files.createDirectory(dl.resolve(".imap-mcp-bbbbbbbbbbbbbbbb.part"));
        Files.setLastModifiedTime(dirNamed, FileTime.from(old));

        int removed = DownloadDirectorySetup.sweep(dl, NOW);

        assertThat(removed).isEqualTo(1);
        assertThat(oldTmp).doesNotExist();
        assertThat(List.of(recentTmp, wrongName, wrongLength, published, target, dirNamed)).allMatch(Files::exists);
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }

    @Test
    void openRunsTheSweepAndLeavesPublishedFiles() throws IOException {
        Path oldTmp = leftover(".imap-mcp-0123456789abcdef.part", NOW.minusSeconds(3600));
        Path keep = leftover("keep.txt", NOW.minusSeconds(3600));

        open(dl);

        assertThat(oldTmp).doesNotExist();
        assertThat(keep).exists();
    }
}
