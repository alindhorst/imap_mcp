package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

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
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.metrics.InMemoryMetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SPEC 5.6: each write verb of UidCommands is recorded as its own op under the hashed tenant. */
class UidCommandsMetricsTest {

    private static final TenantId TENANT = new TenantId("t1");

    private final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    private final InMemoryMetricsRecorder recorder = new InMemoryMetricsRecorder(logIds, Clock.systemUTC());
    private GreenMail greenMail;
    private ImapSessionFactory sessions;
    private StorePool pool;
    private StoreLeases leases;
    private PoolKey key;

    @BeforeEach
    void setUp() throws Exception {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        GreenMailUser user = greenMail.setUser("u@localhost", "u", "pw");
        for (int i = 0; i < 3; i++) {
            MimeMessage m = new MimeMessage(jakarta.mail.Session.getInstance(new Properties()));
            m.setFrom("a@localhost");
            m.setSubject("m" + i);
            m.setText("b");
            user.deliver(m);
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
                k -> account, PoolSettings.DEFAULTS, recorder);
        leases = new StoreLeases(pool, Duration.ofSeconds(10), null, recorder);
    }

    @AfterEach
    void tearDown() {
        recorder.close();
        pool.close();
        sessions.close();
        greenMail.stop();
    }

    private ToolCallContext ctx() {
        return new ToolCallContext(new AccountContext(TENANT), "rid");
    }

    private Map<String, Long> counts() {
        Map<String, Long> m = new java.util.TreeMap<>();
        for (MetricsSnapshot.OperationMetrics o : recorder.snapshot().operations()) {
            assertThat(o.tenant()).isEqualTo(logIds.tenant("t1"));
            m.put(o.op(), o.count());
        }
        return m;
    }

    @Test
    void createAndSubscribeAreRecorded() throws Exception {
        leases.withLease(key, ctx(), lease -> {
            UidCommands.create(lease, "Fresh");
            UidCommands.subscribe(lease, "Fresh");
            return null;
        });
        assertThat(counts()).containsEntry("create", 1L).containsEntry("subscribe", 1L).doesNotContainKey("read");
    }

    @Test
    void folderVerbsAreRecordedUnderTheirOwnOp() throws Exception {
        leases.withLease(key, ctx(), lease -> {
            UidCommands.create(lease, "T");
            IMAPFolder inbox = lease.openReadWrite("INBOX");
            List<Long> uids = List.of(inbox.getUID(inbox.getMessage(1)), inbox.getUID(inbox.getMessage(2)),
                    inbox.getUID(inbox.getMessage(3)));
            UidCommands.uidCopy(inbox, List.of(uids.get(0)), "T");
            UidCommands.uidMove(inbox, List.of(uids.get(1)), "T"); // NO is fine: the attempt is timed
            UidCommands.uidStore(inbox, List.of(uids.get(2)), UidCommands.FlagMode.ADD,
                    new Flags(Flags.Flag.DELETED));
            UidCommands.uidExpunge(inbox, List.of(uids.get(2)));
            return null;
        });
        assertThat(counts()).containsEntry("uid_copy", 1L).containsEntry("uid_move", 1L)
                .containsEntry("uid_store", 1L).containsEntry("uid_expunge", 1L);
    }

    @Test
    void validationFailureBeforeTheWireIsNotRecorded() throws Exception {
        leases.withLease(key, ctx(), lease -> {
            IMAPFolder inbox = lease.openReadWrite("INBOX");
            try {
                UidCommands.uidMove(inbox, List.of(), "X");
            } catch (IllegalArgumentException expected) {
                // empty set rejected locally
            }
            return null;
        });
        assertThat(counts()).doesNotContainKey("uid_move");
    }
}
