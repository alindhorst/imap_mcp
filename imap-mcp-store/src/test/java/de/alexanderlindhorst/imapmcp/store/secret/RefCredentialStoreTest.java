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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class RefCredentialStoreTest {

    private static final String MARKER = "MARKER-SECRET-77c";
    private static final TenantId OWNER = new TenantId("owner");

    @TempDir
    Path dir;

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp.store.secret");
        logger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    private static RefCredentialStore store(Map<String, String> env, List<MailAccount> accounts) {
        return RefCredentialStore.withEnv(OWNER, accounts, env);
    }

    private static RefCredentialStore store(Map<String, String> env) {
        return store(env, List.of());
    }

    private static String text(Secret s) {
        return new String(s.chars());
    }

    @Test
    void envRefResolves() {
        var store = store(Map.of("IMAP_PW", MARKER));
        assertThat(text(store.resolve(new SecretRef("env:IMAP_PW")))).isEqualTo(MARKER);
    }

    @Test
    void unsetOrEmptyEnvFailsValidationNamingVariable() {
        var store = store(Map.of("EMPTY", ""));
        for (String name : new String[] {"EMPTY", "MISSING"}) {
            assertThatThrownBy(() -> store.validate(new SecretRef("env:" + name)))
                    .isInstanceOf(SecretResolutionException.class).hasMessageContaining(name);
        }
    }

    @Test
    void invalidEnvNameRejected() {
        var store = store(Map.of());
        assertThatThrownBy(() -> store.validate(new SecretRef("env:1BAD"))).isInstanceOf(SecretResolutionException.class);
        assertThatThrownBy(() -> store.validate(new SecretRef("env:"))).isInstanceOf(SecretResolutionException.class);
    }

    private Path secretFile(String content, String perms) throws IOException {
        Path f = Files.writeString(dir.resolve("secret"), content);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString(perms));
        return f;
    }

    @Test
    void fileRefResolvesWithOneTrailingNewlineTrimmed() throws IOException {
        var store = store(Map.of());
        Path f = secretFile(MARKER + "\n\n", "rw-------");
        assertThat(text(store.resolve(new SecretRef("file:" + f)))).isEqualTo(MARKER + "\n");
        Files.writeString(f, MARKER + "\r\n");
        assertThat(text(store.resolve(new SecretRef("file:" + f)))).isEqualTo(MARKER);
    }

    @Test
    void missingFileFailsValidation() {
        var store = store(Map.of());
        Path missing = dir.resolve("nope");
        assertThatThrownBy(() -> store.validate(new SecretRef("file:" + missing)))
                .isInstanceOf(SecretResolutionException.class).hasMessageContaining(missing.toString());
    }

    @Test
    void relativeFilePathRejected() {
        assertThatThrownBy(() -> store(Map.of()).validate(new SecretRef("file:relative/path")))
                .isInstanceOf(SecretResolutionException.class);
    }

    @Test
    void worldReadableFileWarnsOnceOnValidation() throws IOException {
        Path f = secretFile(MARKER, "rw-r--r--");
        store(Map.of()).validate(new SecretRef("file:" + f));
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(MARKER));
    }

    @Test
    void privateFileDoesNotWarn() throws IOException {
        Path f = secretFile(MARKER, "rw-------");
        store(Map.of()).validate(new SecretRef("file:" + f));
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
    }

    @Test
    void literalRejectedWithoutEchoingValue() {
        var store = store(Map.of());
        assertThatThrownBy(() -> store.validate(new SecretRef(MARKER)))
                .isInstanceOf(SecretResolutionException.class).hasMessageNotContaining(MARKER)
                .hasMessageContaining("env:").hasMessageContaining("file:");
        assertThatThrownBy(() -> store.resolve(new SecretRef("plain:" + MARKER)))
                .isInstanceOf(SecretResolutionException.class).hasMessageNotContaining(MARKER);
    }

    @Test
    void keyringRecognizedButUnavailable() {
        var store = store(Map.of());
        assertThatThrownBy(() -> store.validate(new SecretRef("keyring:svc/acct")))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("keyring not available here; use env: or file:");
    }

    @Test
    void extraResolverCanBePluggedIn() {
        SecretResolver fake = new SecretResolver() {
            @Override
            public void validate(String target) {
            }

            @Override
            public Secret resolve(String target) {
                return new Secret(("kr-" + target).toCharArray());
            }
        };
        var store = new RefCredentialStore(OWNER, List.of(), Map.of("keyring", fake));
        assertThat(text(store.resolve(new SecretRef("keyring:a/b")))).isEqualTo("kr-a/b");
    }

    @Test
    void fileIsReadLazilyOnEachResolve() throws IOException {
        var store = store(Map.of());
        Path f = secretFile("first", "rw-------");
        SecretRef ref = new SecretRef("file:" + f);
        assertThat(text(store.resolve(ref))).isEqualTo("first");
        Files.writeString(f, "second");
        assertThat(text(store.resolve(ref))).isEqualTo("second");
    }

    @Test
    void nothingLeaksSecretInToStringOrLogs() throws IOException {
        Path f = secretFile(MARKER, "rw-r-----");
        var store = store(Map.of("PW", MARKER));
        store.validate(new SecretRef("file:" + f));
        Secret s = store.resolve(new SecretRef("env:PW"));
        assertThat(s.toString()).doesNotContain(MARKER);
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(MARKER));
    }

    private static MailAccount account(String id, String ref) {
        return new MailAccount(new AccountId(id), "L", null, "h", 993, MailAccount.Security.IMPLICIT_TLS,
                MailAccount.Flavor.GENERIC, "u", new SecretRef(ref));
    }

    @Test
    void resolvesByTenantAndAccountOnlyForOwner() {
        var store = store(Map.of("PW_A", "pa"), List.of(account("a", "env:PW_A")));
        assertThat(text(store.resolve(OWNER, new AccountId("a")).orElseThrow())).isEqualTo("pa");
        assertThat(store.resolve(new TenantId("other"), new AccountId("a"))).isEmpty();
        assertThat(store.resolve(OWNER, new AccountId("unknown"))).isEmpty();
    }
}
