package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code purge_messages} with the user's confirmation through MCP elicitation, end to end over stdio (SPEC 4 v3a, 5.5,
 * 5.8): the shaded jar runs as a subprocess of the SDK's {@code McpClient} with a scripted elicitation handler,
 * against Dovecot 2.4.5 (its SPECIAL-USE {@code \Trash}). The wire (logger {@code imapmcp.imap.trace}) shows that
 * nothing is stored or expunged without an accepted answer, and never a plain EXPUNGE. One account (own Dovecot
 * user) per scenario, because the decline cooldown is per account.
 */
@Testcontainers
class PurgeMessagesDovecotIT {

    private static final Pattern CLIENT_COMMAND =
            Pattern.compile(" imapmcp\\.imap\\.trace - ([A-Z]+\\d+) (?!(?:OK|NO|BAD)\\b)([A-Z][A-Z.]*\\b.*)$");
    private static final String NOT_CONFIRMED_HINT =
            "The user did not confirm; nothing was deleted. Call again only if the user asks for it.";
    private static final List<String> IDS = List.of("accept", "decline", "cancel", "invalid", "rpcerror", "late",
            "noelicit", "scope", "sess", "sess2");

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer(DovecotContainer.Profile.FULL);

    @TempDir
    static Path dir;

    private static ServerConfig config;
    private static McpSyncClient client;
    private static final Map<String, String> accountHandles = new LinkedHashMap<>();
    /** What the scripted user does with the next elicitation request; the requests are recorded. */
    private static volatile Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> user;
    private static final List<McpSchema.ElicitFormRequest> requests = new CopyOnWriteArrayList<>();

    private static List<String> serverArgs() {
        return JarLauncher.builder(config, "stdio").command();
    }

