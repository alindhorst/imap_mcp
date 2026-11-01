package de.alexanderlindhorst.imapmcp.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import de.alexanderlindhorst.imapmcp.core.tool.InputValidator;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.ListFoldersTool;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** list_folders through the real SDK (tools/list entry, output-schema validation); SDK types stay in core.mcp. */
class ListFoldersSdkTest {

    static final TenantId T = new TenantId("t");

    GreenMail greenMail;
    ImapSessionFactory sessions;
    StorePool pool;
    InMemoryMcp.Stateless mcp;
    String handle;

    @BeforeEach
    void setUp() throws Exception {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("ok@localhost", "ok", "pw");
        greenMail.getManagers().getImapHostManager().createMailbox(
                greenMail.getUserManager().getUserByEmail("ok@localhost"), "Archive.2025");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        MailAccount ok = new MailAccount(new AccountId("ok"), "Ok", null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, "ok", new SecretRef("env:A"));
        InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(T, List.of(ok)));
        handle = repo.findByTenant(T).get(0).handle();
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> ok, PoolSettings.DEFAULTS);
        ListFoldersTool tool = new ListFoldersTool(new AccountArgument(repo),
                new StoreLeases(pool, Duration.ofSeconds(10)));
        mcp = new InMemoryMcp.Stateless(new McpToolAdapter(c -> new AccountContext(T))
                .stateless(ListFoldersTool.DEFINITION, tool));
    }

    @AfterEach
    void tearDown() {
        pool.close();
        sessions.close();
        greenMail.stop();
    }

    // AC6

    @Test
    void toolsListEntryHasAnnotationsOutputSchemaAndValidDescription() {
        McpSchema.ListToolsResult listed = McpJsonDefaults.getMapper().convertValue(
                mcp.request(McpSchema.METHOD_TOOLS_LIST, Map.of(), McpTransportContext.EMPTY).result(),
                McpSchema.ListToolsResult.class);
        McpSchema.Tool t = listed.tools().get(0);
        assertThat(DescriptionRules.violations(ListFoldersTool.DEFINITION)).isEmpty();
        assertThat(t.name()).isEqualTo("list_folders");
        assertThat(t.title()).isEqualTo("List mail folders");
        assertThat(t.annotations().readOnlyHint()).isTrue();
        assertThat(t.annotations().destructiveHint()).isFalse();
        assertThat(t.annotations().idempotentHint()).isTrue();
        assertThat(t.outputSchema()).isEqualTo(ListFoldersTool.DEFINITION.outputSchema());
        assertThat(t.description()).contains("selectable=false means the folder cannot be searched "
                + "(IMAP \\Noselect/\\NonExistent, or full name longer than 1000 UTF-8 bytes).");
        InputValidator v = new InputValidator(ListFoldersTool.DEFINITION.inputSchema());
        assertThat(v.validate(Map.of("account", handle), "rid").valid()).isTrue();
        assertThat(v.validate(Map.of("account", handle, "include_counts", false), "rid").valid()).isTrue();
        assertThat(v.validate(Map.of(), "rid").valid()).isFalse();
        assertThat(v.validate(Map.of("account", handle, "x", 1), "rid").valid()).isFalse();
    }

    @Test
    void successOutputIncludingNullsPassesTheSdksOutputSchemaValidation() {
        McpSchema.CallToolResult r = mcp.call("list_folders", Map.of("account", handle), McpTransportContext.EMPTY);
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        Map<?, ?> out = (Map<?, ?>) r.structuredContent();
        assertThat((List<?>) out.get("folders")).hasSize(3); // INBOX, Archive, Archive.2025
        assertThat(out.get("account")).isEqualTo(handle);
    }

    @Test
    void foreignHandleIsAnErrorResult() {
        McpSchema.CallToolResult r = mcp.call("list_folders", Map.of("account", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa"),
                McpTransportContext.EMPTY);
        assertThat(r.isError()).isTrue();
    }
}
