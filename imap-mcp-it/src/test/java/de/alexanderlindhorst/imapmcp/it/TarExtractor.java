package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Defensive extraction of a tar stream copied out of a container, whose content is untrusted: regular files only
 * (symlinks, hard links, devices and directories are skipped), entries whose normalised path leaves the target
 * directory are skipped, and a per-file and a total size cap truncate instead of filling the disk.
 */
final class TarExtractor {

    private TarExtractor() {
    }

    /** Extracts below {@code target}; returns the number of files written. */
    static int extract(InputStream tar, Path target, long perFileCap, long totalCap) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        long total = 0;
        int files = 0;
        try (TarArchiveInputStream in = new TarArchiveInputStream(tar)) {
            TarArchiveEntry entry;
            while (total < totalCap && (entry = in.getNextEntry()) != null) {
                if (!entry.isFile() || entry.isLink() || entry.isSymbolicLink() || entry.isCharacterDevice()
                        || entry.isBlockDevice() || entry.isFIFO()) {
                    continue;
                }
                Path out;
                try {
                    out = root.resolve(entry.getName()).normalize();
                } catch (java.nio.file.InvalidPathException e) {
                    continue;
                }
                if (!out.startsWith(root) || out.equals(root)) {
                    continue;
                }
                Files.createDirectories(out.getParent());
                long budget = Math.min(perFileCap, totalCap - total);
                try (OutputStream os = Files.newOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    long written = 0;
                    int r;
                    while (written < budget && (r = in.read(buf, 0, (int) Math.min(buf.length, budget - written))) > 0) {
                        os.write(buf, 0, r);
                        written += r;
                    }
                    total += written;
                }
                files++;
            }
        }
        return files;
    }
}
