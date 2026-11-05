package de.alexanderlindhorst.imapmcp.core.download;

import de.alexanderlindhorst.imapmcp.core.download.DownloadStorageException.Reason;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only class of {@code imap-mcp-core} that creates, writes, links, moves or deletes files (SPEC 5.4 "Write",
 * enforced by an architecture rule). One instance per download: a temporary {@code .imap-mcp-<16 hex>.part} file is
 * created with CREATE_NEW and mode 0600, the decoded bytes are written (counted, hashed, capped at
 * {@code maxBytes}), and {@link #publish} gives it its final name without replacing anything, by hard link or, where
 * the startup probe found links refused, by reserving the name and renaming over the reservation. {@link #close}
 * removes whatever is left (the temporary file and a reservation) unless the file was published, so it belongs in a
 * {@code finally}. Links are never followed in the last path component. Nothing here logs a name or content.
 */
public final class DownloadSink implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadSink.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_CANDIDATES = 99;
    private static final long ONE_BLOCK = 1024 * 1024;
    private static final Set<StandardOpenOption> CREATE_NEW = Set.of(StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);

    /** A point in the sink's life where {@link Hooks} may act (tests inject failures and interrupts there). */
    enum Step { WRITE, FORCE, BEFORE_PUBLISH, AFTER_RESERVE }

    /** Test seam: observe or fail the steps, and replace the free-space reading. */
    interface Hooks {
        default void step(Step step) throws IOException {
        }

        default long usableSpace(Path dir) throws IOException {
            return Files.getFileStore(dir).getUsableSpace();
        }
    }

    /** @param savedAs the file name in the directory; @param sha256 lowercase hex of the written bytes */
    public record Published(String savedAs, long bytes, String sha256) {
    }

    private static final Hooks NO_HOOKS = new Hooks() {
    };

    private final DownloadDirectory directory;
    private final Hooks hooks;
    private final Path temp;
    private final FileChannel channel;
    private final MessageDigest digest;
    private long written;
    private Path reservation;
    private boolean published;
    private boolean closed;

    private DownloadSink(DownloadDirectory directory, Hooks hooks, Path temp, FileChannel channel) {
        this.directory = directory;
        this.hooks = hooks;
        this.temp = temp;
        this.channel = channel;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static DownloadSink open(DownloadDirectory directory) {
        return open(directory, NO_HOOKS);
    }

    static DownloadSink open(DownloadDirectory directory, Hooks hooks) {
        Path temp = directory.path().resolve(tempName());
        try {
            return new DownloadSink(directory, hooks, temp, FileChannel.open(temp, CREATE_NEW, mode0600(temp)));
        } catch (NoSuchFileException e) {
            throw new DownloadStorageException(Reason.DIRECTORY_UNAVAILABLE, e);
        } catch (IOException e) {
            throw new DownloadStorageException(Reason.IO_ERROR, e);
        }
    }

    /** Appends decoded bytes. Beyond {@code maxBytes} the call fails with {@link DownloadTooLargeException}. */
    public void write(byte[] b, int off, int len) {
        if (written + len > directory.maxBytes()) {
            throw new DownloadTooLargeException();
        }
        try {
            hooks.step(Step.WRITE);
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
        } catch (IOException e) {
            throw failure(e);
        }
        digest.update(b, off, len);
        written += len;
    }

    /**
     * Forces the data to disk and gives the file its final name. The caller has already won
     * {@code tryBeginMutation()} (SPEC 5.5). Clashing names get {@code (2)} to {@code (99)}.
     */
    public Published publish(AttachmentFileName name) {
        try {
            hooks.step(Step.FORCE);
            channel.force(true);
            channel.close();
            hooks.step(Step.BEFORE_PUBLISH);
            String savedAs = directory.hardLinks() ? publishByLink(name) : publishByReservation(name);
            published = true;
            delete(temp, true);
            return new Published(savedAs, written, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            throw failure(e);
        }
    }

    private String publishByLink(AttachmentFileName name) throws IOException {
        for (int n = 1; n <= MAX_CANDIDATES; n++) {
            String candidate = name.candidate(n);
            try {
                Files.createLink(directory.path().resolve(candidate), temp);
                return candidate;
            } catch (FileAlreadyExistsException taken) {
                // next candidate
            }
        }
        throw new DownloadStorageException(Reason.NAME_EXHAUSTED, null);
    }

    private String publishByReservation(AttachmentFileName name) throws IOException {
        for (int n = 1; n <= MAX_CANDIDATES; n++) {
            String candidate = name.candidate(n);
            Path target = directory.path().resolve(candidate);
            try {
                FileChannel.open(target, CREATE_NEW, mode0600(target)).close();
            } catch (FileAlreadyExistsException taken) {
                continue;
            }
            reservation = target;
            hooks.step(Step.AFTER_RESERVE);
            try {
                // rename(2) replaces the empty reservation atomically
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic move unsupported", e);
            }
            reservation = null;
            return candidate;
        }
        throw new DownloadStorageException(Reason.NAME_EXHAUSTED, null);
    }

    /** Removes the temporary file and any reservation; a published file stays. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } catch (IOException e) {
            // the file is deleted next
        }
        if (reservation != null) {
            delete(reservation, false);
            reservation = null;
        }
        if (!published) {
            delete(temp, false);
        }
    }

    /** A leftover is swept at the next start; the failure is logged by exception class only (its text has the path). */
    private static void delete(Path p, boolean warn) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            if (warn) {
                LOG.warn("Could not remove a temporary download file ({})", e.getClass().getSimpleName());
            }
        }
    }

    /** SPEC 5.4: no_space is decided by the free space after the failure, never by exception text. */
    private DownloadStorageException failure(Throwable cause) {
        if (cause instanceof DownloadStorageException d) {
            return d;
        }
        try {
            if (hooks.usableSpace(directory.path()) < ONE_BLOCK) {
                return new DownloadStorageException(Reason.NO_SPACE, cause);
            }
        } catch (IOException | RuntimeException e) {
            // unknown free space: io_error
        }
        return new DownloadStorageException(Reason.IO_ERROR, cause);
    }

    private static FileAttribute<?>[] mode0600(Path p) {
        if (p.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------"))};
        }
        return new FileAttribute<?>[0];
    }

    private static String tempName() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return ".imap-mcp-" + HexFormat.of().formatHex(b) + ".part";
    }
}
