package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Log audit for the v3a tools (SPEC 5.6, 5.8): the shaded jar runs over stdio with the root log level {@code DEBUG}
 * (the IMAP trace stays off), a scripted elicitation handler and a download directory. The log must contain no
 * dialog text, no answer content, no handle ({@code m1.}), no account label (it is part of the dialog), no canary
 * subject, no canary file name and no path of a downloaded file, no credential; but exactly one INFO line per
 * download call and one audit line per purge call.
 */
class V3aLogAuditIT {

    private static final String PASSWORD = "pwmarkerv3a";
    private static final String USER_A = "usermarkerv3aa";
    private static final String USER_B = "usermarkerv3ab";
    /** Account labels are shown in the elicitation dialog: a canary, so the dialog text cannot hide in the log. */
    private static final String ACCOUNT_A = "zzcanaryacct-a";
    private static final String ACCOUNT_B = "zzcanaryacct-b";
    private static final String CANARY_FILE = "ZzCanaryFile";
    private static final String CANARY_SUBJECT = "ZzCanarySubject";

    @TempDir
    Path dir;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static McpSchema.CallToolResult call(McpSyncClient c, String tool, Map<String, Object> args) {
        return c.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    private static String trashOf(McpSyncClient c, String account) {
        return list(ToolResults.payload(call(c, "list_folders", args("account", account))), "folders").stream()
                .filter(f -> "trash".equals(f.get("role"))).map(f -> (String) f.get("folder")).findFirst()
                .orElseThrow();
    }

    private static String firstHandle(McpSyncClient c, String account, String folder) {
        return (String) list(ToolResults.payload(call(c, "search_messages",
                args("account", account, "folder", folder))), "messages").get(0).get("handle");
    }

    @Test
    void debugLogHoldsNoDialogAnswerHandleNameOrPathAndTheAuditLinesAreThere() throws Exception {
        DovecotContainer dovecot = new DovecotContainer(PASSWORD);
        dovecot.start();
        Path downloads = Files.createDirectory(dir.resolve("downloads"));
        Files.setPosixFilePermissions(downloads, PosixFilePermissions.fromString("rwx------"));
        byte[] raw = ascii("From: sender@example.org\r\nTo: recipient@example.org\r\nSubject: " + CANARY_SUBJECT
                + "\r\nDate: Wed, 04 Nov 2026 10:00:00 +0000\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"bnd\"\r\n\r\n"
                + "--bnd\r\nContent-Type: text/plain; charset=us-ascii\r\n\r\nsee attached\r\n"
                + "--bnd\r\nContent-Type: application/octet-stream; name=\"" + CANARY_FILE + ".bin\"\r\n"
                + "Content-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"" + CANARY_FILE
                + ".bin\"\r\n\r\nQUJDREVGR0g=\r\n"
                + "--bnd\r\nContent-Type: application/octet-stream; name=\"" + CANARY_FILE + "Cut.bin\"\r\n"
                + "Content-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"" + CANARY_FILE
                + "Cut.bin\"\r\n\r\nAAAABB\r\n--bnd--\r\n");
        String trashA;
        try (MailSeeder seeder = dovecot.seeder(USER_A)) {
            seeder.appendRfc822("INBOX", raw);
            trashA = "Trash";
            seeder.appendPlain(trashA, CANARY_SUBJECT + " purge a", "ZzCanaryBody");
        }
        try (MailSeeder seeder = dovecot.seeder(USER_B)) {
            seeder.appendPlain("Trash", CANARY_SUBJECT + " purge b", "ZzCanaryBody");
        }
        ServerConfigWriter writer = new ServerConfigWriter(dir).trustStore(TestTrust.writeTruststore(dir))
                .account(ACCOUNT_A, dovecot.getHost(), dovecot.imapsPort(), MailAccount.Security.IMPLICIT_TLS,
                        USER_A, PASSWORD)
                .account(ACCOUNT_B, dovecot.getHost(), dovecot.imapsPort(), MailAccount.Security.IMPLICIT_TLS,
                        USER_B, PASSWORD)
                .allowPurge(ACCOUNT_A).allowPurge(ACCOUNT_B)
                .extraYaml("tools:\n  deadline: 60s\n  download:\n    dir: " + downloads + "\n");
        ServerConfig base = writer.write();
        Map<String, String> env = new LinkedHashMap<>(base.env());
        env.put("IMAP_MCP_LOG_LEVEL", "DEBUG");
        ServerConfig config = new ServerConfig(base.configFile(), base.stateDir(), base.logDir(), Map.copyOf(env),
                base.jvmArgs());
        List<String> cmd = JarLauncher.builder(config, "stdio").command();
        ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                .env(config.env()).build();

        List<McpSchema.ElicitFormRequest> requests = new ArrayList<>();
        int downloadCalls = 0;
        int purgeCalls = 0;
        try (McpSyncClient client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .capabilities(McpSchema.ClientCapabilities.builder().elicitation(true, false).build())
                .elicitation(req -> {
                    requests.add(req);
                    // account A: accept and ask for a session approval; account B: decline
                    return req.message().contains(ACCOUNT_A)
                            ? new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
                                    Map.of("allow_for_session", true))
                            : new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null);
                })
                .requestTimeout(Duration.ofSeconds(120)).initializationTimeout(Duration.ofSeconds(60)).build()) {
            client.initialize();
            Map<String, String> accounts = new LinkedHashMap<>();
            for (Map<String, Object> a : list(ToolResults.payload(call(client, "list_accounts", Map.of())),
                    "accounts")) {
                accounts.put((String) a.get("label"), (String) a.get("account"));
            }
            String a = accounts.get(ACCOUNT_A);
            String b = accounts.get(ACCOUNT_B);

            String message = firstHandle(client, a, "INBOX");
            Path firstSaved = null;
            for (String part : List.of("2", "2", "3", "9")) { // saved, saved again as "(2)", decode_failed, no such part
                McpSchema.CallToolResult r = call(client, "download_attachment",
                        args("account", a, "message", message, "part", part));
                downloadCalls++;
                assertThat(ToolResults.payload(r)).isNotNull();
                if (!Boolean.TRUE.equals(r.isError()) && firstSaved == null) {
                    firstSaved = Path.of((String) map(ToolResults.payload(r), "untrusted").get("path"));
                }
            }
            assertThat(firstSaved).isNotNull();
            assertThat(Files.exists(firstSaved)).isTrue();

            String trashHandleA = firstHandle(client, a, trashOf(client, a));
            McpSchema.CallToolResult purgedA = call(client, "purge_messages",
                    args("account", a, "handles", List.of(trashHandleA)));
            purgeCalls++;
            assertThat(purgedA.isError()).isNotEqualTo(true);
            assertThat(ToolResults.payload(purgedA)).containsEntry("confirmation", "elicited");

            String trashHandleB = firstHandle(client, b, trashOf(client, b));
            for (int i = 0; i < 2; i++) { // declined, then recently_declined
                McpSchema.CallToolResult declined = call(client, "purge_messages",
                        args("account", b, "handles", List.of(trashHandleB)));
                purgeCalls++;
                assertThat(declined.isError()).isEqualTo(true);
            }
            assertThat(requests).as("one dialog per account; the cooldown sends none").hasSize(2);
        }