    private static McpSyncClient newClient(McpSchema.ClientCapabilities caps,
            Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> handler) {
        List<String> cmd = serverArgs();
        ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                .env(config.env()).build();
        McpClient.SyncSpec spec = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(120)).initializationTimeout(Duration.ofSeconds(60));
        if (caps != null) {
            spec = spec.capabilities(caps);
        }
        if (handler != null) {
            spec = spec.elicitation(handler);
        }
        McpSyncClient c = spec.build();
        c.initialize();
        return c;
    }

    @BeforeAll
    static void start() throws Exception {
        ServerConfigWriter writer = new ServerConfigWriter(dir).trustStore(TestTrust.writeTruststore(dir))
                .extraYaml("imap:\n  trace: true\ntools:\n  deadline: 60s\n");
        for (String id : IDS) {
            writer.account(id, DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS, "purge-" + id,
                    DovecotContainer.PASSWORD);
            writer.allowPurge(id);
        }
        config = writer.write();
        client = newClient(McpSchema.ClientCapabilities.builder().elicitation(true, false).build(), req -> {
            requests.add(req);
            return user.apply(req);
        });
        for (Map<String, Object> a : list(call(client, "list_accounts", Map.of()).structured, "accounts")) {
            accountHandles.put((String) a.get("label"), (String) a.get("account"));
        }
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private record Result(boolean error, Map<String, Object> structured) {

        String errorCode() {
            return (String) map(structured, "error").get("code");
        }

        Object detail(String key) {
            return map(map(structured, "error"), "details").get(key);
        }
    }

    private static Result call(McpSyncClient c, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest(tool, args));
        return new Result(Boolean.TRUE.equals(r.isError()), ToolResults.payload(r));
    }

    private static Result ok(String tool, Map<String, Object> args) {
        Result r = call(client, tool, args);
        assertThat(r.error()).as("%s -> %s", tool, r.structured()).isFalse();
        return r;
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    private static MailSeeder seeder(String id) throws MessagingException {
        return DOVECOT.seeder("purge-" + id);
    }

    private static String trashFolder(String id) {
        Result folders = ok("list_folders", args("account", accountHandles.get(id)));
        List<String> trash = list(folders.structured, "folders").stream().filter(f -> "trash".equals(f.get("role")))
                .map(f -> (String) f.get("folder")).toList();
        assertThat(trash).hasSize(1);
        return trash.get(0);
    }

    private static Map<String, String> handlesBySubject(String id, String folder) {
        Result search = ok("search_messages", args("account", accountHandles.get(id), "folder", folder, "page_size", 100));
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> m : list(search.structured, "messages")) {
            out.put((String) map(m, "untrusted").get("subject"), (String) m.get("handle"));
        }
        return out;
    }

    private static Result purge(McpSyncClient c, String id, String... handles) {
        return call(c, "purge_messages", args("account", accountHandles.get(id), "handles", List.of(handles)));
    }

    private static McpSchema.ElicitResult answer(McpSchema.ElicitResult.Action action, Map<String, Object> content) {
        return new McpSchema.ElicitResult(action, content);
    }

    private static long logSize() throws IOException {
        return Files.exists(config.logFile()) ? Files.size(config.logFile()) : 0;
    }

    private static List<String> commandsSince(long offset) throws IOException {
        byte[] all = Files.readAllBytes(config.logFile());
        String text = new String(all, (int) offset, all.length - (int) offset, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            Matcher m = CLIENT_COMMAND.matcher(line);
            if (m.find()) {
                out.add(m.group(2).strip());
            }
        }
        return out;
    }

    private static boolean storesOrExpunges(List<String> commands) {
        return commands.stream().anyMatch(c -> c.startsWith("UID STORE") || c.contains("EXPUNGE"));
    }

    private static void assertNoPlainExpunge(List<String> commands) {
        assertThat(commands).noneMatch(c -> c.equals("EXPUNGE") || c.startsWith("EXPUNGE "));
    }

    private static List<String> subjects(String id, String folder) throws MessagingException {
        try (MailSeeder s = seeder(id)) {
            IMAPFolder f = (IMAPFolder) s.store().getFolder(folder);
            f.open(Folder.READ_ONLY);
            try {
                List<String> out = new ArrayList<>();
                for (var m : f.getMessages()) {
                    out.add(m.getSubject());
                }
                return out;
            } finally {
                f.close(false);
            }
        }
    }

    private static void seedTrash(String id, String... subjects) throws MessagingException {
        String trash = trashFolder(id);
        try (MailSeeder s = seeder(id)) {
            for (String subject : subjects) {
                s.appendPlain(trash, subject, "body of " + subject);
            }
        }
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void acceptDeletesExactlyTheAddressedMessagesAndAsksOncePerCallWithTheFixedText() throws Exception {
        seedTrash("accept", "CANARY-SUBJ-1", "CANARY-SUBJ-2", "CANARY-SUBJ-keep");
        String trash = trashFolder("accept");
        try (MailSeeder s = seeder("accept")) { // a foreign \Deleted message in Trash must survive
            long foreign = s.appendPlain(trash, "foreign-deleted", "body");
            IMAPFolder f = (IMAPFolder) s.store().getFolder(trash);
            f.open(Folder.READ_WRITE);
            f.getMessageByUID(foreign).setFlag(Flags.Flag.DELETED, true);
            f.close(false);
        }
        Map<String, String> handles = handlesBySubject("accept", trash);
        user = req -> answer(McpSchema.ElicitResult.Action.ACCEPT, Map.of());
        requests.clear();
        long log = logSize();

        Result r = purge(client, "accept", handles.get("CANARY-SUBJ-1"), handles.get("CANARY-SUBJ-2"));

        assertThat(r.error()).as("%s", r.structured()).isFalse();
        assertThat(r.structured).containsEntry("confirmation", "elicited");
        assertThat(list(r.structured, "results")).extracting(i -> i.get("status")).containsExactly("ok", "ok");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).message()).isEqualTo("Permanently delete 2 messages from the Trash folder \""
                + trash + "\" of account \"accept\"? This cannot be undone.");
        assertThat(requests.get(0).message()).doesNotContain("CANARY").doesNotContain("sender@example.org");
        assertThat(String.valueOf(requests.get(0).requestedSchema())).contains("allow_for_session");
        assertThat(subjects("accept", trash)).containsExactlyInAnyOrder("CANARY-SUBJ-keep", "foreign-deleted");
        List<String> commands = commandsSince(log);
        assertThat(commands).anyMatch(c -> c.startsWith("UID STORE ") && c.contains("+FLAGS.SILENT (\\Deleted)"));
        assertThat(commands).anyMatch(c -> c.startsWith("UID EXPUNGE "));
        assertNoPlainExpunge(commands);
    }

    @Test
    void declineDeletesNothingAndTheCooldownSuppressesTheNextDialog() throws Exception {
        seedTrash("decline", "keep-1");
        String trash = trashFolder("decline");
        String h = handlesBySubject("decline", trash).get("keep-1");
        user = req -> answer(McpSchema.ElicitResult.Action.DECLINE, null);
        requests.clear();
        long log = logSize();

        Result r = purge(client, "decline", h);

        assertThat(r.error()).isTrue();
        assertThat(r.errorCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(r.detail("reason")).isEqualTo("not_confirmed");
        assertThat(r.detail("elicitation")).isEqualTo("declined");
        assertThat(map(r.structured, "error").get("hint")).isEqualTo(NOT_CONFIRMED_HINT);
        assertThat(map(r.structured, "error").get("retryable")).isEqualTo(false);
        assertThat(storesOrExpunges(commandsSince(log))).isFalse();
        assertThat(subjects("decline", trash)).containsExactly("keep-1");

        Result again = purge(client, "decline", h);
        assertThat(again.detail("elicitation")).isEqualTo("recently_declined");
        assertThat(requests).hasSize(1);
        assertThat(subjects("decline", trash)).containsExactly("keep-1");
    }

    @Test
    void cancelIsCancelledWithTheCooldownToo() throws Exception {
        seedTrash("cancel", "keep-1");
        String trash = trashFolder("cancel");
        String h = handlesBySubject("cancel", trash).get("keep-1");
        user = req -> answer(McpSchema.ElicitResult.Action.CANCEL, null);
        requests.clear();
        Result r = purge(client, "cancel", h);
        assertThat(r.detail("elicitation")).isEqualTo("cancelled");
        assertThat(purge(client, "cancel", h).detail("elicitation")).isEqualTo("recently_declined");
        assertThat(requests).hasSize(1);
        assertThat(subjects("cancel", trash)).containsExactly("keep-1");
    }

    @Test
    void acceptWithContentOutsideTheSchemaIsInvalidAnswerAndDeletesNothing() throws Exception {
        seedTrash("invalid", "keep-1");
        String trash = trashFolder("invalid");
        String h = handlesBySubject("invalid", trash).get("keep-1");
        user = req -> answer(McpSchema.ElicitResult.Action.ACCEPT, Map.of("unknown", true));
        long log = logSize();
        Result r = purge(client, "invalid", h);
        assertThat(r.detail("reason")).isEqualTo("not_confirmed");
        assertThat(r.detail("elicitation")).isEqualTo("invalid_answer");
        assertThat(storesOrExpunges(commandsSince(log))).isFalse();
        assertThat(subjects("invalid", trash)).containsExactly("keep-1");
    }

    @Test
    void anErrorAnswerIsErrorAndDeletesNothing() throws Exception {
        seedTrash("rpcerror", "keep-1");
        String trash = trashFolder("rpcerror");
        String h = handlesBySubject("rpcerror", trash).get("keep-1");
        user = req -> {
            throw new IllegalStateException("scripted failure");
        };
        long log = logSize();
        Result r = purge(client, "rpcerror", h);
        assertThat(r.detail("reason")).isEqualTo("not_confirmed");
        assertThat(r.detail("elicitation")).isEqualTo("error");
        assertThat(storesOrExpunges(commandsSince(log))).isFalse();
        assertThat(subjects("rpcerror", trash)).containsExactly("keep-1");
    }

    @Test
    void aClientWithoutElicitationIsConfirmationUnavailableWithZeroImapIo() throws Exception {
        seedTrash("noelicit", "keep-1");
        String trash = trashFolder("noelicit");
        String h = handlesBySubject("noelicit", trash).get("keep-1");
        try (McpSyncClient plain = newClient(McpSchema.ClientCapabilities.builder().build(), null)) {
            long log = logSize();
            Result r = purge(plain, "noelicit", h);
            assertThat(r.errorCode()).isEqualTo("PERMISSION_DENIED");
            assertThat(r.detail("reason")).isEqualTo("confirmation_unavailable");
            assertThat(commandsSince(log)).isEmpty();
        }
        assertThat(subjects("noelicit", trash)).containsExactly("keep-1");
    }

    @Test
    void handlesOutsideTrashAreRefusedBeforeAnyDialog() throws Exception {
        try (MailSeeder s = seeder("scope")) {
            s.appendPlain("INBOX", "inbox-1", "body");
        }
        String h = handlesBySubject("scope", "INBOX").get("inbox-1");
        user = req -> answer(McpSchema.ElicitResult.Action.ACCEPT, Map.of());
        requests.clear();
        Result r = purge(client, "scope", h);
        assertThat(r.detail("reason")).isEqualTo("purge_scope");
        assertThat(r.detail("field")).isEqualTo("handles[0]");
        assertThat(requests).isEmpty();
        assertThat(subjects("scope", "INBOX")).containsExactly("inbox-1");
    }

    /**
     * No answer within the window (the deadline is 60 s, the window 40 s): not_confirmed/timeout, and an acceptance
     * that arrives afterwards deletes nothing.
     */
    @Test
    void anAcceptanceAfterTheWindowDeletesNothing() throws Exception {
        seedTrash("late", "keep-1");
        String trash = trashFolder("late");
        String h = handlesBySubject("late", trash).get("keep-1");
        user = req -> {
            try {
                Thread.sleep(47_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return answer(McpSchema.ElicitResult.Action.ACCEPT, Map.of());
        };
        long log = logSize();
        Result r = purge(client, "late", h);
        assertThat(r.detail("reason")).isEqualTo("not_confirmed");
        assertThat(r.detail("elicitation")).isEqualTo("timeout");
        Thread.sleep(10_000); // the late acceptance has been delivered by now
        assertThat(storesOrExpunges(commandsSince(log))).isFalse();
        assertThat(subjects("late", trash)).containsExactly("keep-1");
    }

    // ---- session approval (SPEC 4 v3a "Session approval") ----

    private static final Pattern STATUS_BEFORE_DIALOG = Pattern.compile("^STATUS .*\\(UIDVALIDITY UIDNEXT\\)$");

    @Test
    void aSessionApprovalCoversTheNextCallWithoutADialogButNotLaterArrivalsOrOtherAccounts() throws Exception {
        seedTrash("sess", "s1", "s2", "s3");
        seedTrash("sess2", "other-1");
        String trash = trashFolder("sess");
        Map<String, String> h = handlesBySubject("sess", trash);
        user = req -> answer(McpSchema.ElicitResult.Action.ACCEPT, Map.of("allow_for_session", true));
        requests.clear();
        long log = logSize();

        Result first = purge(client, "sess", h.get("s1"));
        assertThat(first.error()).as("%s", first.structured()).isFalse();
        assertThat(first.structured).containsEntry("confirmation", "elicited");
        assertThat(requests).hasSize(1);
        assertThat(commandsSince(log)).anyMatch(c -> STATUS_BEFORE_DIALOG.matcher(c).find());

        // covered: no dialog, reported as session_approval, deleted
        Result second = purge(client, "sess", h.get("s2"));
        assertThat(second.error()).as("%s", second.structured()).isFalse();
        assertThat(second.structured).containsEntry("confirmation", "session_approval");
        assertThat(requests).hasSize(1);
        assertThat(subjects("sess", trash)).containsExactly("s3");

        // a message that reaches Trash after the approval always gets a dialog
        try (MailSeeder s = seeder("sess")) {
            s.appendPlain(trash, "late-arrival", "body");
        }
        Map<String, String> after = handlesBySubject("sess", trash);
        Result third = purge(client, "sess", after.get("late-arrival"));
        assertThat(third.structured).containsEntry("confirmation", "elicited");
        assertThat(requests).hasSize(2);

        // another account is never covered
        Result other = purge(client, "sess2", handlesBySubject("sess2", trashFolder("sess2")).get("other-1"));
        assertThat(other.structured).containsEntry("confirmation", "elicited");
        assertThat(requests).hasSize(3);
        assertNoPlainExpunge(commandsSince(log));
    }
}
