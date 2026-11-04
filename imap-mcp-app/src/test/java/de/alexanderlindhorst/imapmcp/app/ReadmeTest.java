package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.secret.CommandRunner;
import de.alexanderlindhorst.imapmcp.store.secret.Platform;
import de.alexanderlindhorst.imapmcp.store.secret.RefCredentialStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #60: the README must stay consistent with the implemented config, env variables and launch commands. */
class ReadmeTest {

    private static String readme;

    @BeforeAll
    static void readReadme() throws IOException {
        // Surefire runs with the module directory as working directory.
        readme = Files.readString(Path.of("..", "README.md"));
    }

    private static List<String> yamlBlocks() {
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("(?s)```yaml\\R(.*?)```").matcher(readme);
        while (m.find()) {
            blocks.add(m.group(1));
        }
        return blocks;
    }

    /** AC1: every YAML example loads with the real config loader (secret refs are checked syntactically). */
    @Test
    void yamlExamplesLoadWithConfigLoader(@TempDir Path dir) throws IOException {
        List<String> blocks = yamlBlocks();
        assertThat(blocks).as("README has YAML examples").isNotEmpty();
        assertThat(blocks).anyMatch(b -> b.contains("keyring:imap-mcp/"));
        assertThat(blocks).anyMatch(b -> b.contains("env:"));
        assertThat(blocks).anyMatch(b -> b.contains("file:"));
        int i = 0;
        for (String block : blocks) {
            Path f = dir.resolve("config-" + i++ + ".yaml");
            Files.writeString(f, block);
            AppConfig c = ConfigLoader.load(f, Map.of());
            assertThat(c.accounts()).isNotEmpty();
        }
    }

    /**
     * #143 / #62: every {@code keyring:} reference in the README passes the store's own validation (form, length,
     * control characters) and is looked up under exactly the service and account the README's storing commands use.
     */
    @Test
    void keyringRefsPassTheStoreRulesAndMatchTheDocumentedStoreCommands(@TempDir Path dir) throws IOException {
        List<List<String>> calls = new ArrayList<>();
        CommandRunner runner = (argv, timeout) -> {
            calls.add(List.copyOf(argv));
            return new CommandRunner.Result(0, "pw".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0);
        };
        Set<String> refs = new HashSet<>();
        int i = 0;
        for (String block : yamlBlocks()) {
            Path f = dir.resolve("keyring-" + i++ + ".yaml");
            Files.writeString(f, block);
            List<MailAccount> accounts = ConfigLoader.load(f, Map.of()).accounts().stream()
                    .map(AppConfig.Account::toMailAccount).toList();
            RefCredentialStore store = RefCredentialStore.withEnvAndKeyring(new TenantId("local"), accounts, Map.of(),
                    Platform.LINUX, runner, p -> true, Duration.ofSeconds(5));
            for (MailAccount a : accounts) {
                if (a.secretRef().value().startsWith("keyring:")) {
                    store.validate(a.secretRef());
                    refs.add(a.secretRef().value());
                }
            }
        }
        assertThat(refs).as("README keyring references").isNotEmpty();
        assertThat(calls).hasSize(refs.size());
        for (List<String> argv : calls) {
            assertThat(argv).contains("imap-mcp", "work@example.com");
        }
        assertThat(readme).contains("service imap-mcp account work@example.com")
                .contains("-s imap-mcp -a work@example.com");
    }

    /** AC2: env variables named in the README are exactly known ones (config overrides + path variables). */
    @Test
    void namedEnvVariablesAreKnown() {
        Set<String> known = new HashSet<>(AppPaths.ENV_VARS);
        known.addAll(ConfigLoader.ENV_OVERRIDES);
        Set<String> named = new HashSet<>();
        Matcher m = Pattern.compile("\\b(IMAP_MCP_[A-Z_]+|XDG_[A-Z_]+)\\b").matcher(readme);
        while (m.find()) {
            named.add(m.group(1));
        }
        assertThat(named).isNotEmpty().isSubsetOf(known);
        // Everything the code reads is documented.
        assertThat(named).containsAll(known);
    }

