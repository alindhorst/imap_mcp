package de.alexanderlindhorst.imapmcp.store.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.store.secret.KeyringLookupException.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class KeyringSecretResolverTest {

    private static final String MARKER = "MARKER-SECRET-91d";
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    /** Fake runner: records argv, replays a canned outcome or simulates a hang (then records destruction). */
    static final class FakeRunner implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        int exitCode;
        byte[] stdout = new byte[0];
        int stderrLength;
        boolean hang;
        volatile boolean destroyed;
        IOException failToStart;

        FakeRunner reply(int exit, String out) {
            exitCode = exit;
            stdout = out.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        @Override
        public Result run(List<String> argv, Duration timeout) throws IOException, TimeoutException {
            calls.add(List.copyOf(argv));
            if (failToStart != null) {
                throw failToStart;
            }
            if (hang) {
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                destroyed = true;
                throw new TimeoutException("timed out");
            }
            return new Result(exitCode, stdout.clone(), stderrLength);
        }
    }

    private final FakeRunner runner = new FakeRunner();
    private final KeyringSecretResolver resolver = new KeyringSecretResolver(runner);
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp.store.secret");
        logger.setLevel(Level.TRACE);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    private String lookup(Platform p, String service, String account) {
        try (Secret s = resolver.lookup(p, service, account, TIMEOUT)) {
            return new String(s.chars());
        }
    }

    @Test
    void linuxArgvIsExact() {
        runner.reply(0, "pw");
        lookup(Platform.LINUX, "imap-mcp", "alice");
        assertThat(runner.calls).containsExactly(List.of("secret-tool", "lookup", "service", "imap-mcp", "account", "alice"));
    }

    @Test
    void macosArgvIsExact() {
        runner.reply(0, "pw\n");
        lookup(Platform.MACOS, "imap-mcp", "alice");
        assertThat(runner.calls)
                .containsExactly(List.of("/usr/bin/security", "find-generic-password", "-s", "imap-mcp", "-a", "alice", "-w"));
    }

    @Test
    void specialCharactersArePassedLiterally() {
        runner.reply(0, "pw");
        String svc = "my svc \"q\" $(rm -rf /)";
        String acc = "a'b `c` ; x";
        lookup(Platform.LINUX, svc, acc);
        lookup(Platform.MACOS, svc, acc);
        assertThat(runner.calls.get(0)).containsSubsequence("service", svc, "account", acc);
        assertThat(runner.calls.get(1)).containsSubsequence("-s", svc, "-a", acc);
    }

    @Test
    void stripsExactlyOneTrailingNewline() {
        runner.reply(0, "pw\n");
        assertThat(lookup(Platform.LINUX, "s", "a")).isEqualTo("pw");
        runner.reply(0, "pw\n\n");
        assertThat(lookup(Platform.LINUX, "s", "a")).isEqualTo("pw\n");
        runner.reply(0, "pw");
        assertThat(lookup(Platform.LINUX, "s", "a")).isEqualTo("pw");
        runner.reply(0, "päss\n");
        assertThat(lookup(Platform.LINUX, "s", "a")).isEqualTo("päss");
    }

    @Test
    void secretToolExit1WithEmptyStdoutIsNotFound() {
        runner.reply(1, "");
        assertThatThrownBy(() -> lookup(Platform.LINUX, "imap-mcp", "alice"))
                .isInstanceOfSatisfying(KeyringLookupException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND);
                    assertThat(e.getMessage()).contains("imap-mcp", "alice");
                });
    }

    @Test
    void securityExit44IsNotFound() {
        runner.reply(44, "");
        assertThatThrownBy(() -> lookup(Platform.MACOS, "imap-mcp", "alice"))
                .isInstanceOfSatisfying(KeyringLookupException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND);
                    assertThat(e.getMessage()).contains("imap-mcp", "alice");
                });
    }

    @Test
    void otherExitCodesAreGenericFailureAndLeakNothing() {
        runner.reply(2, MARKER);
        runner.stderrLength = 12;
        assertThatThrownBy(() -> lookup(Platform.LINUX, "imap-mcp", "alice"))
                .isInstanceOfSatisfying(KeyringLookupException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.FAILED);
                    assertThat(e.getMessage()).contains("imap-mcp", "alice", "2").doesNotContain(MARKER);
                });
        runner.reply(1, MARKER); // exit 1 with non-empty stdout is not "not found"
        assertThatThrownBy(() -> lookup(Platform.LINUX, "imap-mcp", "alice"))
                .isInstanceOfSatisfying(KeyringLookupException.class, e -> assertThat(e.kind()).isEqualTo(Kind.FAILED));
        assertThat(logs.list).allSatisfy(ev -> assertThat(ev.getFormattedMessage()).doesNotContain(MARKER));
    }

    @Test
    void emptySecretIsAFailure() {
        runner.reply(0, "\n");
        assertThatThrownBy(() -> lookup(Platform.LINUX, "imap-mcp", "alice"))
                .isInstanceOfSatisfying(KeyringLookupException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND));
    }

    @Test
    void commandThatCannotStartIsActionable() {
        runner.failToStart = new IOException("Cannot run program \"secret-tool\" " + MARKER);
        assertThatThrownBy(() -> lookup(Platform.LINUX, "imap-mcp", "alice"))
                .isInstanceOfSatisfying(KeyringLookupException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.COMMAND_UNAVAILABLE);
                    assertThat(e.getMessage()).contains("secret-tool", "imap-mcp", "alice").doesNotContain(MARKER);
                });
    }

    @Test
    void hangingCommandTimesOutAndIsDestroyed() {
        runner.hang = true;
        long start = System.nanoTime();
        assertThatThrownBy(() -> lookup(Platform.LINUX, "imap-mcp", "alice"))
                .isInstanceOfSatisfying(KeyringLookupException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TIMEOUT);
                    assertThat(e.getMessage()).contains("imap-mcp", "alice");
                });
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(TIMEOUT.plusSeconds(1));
        assertThat(runner.destroyed).isTrue();
    }

    @Test
    void windowsAndOtherAreUnsupportedWithoutRunningAnything() {
        for (Platform p : new Platform[] {Platform.WINDOWS, Platform.OTHER}) {
            assertThatThrownBy(() -> lookup(p, "imap-mcp", "alice"))
                    .isInstanceOfSatisfying(KeyringLookupException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNSUPPORTED_PLATFORM));
        }
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void secretIsRedactedAndNoLogEventCarriesStdout() {
        runner.reply(0, MARKER + "\n");
        runner.stderrLength = 3;
        try (Secret s = resolver.lookup(Platform.LINUX, "imap-mcp", "alice", TIMEOUT)) {
            assertThat(s.toString()).doesNotContain(MARKER);
            assertThat(new String(s.chars())).isEqualTo(MARKER);
        }
        assertThat(logs.list).allSatisfy(ev -> {
            assertThat(ev.getFormattedMessage()).doesNotContain(MARKER);
            assertThat(String.valueOf(ev.getArgumentArray() == null ? "" : String.join(",", java.util.Arrays.stream(ev.getArgumentArray()).map(String::valueOf).toList())))
                    .doesNotContain(MARKER);
        });
    }

    @Test
    void platformMapsOsNames() {
        assertThat(Platform.fromOsName("Linux")).isEqualTo(Platform.LINUX);
        assertThat(Platform.fromOsName("Mac OS X")).isEqualTo(Platform.MACOS);
        assertThat(Platform.fromOsName("Windows 11")).isEqualTo(Platform.WINDOWS);
        assertThat(Platform.fromOsName("FreeBSD")).isEqualTo(Platform.OTHER);
        assertThat(Platform.fromOsName(null)).isEqualTo(Platform.OTHER);
    }
}
