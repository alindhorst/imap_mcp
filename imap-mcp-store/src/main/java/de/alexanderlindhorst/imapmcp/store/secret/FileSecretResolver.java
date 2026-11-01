package de.alexanderlindhorst.imapmcp.store.secret;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves {@code file:/absolute/path} (Docker secrets, mounted files); the file is read on every resolve. */
final class FileSecretResolver implements SecretResolver {

    private static final Logger LOG = LoggerFactory.getLogger(FileSecretResolver.class);

    @Override
    public void validate(String target) {
        Path path = path(target);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new SecretResolutionException("secret file " + path + " does not exist or is not readable");
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                LOG.warn("secret file {} is group- or world-readable; restrict it to the service user", path);
            }
        } catch (UnsupportedOperationException | IOException e) {
            // no POSIX permissions available: nothing to check
        }
    }

    @Override
    public Secret resolve(String target) {
        Path path = path(target);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new SecretResolutionException("secret file " + path + " cannot be read");
        }
        int end = bytes.length;
        if (end > 0 && bytes[end - 1] == '\n') {
            end--;
            if (end > 0 && bytes[end - 1] == '\r') {
                end--;
            }
        }
        if (end == 0) {
            throw new SecretResolutionException("secret file " + path + " is empty");
        }
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes, 0, end));
        char[] chars = new char[decoded.remaining()];
        decoded.get(chars);
        Arrays.fill(decoded.array(), '\0');
        Arrays.fill(bytes, (byte) 0);
        return new Secret(chars);
    }

    private static Path path(String target) {
        Path path;
        try {
            path = Path.of(target);
        } catch (InvalidPathException e) {
            throw new SecretResolutionException("file: reference has an invalid path");
        }
        if (!path.isAbsolute()) {
            throw new SecretResolutionException("file: reference must be an absolute path");
        }
        return path;
    }
}
