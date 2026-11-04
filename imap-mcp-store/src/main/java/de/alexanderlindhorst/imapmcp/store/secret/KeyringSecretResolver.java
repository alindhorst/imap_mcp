package de.alexanderlindhorst.imapmcp.store.secret;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.store.secret.KeyringLookupException.Kind;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
            if (platform == Platform.MACOS && looksHexPrinted(out, end)) {
                return macosHexSecret(out, end, service, account, timeout, what);
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

    /**
     * {@code security -w} prints the password as bare lowercase hex ({@code printf "%02x"}) when any byte is outside
     * 0x20..0x7e (Apple Security, SecurityTool/macOS/keychain_find.c, do_password_item_printing), so non-ASCII
     * passwords arrive hex-encoded without any marker. An ASCII password that happens to be even-length lowercase
     * hex looks identical, so such output is disambiguated with {@code -g}, whose stderr line is
     * {@code password: "..."} for printable ASCII and {@code password: 0xHEX  "..."} otherwise
     * (keychain_utilities.c, print_buffer).
     */
    private static boolean looksHexPrinted(byte[] out, int end) {
        if (end == 0 || end % 2 != 0) {
            return false;
        }
        for (int i = 0; i < end; i++) {
            byte b = out[i];
            if (!((b >= '0' && b <= '9') || (b >= 'a' && b <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private Secret macosHexSecret(byte[] wOut, int wEnd, String service, String account, Duration timeout, String what) {
        List<String> argv = List.of("/usr/bin/security", "find-generic-password", "-s", service, "-a", account, "-g");
        CommandRunner.Result g;
        try {
            g = runner.run(argv, timeout);
        } catch (TimeoutException e) {
            throw new KeyringLookupException(Kind.TIMEOUT,
                    "lookup of " + what + " timed out after " + timeout.toMillis() + " ms; is the keyring locked?");
        } catch (IOException e) {
            throw new KeyringLookupException(Kind.COMMAND_UNAVAILABLE,
                    "cannot run /usr/bin/security for " + what + "; is it installed and on the PATH?");
        }
        byte[] err = g.stderr();
        byte[] gOut = g.stdout();
        byte[] bytes = null;
        try {
            LOG.debug("security -g finished with exit code {}", g.exitCode());
            if (g.exitCode() == 0) {
                int[] span = passwordSpan(err);
                if (span == null) {
                    // quoted form: genuine ASCII password, the -w output was literal text
                    return decodeUtf8(wOut, wEnd, what);
                }
                if (span[1] - span[0] == wEnd && hexEqualsIgnoreCase(err, span[0], wOut, wEnd)) {
                    bytes = new byte[wEnd / 2];
                    for (int i = 0; i < bytes.length; i++) {
                        bytes[i] = (byte) (Character.digit(wOut[2 * i], 16) << 4 | Character.digit(wOut[2 * i + 1], 16));
                    }
                    return decodeUtf8(bytes, bytes.length, what);
                }
            }
            throw new KeyringLookupException(Kind.FAILED, "lookup of " + what
                    + " failed: the macOS keychain item could not be read unambiguously (security -w printed hex)");
        } finally {
            Arrays.fill(err, (byte) 0);
            Arrays.fill(gOut, (byte) 0);
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    /**
     * Locates the {@code 0x} hex digits of the {@code password:} line in {@code -g} stderr: {start, end} of the
     * digits, or {@code null} if the line is the quoted ASCII form. Throws if neither form is found.
     */
    private static int[] passwordSpan(byte[] err) {
        byte[] prefix = "password: ".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + prefix.length < err.length; i++) {
            if ((i == 0 || err[i - 1] == '\n') && Arrays.equals(err, i, i + prefix.length, prefix, 0, prefix.length)) {
                int p = i + prefix.length;
                if (err[p] == '"') {
                    return null;
                }
                if (p + 2 < err.length && err[p] == '0' && err[p + 1] == 'x') {
                    int q = p + 2;
                    while (q < err.length && Character.digit(err[q], 16) >= 0) {
                        q++;
                    }
                    return new int[] {p + 2, q};
                }
                break;
            }
        }
        return new int[] {0, -1}; // unrecognised; never equals the -w length
    }

    private static boolean hexEqualsIgnoreCase(byte[] a, int from, byte[] b, int len) {
        for (int i = 0; i < len; i++) {
            if (Character.toLowerCase((char) a[from + i]) != (char) b[i]) {
                return false;
            }
        }
        return true;
    }

    private static Secret decodeUtf8(byte[] bytes, int len, String what) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, len));
            char[] chars = new char[decoded.remaining()];
            decoded.get(chars);
            Arrays.fill(decoded.array(), '\0');
            return new Secret(chars);
        } catch (CharacterCodingException e) {
            throw new KeyringLookupException(Kind.FAILED,
                    "lookup of " + what + " failed: the stored password is not valid UTF-8");
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
