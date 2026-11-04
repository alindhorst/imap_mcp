package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.store.secret.CommandRunner;
import de.alexanderlindhorst.imapmcp.store.secret.Platform;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** #262: key ring built from security.handleKeys / signingKeyId / handleKey / the handle-key state file. */
class AppComponentsKeyRingTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private static final CommandRunner NO_RUNNER = (argv, t) -> {
        throw new IOException("no command expected");
    };
    /** Distinct, recognizable key bytes; the encoded forms must never show up in a message. */
    private static final String K1 = key(1);
    private static final String K2 = key(2);
    private static final String K3 = key(3);

    @TempDir
    Path dir;

    private final List<AppComponents> created = new ArrayList<>();

    @AfterEach
    void closeAll() {
        created.forEach(AppComponents::close);
    }

    private static String key(int fill) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private AppConfig config(String security) throws IOException {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, """
                accounts:
                  - id: work
                    host: 127.0.0.1
                    port: %d
                    username: user
                    password: env:MAIL_PW
                """.formatted(GREEN_MAIL.getImaps().getPort()) + security);
        return ConfigLoader.load(f, Map.of());
    }

    private static String ring(int signing, String... idKeyEnv) {
        StringBuilder sb = new StringBuilder("security:\n  signingKeyId: " + signing + "\n  handleKeys:\n");
        for (int i = 0; i < idKeyEnv.length; i += 2) {
            sb.append("    - id: ").append(idKeyEnv[i]).append("\n      key: env:").append(idKeyEnv[i + 1]).append('\n');
        }
        return sb.toString();
    }

    private AppComponents create(String security, Map<String, String> keyEnv) throws IOException {
        Map<String, String> env = new HashMap<>(keyEnv);
        env.put("MAIL_PW", "pw");
        AppComponents a = AppComponents.create(config(security), new AppPaths(dir.resolve("config.yaml"),
                dir.resolve("state"), dir.resolve("logs")), Clock.systemUTC(), env, p -> {
                    p.put("mail.imaps.ssl.trust", "*");
                    p.put("mail.imaps.ssl.checkserveridentity", "false");
                    return p;
                }, Platform.LINUX, NO_RUNNER, platform -> true);
        created.add(a);
        return a;
    }

    private Session session(AppComponents c) {
        var byName = new HashMap<String, McpServerFeatures.SyncToolSpecification>();
        c.statefulToolSpecs().forEach(s -> byName.put(s.tool().name(), s));
        McpSyncServerExchange ex = mock(McpSyncServerExchange.class);
        when(ex.transportContext()).thenReturn(McpTransportContext.EMPTY);
        return new Session(byName, ex);
    }

    private record Session(Map<String, McpServerFeatures.SyncToolSpecification> specs, McpSyncServerExchange ex) {
        McpSchema.CallToolResult call(String tool, Map<String, Object> args) {
            return specs.get(tool).callHandler().apply(ex, new McpSchema.CallToolRequest(tool, args));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> ok(String tool, Map<String, Object> args) {
            McpSchema.CallToolResult r = call(tool, args);
            assertThat(r.isError()).as(tool + ": " + r.content()).isNotEqualTo(Boolean.TRUE);
            return (Map<String, Object>) r.structuredContent();
        }

        @SuppressWarnings("unchecked")
        String account() {
            return (String) ((List<Map<String, Object>>) ok("list_accounts", Map.of()).get("accounts")).get(0)
                    .get("account");
        }

        /** Error payload text without the per-call request id. */
        String errorText(String tool, Map<String, Object> args) {
            McpSchema.CallToolResult r = call(tool, args);
            assertThat(r.isError()).isEqualTo(Boolean.TRUE);
            return ((McpSchema.TextContent) r.content().get(0)).text().replaceAll("\"request_id\"\\s*:\\s*\"[^\"]*\"", "");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages(Map<String, Object> searchResult) {
            return (List<Map<String, Object>>) searchResult.get("messages");
        }
    }

    private static void deliver(int n) {
        var user = GREEN_MAIL.setUser("user@localhost", "user", "pw");
        for (int i = 0; i < n; i++) {
            user.deliver(GreenMailUtil.createTextEmail("user@localhost", "from@example.org", "s" + i, "b" + i,
                    GREEN_MAIL.getImaps().getServerSetup()));
        }
    }

    private static Map<String, Object> get(String account, String handle) {
        return Map.of("account", account, "message", handle);
    }

    // AC1: rotation

    @Test
    @SuppressWarnings("unchecked")
    void handleAndCursorMintedUnderKey1SurviveAddingKey2AsSignerAndFailAfterKey1IsRemoved() throws IOException {
        deliver(51); // one more than a page, so the first page has a next_cursor
        Map<String, String> env = Map.of("K1", K1, "K2", K2);

        // step 0: only id 1
        Session s0 = session(create(ring(1, "1", "K1"), env));
        String acct = s0.account();
        Map<String, Object> page0 = s0.ok("search_messages", Map.of("account", acct, "folder", "INBOX"));
        String h1 = (String) s0.messages(page0).get(0).get("handle");
        String c1 = (String) page0.get("next_cursor");
        assertThat(c1).isNotNull();
        created.forEach(AppComponents::close);
        created.clear();

        // step 1: id 2 added and signing; ids 1 and 2 verify
        Session s1 = session(create(ring(2, "1", "K1", "2", "K2"), env));
        assertThat(s1.ok("get_message", get(acct, h1)).get("untrusted")).isNotNull();
        Map<String, Object> next = s1.ok("search_messages", Map.of("account", acct, "folder", "INBOX", "cursor", c1));
        assertThat(s1.messages(next)).isNotEmpty();
        Map<String, Object> page1 = s1.ok("search_messages", Map.of("account", acct, "folder", "INBOX"));
        String h2 = (String) s1.messages(page1).get(0).get("handle");
        assertThat(h2).as("new handles are signed under the signing id").isNotEqualTo(h1);
        assertThat(s1.ok("get_message", get(acct, h2)).get("untrusted")).isNotNull();
        created.forEach(AppComponents::close);
        created.clear();

        // step 2: id 1 removed
        Session s2 = session(create(ring(2, "2", "K2"), env));
        assertThat(s2.ok("get_message", get(acct, h2)).get("untrusted")).isNotNull();
        String gone = s2.errorText("get_message", get(acct, h1));
        assertThat(gone).contains("INVALID_HANDLE");
        assertThat(gone).isEqualTo(s2.errorText("get_message", get(acct, "not-a-handle")));
        assertThat(s2.errorText("search_messages", Map.of("account", acct, "folder", "INBOX", "cursor", c1)))
                .contains("INVALID_CURSOR");
    }

    // AC2: compatibility

    @Test
    void singleHandleKeyAndAnId1EntryAreTheSameKey() throws IOException {
        deliver(1);
        Session a = session(create("security:\n  handleKey: env:K1\n", Map.of("K1", K1)));
        String acct = a.account();
        String h = (String) a.messages(a.ok("search_messages", Map.of("account", acct, "folder", "INBOX"))).get(0)
                .get("handle");
        Session b = session(create(ring(1, "1", "K1"), Map.of("K1", K1)));
        assertThat(b.ok("get_message", get(acct, h)).get("untrusted")).isNotNull();
        assertThat(Files.exists(dir.resolve("state").resolve("handle-key"))).isFalse();
    }

    @Test
    void stateFileKeyIsId1WhileSigningAndStaysAVerifyKeyAfterSwitchingTheSigner() throws IOException {
        deliver(1);
        Session a = session(create("", Map.of()));
        String acct = a.account();
        String h = (String) a.messages(a.ok("search_messages", Map.of("account", acct, "folder", "INBOX"))).get(0)
                .get("handle");
        Path file = dir.resolve("state").resolve("handle-key");
        String before = Files.readString(file);
        created.forEach(AppComponents::close);
        created.clear();

        Session b = session(create(ring(2, "2", "K2"), Map.of("K2", K2)));
        assertThat(b.ok("get_message", get(acct, h)).get("untrusted")).isNotNull();
        assertThat(Files.readString(file)).isEqualTo(before);
    }

    @Test
    void stateFileIsNotCreatedWhileAnotherIdSignsAndIdOneIsThenOutOfTheRing() throws IOException {
        deliver(1);
        Session a = session(create(ring(2, "2", "K2"), Map.of("K2", K2)));
        assertThat(Files.exists(dir.resolve("state").resolve("handle-key"))).isFalse();
        String acct = a.account();
        String h = (String) a.messages(a.ok("search_messages", Map.of("account", acct, "folder", "INBOX"))).get(0)
                .get("handle");
        assertThat(a.ok("get_message", get(acct, h)).get("untrusted")).isNotNull();
    }

    // AC3: startup errors

    private void failsNaming(String security, Map<String, String> env, String path) {
        assertThatThrownBy(() -> create(security, env)).isInstanceOf(AppStartupException.class)
                .hasMessageContaining(path).hasMessageContaining("config.yaml")
                .message().doesNotContain(K1).doesNotContain(K2).doesNotContain(K3);
    }

    @Test
    void unknownSigningIdFailsStartup() {
        failsNaming(ring(5, "1", "K1", "2", "K2"), Map.of("K1", K1, "K2", K2), "security.signingKeyId");
        failsNaming(ring(2, "3", "K3"), Map.of("K3", K3), "security.signingKeyId");
    }

    @Test
    void unresolvableKeyFailsStartupNamingTheEntry() {
        failsNaming(ring(1, "1", "K1", "2", "NOPE"), Map.of("K1", K1), "security.handleKeys[1].key");
    }

    @Test
    void keyOfWrongLengthFailsStartupNamingTheEntry() {
        String short16 = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
        failsNaming(ring(1, "1", "K1", "2", "S"), Map.of("K1", K1, "S", short16), "security.handleKeys[1].key");
    }

    @Test
    void keyWithBadEncodingFailsStartupNamingTheEntry() {
        String padded = Base64.getUrlEncoder().encodeToString(new byte[31]); // 31 bytes: '=' padding
        String standard = Base64.getEncoder().encodeToString(new byte[]{-1, -2, -3, -1, -2, -3, -1, -2, -3, -1, -2, -3,
                -1, -2, -3, -1, -2, -3, -1, -2, -3, -1, -2, -3, -1, -2, -3, -1, -2, -3, -1, -2}); // '+' and '/'
        for (String bad : List.of("!!!not base64!!!", padded, standard,
                Base64.getUrlEncoder().encodeToString(new byte[32]))) { // 32 bytes but padded (44 chars)
            failsNaming(ring(1, "1", "K1", "2", "BAD"), Map.of("K1", K1, "BAD", bad), "security.handleKeys[1].key");
        }
    }

    @Test
    void sameKeyBytesUnderTwoIdsFailStartup() {
        failsNaming(ring(1, "1", "K1", "2", "K2", "3", "K2B"), Map.of("K1", K1, "K2", K2, "K2B", K2),
                "security.handleKeys[2].key");
        failsNaming("security:\n  handleKey: env:K1\n  signingKeyId: 2\n  handleKeys:\n    - id: 2\n      key: env:K1B\n",
                Map.of("K1", K1, "K1B", K1), "security.handleKeys[0].key");
    }

    @Test
    void badSingleHandleKeyFailsStartupNamingTheKey() {
        failsNaming("security:\n  handleKey: env:S\n", Map.of("S", "AAAA"), "security.handleKey");
    }

    @Test
    void configKeyEqualToTheStateFileKeyFailsStartup() throws IOException {
        create("", Map.of()); // generates the state file
        created.forEach(AppComponents::close);
        created.clear();
        String stateKey = Files.readString(dir.resolve("state").resolve("handle-key")).strip();
        assertThatThrownBy(() -> create(ring(2, "2", "SK"), Map.of("SK", stateKey)))
                .isInstanceOf(AppStartupException.class).hasMessageContaining("security.handleKeys[0].key")
                .message().doesNotContain(stateKey);
    }
}
