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

    /** B3: the http-public example (a block tagged {@code yaml http-public}) loads with the real http-public loader. */
    @Test
    void theHttpPublicExampleLoadsWithThePublicConfigLoader(@TempDir Path dir) throws IOException {
        Matcher m = Pattern.compile("(?s)```yaml http-public\\R(.*?)```").matcher(readme);
        assertThat(m.find()).as("README has an http-public YAML example").isTrue();
        Path f = dir.resolve("public.yaml");
        Files.writeString(f, m.group(1));
        var c = de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader.load(f, Map.of());
        assertThat(c.kms().provider()).isEqualTo("local");
        assertThat(readme).contains("--mode stdio|http-local|http-public").contains("Mcp-Session-Id")
                .doesNotContain("Not implemented; planned for phase v3");
        // review of PR #362: tools bullet, status line and the Vault provider
        assertThat(readme).contains("`purge_messages` is registered only with `purge.enabled` (which requires `http.stateful`)")
                .doesNotContain("the stateful transport for `purge_messages` and the linking surface follow")
                .contains("`kms.provider: vault`").contains("`kms.vault.allowInsecureHttp`")
                .contains("`update` on `<transitMount>/encrypt/<keyName>` and `<transitMount>/decrypt/<keyName>`")
                .contains("`read` on `<transitMount>/keys/<keyName>`").contains("`http_403`")
                .contains("Rotation with `kms.provider: local`").contains("rotate the Transit key");
    }

    /** B3 part 3 (#315): the linking surface section for operators. */
    @Test
    void theReadmeDocumentsTheLinkingSurfaceForOperators() {
        assertThat(readme).contains("#### Linking surface (`/accounts`)")
                .contains("`oauth.webClientId`").contains("`oauth.webClientSecret`")
                                .contains("`<public.baseUrl>/accounts/callback`")
                .contains("`realm_access.roles`").contains("`id.token.claim`").contains("realm roles")
                .contains("`303`").contains("Post/Redirect/Get")
                .contains("10 probes per tenant per hour").contains("per replica")
                .contains("`public.maxAccountsPerTenant`").contains("`oauth.allowWithoutRole`")
                .contains("`link_url`").contains("`__Host-session`")
                .doesNotContain("the linking surface follows").doesNotContain("`keyring:` or `cmd:`")
                .contains("`env:`, `file:` or `keyring:`").contains("only with `file:` or `keyring:`")
                .contains("`purge.enabled` is true, `read_only` is off");
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
            // The opt-in smoke tests (#258) read their own IMAP_MCP_SMOKE_* variables; the server never does.
            if (!m.group(1).startsWith("IMAP_MCP_SMOKE_")) {
                named.add(m.group(1));
            }
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
        assertThat(DocChecks.findSecretLikeToken(readme)).as("secret-like token outside link fragments").isEmpty();
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
        assertThat(readme).contains("phase v3a").contains("reversible write tools")
                .contains("enabled by default").contains("`readOnly`")
                .contains("`http-public` (v3b) is available")
                .contains("moves to Trash, not a permanent delete")
                .contains("`read_only`").contains("account_read_only")
                .contains("at least 30 s").contains("100 handles").contains("10 folders")
                .contains("upgrading from 0.1.0").contains("`destructiveHint`")
                .contains("set_flags, move_messages, copy_messages, delete_messages and create_folder")
                .doesNotContain("phase v1").doesNotContain("phase v2");
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
        assertThat(sections.keySet()).containsExactlyInAnyOrder("imap", "pool", "tools", "secrets", "http", "security");
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
        assertThat(actual.security()).isEqualTo(expected.security());
        assertThat(readme).contains("`http.token`").contains("`security.handleKey`")
                .contains("`security.handleKeys`").contains("`security.signingKeyId`");
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
                de.alexanderlindhorst.imapmcp.core.tools.write.CreateFolderTool.DEFINITION,
                de.alexanderlindhorst.imapmcp.core.tools.write.PurgeMessagesTool.DEFINITION,
                de.alexanderlindhorst.imapmcp.core.tools.DownloadAttachmentTool.DEFINITION);
        assertThat(listed).containsExactlyInAnyOrderElementsOf(defs.stream().map(d -> d.name()).toList());
        for (var d : defs) {
            if (d.hints().readOnly()) {
                assertThat(lines.get(d.name())).as(d.name()).doesNotContain("write;");
            } else if (d.name().equals("download_attachment")) { // a local file, allowed on read-only accounts
                assertThat(lines.get(d.name())).as(d.name()).contains("allowed on `readOnly` accounts")
                        .contains("`tools.download.dir`").doesNotContain("refused on `readOnly`");
            } else if (d.name().equals("purge_messages")) {
                assertThat(lines.get(d.name())).as(d.name()).contains("permanent").contains("Trash only")
                        .contains("`allowPurge`").contains("`stdio`").contains("`purge.enabled`").contains("confirm");
            } else {
                assertThat(lines.get(d.name())).as(d.name()).contains("write; refused on `readOnly` accounts");
            }
        }
        assertThat(lines.get("delete_messages")).contains("moves to Trash, not a permanent delete");
    }

    /** v3a (#289): purge workflow, elicitation, session approval, host timeouts, deadline, http-local, download. */
    @Test
    void v3aSectionsDocumentPurgeElicitationDownloadAndTheirLimits() {
        Matcher m = Pattern.compile("(?s)\\n### Permanent deletion \\(purge_messages\\)\\R(.*?)(?=\\n##+ |\\z)")
                .matcher(readme);
        assertThat(m.find()).as("README has a '### Permanent deletion (purge_messages)' section").isTrue();
        assertThat(m.group(1)).contains("`delete_messages`").contains("Trash").contains("only from the Trash")
                .contains("`allowPurge: true`").contains("registered in `stdio` and over the stateful HTTP transport")
                .contains("`http-public` only with `purge.enabled` and `http.stateful`").contains("not available in `http-local`")
                .contains("elicitation").contains("Allow for this session").contains("15 minutes")
                .contains("500 messages").contains("`recently_declined`").contains("60 s")
                .contains("`tools.deadline`").contains("at least 60 s").contains("a late acceptance still deletes")
                .contains("`already_gone`").contains("`confirmation_unavailable`").contains("`purge_scope`")
                .contains("`purge_disabled`").contains("`not_confirmed`").contains("`readOnly: true`")
                .contains("UIDPLUS").contains("`allow_purge`").contains("cannot be undone");
        Matcher d = Pattern.compile("(?s)\\n### Downloading attachments \\(download_attachment\\)\\R(.*?)(?=\\n##+ |\\z)")
                .matcher(readme);
        assertThat(d.find()).as("README has a '### Downloading attachments (download_attachment)' section").isTrue();
        assertThat(d.group(1)).contains("`tools.download.dir`").contains("`tools.download.maxBytes`")
                .contains("`tools.download.displayDir`").contains("25MiB").contains("100MiB")
                .contains("--user").contains("--userns=keep-id").contains("0600").contains("never overwrites")
                .contains("sha256").contains("untrusted").contains("Docker Desktop on macOS");
    }

    /** v3a (#289): the YAML for allowPurge and the download keys loads, and the keys land in the config. */
    @Test
    void v3aYamlExamplesSetAllowPurgeAndTheDownloadKeys(@TempDir Path dir) throws IOException {
        List<String> purge = yamlBlocks().stream().filter(b -> b.contains("allowPurge: true")).toList();
        assertThat(purge).hasSize(1);
        Path f = dir.resolve("purge.yaml");
        Files.writeString(f, purge.getFirst());
        AppConfig c = ConfigLoader.load(f, Map.of());
        assertThat(c.accounts()).filteredOn(AppConfig.Account::allowPurge).hasSize(1);
        assertThat(c.tools().deadline()).isGreaterThanOrEqualTo(java.time.Duration.ofSeconds(60));
        List<String> download = yamlBlocks().stream().filter(b -> b.contains("displayDir:")).toList();
        assertThat(download).hasSize(1);
        Path g = dir.resolve("download.yaml");
        Files.writeString(g, download.getFirst());
        AppConfig.Download dl = ConfigLoader.load(g, Map.of()).tools().download();
        assertThat(dl.dir()).isNotNull();
        assertThat(dl.displayDir()).isNotNull();
        assertThat(dl.maxBytes()).isEqualTo(25L * 1024 * 1024);
    }

    /** v3a (#289): the smoke checklist names the v3a manual checks. */
    @Test
    void smokeChecklistListsTheV3aChecks() {
        Matcher m = Pattern.compile("(?s)\\n### Smoke checklist for v3a\\R(.*?)(?=\\n##+ |\\z)").matcher(readme);
        assertThat(m.find()).as("README has a '### Smoke checklist for v3a' section").isTrue();
        for (String item : List.of("Gmail Trash purge", "Microsoft 365 Deleted Items", "Real elicitation client",
                "Gmail attachment download", "Docker Desktop on macOS owner probe")) {
            assertThat(m.group(1)).as("checklist item").contains(item);
        }
    }

    /** v3b D-2 (#323, D8): purge in http-public stays off until the owner's smoke test with the Claude connector. */
    @Test
    void smokeChecklistListsTheV3bConnectorPurgeCheck() {
        Matcher m = Pattern.compile("(?s)\\n### Smoke checklist for v3b\\R(.*?)(?=\\n##+ |\\z)").matcher(readme);
        assertThat(m.find()).as("README has a '### Smoke checklist for v3b' section").isTrue();
        assertThat(m.group(1)).contains("Claude connector").contains("purge_messages").contains("`purge.enabled`")
                .contains("form elicitation").contains("off by default").contains("pending");
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
        for (String heading : List.of("COPYUID on labels", "Copy into a label the message already has",
                "Move into and out of All Mail", "Move out of Trash", "Junk, Starred and Important",
                "Delete from a label", "Trash role under a localized UI", "Keyword persistence",
                "Move into Drafts or Sent refused")) {
            assertThat(section).as("checklist item").contains(heading);
        }
    }

    /** #261: CLAUDE.md is not versioned; README and TUTORIAL must not refer to it. */
    @Test
    void readmeAndTutorialDoNotMentionClaudeMd() throws IOException {
        assertThat(readme).doesNotContain("CLAUDE.md");
        assertThat(Files.readString(Path.of("..", "doc", "TUTORIAL.md"))).doesNotContain("CLAUDE.md");
    }

    /** #261: the error reference lists exactly the ErrorCode values, with the retryable flag the enum carries. */
    @Test
    void errorReferenceMatchesErrorCodes() {
        Matcher sec = Pattern.compile("(?s)\\n### Errors\\R(.*?)(?=\\n##+ |\\z)").matcher(readme);
        assertThat(sec.find()).as("README has a '### Errors' section").isTrue();
        String section = sec.group(1);
        Matcher row = Pattern.compile("(?m)^\\| `([A-Z_]+)` \\| (yes|no) \\|").matcher(section);
        Map<String, Boolean> rows = new java.util.LinkedHashMap<>();
        while (row.find()) {
            rows.put(row.group(1), row.group(2).equals("yes"));
        }
        Map<String, Boolean> expected = new java.util.LinkedHashMap<>();
        for (var c : de.alexanderlindhorst.imapmcp.core.error.ErrorCode.values()) {
            expected.put(c.name(), c.retryable());
        }
        assertThat(rows).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(section).contains("retry_after_ms").contains("details.outcome").contains("`unknown`")
                .contains("details.field").contains("details.reason").contains("details.max")
                .contains("isError").contains("no `structuredContent`").contains("request_id")
                .contains("account_read_only");
        assertThat(section).contains("purge_disabled").contains("purge_scope").contains("confirmation_unavailable")
                .contains("not_confirmed").contains("part_not_found").contains("not_a_leaf")
                .contains("transfer_encoding").contains("decode_failed").contains("details.elicitation");
    }

    /** #261: README:95 wording; the old bare error code is gone. */
    @Test
    void readOnlyRefusalIsPermissionDenied() {
        assertThat(readme).contains("`PERMISSION_DENIED` with `details.reason` `account_read_only`")
                .doesNotContain("(error code `account_read_only`)");
    }

    /** #261: UTF8=ACCEPT for any server, deadline bound, \Deleted, v3 scope, macOS keyring, smoke tests. */
    @Test
    void v011Details() {
        assertThat(readme).contains("any server that advertises `UTF8=ACCEPT` or `UTF8=ONLY`")
                .contains("at most 10m")
                .contains("`\\Deleted` messages are not filtered out")
                .contains("v3a gated deletion from Trash (`purge_messages`, `stdio`; v3b: also `http-public` with `purge.enabled`) and `download_attachment`")
                .contains("`security -g`").doesNotContain("only ASCII passwords");
        assertThat(readme).contains("mvn -Psmoke -pl imap-mcp-it -am verify")
                .contains("IMAP_MCP_SMOKE_GMAIL_USER").contains("IMAP_MCP_SMOKE_GMAIL_PASSWORD")
                .contains("IMAP_MCP_SMOKE_M365_USER").contains("IMAP_MCP_SMOKE_M365_PASSWORD")
                .contains("IMAP_MCP_SMOKE_<GMAIL|M365>_HOST").contains("IMAP_MCP_SMOKE_WRITE=1")
                .contains("skipped");
    }

    /** #426: the build docs name the property that admits skipped tests (test-completeness gate, SPEC 5.8). */
    @Test
    void buildDocsNameThePartialBuildProperty() {
        assertThat(readme).contains("-Dimapmcp.partialBuild=true");
    }
}
