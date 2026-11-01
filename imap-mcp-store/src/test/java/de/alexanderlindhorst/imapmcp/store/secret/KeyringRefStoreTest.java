package de.alexanderlindhorst.imapmcp.store.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class KeyringRefStoreTest {

    private static final String MARKER = "MARKER-SECRET-62k";
    private static final TenantId OWNER = new TenantId("owner");

    /** Fake runner answering per account (last argv element before -w / last element). */
    static final class FakeRunner implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        int exitCode;
        String out = MARKER + "\n";

        @Override
        public Result run(List<String> argv, Duration timeout) throws IOException {
            calls.add(List.copyOf(argv));
            return new Result(exitCode, out.getBytes(StandardCharsets.UTF_8), 0);
        }
    }

    private final FakeRunner runner = new FakeRunner();
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

    private static MailAccount account(String id, String ref) {
        return new MailAccount(new AccountId(id), id, null, "imap.example.com", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "user", new SecretRef(ref));
    }

    private RefCredentialStore store(Platform p, boolean binary, List<MailAccount> accounts) {
        return RefCredentialStore.withEnvAndKeyring(OWNER, accounts, Map.of("E", MARKER), p, runner, pl -> binary,
                Duration.ofSeconds(5));
    }

    private static String text(Secret s) {
        return new String(s.chars());
    }

    @Test
    void refParsesSplittingAtFirstSlash() {
        var store = store(Platform.LINUX, true, List.of());
        store.validate(new SecretRef("keyring:imap-mcp/work@example.com"));
        store.validate(new SecretRef("keyring:a/b/c"));
        assertThat(runner.calls.get(0)).containsSubsequence("service", "imap-mcp", "account", "work@example.com");
        assertThat(runner.calls.get(1)).containsSubsequence("service", "a", "account", "b/c");
    }

    @Test
    void malformedRefsAreRejectedWithoutRunningAnything() {
        var store = store(Platform.LINUX, true, List.of());
        String big = "x".repeat(256);
        for (String bad : new String[] {"keyring:", "keyring:/a", "keyring:s/", "keyring:noslash", "keyring:s/a\u0007b",
                "keyring:s\n/a", "keyring:" + big + "/a", "keyring:s/" + big}) {
            assertThatThrownBy(() -> store.validate(new SecretRef(bad)))
                    .as(bad).isInstanceOf(SecretResolutionException.class).hasMessageContaining("keyring:");
        }
        store.validate(new SecretRef("keyring:" + "x".repeat(255) + "/" + "y".repeat(255)));
        assertThat(runner.calls).hasSize(1);
    }

    @Test
    void malformedRefInValidateAllNamesTheAccount() {
        var store = store(Platform.LINUX, true, List.of(account("work", "keyring:/a")));
        assertThatThrownBy(store::validateAll).isInstanceOf(SecretResolutionException.class)
                .hasMessageStartingWith("account 'work': ");
    }

    @Test
    void windowsIsRejectedWithoutRunningACommand() {
        var store = store(Platform.WINDOWS, true, List.of());
        assertThatThrownBy(() -> store.validate(new SecretRef("keyring:s/a")))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("not supported on Windows").hasMessageContaining("env: or file:");
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void missingBinaryGivesKeyringNotAvailableHere() {
        var store = store(Platform.LINUX, false, List.of());
        assertThatThrownBy(() -> store.validate(new SecretRef("keyring:s/a")))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("keyring not available here").hasMessageContaining("env:");
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void eachRefIsResolvedOnceAtStartupAndNeverAgain() {
        var accounts = List.of(account("a1", "keyring:svc/one"), account("a2", "keyring:svc/two"));
        var store = store(Platform.LINUX, true, accounts);
        store.validateAll();
        assertThat(runner.calls).hasSize(2);
        for (int i = 0; i < 10; i++) {
            for (MailAccount a : accounts) {
                try (Secret s = store.resolve(OWNER, a.id()).orElseThrow()) {
                    assertThat(text(s)).isEqualTo(MARKER);
                }
            }
        }
        assertThat(runner.calls).hasSize(2);
    }

    @Test
    void resolveReturnsIndependentCopiesSoClosingDoesNotWipeTheCache() {
        var store = store(Platform.LINUX, true, List.of());
        store.validate(new SecretRef("keyring:svc/one"));
        Secret first = store.resolve(new SecretRef("keyring:svc/one"));
        first.close();
        assertThat(text(store.resolve(new SecretRef("keyring:svc/one")))).isEqualTo(MARKER);
    }

    @Test
    void resolveOfNeverValidatedKeyringRefFailsWithoutRunningACommand() {
        var store = store(Platform.LINUX, true, List.of());
        assertThatThrownBy(() -> store.resolve(new SecretRef("keyring:svc/one")))
                .isInstanceOf(SecretResolutionException.class).hasMessageContaining("not been validated");
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void runnerFailureNamesAccountAndServiceButNoValue() {
        runner.exitCode = 2;
        var store = store(Platform.LINUX, true, List.of(account("work", "keyring:imap-mcp/me@example.com")));
        assertThatThrownBy(store::validateAll).isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("account 'work'").hasMessageContaining("imap-mcp").hasMessageContaining("me@example.com")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(MARKER));
        assertThat(logs.list).allSatisfy(ev -> assertThat(ev.getFormattedMessage()).doesNotContain(MARKER));
    }

    @Test
    void markerNeverAppearsInToStringOrLogsOnSuccess() {
        var store = store(Platform.LINUX, true, List.of(account("work", "keyring:svc/one")));
        store.validateAll();
        Secret s = store.resolve(OWNER, new AccountId("work")).orElseThrow();
        assertThat(s.toString()).doesNotContain(MARKER);
        assertThat(store.toString()).doesNotContain(MARKER);
        assertThat(logs.list).allSatisfy(ev -> assertThat(ev.getFormattedMessage()).doesNotContain(MARKER));
    }

    @Test
    void envAndFileStayLazyWhenKeyringIsWiredIn() {
        var store = store(Platform.LINUX, true, List.of());
        assertThat(text(store.resolve(new SecretRef("env:E")))).isEqualTo(MARKER);
        assertThat(runner.calls).isEmpty();
    }

    @Test
    void validateAllCoversEnvRefsAndNamesTheAccount() {
        var store = store(Platform.LINUX, true, List.of(account("ok", "env:E"), account("broken", "env:MISSING")));
        assertThatThrownBy(store::validateAll).isInstanceOf(SecretResolutionException.class)
                .hasMessageStartingWith("account 'broken': ").hasMessageContaining("MISSING");
    }

    @Test
    void withEnvStillRejectsKeyring() {
        var store = RefCredentialStore.withEnv(OWNER, List.of(), Map.of());
        assertThatThrownBy(() -> store.validate(new SecretRef("keyring:s/a")))
                .isInstanceOf(SecretResolutionException.class).hasMessageContaining("keyring not available here");
    }

    @Test
    void pathProbeFindsExecutableOnInjectedPath(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws IOException {
        java.nio.file.Path tool = java.nio.file.Files.createFile(dir.resolve("secret-tool"));
        assertThat(BinaryProbe.system(Map.of("PATH", "/nonexistent:" + dir)).available(Platform.LINUX)).isFalse();
        tool.toFile().setExecutable(true);
        assertThat(BinaryProbe.system(Map.of("PATH", "/nonexistent:" + dir)).available(Platform.LINUX)).isTrue();
        assertThat(BinaryProbe.system(Map.of()).available(Platform.LINUX)).isFalse();
        assertThat(BinaryProbe.system(Map.of("PATH", dir.toString())).available(Platform.WINDOWS)).isFalse();
    }
}
