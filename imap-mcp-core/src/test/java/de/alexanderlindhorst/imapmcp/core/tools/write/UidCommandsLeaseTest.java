package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
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
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** CREATE / SUBSCRIBE go through the lease's single connection (no folder open, no second socket). */
class UidCommandsLeaseTest {

    private static final TenantId TENANT = new TenantId("t1");

    private GreenMail greenMail;
    private ImapSessionFactory sessions;
    private StorePool pool;
    private StoreLeases leases;
    private PoolKey key;
    private final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    private Logger traceLogger;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        GreenMailUser user = greenMail.setUser("u@localhost", "u", "pw");
        MimeMessage m = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()));
        try {
            m.setFrom("a@localhost");
            m.setSubject("m1");
            m.setText("b");
            user.deliver(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        MailAccount account = new MailAccount(new AccountId("a"), "label", null, "127.0.0.1",
                greenMail.getImaps().getPort(), Security.IMPLICIT_TLS, Flavor.GENERIC, "u", new SecretRef("env:X"));
        key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, account, "acc_test"));
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> account, PoolSettings.DEFAULTS);
        leases = new StoreLeases(pool, Duration.ofSeconds(10));
        traceLogger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(trace);
        trace.start();
    }

    @AfterEach
    void tearDown() {
        traceLogger.detachAppender(trace);
        pool.close();
        sessions.close();
        greenMail.stop();
    }

    private ToolCallContext ctx() {
        return new ToolCallContext(new AccountContext(TENANT), "rid");
    }

    private String wire() {
        return String.join("\n", trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void createAndSubscribeReturnOkAndNoWithResponseCodeOnOneSocket() throws Exception {
        leases.withLease(key, ctx(), lease -> {
            assertThat(UidCommands.create(lease, "Fresh").ok()).isTrue();
            assertThat(UidCommands.subscribe(lease, "Fresh").ok()).isTrue();
            UidCommands.Result again = UidCommands.create(lease, "Fresh");
            assertThat(again.ok()).isFalse();
            assertThat(again.no()).isTrue();
            assertThat(again.tagged().getRest()).isNotNull();
            return null;
        });
        assertThat(pool.createdCount()).isEqualTo(1);
    }

    @Test
    void createWhileAFolderIsOpenOnTheLeaseIsRefused() throws Exception {
        leases.withLease(key, ctx(), lease -> {
            lease.openReadWrite("INBOX");
            assertThatThrownBy(() -> UidCommands.create(lease, "X")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> UidCommands.subscribe(lease, "X")).isInstanceOf(IllegalStateException.class);
            return null;
        });
        assertThat(pool.createdCount()).isEqualTo(1);
        assertThat(wire()).doesNotContain("CREATE");
    }

    @Test
    void nonAsciiMailboxNameRoundTripsInModifiedUtf7() throws Exception {
        String name = "Entwürfe 日本";
        leases.withLease(key, ctx(), lease -> {
            assertThat(UidCommands.create(lease, name).ok()).isTrue();
            IMAPFolder inbox = lease.openReadWrite("INBOX");
            assertThat(inbox.getMode()).isEqualTo(Folder.READ_WRITE);
            long uid = inbox.getUID(inbox.getMessage(1));
            UidCommands.Result r = UidCommands.uidCopy(inbox, List.of(uid), name);
            assertThat(r.ok()).isTrue();
            UidCommands.uidStore(inbox, List.of(uid), UidCommands.FlagMode.ADD, new Flags(Flags.Flag.SEEN));
            lease.closeFolder();
            IMAPFolder f = lease.openReadWrite(name);
            assertThat(f.getMessageCount()).isEqualTo(1);
            return null;
        });
        assertThat(wire()).contains("CREATE \"Entw&APw-rfe &ZeVnLA-\"");
        assertThat(pool.createdCount()).isEqualTo(1);
    }
}
