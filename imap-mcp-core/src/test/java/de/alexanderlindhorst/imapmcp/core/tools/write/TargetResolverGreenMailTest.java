package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.folder.FolderResolver;
import de.alexanderlindhorst.imapmcp.core.folder.FolderRole;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Target resolution on the wire against GreenMail (no SPECIAL-USE): LIST only, the target is never selected. */
class TargetResolverGreenMailTest {

    private static final String USER = "targetuser";
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
        greenMail.setUser(USER + "@localhost", USER, "pw");
        MailAccount account = new MailAccount(new AccountId("a"), "label", null, "127.0.0.1",
                greenMail.getImaps().getPort(), Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
        key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, account, "acc_test"));
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> k.equals(key) ? account : null, PoolSettings.DEFAULTS);
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

    private void createFolder(String name) throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(
                greenMail.getUserManager().getUserByEmail(USER + "@localhost"), name);
    }

    private <T> T lease(java.util.function.Function<de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease, T> f)
            throws Exception {
        return leases.withLease(key, new ToolCallContext(new AccountContext(TENANT), "rid"), l -> f.apply(l));
    }

    @Test
    void trashOnServerWithoutSpecialUseIsFolderNotFound() throws Exception {
        createFolder("Trash"); // present by name, but no \Trash attribute
        FolderResolver.Resolution r = lease(l -> {
            try {
                return TargetResolver.resolveTrash(l, "rid");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(r.error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(r.error().details()).isEqualTo(Map.of("folder_role", "trash"));
        assertThat(r.error().hint()).contains("move_messages");
    }

    @Test
    void resolutionNeverSelectsOrExaminesTheTarget() throws Exception {
        createFolder("Work");
        lease(l -> {
            try {
                assertThat(TargetResolver.resolve(l, "Work", null, "rid").fullName()).isEqualTo("Work");
                assertThat(TargetResolver.resolve(l, "Missing", null, "rid").error()).isNotNull();
                assertThat(TargetResolver.resolve(l, null, FolderRole.SENT, "rid").error()).isNotNull();
                assertThat(TargetResolver.resolveTrash(l, "rid").error()).isNotNull();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        List<String> lines = trace.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).anyMatch(l -> l.matches("[A-Z]+\\d+ LIST .*"));
        assertThat(lines).noneMatch(l -> l.matches("[A-Z]+\\d+ (SELECT|EXAMINE|STORE|EXPUNGE|FETCH|APPEND|COPY|CREATE|DELETE) .*"));
    }
}
