package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.testsupport.FaultingImapProxy;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * #137 AC2/AC3 at unit level: get_message and list_attachments against an IMAPS relay that answers or drops the
 * BODYSTRUCTURE FETCH (command rejection keeps the Store, connection loss discards it).
 */
class GetMessageImapErrorTest {

    static final TenantId T = new TenantId("t");
    static final AccountId ID = new AccountId("acct");

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    final MessageHandles handles = new MessageHandles(new KeyRing(1, Map.of(1, new byte[32])));
    FaultingImapProxy proxy;
    ImapSessionFactory sessions;
    StorePool pool;
    PoolKey key;
    AccountArgument accounts;
    GetMessageTool getMessage;
    ListAttachmentsTool listAttachments;
    String account;
    String handle;

    @BeforeEach
    void setUp() throws Exception {
        GREEN_MAIL.setUser("user@localhost", "user", "pw");
        proxy = new FaultingImapProxy(GREEN_MAIL.getImaps().getPort());
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        MailAccount acct = new MailAccount(ID, "Acct", null, "127.0.0.1", proxy.port(), Security.IMPLICIT_TLS,
                Flavor.GENERIC, "user", new SecretRef("env:A"));
        InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(T, List.of(acct)));
        accounts = new AccountArgument(repo);
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> acct, PoolSettings.DEFAULTS);
        StoreLeases leases = new StoreLeases(pool, Duration.ofSeconds(10));
        MessageLocator locator = new MessageLocator(handles);
        getMessage = new GetMessageTool(new AccountArgument(repo), locator, leases, new BodyWindowReader(), 100000);
        listAttachments = new ListAttachmentsTool(new AccountArgument(repo), locator, leases);
        account = InMemoryAccountRepository.handle(T, ID);
        key = PoolKey.of(new AccountContext(T), new ResolvedAccount(T, acct, account));
        handle = deliver();
    }

    @AfterEach
    void tearDown() throws Exception {
        pool.close();
        sessions.close();
        proxy.close();
    }

    private String deliver() throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new java.util.Properties()));
        m.setFrom("a@localhost");
        m.setRecipients(Message.RecipientType.TO, "user@localhost");
        m.setSubject("s");
        m.setText("body");
        m.saveChanges();
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        try (Store s = Session.getInstance(props).getStore("imaps")) {
            s.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "pw");
            IMAPFolder f = (IMAPFolder) s.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.appendMessages(new Message[] {m});
            String h = handles.encode(T, ID,
                    new MessageRef("INBOX", f.getUIDValidity(), f.getUID(f.getMessage(f.getMessageCount()))));
            f.close(false);
            return h;
        }
    }

    private ToolOutcome call(ToolHandler tool) {
        return tool.handle(new ToolCallContext(new AccountContext(T), "rid"),
                Map.of("account", account, "message", handle));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(ToolOutcome o) {
        assertThat(o).isInstanceOf(ToolOutcome.Failure.class);
        return (Map<String, Object>) ((ToolOutcome.Failure) o).error().toMap().get("error");
    }

    @SuppressWarnings("unchecked")
    private void assertRejectedFetchIsUpstreamErrorAndTheStoreIsReturned(ToolHandler tool, boolean serverTextSurfaces) {
        proxy.rejectWithNo("BODYSTRUCTURE", "fetch refused \u0001by\tpolicy " + "x".repeat(400));
        long destroyed = pool.destroyedCount();
        Map<String, Object> error = error(call(tool));
        assertThat(error.get("code")).isEqualTo(ErrorCode.UPSTREAM_ERROR.name());
        Map<String, Object> details = (Map<String, Object>) error.get("details");
        String text = details == null ? null : (String) details.get("server_text");
        if (serverTextSurfaces) {
            assertThat(text).contains("fetch refused").contains("by policy").hasSizeLessThanOrEqualTo(200)
                    .doesNotContainPattern("[\\p{Cntrl}]");
        } else { // Angus' fetchBodyStructure swallows the NO text; our own wording must not pose as server text
            assertThat(text).isNull();
        }
        assertThat(pool.destroyedCount()).isEqualTo(destroyed);
        assertThat(pool.numIdle(key)).isEqualTo(1);
        assertThat(pool.numActive(key)).isZero();
    }

    @Test
    void getMessageFetchAnsweredWithNoIsUpstreamErrorWithSanitizedTextAndTheStoreIsReturned() {
        assertRejectedFetchIsUpstreamErrorAndTheStoreIsReturned(getMessage, true);
    }

    @Test
    void listAttachmentsFetchAnsweredWithNoIsUpstreamErrorAndTheStoreIsReturned() {
        assertRejectedFetchIsUpstreamErrorAndTheStoreIsReturned(listAttachments, false);
    }

    private void assertDroppedConnectionIsUnavailableDiscardsAndTheNextCallSucceeds(ToolHandler tool) {
        assertThat(call(tool)).isInstanceOf(ToolOutcome.Success.class); // warm
        long destroyed = pool.destroyedCount();
        proxy.dropConnectionOn("BODYSTRUCTURE");
        Map<String, Object> error = error(call(tool));
        assertThat(error.get("code")).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE.name());
        assertThat(pool.destroyedCount()).isEqualTo(destroyed + 1);
        assertThat(pool.numIdle(key)).isZero();
        proxy.relay();
        assertThat(call(tool)).isInstanceOf(ToolOutcome.Success.class);
    }

    @Test
    void getMessageConnectionDropDuringFetchIsUpstreamUnavailableDiscardsTheStoreAndTheNextCallSucceeds() {
        assertDroppedConnectionIsUnavailableDiscardsAndTheNextCallSucceeds(getMessage);
    }

    @Test
    void listAttachmentsConnectionDropDuringFetchIsUpstreamUnavailableDiscardsTheStoreAndTheNextCallSucceeds() {
        assertDroppedConnectionIsUnavailableDiscardsAndTheNextCallSucceeds(listAttachments);
    }

    @Test
    void runtimeExceptionInGetMessageIsInternalErrorAndDiscardsTheStore() throws Exception {
        BodyWindowReader reader = mock(BodyWindowReader.class);
        when(reader.read(any(), anyLong(), any(), anyLong(), anyInt())).thenThrow(new IllegalStateException("boom"));
        GetMessageTool broken = new GetMessageTool(accounts, new MessageLocator(handles),
                new StoreLeases(pool, Duration.ofSeconds(10)), reader, 100000);
        Map<String, Object> error = error(call(broken));
        assertThat(error.get("code")).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(pool.destroyedCount()).isEqualTo(1);
        assertThat(pool.numIdle(key)).isZero();
    }
}
