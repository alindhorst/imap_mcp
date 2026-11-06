package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code purge_messages} in {@code http-local} with {@code http.stateful: true} (SPEC 4 v3b "Purge over HTTP", the O4
 * retrofit), end to end: the shaded jar on a free loopback port against Dovecot, driven by the SDK's Streamable HTTP
 * client with the generated bearer token and a scripted elicitation handler. The confirmation travels on the call's
 * SSE stream and the answer comes back as a POST of the same session. Session approvals belong to the session.
 */
@Testcontainers
class HttpLocalPurgeIT {

    private static final String USER = "purge-http";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer(DovecotContainer.Profile.FULL);

    @TempDir
    static Path dir;

    private static int port;
    private static String token;
    private static RunningServer server;
    private static String account;
    private static final List<McpSchema.ElicitFormRequest> requests = new CopyOnWriteArrayList<>();
    private static volatile Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> user;

    @BeforeAll
    static void start() throws Exception {
        port = freePort();
        ServerConfig cfg = new ServerConfigWriter(dir)
                .account("work", DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS, USER,
                        DovecotContainer.PASSWORD)
                .allowPurge("work").trustStore(TestTrust.writeTruststore(dir))
                .extraYaml("http:\n  port: " + port + "\n  stateful: true\ntools:\n  deadline: 60s\n").write();
        server = JarLauncher.start(cfg, "http-local");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            if (!server.process().isAlive()) {
                throw new IllegalStateException("server exited early: " + server.exitCode() + " " + server.stderr());
            }
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    break;
                }
            } catch (IOException notYet) {
                Thread.sleep(200);
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("server did not become healthy");
            }
        }
        token = Files.readString(cfg.stateDir().resolve("http-token")).trim();
        try (McpSyncClient c = client(null)) {
            c.initialize();
            account = (String) list(call(c, "list_accounts", Map.of()), "accounts").get(0).get("account");
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    private static McpSyncClient client(Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> handler) {
        McpClient.SyncSpec spec = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((b, m, u, body, ctx) -> b.header("Authorization", "Bearer " + token)).build())
                .requestTimeout(Duration.ofSeconds(120)).initializationTimeout(Duration.ofSeconds(30));
        if (handler != null) {
            spec = spec.capabilities(McpSchema.ClientCapabilities.builder().elicitation(true, false).build())
                    .elicitation(req -> {
                        requests.add(req);
                        return handler.apply(req);
                    });
        }
        McpSyncClient c = spec.build();
        c.initialize();
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(McpSyncClient c, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(r.structuredContent()).as(tool).isInstanceOf(Map.class);
        Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) r.structuredContent());
        m.put("__error", Boolean.TRUE.equals(r.isError()));
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

    private static String trash(McpSyncClient c) {
        return list(call(c, "list_folders", Map.of("account", account)), "folders").stream()
                .filter(f -> "trash".equals(f.get("role"))).map(f -> (String) f.get("folder")).findFirst().orElseThrow();
    }

    private static void seed(McpSyncClient c, String... subjects) throws MessagingException {
        String trash = trash(c);
        try (MailSeeder s = DOVECOT.seeder(USER)) {
            for (String subject : subjects) {
                s.appendPlain(trash, subject, "body of " + subject);
            }
        }
    }

    private static Map<String, String> handles(McpSyncClient c) {
        Map<String, Object> search = call(c, "search_messages",
                Map.of("account", account, "folder", trash(c), "page_size", 100));
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> m : list(search, "messages")) {
            out.put((String) map(m, "untrusted").get("subject"), (String) m.get("handle"));
        }
        return out;
    }

    private static List<String> subjects(McpSyncClient c) throws MessagingException {
        try (MailSeeder s = DOVECOT.seeder(USER)) {
            IMAPFolder f = (IMAPFolder) s.store().getFolder(trash(c));
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

    private static Map<String, Object> purge(McpSyncClient c, String... handles) {
        return call(c, "purge_messages", Map.of("account", account, "handles", List.of(handles)));
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void purgeIsListedAndTheConfirmationRoundTripsOverTheCallsStream() throws Exception {
        user = req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of());
        requests.clear();
        try (McpSyncClient c = client(user)) {
            assertThat(c.listTools().tools()).extracting(McpSchema.Tool::name).contains("purge_messages");
            seed(c, "http-gone-1", "http-gone-2", "http-keep");
            Map<String, String> h = handles(c);
            Map<String, Object> r = purge(c, h.get("http-gone-1"), h.get("http-gone-2"));
            assertThat(r.get("__error")).isEqualTo(false);
            assertThat(r).containsEntry("confirmation", "elicited");
            assertThat(list(r, "results")).extracting(i -> i.get("status")).containsExactly("ok", "ok");
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).message()).startsWith("Permanently delete 2 messages from the Trash folder")
                    .endsWith("This cannot be undone.").doesNotContain("http-gone");
            assertThat(subjects(c)).containsExactly("http-keep");
        }
    }

    @Test
    void aDeclineDeletesNothing() throws Exception {
        user = req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null);
        try (McpSyncClient c = client(user)) {
            seed(c, "http-declined");
            String h = handles(c).get("http-declined");
            Map<String, Object> r = purge(c, h);
            assertThat(r.get("__error")).isEqualTo(true);
            assertThat(map(map(r, "error"), "details")).containsEntry("reason", "not_confirmed")
                    .containsEntry("elicitation", "declined");
            assertThat(subjects(c)).contains("http-declined");
        }
    }

    @Test
    void aClientThatCannotElicitGetsConfirmationUnavailable() throws Exception {
        try (McpSyncClient c = client(null)) {
            seed(c, "http-no-elicit");
            String h = handles(c).get("http-no-elicit");
            Map<String, Object> r = purge(c, h);
            assertThat(r.get("__error")).isEqualTo(true);
            assertThat(map(map(r, "error"), "details")).containsEntry("reason", "confirmation_unavailable");
            assertThat(subjects(c)).contains("http-no-elicit");
        }
    }

    @Test
    void aSessionApprovalCoversTheNextCallOfThatSessionOnly() throws Exception {
        user = req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT,
                Map.of("allow_for_session", true));
        requests.clear();
        try (McpSyncClient first = client(user)) {
            seed(first, "sess-1", "sess-2", "sess-3");
            Map<String, String> h = handles(first);
            assertThat(purge(first, h.get("sess-1"))).containsEntry("confirmation", "elicited");
            assertThat(purge(first, h.get("sess-2"))).containsEntry("confirmation", "session_approval");
            assertThat(requests).hasSize(1);
            // another session of the same tenant and account is asked again
            user = req -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of());
            try (McpSyncClient second = client(user)) {
                assertThat(purge(second, h.get("sess-3"))).containsEntry("confirmation", "elicited");
            }
            assertThat(requests).hasSize(2);
        }
    }

    @Test
    void getAndDeleteOnMcpNeedTheTokenToo() throws Exception {
        for (String method : List.of("GET", "DELETE")) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(method).isEqualTo(401);
        }
    }
}
