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
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader;
import de.alexanderlindhorst.imapmcp.core.tools.GetMessageTool;
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
class GetMessageSdkTest {

    static final TenantId T = new TenantId("t");
    static final AccountId ID = new AccountId("acct");

    static final int CAP = 100000;

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
        GetMessageTool tool = new GetMessageTool(new AccountArgument(repo), new MessageLocator(handles),
                new StoreLeases(pool, Duration.ofSeconds(10)), new BodyWindowReader(), CAP);
        mcp = new InMemoryMcp.Stateless(new McpToolAdapter(c -> new AccountContext(T))
                .stateless(GetMessageTool.definition(CAP), tool));
    }

    @AfterEach
    void tearDown() {
        pool.close();
        sessions.close();
        greenMail.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolsListEntryHasAnnotationsPublishedCapAndValidDescription() {
        McpSchema.ListToolsResult listed = McpJsonDefaults.getMapper().convertValue(
                mcp.request(McpSchema.METHOD_TOOLS_LIST, Map.of(), McpTransportContext.EMPTY).result(),
                McpSchema.ListToolsResult.class);
        McpSchema.Tool t = listed.tools().get(0);
        assertThat(DescriptionRules.violations(GetMessageTool.definition(CAP))).isEmpty();
        assertThat(t.name()).isEqualTo("get_message");
        assertThat(t.title()).isEqualTo("Read message");
        assertThat(t.annotations().readOnlyHint()).isTrue();
        assertThat(t.annotations().destructiveHint()).isFalse();
        assertThat(t.outputSchema()).isEqualTo(GetMessageTool.definition(CAP).outputSchema());
        Map<String, Object> props = (Map<String, Object>) t.inputSchema().get("properties");
        assertThat(((Map<String, Object>) props.get("max_chars")).get("maximum")).isEqualTo(CAP);
        InputValidator v = new InputValidator(GetMessageTool.definition(CAP).inputSchema());
        assertThat(v.validate(Map.of("account", "a", "message", "m"), "rid").valid()).isTrue();
        assertThat(v.validate(Map.of("account", "a", "message", "m", "max_chars", 100000, "offset", 0,
                "include_headers", true), "rid").valid()).isTrue();
        assertThat(v.validate(Map.of("account", "a", "message", "m", "max_chars", 0), "rid").valid()).isFalse();
        assertThat(v.validate(Map.of("account", "a", "message", "m", "offset", -1), "rid").valid()).isFalse();
        assertThat(v.validate(Map.of("account", "a", "message", "m", "extra", 1), "rid").valid()).isFalse();
    }

    String deliver(boolean attachment) throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("hello \u00e4", "UTF-8");
        mixed.addBodyPart(text);
        if (attachment) {
            MimeBodyPart att = new MimeBodyPart();
            att.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(new byte[50], "application/pdf")));
            att.setFileName("x.pdf");
            att.setDisposition("attachment");
            mixed.addBodyPart(att);
        }
        MimeMessage msg = new MimeMessage(Session.getInstance(new java.util.Properties()));
        msg.setFrom("a@localhost");
        msg.setRecipients(Message.RecipientType.TO, "user@localhost");
        msg.setSubject("s");
        msg.setContent(mixed);
        msg.saveChanges();
        return append(msg);
    }

    String deliverMessage(MimeMultipart content) throws Exception {
        MimeMessage msg = new MimeMessage(Session.getInstance(new java.util.Properties()));
        msg.setFrom("a@localhost");
        msg.setRecipients(Message.RecipientType.TO, "user@localhost");
        msg.setSubject("s");
        msg.setContent(content);
        msg.saveChanges();
        return append(msg);
    }

    private String append(MimeMessage msg) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        try (Store s = Session.getInstance(props).getStore("imaps")) {
            s.connect("127.0.0.1", greenMail.getImaps().getPort(), "user", "pw");
            IMAPFolder f = (IMAPFolder) s.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.appendMessages(new Message[] {msg});
            String handle = handles.encode(T, ID,
                    new MessageRef("INBOX", f.getUIDValidity(), f.getUID(f.getMessage(f.getMessageCount()))));
            f.close(false);
            return handle;
        }
    }

    @Test
    void successOutputPassesTheSdksOutputSchemaValidationWithAndWithoutHeaders() throws Exception {
        String handle = deliver(true);
        String account = InMemoryAccountRepository.handle(T, ID);
        McpSchema.CallToolResult r = mcp.call("get_message", Map.of("account", account, "message", handle),
                McpTransportContext.EMPTY);
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(((Map<?, ?>) r.structuredContent()).get("attachments_total")).isEqualTo(1);
        McpSchema.CallToolResult withHeaders = mcp.call("get_message",
                Map.of("account", account, "message", handle, "include_headers", true, "max_chars", 5),
                McpTransportContext.EMPTY);
        assertThat(withHeaders.isError()).isNotEqualTo(Boolean.TRUE);
        Map<?, ?> untrusted = (Map<?, ?>) ((Map<?, ?>) withHeaders.structuredContent()).get("untrusted");
        assertThat(untrusted.get("headers")).isNotNull();
        assertThat(((Map<?, ?>) untrusted.get("body")).get("truncated")).isEqualTo(true);
    }

    /** #137 AC1: an attachment-only mail has no text part; the body object must still satisfy the outputSchema. */
    @Test
    @SuppressWarnings("unchecked")
    void messageWithoutTextPartSucceedsAndPassesTheOutputSchemaValidation() throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart att = new MimeBodyPart();
        att.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(new byte[50], "application/pdf")));
        att.setFileName("only.pdf");
        att.setDisposition("attachment");
        mixed.addBodyPart(att);
        String handle = deliverMessage(mixed);
        String account = InMemoryAccountRepository.handle(T, ID);

        McpSchema.CallToolResult r = mcp.call("get_message", Map.of("account", account, "message", handle),
                McpTransportContext.EMPTY);

        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        Map<String, Object> out = (Map<String, Object>) r.structuredContent();
        Map<String, Object> body = (Map<String, Object>) ((Map<String, Object>) out.get("untrusted")).get("body");
        assertThat(body.get("content_type")).isNull();
        assertThat(body).containsEntry("text", "").containsEntry("total_chars", 0L).containsEntry("truncated", false);
        assertThat(out.get("attachments_total")).isEqualTo(1);
        Map<String, Object> pdf = ((List<Map<String, Object>>) out.get("attachments")).get(0);
        assertThat((Map<String, Object>) pdf.get("untrusted")).containsEntry("filename", "only.pdf")
                .containsEntry("content_type", "application/pdf");
    }

    @Test
    void maxCharsAboveTheCapIsInvalidArgumentWithMaxThroughTheSdk() {
        String account = InMemoryAccountRepository.handle(T, ID);
        McpSchema.CallToolResult r = mcp.call("get_message",
                Map.of("account", account, "message", "m", "max_chars", 100001), McpTransportContext.EMPTY);
        assertThat(r.isError()).isTrue();
        Map<?, ?> error = (Map<?, ?>) ((Map<?, ?>) r.structuredContent()).get("error");
        assertThat(error.get("code")).isEqualTo("INVALID_ARGUMENT");
        assertThat(((Map<?, ?>) error.get("details")).get("max")).isEqualTo(100000);
    }
}