    /** AC3: no real addresses, no secret-looking literals. */
    @Test
    void containsNoRealAddressesOrSecrets() {
        Matcher m = Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})").matcher(readme);
        while (m.find()) {
            assertThat(m.group(1)).as("address domain in README").isEqualTo("example.com");
        }
        assertThat(readme).doesNotContainPattern("\\b(?![A-Z_]+\\b)[A-Za-z0-9_-]{32,}");
        Matcher pw = Pattern.compile("(?m)^\\s*(password|token|handleKey):\\s*(\\S+)").matcher(readme);
        while (pw.find()) {
            assertThat(pw.group(2)).as("secret value of " + pw.group(1))
                    .matches("(env:|file:|keyring:).+");
        }
    }

    /** AC4: stdio launch command and trace warning appear verbatim. */
    @Test
    void containsStdioCommandAndTraceWarning() {
        assertThat(readme).contains("java -Xlog:disable -Xlog:all=warning:stderr -jar")
                .contains("--mode stdio");
        assertThat(readme).contains("WARNING")
                .contains("may contain message content (subjects, addresses, bodies)")
                .contains("credentials stay redacted")
                .contains("disable it after diagnosis");
    }

    @Test
    void describesImplementedBehaviourOnly() {
        assertThat(readme).doesNotContain("specification phase").doesNotContain("no code yet")
                .doesNotContain("IMAP_MCP_LOG_MAX_FILE_SIZE");
        assertThat(readme).contains("JDK 25 or newer").contains("java-26").contains("3.9.6");
        assertThat(readme).contains("docker exec").contains("/config/state/http-token")
                .contains("claude mcp add").contains("--transport http").contains("Authorization: Bearer")
                .contains("flavor: auto").contains("gimaps").contains("secret-tool store")
                .contains("security add-generic-password").contains("within 5 minutes");
        assertThat(readme).contains("phase v2").contains("reversible write tools")
                .contains("enabled by default").contains("`readOnly`")
                .contains("`http-public` and the later phases are not implemented")
                .contains("moves to Trash, not a permanent delete")
                .contains("`read_only`").contains("account_read_only")
                .contains("at least 30 s").contains("100 handles").contains("10 folders")
                .contains("upgrading from 0.1.0").contains("`destructiveHint`")
                .contains("set_flags, move_messages, copy_messages, delete_messages and create_folder")
                .doesNotContain("phase v1");
    }

    /**
     * The option table: every documented key must be accepted by the loader, and loading all of them at their
     * documented defaults must equal loading a minimal config (so the table cannot drift from the code).
     */
    @Test
    void optionTableMatchesLoaderDefaults(@TempDir Path dir) throws IOException {
        Map<String, StringBuilder> sections = new java.util.LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^\\| `([a-zA-Z]+)\\.([a-zA-Z]+)` \\| `([^`]*)` \\|").matcher(readme);
        int rows = 0;
        while (m.find()) {
            rows++;
            sections.computeIfAbsent(m.group(1), k -> new StringBuilder(k + ":\n"))
                    .append("  ").append(m.group(2)).append(": ").append(m.group(3)).append('\n');
        }
        assertThat(sections.keySet()).containsExactlyInAnyOrder("imap", "pool", "tools", "secrets", "http");
        assertThat(rows).isGreaterThanOrEqualTo(24);
        String account = "accounts:\n  - id: a\n    host: imap.example.com\n    username: u\n    password: env:X\n";
        Path minimal = dir.resolve("minimal.yaml");
        Files.writeString(minimal, account);
        Path full = dir.resolve("full.yaml");
        Files.writeString(full, account + String.join("", sections.values().stream().map(StringBuilder::toString).toList()));
        AppConfig expected = ConfigLoader.load(minimal, Map.of());
        AppConfig actual = ConfigLoader.load(full, Map.of());
        assertThat(actual.imap()).isEqualTo(expected.imap());
        assertThat(actual.pool()).isEqualTo(expected.pool());
        assertThat(actual.tools()).isEqualTo(expected.tools());
        assertThat(actual.secrets()).isEqualTo(expected.secrets());
        assertThat(actual.http()).isEqualTo(expected.http());
        assertThat(readme).contains("`http.token`").contains("`security.handleKey`");
    }

    /** #165 AC2: the groupId is final; the README states it and does not call it a placeholder. */
    @Test
    void groupIdIsStatedAndNotAPlaceholder() {
        assertThat(readme).contains("group `de.alexanderlindhorst.imapmcp`");
        assertThat(readme).doesNotContainIgnoringCase("placeholder");
    }

    /** #165 AC3: a short Release section describes the procedure without hard-coding a version. */
    @Test
    void releaseSectionDescribesTheProcedure() {
        Matcher m = Pattern.compile("(?s)\\n## Release\\R(.*?)(?=\\n## |\\z)").matcher(readme);
        assertThat(m.find()).as("README has a '## Release' section").isTrue();
        String section = m.group(1);
        assertThat(section).contains("versions:set").contains("v<version>").contains("-all.jar")
                .contains("SHA-256").contains("snapshot").contains("gitea-pr.sh")
                .doesNotContain("```yaml").doesNotContain("0.1.");
        long lines = section.lines().filter(l -> !l.isBlank()).count();
        assertThat(lines).as("non-blank lines in Release section").isBetween(5L, 10L);
    }

    @Test
    void logStatementNamesTheTraceException() {
        assertThat(readme).contains(
                "not logged (except in the opt-in IMAP protocol trace below; credentials stay redacted there too)");
    }

    /** #204: the Tools section lists exactly the registered tool names; v2 tools are marked as write tools. */
    @Test
    void toolsSectionListsExactlyTheRegisteredTools() {
        Matcher m = Pattern.compile("(?s)\\n## Tools\\R(.*?)(?=\\n## |\\z)").matcher(readme);
        assertThat(m.find()).as("README has a '## Tools' section").isTrue();
        String section = m.group(1);
        List<String> listed = new ArrayList<>();
        Matcher l = Pattern.compile("(?m)^- `([a-z_]+)`: (.*)$").matcher(section);
        java.util.Map<String, String> lines = new java.util.LinkedHashMap<>();
        while (l.find()) {
            listed.add(l.group(1));
            lines.put(l.group(1), l.group(2));
        }
        List<de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition> defs = List.of(
                de.alexanderlindhorst.imapmcp.core.tools.ListAccountsTool.DEFINITION,
                de.alexanderlindhorst.imapmcp.core.tools.ListFoldersTool.DEFINITION,
                de.alexanderlindhorst.imapmcp.core.tools.SearchMessagesTool.DEFINITION,
                de.alexanderlindhorst.imapmcp.core.tools.GetMessageTool.definition(100_000),
                de.alexanderlindhorst.imapmcp.core.tools.ListAttachmentsTool.DEFINITION,
                de.alexanderlindhorst.imapmcp.core.tools.write.SetFlagsTool.DEFINITION,
                de.alexanderlindhorst.imapmcp.core.tools.write.MoveMessagesTool.DEFINITION,
                de.alexanderlindhorst.imapmcp.core.tools.write.CopyMessagesTool.DEFINITION,
                de.alexanderlindhorst.imapmcp.core.tools.write.DeleteMessagesTool.DEFINITION,
                de.alexanderlindhorst.imapmcp.core.tools.write.CreateFolderTool.DEFINITION);
        assertThat(listed).containsExactlyInAnyOrderElementsOf(defs.stream().map(d -> d.name()).toList());
        for (var d : defs) {
            if (d.hints().readOnly()) {
                assertThat(lines.get(d.name())).as(d.name()).doesNotContain("write;");
            } else {
                assertThat(lines.get(d.name())).as(d.name()).contains("write; refused on `readOnly` accounts");
            }
        }
        assertThat(lines.get("delete_messages")).contains("moves to Trash, not a permanent delete");
    }

    /** #204: SPEC v0.8 has no write-tools switch; the only switch is accounts[].readOnly. */
    @Test
    void doesNotMentionAWriteToolsSwitch() {
        assertThat(readme).doesNotContain("writeTools");
    }

    /** #206: Gmail write behaviour comes from the server; the README says so and lists the manual smoke checks. */
    @Test
    void gmailSectionHasWriteToolsParagraphAndSmokeChecklist() {
        Matcher m = Pattern.compile("(?s)\\n### Gmail\\R(.*?)(?=\\n##+ |\\z)").matcher(readme);
        assertThat(m.find()).as("README has a '### Gmail' section").isTrue();
        String section = m.group(1);
        assertThat(section).contains("Write tools on Gmail")
                .contains("tested against GreenMail and Dovecot only, not against real Gmail")
                .contains("adds a label").contains("replaces the source label").contains("All Mail")
                .contains("UTF8=ACCEPT").doesNotContain("writeTools").doesNotContain("modified UTF-7");
        for (String heading : List.of("COPYUID on labels", "copy into a label the message already has",
                "move into and out of All Mail", "move out of Trash", "Junk, Starred and Important",
                "delete from a label", "Trash role under a localized UI", "keyword persistence",
                "move into Drafts or Sent refused")) {
            assertThat(section).as("checklist item").contains(heading);
        }
    }
}