        String log;
        try (Stream<Path> files = Files.list(config.logDir())) {
            StringBuilder sb = new StringBuilder();
            for (Path f : files.sorted().toList()) {
                sb.append(Files.readString(f));
            }
            log = sb.toString();
        }
        assertThat(log).as("DEBUG is active: the audit is not vacuous").contains(" DEBUG ");
        assertThat(log).doesNotContain(CANARY_FILE, CANARY_SUBJECT, "ZzCanaryBody", downloads.toString(),
                ACCOUNT_A, ACCOUNT_B, PASSWORD, USER_A, USER_B, "m1.", "Permanently delete", "cannot be undone",
                "allow_for_session", "Allow for this session");
        List<String> downloadLines = log.lines().filter(l -> l.contains("write call tool=download_attachment"))
                .toList();
        assertThat(downloadLines).as("one INFO line per download call").hasSize(downloadCalls)
                .allMatch(l -> l.contains(" INFO "));
        assertThat(downloadLines).filteredOn(l -> l.contains("bytes=")).hasSize(2)
                .anyMatch(l -> l.contains("renamed=true")).anyMatch(l -> l.contains("renamed=false"));
        assertThat(downloadLines).filteredOn(l -> l.contains("error_code=")).hasSize(2);
        List<String> audit = log.lines().filter(l -> l.contains("imapmcp.audit")).toList();
        assertThat(audit).as("one audit line per purge call, INFO").anyMatch(l -> l.contains("confirmation=elicited"));
        assertThat(audit.stream().filter(l -> l.contains("purge call tool=purge_messages")).count())
                .isEqualTo(purgeCalls);
        assertThat(audit.stream().filter(l -> l.contains("purge approval created")).count())
                .as("the accepted session approval is one more audit line").isEqualTo(1);
        assertThat(audit).allMatch(l -> l.contains(" INFO "));
        dovecot.stop();
    }
}
