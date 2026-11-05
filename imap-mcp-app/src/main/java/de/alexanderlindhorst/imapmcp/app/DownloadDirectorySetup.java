package de.alexanderlindhorst.imapmcp.app;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.core.download.DownloadDirectory;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup checks of the {@code download_attachment} directory (SPEC 5.3, 5.4): resolves it once to its real path,
 * rejects directories that are writable by others or hold the server's own config or state, runs the owner probe
 * (which also decides the publication strategy) and sweeps leftover temporary files. Errors name the config key
 * and the config file, never a secret. The directory is never created.
 */
final class DownloadDirectorySetup {

    private static final Logger LOG = LoggerFactory.getLogger("imapmcp.app");
    private static final String KEY = "tools.download.dir";
    private static final Pattern TEMP_NAME = Pattern.compile("\\.imap-mcp-[0-9a-f]{16}\\.part");
    private static final Duration SWEEP_AGE = Duration.ofMinutes(20);
    private static final String APP_DIR = "imap-mcp";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the probe found. Uids are null where the {@code unix} attribute view is missing. */
    record ProbeResult(Integer probeUid, Integer dirUid, boolean mode0600, boolean hardLinks) {
    }

    /** Test seam: a foreign-owned directory cannot be made by a non-root CI user. */
    @FunctionalInterface
    interface Probe {
        ProbeResult run(Path dir) throws IOException;
    }

    static final Probe PROBE = DownloadDirectorySetup::probe;

    private DownloadDirectorySetup() {
    }

    /** The default config and state locations of SPEC 5.7 (existing ones are rejected as a download directory). */
    static Set<Path> defaultLocations(Map<String, String> env, Path home) {
        Set<Path> out = new HashSet<>();
        out.add(home.resolve(".config").resolve(APP_DIR));
        out.add(home.resolve(".local").resolve("state").resolve(APP_DIR));
        addXdg(out, env.get(AppPaths.ENV_XDG_CONFIG_HOME));
        addXdg(out, env.get(AppPaths.ENV_XDG_STATE_HOME));
        return out;
    }

    private static void addXdg(Set<Path> out, String xdg) {
        if (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()) {
            out.add(Path.of(xdg).resolve(APP_DIR));
        }
    }

    static DownloadDirectory open(AppConfig.Download cfg, AppPaths paths, Set<Path> defaultLocations, Probe probe,
            Instant now) {
        String where = KEY + " in " + paths.configFile() + ": ";
        Path real;
        try {
            real = cfg.dir().toRealPath();
        } catch (NoSuchFileException e) {
            throw new AppStartupException(where + "the directory does not exist (the server never creates it)");
        } catch (IOException e) {
            throw new AppStartupException(where + "the directory cannot be resolved");
        }
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new AppStartupException(where + "the path is not a directory");
        }
        checkPermissions(real, where);
        reject(real, paths.stateDir(), "is the state directory", where);
        reject(real, paths.logDir(), "is the log directory", where);
        reject(real, paths.configFile().getParent(), "is the directory of the config file", where);
        for (Path d : defaultLocations) {
            reject(real, d, "is a default config or state location", where);
        }

        ProbeResult r;
        try {
            r = probe.run(real);
        } catch (IOException e) {
            throw new AppStartupException(where + "cannot create files in the directory; it must be writable by "
                    + "the user the server runs as");
        }
        if (r.probeUid() == null || r.dirUid() == null) {
            LOG.warn("Download directory owner check skipped: no unix attribute view on this file system");
        } else if (!r.probeUid().equals(r.dirUid())) {
            throw new AppStartupException(where + "the directory is owned by another user than the one that "
                    + "creates files in it; use a directory the server's user owns");
        }
        if (!r.mode0600()) {
            LOG.warn("Download directory file system does not keep mode 0600; 'never executable' is not guaranteed");
        }
        if (!r.hardLinks()) {
            LOG.info("Download directory refuses hard links; publishing by reservation and rename");
        }
        int swept = sweep(real, now);
        if (swept > 0) {
            LOG.warn("Removed {} leftover temporary download file(s)", swept);
        }
        try {
            return DownloadDirectory.of(real, r.hardLinks(), cfg.maxBytes(), cfg.displayDir());
        } catch (IOException e) {
            throw new AppStartupException(where + "the directory cannot be read");
        }
    }

    private static void checkPermissions(Path real, String where) {
        try {
            Set<PosixFilePermission> p = Files.getPosixFilePermissions(real);
            if (p.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new AppStartupException(where + "the directory is writable by others; remove that permission");
            }
            if (p.contains(PosixFilePermission.GROUP_WRITE)) {
                LOG.warn("Download directory is writable by its group");
            }
        } catch (UnsupportedOperationException e) {
            LOG.warn("Download directory permissions cannot be checked on this file system");
        } catch (IOException e) {
            throw new AppStartupException(where + "the directory permissions cannot be read");
        }
    }

    private static void reject(Path real, Path other, String what, String where) {
        if (other == null || !Files.exists(other)) {
            return;
        }
        try {
            if (Files.isSameFile(real, other)) {
                throw new AppStartupException(where + "the directory " + what
                        + "; a downloaded file there would be adopted on a later start");
            }
        } catch (IOException e) {
            // cannot compare: cannot be the same file we could use either
        }
    }

    private static ProbeResult probe(Path dir) throws IOException {
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        boolean unix = dir.getFileSystem().supportedFileAttributeViews().contains("unix");
        Path first = dir.resolve(tempName());
        Path second = dir.resolve(tempName());
        boolean linked = false;
        try {
            var opts = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            if (posix) {
                Files.newByteChannel(first, opts, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------"))).close();
            } else {
                Files.newByteChannel(first, opts).close();
            }
            boolean mode = posix && Files.getPosixFilePermissions(first, LinkOption.NOFOLLOW_LINKS)
                    .equals(PosixFilePermissions.fromString("rw-------"));
            Integer probeUid = unix ? (Integer) Files.getAttribute(first, "unix:uid", LinkOption.NOFOLLOW_LINKS) : null;
            Integer dirUid = unix ? (Integer) Files.getAttribute(dir, "unix:uid") : null;
            try {
                Files.createLink(second, first);
                linked = true;
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                linked = false;
            }
            return new ProbeResult(probeUid, dirUid, mode, linked);
        } finally {
            for (Path p : new Path[] {first, second}) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warn("Could not remove a download directory probe file; the startup sweep will");
                }
            }
        }
    }

    private static String tempName() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return ".imap-mcp-" + HexFormat.of().formatHex(b) + ".part";
    }

    /** Deletes regular, unlinked {@code .imap-mcp-<16 hex>.part} files older than 20 min; returns the count. */
    static int sweep(Path dir, Instant now) {
        int removed = 0;
        Instant cutoff = now.minus(SWEEP_AGE);
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path p : s) {
                if (!TEMP_NAME.matcher(p.getFileName().toString()).matches()) {
                    continue;
                }
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (a.isRegularFile() && a.lastModifiedTime().toInstant().isBefore(cutoff)
                            && Files.deleteIfExists(p)) {
                        removed++;
                    }
                } catch (IOException e) {
                    // vanished or not removable: left for the next start
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not scan the download directory for leftover temporary files");
        }
        return removed;
    }
}
