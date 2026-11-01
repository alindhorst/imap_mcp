package de.alexanderlindhorst.imapmcp.store.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates and persists the bearer token, the handle master key and the installation salt as base64url
 * (no padding) text files with 0600 permissions in a 0700 directory. Configured values win and are never
 * written. Files are published atomically without replacing an existing one, so concurrent first starts end
 * up with the same value. Values are never logged.
 */
public final class StateDirectory {

    private static final Logger LOG = LoggerFactory.getLogger(StateDirectory.class);
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int KEY_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int MIN_TOKEN_CHARS = 32;

    private final Path dir;
    private final char[] configuredToken;
    private final char[] configuredHandleKey;
    private final boolean posix;
    private boolean dirChecked;
    private char[] token;
    private byte[] handleKey;
    private byte[] salt;

    public StateDirectory(Path dir) {
        this(dir, null, null);
    }

    /**
     * @param configuredToken     already resolved {@code http.token}, or null
     * @param configuredHandleKey already resolved {@code security.handleKey} (base64url of 32 bytes), or null
     */
    public StateDirectory(Path dir, char[] configuredToken, char[] configuredHandleKey) {
        this.dir = dir;
        this.configuredToken = configuredToken == null ? null : configuredToken.clone();
        this.configuredHandleKey = configuredHandleKey == null ? null : configuredHandleKey.clone();
        this.posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** Fails startup if the token cannot be configured or persisted: the server must not run without one. */
    public synchronized char[] bearerToken() {
        if (token == null) {
            if (configuredToken != null) {
                if (configuredToken.length < MIN_TOKEN_CHARS) {
                    throw new StateDirectoryException("Configured http.token is shorter than " + MIN_TOKEN_CHARS
                            + " characters; configure a longer secret.");
                }
                token = configuredToken.clone();
            } else {
                Path file = dir.resolve("http-token");
                try {
                    String v = loadOrCreate("http-token", KEY_BYTES, "bearer token");
                    if (v.length() < MIN_TOKEN_CHARS) {
                        throw new StateDirectoryException("Persisted bearer token at " + file
                                + " is too short; delete the file to generate a new one.");
                    }
                    token = v.toCharArray();
                } catch (IOException e) {
                    throw new StateDirectoryException("Cannot read or create the bearer token at " + file
                            + "; make the state directory writable or configure http.token.", e);
                }
            }
        }
        return token.clone();
    }

    /** 32-byte master key for the key ring. Ephemeral (with a WARN) if it cannot be persisted. */
    public synchronized byte[] handleKey() {
        if (handleKey == null) {
            if (configuredHandleKey != null) {
                handleKey = decodeExact(new String(configuredHandleKey), KEY_BYTES,
                        "Configured security.handleKey must be the base64url (no padding) encoding of exactly "
                                + KEY_BYTES + " bytes.");
            } else {
                handleKey = persistedOrEphemeral("handle-key", KEY_BYTES, "handle key",
                        "handles will be invalid after restart");
            }
        }
        return handleKey.clone();
    }

    /** 16-byte salt for account handles. Ephemeral (with a WARN) if it cannot be persisted. */
    public synchronized byte[] installationSalt() {
        if (salt == null) {
            salt = persistedOrEphemeral("installation-salt", SALT_BYTES, "installation salt",
                    "account handles will change after restart");
        }
        return salt.clone();
    }

    private byte[] persistedOrEphemeral(String name, int bytes, String what, String consequence) {
        Path file = dir.resolve(name);
        try {
            return decodeExact(loadOrCreate(name, bytes, what), bytes,
                    "Persisted " + what + " at " + file + " is corrupt; delete the file to generate a new one.");
        } catch (IOException e) {
            LOG.warn("Cannot persist the {} under {}; using an ephemeral value, {}", what, file, consequence);
            byte[] b = new byte[bytes];
            RANDOM.nextBytes(b);
            return b;
        }
    }

    private static byte[] decodeExact(String encoded, int bytes, String errorMessage) {
        try {
            byte[] b = Base64.getUrlDecoder().decode(encoded);
            if (b.length == bytes) {
                return b;
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to the message below, which does not echo the value
        }
        throw new StateDirectoryException(errorMessage);
    }

    /** Returns the stored value, or generates one and publishes it atomically; the winner's value is re-read. */
    private String loadOrCreate(String name, int bytes, String what) throws IOException {
        Path target = dir.resolve(name);
        if (Files.isDirectory(dir)) {
            checkDirectoryOnce();
        }
        String existing = readIfPresent(target);
        if (existing != null) {
            return existing;
        }
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir, posixAttr("rwx------"));
            checkDirectoryOnce();
        }
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        String value = ENC.encodeToString(raw);
        Path tmpFile = dir.resolve("." + name + "." + HexFormat.of().formatHex(raw, 0, 4) + ".tmp");
        Files.createFile(tmpFile, posixAttr("rw-------"));
        try {
            Files.writeString(tmpFile, value, StandardCharsets.US_ASCII);
            publish(tmpFile, target);
            LOG.info("generated {} at {}", what, target);
            return value;
        } catch (FileAlreadyExistsException lost) {
            String winner = readIfPresent(target);
            if (winner == null) {
                throw lost;
            }
            return winner;
        } finally {
            Files.deleteIfExists(tmpFile);
        }
    }

    /** Hard link fails atomically if the target exists and exposes only complete content; move is the fallback. */
    private static void publish(Path tmpFile, Path target) throws IOException {
        try {
            Files.createLink(target, tmpFile);
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (IOException | UnsupportedOperationException e) {
            Files.move(tmpFile, target);
        }
    }

    private static String readIfPresent(Path file) throws IOException {
        try {
            String v = Files.readString(file, StandardCharsets.US_ASCII).strip();
            if (v.isEmpty()) {
                throw new StateDirectoryException(
                        "State file " + file + " is empty; delete it to generate a new value.");
            }
            return v;
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private void checkDirectoryOnce() {
        if (dirChecked) {
            return;
        }
        dirChecked = true;
        if (!posix) {
            LOG.warn("State directory {} is on a non-POSIX file system; file permissions cannot be enforced", dir);
            return;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir);
            if (perms.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"))) {
                LOG.warn("State directory {} is accessible by group or others; restrict it to 0700", dir);
            }
        } catch (IOException e) {
            LOG.warn("Cannot read the permissions of state directory {}", dir);
        }
    }

    @SuppressWarnings("unchecked")
    private FileAttribute<?>[] posixAttr(String perms) {
        return posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms))}
                : new FileAttribute<?>[0];
    }
}
