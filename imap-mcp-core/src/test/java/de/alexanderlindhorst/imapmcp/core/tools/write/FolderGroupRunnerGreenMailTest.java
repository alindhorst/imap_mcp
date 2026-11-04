package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.LeaseTestSeams;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tools.write.FolderGroupRunner.AbsentStatus;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The runner's LIST / SELECT / UID FETCH path against a real IMAP server. */
class FolderGroupRunnerGreenMailTest {

    private GreenMail greenMail;
    private IMAPStore store;
    private ToolCallContext ctx;
    private long validity;
    private long[] uids;

    @BeforeEach
    void setUp() throws Exception {
        greenMail = new GreenMail(ServerSetupTest.IMAP.dynamicPort());
        greenMail.start();
        GreenMailUser user = greenMail.setUser("u@localhost", "u", "p");
        Session session = Session.getInstance(new Properties());
        for (int i = 0; i < 3; i++) {
            MimeMessage m = new MimeMessage(session);
            m.setSubject("s" + i);
            m.setText("body");
            m.setFrom("a@localhost");
            m.setRecipients(Message.RecipientType.TO, "u@localhost");
            user.deliver(m);
        }
        Properties p = new Properties();
        p.put("mail.imap.host", "127.0.0.1");
        p.put("mail.imap.port", String.valueOf(greenMail.getImap().getPort()));
        store = (IMAPStore) Session.getInstance(p).getStore("imap");
        store.connect("u", "p");
        IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        validity = inbox.getUIDValidity();
        Message[] all = inbox.getMessages();
        uids = new long[all.length];
        for (int i = 0; i < all.length; i++) {
            uids[i] = inbox.getUID(all[i]);
        }
        inbox.close(false);
        ctx = new ToolCallContext(new AccountContext(new TenantId("t1")), "rid");
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
        greenMail.stop();
    }

    private WritePreflight.Group inboxGroup(long validityOfHandles, long... uidList) {
        List<WritePreflight.Item> items = new ArrayList<>();
        for (long u : uidList) {
            items.add(new WritePreflight.Item(0, "h" + u, new MessageRef("INBOX", validityOfHandles, u)));
        }
        return new WritePreflight.Group("INBOX", items);
    }

    @Test
    void presentUidsReachTheStepAbsentOnesAreReportedAndNothingIsMarkedSeen() throws Exception {
        ImapLease lease = LeaseTestSeams.lease(store, ctx);
        List<Long> seen = new ArrayList<>();
        long absentUid = uids[2] + 100;
        var out = new FolderGroupRunner(Clock.systemUTC()).run(lease, ctx,
                List.of(inboxGroup(validity, uids[0], absentUid)), AbsentStatus.NOT_FOUND, (f, present, gate) -> {
                    present.forEach(i -> seen.add(i.ref().uid()));
                    return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
                });
        assertThat(seen).containsExactly(uids[0]);
        assertThat(out).extracting(BulkResult.Item::status).containsExactlyInAnyOrder(ItemStatus.OK, ItemStatus.NOT_FOUND);
        lease.closeFolder();
        IMAPFolder inbox = (IMAPFolder) store.getFolder("INBOX");
        inbox.open(Folder.READ_ONLY);
        for (Message m : inbox.getMessages()) {
            assertThat(m.isSet(Flags.Flag.SEEN)).isFalse();
        }
        inbox.close(false);
        assertThat(inbox.isOpen()).isFalse();
    }

    @Test
    void changedUidValidityIsStaleAndAMissingFolderIsFolderMissing() throws Exception {
        ImapLease lease = LeaseTestSeams.lease(store, ctx);
        var out = new FolderGroupRunner(Clock.systemUTC()).run(lease, ctx,
                List.of(inboxGroup(validity + 1, uids[0]),
                        new WritePreflight.Group("Nope", List.of(new WritePreflight.Item(1, "x",
                                new MessageRef("Nope", validity, 1))))),
                AbsentStatus.ALREADY_GONE, (f, present, gate) -> {
                    throw new AssertionError("step must not run");
                });
        assertThat(out).extracting(BulkResult.Item::status).containsOnly(ItemStatus.STALE_REFERENCE);
        assertThat(out.get(0).reason()).isNotEqualTo(out.get(1).reason());
        assertThat(ctx.mutationStarted()).isFalse();
    }
}
