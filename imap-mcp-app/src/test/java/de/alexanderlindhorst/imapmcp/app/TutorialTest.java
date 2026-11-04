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
    private static final String WRONG_DEADLINE_ERROR = "tools.deadline: must be at least 30s while any account is not readOnly";

    private static String tutorial;
    private static String readme;

    @BeforeAll
    static void read() throws IOException {
        // Surefire runs with the module directory as working directory.
        tutorial = Files.readString(Path.of("..", "doc", "TUTORIAL.md"));
        readme = Files.readString(Path.of("..", "README.md"));
    }

    /** Valid YAML blocks, i.e. those without an {@code invalid-*} marker on the fence. */
    private static List<String> yamlBlocks(boolean invalid) {
        return invalid ? yamlBlocks("invalid-id") : yamlBlocks(null);
    }

    /** YAML blocks fenced as {@code yaml <marker>} (the deliberately wrong examples), or unmarked ones if null. */
    private static List<String> yamlBlocks(String marker) {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)```yaml( invalid-[a-z]+)?\\R(.*?)```").matcher(tutorial);
        while (m.find()) {
            String found = m.group(1) == null ? null : m.group(1).trim();
            if (java.util.Objects.equals(found, marker)) {
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

    @Test
    void writeToolsSectionIsDocumentedAndItsYamlLoads(@TempDir Path dir) throws IOException {
        assertThat(tutorial).contains("## 7. Write tools and read-only accounts");
        assertThat(tutorial.indexOf("## 7. Write tools")).isGreaterThan(tutorial.indexOf("## 6. After adding"))
                .isLessThan(tutorial.indexOf("## 8. Pitfalls at a glance"));
        assertThat(tutorial).contains("set_flags", "move_messages", "copy_messages", "delete_messages",
                "create_folder", "tools/list", "list_accounts", "read_only", "PERMISSION_DENIED", "account_read_only",
                "move back with the returned handles",
                "delete moves to Trash; nothing is permanently deleted in this version");
        List<String> withReadOnly = yamlBlocks(false).stream().filter(b -> b.contains("readOnly: true")).toList();
        assertThat(withReadOnly).hasSize(1);
        Path f = dir.resolve("two.yaml");
        Files.writeString(f, withReadOnly.getFirst());
        List<AppConfig.Account> accounts = ConfigLoader.load(f, Map.of()).accounts();
        assertThat(accounts).hasSize(2);
        assertThat(accounts).filteredOn(AppConfig.Account::readOnly).hasSize(1);
    }

    @Test
    void wrongDeadlineVariantFailsWithTheDocumentedError(@TempDir Path dir) throws IOException {
        List<String> wrong = yamlBlocks("invalid-deadline");
        assertThat(wrong).hasSize(1);
        assertThat(wrong.getFirst()).contains("deadline: 20s");
        Path f = dir.resolve("deadline.yaml");
        Files.writeString(f, wrong.getFirst());
        assertThatThrownBy(() -> ConfigLoader.load(f, Map.of())).isInstanceOf(ConfigException.class)
                .hasMessageContaining(WRONG_DEADLINE_ERROR);
        assertThat(tutorial).contains(WRONG_DEADLINE_ERROR);
    }

    /** #207: SPEC v0.8 has no write-tools switch. */
    @Test
    void doesNotMentionAWriteToolsSwitch() {
        assertThat(tutorial).doesNotContain("writeTools");
    }

    @Test
    void pitfallsListTheWriteToolLines() {
        String pitfalls = tutorial.substring(tutorial.indexOf("## 8. Pitfalls at a glance"));
        assertThat(pitfalls).contains("active on every account without `readOnly: true`")
                .contains("no per-tool selection").contains("`UNSUPPORTED`");
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
