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
import de.alexanderlindhorst.imapmcp.core.tools.ListAccountsTool;
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

/** list_accounts through the real SDK (tools/list entry, output-schema validation); SDK types stay in core.mcp. */
class ListAccountsSdkTest {

    static final TenantId T = new TenantId("t");

    GreenMail greenMail;
    ImapSessionFactory sessions;
    StorePool pool;
    InMemoryMcp.Stateless mcp;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("ok@localhost", "ok", "pw");
        greenMail.setUser("bad@localhost", "bad", "right");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        int port = greenMail.getImaps().getPort();
        MailAccount ok = new MailAccount(new AccountId("ok"), "Ok", "ok@example.org", "127.0.0.1", port,
                Security.IMPLICIT_TLS, Flavor.GENERIC, "ok", new SecretRef("env:A"));
        MailAccount bad = new MailAccount(new AccountId("bad"), "Bad", null, "127.0.0.1", port,
                Security.IMPLICIT_TLS, Flavor.GMAIL, "bad", new SecretRef("env:B"));
        InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(T, List.of(ok, bad)));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> k.accountId().value().equals("ok") ? ok : bad, PoolSettings.DEFAULTS);
        ListAccountsTool tool = new ListAccountsTool(repo, new CapabilityCache(),
                new StoreLeases(pool, Duration.ofSeconds(10)), 4);
        mcp = new InMemoryMcp.Stateless(new McpToolAdapter(c -> new AccountContext(T))
                .stateless(ListAccountsTool.DEFINITION, tool));
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
        assertThat(DescriptionRules.violations(ListAccountsTool.DEFINITION)).isEmpty();
        assertThat(t.name()).isEqualTo("list_accounts");
        assertThat(t.title()).isEqualTo("List mail accounts");
        McpSchema.ToolAnnotations a = t.annotations();
        assertThat(a.readOnlyHint()).isTrue();
        assertThat(a.destructiveHint()).isFalse();
        assertThat(a.idempotentHint()).isTrue();
        assertThat(a.openWorldHint()).isTrue();
        assertThat(t.outputSchema()).isEqualTo(ListAccountsTool.DEFINITION.outputSchema()).containsKey("properties");
        assertThat(t.inputSchema()).containsEntry("additionalProperties", false);
        InputValidator v = new InputValidator(ListAccountsTool.DEFINITION.inputSchema());
        assertThat(v.validate(Map.of(), "rid").valid()).isTrue();
        assertThat(v.validate(Map.of("x", 1), "rid").valid()).isFalse();
    }

    @Test
    void successOutputIncludingNullsPassesTheSdksOutputSchemaValidation() {
        McpSchema.CallToolResult r = mcp.call("list_accounts", Map.of(), McpTransportContext.EMPTY);
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((Map<?, ?>) r.structuredContent()).get("accounts")).asList().hasSize(2);
    }
}
