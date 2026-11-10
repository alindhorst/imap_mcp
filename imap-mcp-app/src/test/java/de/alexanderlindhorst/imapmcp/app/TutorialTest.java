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

    // ---- doc helpers; they take the text as a parameter so they can be tested on synthetic input ----

    private static final Pattern SECRET_LIKE = Pattern.compile("\\b(?![A-Z_]+\\b)[A-Za-z0-9_-]{32,}");

    /** The numbers of all numbered level-2 headings, in document order. */
    static List<Integer> headingNumbers(String doc) {
        List<Integer> numbers = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^## (\\d+)\\. ").matcher(doc);
        while (m.find()) {
            numbers.add(Integer.parseInt(m.group(1)));
        }
        return numbers;
    }

    /** Fails unless the numbered level-2 headings are numbered 1..n in order. */
    static void assertSequentialNumbering(String doc) {
        List<Integer> numbers = headingNumbers(doc);
        List<Integer> expected = java.util.stream.IntStream.rangeClosed(1, numbers.size()).boxed().toList();
        assertThat(numbers).as("numbered level-2 headings in document order").isEqualTo(expected);
    }

    /** Start of the numbered level-2 heading whose title starts with {@code title}; the number is not part of the match. */
    static int headingStart(String doc, String title) {
        Matcher m = Pattern.compile("(?m)^## \\d+\\. " + Pattern.quote(title)).matcher(doc);
        assertThat(m.find()).as("tutorial has a numbered level-2 heading titled '%s'", title).isTrue();
        return m.start();
    }

    /**
     * The section body from the title's heading up to the next level-2 heading (or the end). Relies on fenced blocks
     * in the tutorial containing no line that starts with {@code #}; {@code ### x} stays inside its section.
     */
    static String sectionBody(String doc, String title) {
        int start = headingStart(doc, title);
        Matcher next = Pattern.compile("(?m)^## ").matcher(doc);
        return next.find(start + 3) ? doc.substring(start, next.start()) : doc.substring(start);
    }

    /** Removes only the {@code #fragment} of markdown link targets; path and URL parts stay. */
    static String stripLinkFragments(String doc) {
        return doc.replaceAll("(\\]\\([^)\\s#]*)#[^)\\s]*\\)", "$1)");
    }

    /** The first secret-like token outside link fragments, if any. */
    static java.util.Optional<String> findSecretLikeToken(String doc) {
        Matcher m = SECRET_LIKE.matcher(stripLinkFragments(doc));
        return m.find() ? java.util.Optional.of(m.group()) : java.util.Optional.empty();
    }

    @Test
    void numberingCheckRejectsAGap() {
        assertThatThrownBy(() -> assertSequentialNumbering("## 1. A\n## 2. B\n## 4. C\n"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void numberingCheckRejectsADuplicate() {
        assertThatThrownBy(() -> assertSequentialNumbering("## 1. A\n## 2. B\n## 2. C\n"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void numberingCheckIgnoresUnnumberedHeadings() {
        assertSequentialNumbering("## Intro\n## 1. A\n### sub\n## 2. B\n");
    }

    @Test
    void linkFragmentIsNotTreatedAsASecret() {
        String slug = "a".repeat(10) + "-" + "b".repeat(29);
        assertThat(findSecretLikeToken("see [x](../README.md#" + slug + ") here")).isEmpty();
    }

    @Test
    void bareTokenInUrlPathOrPlainTextStillCounts() {
        String token = "a".repeat(40);
        assertThat(findSecretLikeToken("see [x](https://h/" + token + "#frag) here")).contains(token);
        assertThat(findSecretLikeToken("the token is " + token + " ok")).contains(token);
        assertThat(findSecretLikeToken("see [x](" + token + ")")).contains(token);
    }

    @Test
    void tutorialSectionsAreNumberedWithoutGaps() {
        assertSequentialNumbering(tutorial);
    }

    @Test
    void missingHeadingFailsWithItsTitle() {
        assertThatThrownBy(() -> headingStart(tutorial, "No such section")).isInstanceOf(AssertionError.class)
                .hasMessageContaining("No such section");
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
        assertThat(headingStart(tutorial, "Write tools and read-only accounts"))
                .isGreaterThan(headingStart(tutorial, "After adding"))
                .isLessThan(headingStart(tutorial, "Permanent deletion"));
        assertThat(tutorial).contains("set_flags", "move_messages", "copy_messages", "delete_messages",
                "create_folder", "tools/list", "list_accounts", "read_only", "PERMISSION_DENIED", "account_read_only",
                "move back with the returned handles",
                "delete moves to Trash; `delete_messages` never deletes permanently");
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

    /** v3a (#289): the tutorial walks through purge, the session approval, the host timeout and the download setup. */
    @Test
    void v3aSectionExplainsPurgeAndDownloadAndItsYamlLoads(@TempDir Path dir) throws IOException {
        assertThat(headingStart(tutorial, "Permanent deletion and downloads"))
                .isGreaterThan(headingStart(tutorial, "Write tools"))
                .isLessThan(headingStart(tutorial, "Pitfalls at a glance"));
        assertThat(tutorial).contains("purge_messages", "allowPurge: true", "delete_messages", "Trash",
                "elicitation", "Allow for this session", "15 minutes", "500 messages", "`already_gone`",
                "late acceptance still deletes", "tools.deadline", "60s", "download_attachment",
                "tools.download.dir", "displayDir", "--user", "http-local");
        List<String> purge = yamlBlocks(false).stream().filter(b -> b.contains("allowPurge: true")).toList();
        assertThat(purge).hasSize(1);
        Path f = dir.resolve("purge.yaml");
        Files.writeString(f, purge.getFirst());
        assertThat(ConfigLoader.load(f, Map.of()).accounts()).filteredOn(AppConfig.Account::allowPurge).hasSize(1);
        List<String> download = yamlBlocks(false).stream().filter(b -> b.contains("tools:") && b.contains("download:"))
                .toList();
        assertThat(download).hasSize(1);
        Path g = dir.resolve("download.yaml");
        Files.writeString(g, download.getFirst());
        assertThat(ConfigLoader.load(g, Map.of()).tools().download().dir()).isNotNull();
        String pitfalls = sectionBody(tutorial, "Pitfalls at a glance");
        assertThat(pitfalls).contains("`allowPurge`").contains("purge_messages").contains("`tools.download.dir`");
    }

    /** #207: SPEC v0.8 has no write-tools switch. */
    @Test
    void doesNotMentionAWriteToolsSwitch() {
        assertThat(tutorial).doesNotContain("writeTools");
    }

    @Test
    void pitfallsListTheWriteToolLines() {
        String pitfalls = sectionBody(tutorial, "Pitfalls at a glance");
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
        assertThat(findSecretLikeToken(tutorial)).as("secret-like token outside link fragments").isEmpty();
        Matcher pw = Pattern.compile("(?m)^\\s*(password|token):\\s*(\\S+)").matcher(tutorial);
        while (pw.find()) {
            assertThat(pw.group(2)).matches("(env:|file:|keyring:).+");
        }
    }
}
