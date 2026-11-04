package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Functional end-to-end test of the stdio mode (SPEC 5.8): the shaded jar runs as a subprocess of the SDK's own
 * {@link StdioClientTransport}, against Dovecot, once over implicit TLS (account "tls") and once over STARTTLS
 * (account "starttls"), both for the same Dovecot user.
 *
 * <p>Schema validation (AC2): every {@code structuredContent} is validated here, explicitly, against the
 * {@code outputSchema} the server published in {@code tools/list}, with the SDK's own
 * {@link JsonSchemaValidator} (McpJsonDefaults). This does not depend on the client's optional call-time caching.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StdioFunctionalIT {

    private static final String USER = "e2e";
    private static final int PLAIN_MESSAGES = 57;
    /** 57 plain + html + long body + multipart with attachments. */
    private static final int INBOX_TOTAL = PLAIN_MESSAGES + 3;
    private static final int SENT_TOTAL = 3;
    private static final String HTML_SUBJECT = "html-message";
    private static final String LONG_SUBJECT = "long-message";
    private static final String ATTACH_SUBJECT = "attachment-message";
    private static final List<String> V1_TOOLS =
            List.of("get_message", "list_accounts", "list_attachments", "list_folders", "search_messages");

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer();

    @TempDir
    static Path dir;

    private static ServerConfig config;
    private static McpSyncClient client;
    private static final List<String> STDERR = new CopyOnWriteArrayList<>();
    private static final Map<String, McpSchema.Tool> TOOLS = new LinkedHashMap<>();
    private static final JsonSchemaValidator VALIDATOR = McpJsonDefaults.getSchemaValidator();

    private static String longBody;
    private static String sentFolder;
    private static String tlsAccount;
    private static String starttlsAccount;
    /** subject -> handle, for every INBOX message, collected by the paging test. */
    private static final Map<String, String> HANDLES = new LinkedHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        seed();
        config = new ServerConfigWriter(dir)
                .account("tls", DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS, USER,
                        DovecotContainer.PASSWORD)
                .account("starttls", DOVECOT.getHost(), DOVECOT.startTlsPort(), MailAccount.Security.STARTTLS, USER,
                        DovecotContainer.PASSWORD)
                .readOnly("tls").readOnly("starttls") // this suite asserts the v1 tool set (#195)
                .trustStore(TestTrust.writeTruststore(dir)).write();

        // The documented command line, as built by JarLauncher: java -Xlog... [jvm args] -jar <jar> --mode stdio --config <f>
        List<String> cmd = JarLauncher.builder(config, "stdio").command();
        ServerParameters params = ServerParameters.builder(cmd.get(0))
                .args(cmd.subList(1, cmd.size()))
                .env(config.env())
                .build();
        StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
        transport.setStdErrorHandler(STDERR::add);
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(60)).build();
        client.initialize();
        for (McpSchema.Tool t : client.listTools().tools()) {
            TOOLS.put(t.name(), t);
        }
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
    }

    private static void seed() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append(String.format("long body line %04d with some filler text to make it longer%n", i));
        }
        longBody = sb.toString().replace("\r\n", "\n");
        try (MailSeeder seeder = DOVECOT.seeder(USER)) {
            for (int i = 0; i < PLAIN_MESSAGES; i++) {
                seeder.appendPlain("INBOX", String.format("plain-%03d", i), "body of message " + i);
            }
            seeder.appendHtml("INBOX", HTML_SUBJECT,
                    "<html><body><p>Hello <b>html</b> world</p><p>second paragraph</p></body></html>");
            seeder.appendPlain("INBOX", LONG_SUBJECT, longBody);
            seeder.appendMultipart("INBOX", ATTACH_SUBJECT, "text alternative", "<p>html alternative</p>",
                    List.of(new MailSeeder.Attachment("report.pdf", "application/pdf", new byte[] {1, 2, 3, 4, 5}),
                            new MailSeeder.Attachment("notes.txt", "text/plain", "some notes".getBytes())));
            sentFolder = folderWithAttribute(seeder, "\\Sent");
            for (int i = 0; i < SENT_TOTAL; i++) {
                seeder.appendPlain(sentFolder, "sent-" + i, "sent body " + i);
            }
        }
    }

    private static String folderWithAttribute(MailSeeder seeder, String attribute) throws MessagingException {
        for (Folder f : seeder.store().getDefaultFolder().list("*")) {
            if (List.of(((IMAPFolder) f).getAttributes()).contains(attribute)) {
                return f.getFullName();
            }
        }
        throw new IllegalStateException("no folder with " + attribute);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(String tool, Map<String, Object> args) throws IOException {
        McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(r.isError()).as("%s isError", tool).isNotEqualTo(Boolean.TRUE);
        assertThat(r.structuredContent()).as("%s structuredContent", tool).isInstanceOf(Map.class);
        Map<String, Object> structured = (Map<String, Object>) r.structuredContent();

        // AC2: valid against the published output schema ...
        JsonSchemaValidator.ValidationResponse v = VALIDATOR.validate(TOOLS.get(tool).outputSchema(), structured);
        assertThat(v.valid()).as("%s output schema: %s", tool, v.errorMessage()).isTrue();
        // ... plus exactly one mirror TextContent carrying the same JSON.
        assertThat(r.content()).hasSize(1);
        assertThat(r.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
        Map<String, Object> mirror = McpJsonDefaults.getMapper()
                .readValue(((McpSchema.TextContent) r.content().get(0)).text(), new TypeRef<Map<String, Object>>() {
                });
        assertThat(mirror).isEqualTo(structured);
        return structured;
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

    private static String subject(Map<String, Object> message) {
        return (String) map(message, "untrusted").get("subject");
    }

    private static void resolveAccounts() throws IOException {
        if (tlsAccount != null) {
            return;
        }
        Map<String, Object> accounts = call("list_accounts", args());
        for (Map<String, Object> a : list(accounts, "accounts")) {
            assertThat(a.get("status")).isEqualTo("ok");
            if ("tls".equals(a.get("label"))) {
                tlsAccount = (String) a.get("account");
            } else if ("starttls".equals(a.get("label"))) {
                starttlsAccount = (String) a.get("account");
            }
        }
        assertThat(tlsAccount).isNotNull();
        assertThat(starttlsAccount).isNotNull();
    }

    /** All INBOX messages by paging with the default page size; returns the page sizes. */
    private static List<Integer> pageThroughInbox(String account) throws IOException {
        List<Integer> sizes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        do {
            Map<String, Object> a = args("account", account, "folder", "INBOX");
            if (cursor != null) {
                a.put("cursor", cursor);
            }
            Map<String, Object> page = call("search_messages", a);
            assertThat(((Number) page.get("total")).intValue()).isEqualTo(INBOX_TOTAL);
            List<Map<String, Object>> messages = list(page, "messages");
            sizes.add(messages.size());
            for (Map<String, Object> m : messages) {
                assertThat(seen.add((String) m.get("handle"))).as("handle seen twice").isTrue();
                HANDLES.putIfAbsent(subject(m), (String) m.get("handle"));
            }
            cursor = (String) page.get("next_cursor");
            assertThat(page.get("has_more")).isEqualTo(cursor != null);
        } while (cursor != null);
        assertThat(seen).hasSize(INBOX_TOTAL);
        return sizes;
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void toolsListHasExactlyTheFiveV1ToolsWithAnnotationsAndOutputSchemas() {
        assertThat(TOOLS.keySet()).containsExactlyInAnyOrderElementsOf(V1_TOOLS);
        for (McpSchema.Tool t : TOOLS.values()) {
            McpSchema.ToolAnnotations a = t.annotations();
            assertThat(a).as(t.name()).isNotNull();
            assertThat(a.readOnlyHint()).as(t.name()).isTrue();
            assertThat(a.destructiveHint()).as(t.name()).isFalse();
            assertThat(a.idempotentHint()).as(t.name()).isTrue();
            assertThat(a.openWorldHint()).as(t.name()).isTrue();
            assertThat(t.outputSchema()).as(t.name()).isNotEmpty();
            assertThat(t.inputSchema()).as(t.name()).isNotNull();
            // The validator used for AC2 is not vacuous: an empty object violates every required output.
            assertThat(VALIDATOR.validate(t.outputSchema(), Map.of()).valid()).as(t.name()).isFalse();
        }
    }

    @Test
    @Order(2)
    void bothAccountsListedAndReachableOverImplicitTlsAndStarttls() throws Exception {
        resolveAccounts();
        // AC7: both transports work; list_accounts probes each account (status ok, checked in resolveAccounts).
        for (String account : List.of(tlsAccount, starttlsAccount)) {
            Map<String, Object> folders = call("list_folders", args("account", account));
            assertThat(list(folders, "folders")).extracting(f -> f.get("folder")).contains("INBOX");
            Map<String, Object> page = call("search_messages", args("account", account, "folder", "INBOX"));
            assertThat(((Number) page.get("total")).intValue()).isEqualTo(INBOX_TOTAL);
        }
    }

    @Test
    @Order(3)
    void listFoldersReportsSpecialUseRolesAndCounts() throws Exception {
        resolveAccounts();
        Map<String, Object> folders = call("list_folders", args("account", tlsAccount));
        Map<String, String> byRole = new LinkedHashMap<>();
        for (Map<String, Object> f : list(folders, "folders")) {
            if (f.get("role") != null) {
                byRole.put((String) f.get("role"), (String) f.get("folder"));
            }
        }
        assertThat(byRole).containsKeys("sent", "trash", "drafts", "junk", "archive");
        assertThat(byRole.get("sent")).isEqualTo(sentFolder);
        Map<String, Object> inbox = list(folders, "folders").stream()
                .filter(f -> "INBOX".equals(f.get("folder"))).findFirst().orElseThrow();
        assertThat(((Number) inbox.get("total")).intValue()).isEqualTo(INBOX_TOTAL);
        assertThat(((Number) inbox.get("unread")).intValue()).isEqualTo(INBOX_TOTAL);
    }

    @Test
    @Order(4)
    void pagingAcrossThreePagesReturnsEverySeededMessageExactlyOnce() throws Exception {
        resolveAccounts();
        assertThat(pageThroughInbox(tlsAccount)).containsExactly(25, 25, 10);
        assertThat(HANDLES).hasSize(INBOX_TOTAL).containsKeys(HTML_SUBJECT, LONG_SUBJECT, ATTACH_SUBJECT,
                "plain-000", "plain-056");
    }

    @Test
    @Order(5)
    void getMessageReturnsPlainAndHtmlBodies() throws Exception {
        resolveAccounts();
        pageThroughInbox(tlsAccount);
        Map<String, Object> plain = call("get_message",
                args("account", tlsAccount, "message", HANDLES.get("plain-007")));
        Map<String, Object> plainBody = map(map(plain, "untrusted"), "body");
        assertThat(plainBody.get("text").toString().strip()).isEqualTo("body of message 7");
        assertThat(plainBody.get("converted_from_html")).isEqualTo(false);
        assertThat(plainBody.get("truncated")).isEqualTo(false);

        Map<String, Object> html = call("get_message", args("account", tlsAccount, "message", HANDLES.get(HTML_SUBJECT)));
        Map<String, Object> htmlBody = map(map(html, "untrusted"), "body");
        assertThat(htmlBody.get("converted_from_html")).isEqualTo(true);
        assertThat(htmlBody.get("text").toString()).contains("Hello").contains("html").contains("second paragraph")
                .doesNotContain("<p>").doesNotContain("<b>");
    }

    @Test
    @Order(6)
    void getMessageContinuationWindowsConcatenateToTheFullBody() throws Exception {
        resolveAccounts();
        pageThroughInbox(tlsAccount);
        String handle = HANDLES.get(LONG_SUBJECT);
        StringBuilder joined = new StringBuilder();
        long offset = 0;
        int windows = 0;
        while (true) {
            Map<String, Object> m = call("get_message",
                    args("account", tlsAccount, "message", handle, "max_chars", 1000, "offset", offset));
            Map<String, Object> body = map(map(m, "untrusted"), "body");
            joined.append(body.get("text"));
            windows++;
            assertThat(((Number) body.get("offset")).longValue()).isEqualTo(offset);
            if (Boolean.TRUE.equals(body.get("truncated"))) {
                assertThat(body.get("next_offset")).isNotNull();
                offset = ((Number) body.get("next_offset")).longValue();
            } else {
                assertThat(body.get("next_offset")).isNull();
                break;
            }
            assertThat(windows).as("runaway continuation").isLessThan(100);
        }
        assertThat(windows).isGreaterThan(3);
        assertThat(joined.toString().replace("\r\n", "\n").strip()).isEqualTo(longBody.strip());
    }

    @Test
    @Order(7)
    void listAttachmentsAndGetMessageAgreeOnTheAttachments() throws Exception {
        resolveAccounts();
        pageThroughInbox(tlsAccount);
        String handle = HANDLES.get(ATTACH_SUBJECT);
        Map<String, Object> attachments = call("list_attachments", args("account", tlsAccount, "message", handle));
        assertThat(((Number) attachments.get("total")).intValue()).isEqualTo(2);
        assertThat(list(attachments, "attachments")).extracting(a -> map(a, "untrusted").get("filename"))
                .containsExactlyInAnyOrder("report.pdf", "notes.txt");
        assertThat(list(attachments, "attachments")).extracting(a -> map(a, "untrusted").get("content_type").toString())
                .anyMatch(t -> t.startsWith("application/pdf"));

        Map<String, Object> m = call("get_message", args("account", tlsAccount, "message", handle));
        assertThat(((Number) m.get("attachments_total")).intValue()).isEqualTo(2);
        assertThat(list(m, "attachments")).isEqualTo(list(attachments, "attachments"));
    }

    @Test
    @Order(8)
    void searchByFolderRoleSentFindsTheSentMessages() throws Exception {
        resolveAccounts();
        for (String account : List.of(tlsAccount, starttlsAccount)) {
            Map<String, Object> page = call("search_messages", args("account", account, "folder_role", "sent"));
            assertThat(page.get("folder")).isEqualTo(sentFolder);
            assertThat(((Number) page.get("total")).intValue()).isEqualTo(SENT_TOTAL);
            assertThat(list(page, "messages")).extracting(StdioFunctionalIT::subject)
                    .containsExactlyInAnyOrder("sent-0", "sent-1", "sent-2");
        }
    }

    @Test
    @Order(9)
    void starttlsAccountBehavesLikeTheImplicitTlsAccount() throws Exception {
        resolveAccounts();
        assertThat(pageThroughInbox(starttlsAccount)).containsExactly(25, 25, 10);
        // Handles are bound to their account: read a message through the STARTTLS account with its own handle.
        Map<String, Object> m = call("search_messages",
                args("account", starttlsAccount, "folder", "INBOX", "subject", ATTACH_SUBJECT));
        String handle = (String) list(m, "messages").get(0).get("handle");
        Map<String, Object> msg = call("get_message", args("account", starttlsAccount, "message", handle));
        assertThat(((Number) msg.get("attachments_total")).intValue()).isEqualTo(2);
    }

    @Test
    @Order(10)
    void plaintextIsNotConfigurable() throws Exception {
        Path other = Files.createDirectories(dir.resolve("plaintext"));
        ServerConfig cfg = new ServerConfigWriter(other)
                .account("plain", DOVECOT.getHost(), DOVECOT.startTlsPort(), MailAccount.Security.STARTTLS, USER,
                        DovecotContainer.PASSWORD)
                .write();
        Files.writeString(cfg.configFile(), Files.readString(cfg.configFile()).replace("security: starttls",
                "security: none"));
        try (RunningServer server = JarLauncher.start(cfg, "stdio")) {
            assertThat(server.waitFor(30)).isTrue();
            assertThat(server.exitCode()).isNotZero();
            assertThat(server.stdout().readAllBytes()).isEmpty();
            assertThat(server.stderr()).contains("security");
        }
    }

    /**
     * Self-contained (#143): reads mail itself (search, get_message with body and attachment), so the assertions are
     * meaningful whatever ran before; unordered tests run after the {@code @Order}ed ones anyway.
     */
    @Test
    void theWholeFlowLeftNoMessageSeenAndLoggedNoMailContent() throws Exception {
        resolveAccounts();
        pageThroughInbox(tlsAccount);
        call("get_message", args("account", tlsAccount, "message", HANDLES.get("plain-007")));
        call("get_message", args("account", tlsAccount, "message", HANDLES.get(ATTACH_SUBJECT)));
        call("list_attachments", args("account", tlsAccount, "message", HANDLES.get(ATTACH_SUBJECT)));
        try (MailSeeder seeder = DOVECOT.seeder(USER)) {
            for (String folder : List.of("INBOX", sentFolder)) {
                IMAPFolder f = (IMAPFolder) seeder.store().getFolder(folder);
                f.open(Folder.READ_ONLY);
                try {
                    Message[] all = f.getMessages();
                    assertThat(all).isNotEmpty();
                    for (Message m : all) {
                        assertThat(m.isSet(Flags.Flag.SEEN)).as("\\Seen in %s", folder).isFalse();
                    }
                } finally {
                    f.close(false);
                }
            }
        }
        // stderr and the log file must carry no mail content (subjects, bodies).
        String logs = String.join("\n", STDERR);
        assertThat(config.logFile()).as("the server's log file").exists();
        logs += Files.readString(config.logFile());
        assertThat(logs).doesNotContain("plain-0").doesNotContain("body of message").doesNotContain("report.pdf")
                .doesNotContain("long body line").doesNotContain("secret");
    }
}
