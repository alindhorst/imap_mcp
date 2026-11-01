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
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
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
import de.alexanderlindhorst.imapmcp.core.tools.ListAttachmentsTool;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** list_attachments through the real SDK: tools/list entry, input validation and output-schema validation. */
class ListAttachmentsSdkTest {

    static final TenantId T = new TenantId("t");
    static final AccountId ID = new AccountId("acct");

    GreenMail greenMail;
    ImapSessionFactory sessions;
    StorePool pool;
    InMemoryMcp.Stateless mcp;
    MessageHandles handles = new MessageHandles(new KeyRing(1, Map.of(1, new byte[32])));

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("user@localhost", "user", "pw");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        MailAccount acct = new MailAccount(ID, "Acct", null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, "user", new SecretRef("env:A"));
        InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(T, List.of(acct)));
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> acct, PoolSettings.DEFAULTS);
        ListAttachmentsTool tool = new ListAttachmentsTool(new AccountArgument(repo), new MessageLocator(handles),
                new StoreLeases(pool, Duration.ofSeconds(10)));
        mcp = new InMemoryMcp.Stateless(new McpToolAdapter(c -> new AccountContext(T))
                .stateless(ListAttachmentsTool.DEFINITION, tool));
    }

    @AfterEach
    void tearDown() {
        pool.close();
        sessions.close();
        greenMail.stop();
    }

    @Test
    void toolsListEntryHasAnnotationsOutputSchemaAndValidDescription() {
        McpSchema.ListToolsResult listed = McpJsonDefaults.getMapper().convertValue(
                mcp.request(McpSchema.METHOD_TOOLS_LIST, Map.of(), McpTransportContext.EMPTY).result(),
                McpSchema.ListToolsResult.class);
        McpSchema.Tool t = listed.tools().get(0);
        assertThat(DescriptionRules.violations(ListAttachmentsTool.DEFINITION)).isEmpty();
        assertThat(t.name()).isEqualTo("list_attachments");
        assertThat(t.title()).isEqualTo("List attachments");
        assertThat(t.annotations().readOnlyHint()).isTrue();
        assertThat(t.annotations().destructiveHint()).isFalse();
        assertThat(t.outputSchema()).isEqualTo(ListAttachmentsTool.DEFINITION.outputSchema());
        InputValidator v = new InputValidator(ListAttachmentsTool.DEFINITION.inputSchema());
        assertThat(v.validate(Map.of("account", "a", "message", "m"), "rid").valid()).isTrue();
        assertThat(v.validate(Map.of("account", "a"), "rid").valid()).isFalse();
        assertThat(v.validate(Map.of("account", "a", "message", ""), "rid").valid()).isFalse();
        assertThat(v.validate(Map.of("account", "a", "message", "x".repeat(1401)), "rid").valid()).isFalse();
        assertThat(v.validate(Map.of("account", "a", "message", "m", "extra", 1), "rid").valid()).isFalse();
    }

    @Test
    void successOutputPassesTheSdksOutputSchemaValidation() throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("hello");
        mixed.addBodyPart(text);
        MimeBodyPart att = new MimeBodyPart();
        att.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(new byte[50], "application/pdf")));
        att.setFileName("x.pdf");
        att.setDisposition("attachment");
        mixed.addBodyPart(att);
        MimeMessage msg = new MimeMessage(Session.getInstance(new java.util.Properties()));
        msg.setFrom("a@localhost");
        msg.setRecipients(Message.RecipientType.TO, "user@localhost");
        msg.setContent(mixed);
        msg.saveChanges();
        String handle;
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        try (Store s = Session.getInstance(props).getStore("imaps")) {
            s.connect("127.0.0.1", greenMail.getImaps().getPort(), "user", "pw");
            IMAPFolder f = (IMAPFolder) s.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.appendMessages(new Message[] {msg});
            handle = handles.encode(T, ID, new MessageRef("INBOX", f.getUIDValidity(), f.getUID(f.getMessage(1))));
            f.close(false);
        }
        String account = InMemoryAccountRepository.handle(T, ID);
        McpSchema.CallToolResult r = mcp.call("list_attachments", Map.of("account", account, "message", handle),
                McpTransportContext.EMPTY);
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((Map<?, ?>) r.structuredContent()).get("total")).isEqualTo(1);
    }
}
