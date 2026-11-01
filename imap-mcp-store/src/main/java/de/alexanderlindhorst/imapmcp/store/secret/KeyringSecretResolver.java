package de.alexanderlindhorst.imapmcp.store.secret;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.store.secret.KeyringLookupException.Kind;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks up secrets in the OS keyring through its CLI ({@code secret-tool} on Linux, {@code /usr/bin/security} on
 * macOS). Arguments go as an argv array (no shell). Command output is never logged.
 */
public final class KeyringSecretResolver {

    private static final Logger LOG = LoggerFactory.getLogger(KeyringSecretResolver.class);
    private static final int MACOS_NOT_FOUND = 44;

    private final CommandRunner runner;

    public KeyringSecretResolver(CommandRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public Secret lookup(Platform platform, String service, String account, Duration timeout) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(account, "account");
        List<String> argv = argv(platform, service, account);
        String command = argv.getFirst();
        String what = "keyring entry (service '" + service + "', account '" + account + "')";

        CommandRunner.Result result;
        try {
            result = runner.run(argv, timeout);
        } catch (TimeoutException e) {
            throw new KeyringLookupException(Kind.TIMEOUT,
                    "lookup of " + what + " timed out after " + timeout.toMillis() + " ms; is the keyring locked?");
        } catch (IOException e) {
            throw new KeyringLookupException(Kind.COMMAND_UNAVAILABLE,
                    "cannot run " + command + " for " + what + "; is it installed and on the PATH?");
        }

        byte[] out = result.stdout();
        try {
            LOG.debug("{} finished with exit code {}, stderr length {}", command, result.exitCode(), result.stderrLength());
            int end = out.length;
            if (result.exitCode() != 0) {
                boolean notFound = platform == Platform.MACOS
                        ? result.exitCode() == MACOS_NOT_FOUND
                        : result.exitCode() == 1 && end == 0;
                if (notFound) {
                    throw new KeyringLookupException(Kind.NOT_FOUND, what + " not found; create it first");
                }
                throw new KeyringLookupException(Kind.FAILED,
                        "lookup of " + what + " failed: " + command + " exited with code " + result.exitCode());
            }
            if (end > 0 && out[end - 1] == '\n') {
                end--;
            }
            if (end == 0) {
                throw new KeyringLookupException(Kind.NOT_FOUND, what + " not found or empty");
            }
            CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(out, 0, end));
            char[] chars = new char[decoded.remaining()];
            decoded.get(chars);
            Arrays.fill(decoded.array(), '\0');
            return new Secret(chars);
        } finally {
            Arrays.fill(out, (byte) 0);
        }
    }

    private static List<String> argv(Platform platform, String service, String account) {
        return switch (platform) {
            case LINUX -> List.of("secret-tool", "lookup", "service", service, "account", account);
            case MACOS -> List.of("/usr/bin/security", "find-generic-password", "-s", service, "-a", account, "-w");
            case WINDOWS, OTHER -> throw new KeyringLookupException(Kind.UNSUPPORTED_PLATFORM,
                    "OS keyring is not supported on " + platform + " (keyring entry '" + service + "'/'" + account + "')");
        };
    }
}
