package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.ConfigException;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.secret.CommandRunner;
import de.alexanderlindhorst.imapmcp.store.secret.Platform;
import de.alexanderlindhorst.imapmcp.store.secret.RefCredentialStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #163: doc/TUTORIAL.md must stay consistent with the config loader and the keyring resolver. */
class TutorialTest {

    private static final String WRONG_ID_ERROR = "accounts[0].id: must match [a-z0-9-]{1,32}";

    private static String tutorial;
    private static String readme;

    @BeforeAll
    static void read() throws IOException {
        // Surefire runs with the module directory as working directory.
        tutorial = Files.readString(Path.of("..", "doc", "TUTORIAL.md"));
        readme = Files.readString(Path.of("..", "README.md"));
    }

    /** YAML blocks; a block fenced as {@code yaml invalid-id} is the deliberately wrong example. */
    private static List<String> yamlBlocks(boolean invalid) {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)```yaml( invalid-id)?\\R(.*?)```").matcher(tutorial);
        while (m.find()) {
            if ((m.group(1) != null) == invalid) {
                blocks.add(m.group(2));
            }
        }
        return blocks;
    }

    @Test
    void readmeLinksToTheTutorial() {
        assertThat(readme).contains("](doc/TUTORIAL.md)");
    }

    @Test
    void validYamlBlocksLoadAndTheWrongOneFailsWithTheDocumentedError(@TempDir Path dir) throws IOException {
        List<String> valid = yamlBlocks(false);
        assertThat(valid).isNotEmpty();
        assertThat(valid).anyMatch(b -> b.contains("keyring:imap-mcp/"));
        assertThat(valid).anyMatch(b -> b.contains("env:"));
        int i = 0;
        for (String block : valid) {
            Path f = dir.resolve("ok-" + i++ + ".yaml");
            Files.writeString(f, block);
            assertThat(ConfigLoader.load(f, Map.of()).accounts()).isNotEmpty();
        }
        List<String> wrong = yamlBlocks(true);
        assertThat(wrong).hasSize(1);
        Path f = dir.resolve("wrong.yaml");
        Files.writeString(f, wrong.getFirst());
        assertThatThrownBy(() -> ConfigLoader.load(f, Map.of())).isInstanceOf(ConfigException.class)
                .hasMessageContaining(WRONG_ID_ERROR);
        assertThat(tutorial).contains(WRONG_ID_ERROR);
    }

    /** Every keyring: reference passes the store rules and is looked up with the documented attributes. */
    @Test
    void keyringRefsPassTheStoreRulesAndUseTheDocumentedAttributes(@TempDir Path dir) throws IOException {
        List<List<String>> calls = new ArrayList<>();
        CommandRunner runner = (argv, timeout) -> {
            calls.add(List.copyOf(argv));
            return new CommandRunner.Result(0, "pw".getBytes(StandardCharsets.UTF_8), 0);
        };
        int refs = 0;
        int i = 0;
        for (String block : yamlBlocks(false)) {
            Path f = dir.resolve("keyring-" + i++ + ".yaml");
            Files.writeString(f, block);
            List<MailAccount> accounts = ConfigLoader.load(f, Map.of()).accounts().stream()
                    .map(AppConfig.Account::toMailAccount).toList();
            RefCredentialStore store = RefCredentialStore.withEnvAndKeyring(new TenantId("local"), accounts, Map.of(),
                    Platform.LINUX, runner, p -> true, Duration.ofSeconds(5));
            for (MailAccount a : accounts) {
                if (a.secretRef().value().startsWith("keyring:")) {
                    store.validate(a.secretRef());
                    refs++;
                }
            }
        }
        assertThat(refs).isPositive();
        assertThat(calls).hasSize(refs);
        for (List<String> argv : calls) {
            assertThat(argv.subList(0, 3)).containsExactly("secret-tool", "lookup", "service");
            assertThat(argv.get(4)).isEqualTo("account");
            assertThat(argv.get(3)).isEqualTo("imap-mcp");
            assertThat(tutorial).contains("secret-tool lookup service imap-mcp account " + argv.get(5));
            assertThat(tutorial).contains("secret-tool store --label='imap-mcp: work' service imap-mcp account "
                    + argv.get(5));
        }
        assertThat(tutorial).contains("secret-tool lookup service <service> account <account>");
    }

    @Test
    void stdioSectionHasTheLogFlagsAndTheClaudeCommand() {
        assertThat(tutorial).contains("-Xlog:disable -Xlog:all=warning:stderr -jar")
                .contains("--mode stdio").contains("claude mcp add").contains("-s user");
    }

    @Test
    void httpSectionHasTransportAndBearer() {
        assertThat(tutorial).contains("--transport http").contains("Authorization: Bearer")
                .contains("--mode http-local").contains("http-token");
    }

    @Test
    void containsNoRealAddressesOrSecrets() {
        Matcher m = Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})").matcher(tutorial);
        while (m.find()) {
            assertThat(m.group(1)).isEqualTo("example.com");
        }
        assertThat(tutorial).doesNotContainPattern("\\b(?![A-Z_]+\\b)[A-Za-z0-9_-]{32,}");
        Matcher pw = Pattern.compile("(?m)^\\s*(password|token):\\s*(\\S+)").matcher(tutorial);
        while (pw.find()) {
            assertThat(pw.group(2)).matches("(env:|file:|keyring:).+");
        }
    }
}
