package de.alexanderlindhorst.imapmcp.core.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * The resolved download directory of {@code download_attachment} (SPEC 5.3, 5.4, 5.7): the real path resolved
 * once at startup, the identity of that directory, the publication strategy decided by the startup probe, the size
 * limit and the directory shown in the output. Holds no mail-derived value; the startup checks live in the app
 * module, the file sink reads this class.
 */
public final class DownloadDirectory {

    private final Path path;
    private final Object fileKey;
    private final boolean hardLinks;
    private final long maxBytes;
    private final Path displayDir;

    private DownloadDirectory(Path path, Object fileKey, boolean hardLinks, long maxBytes, Path displayDir) {
        this.path = path;
        this.fileKey = fileKey;
        this.hardLinks = hardLinks;
        this.maxBytes = maxBytes;
        this.displayDir = displayDir;
    }

    /**
     * @param realPath  the directory after {@code toRealPath()}
     * @param hardLinks whether the startup probe could hard-link (false selects the reservation fallback, 5.4)
     * @param displayDir {@code tools.download.displayDir} or null
     */
    public static DownloadDirectory of(Path realPath, boolean hardLinks, long maxBytes, Path displayDir)
            throws IOException {
        Objects.requireNonNull(realPath);
        BasicFileAttributes a = Files.readAttributes(realPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new DownloadDirectory(realPath, a.fileKey(), hardLinks, maxBytes, displayDir);
    }

    public Path path() {
        return path;
    }

    public boolean hardLinks() {
        return hardLinks;
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** {@code tools.download.displayDir} if set, else the real directory (configuration, not email-derived). */
    public String reportedDirectory() {
        return (displayDir != null ? displayDir : path).toString();
    }

    /** Usable space of the file store holding the directory (SPEC 4, step 9). */
    public long usableSpace() {
        try {
            return Files.getFileStore(path).getUsableSpace();
        } catch (IOException e) {
            throw new DownloadStorageException(DownloadStorageException.Reason.IO_ERROR, e);
        }
    }

    /**
     * Call-time check (SPEC 4, step 4): still a directory, reached without a link in the last component, and the
     * same file as at startup. Any failure to look is "not available".
     */
    public boolean stillAvailable() {
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.isDirectory() && (fileKey == null || fileKey.equals(a.fileKey()));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
