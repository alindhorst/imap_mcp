package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.tools.write.CopyUidParser.CopyUid;
import de.alexanderlindhorst.imapmcp.core.tools.write.UidCommands.FlagMode;
import de.alexanderlindhorst.imapmcp.core.tools.write.UidCommands.Result;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UidCommandsTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private static final Session MAIL = Session.getInstance(new Properties());

    private GreenMailUser user;
    private Store store;
    private IMAPFolder inbox;
    private final ByteArrayOutputStream debug = new ByteArrayOutputStream();
    private final List<Long> uids = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        user = GREEN_MAIL.setUser("user@localhost", "user", "secret");
        for (int i = 1; i <= 4; i++) {
            MimeMessage m = new MimeMessage(MAIL);
            m.setFrom("a@localhost");
            m.setSubject("m" + i);
            m.setText("body " + i);
            user.deliver(m);
        }
        Properties props = new Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Session session = Session.getInstance(props);
        session.setDebug(true);
        session.setDebugOut(new PrintStream(debug, true, StandardCharsets.UTF_8));
        store = session.getStore("imaps");
        store.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
        inbox = (IMAPFolder) store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
        for (Message m : inbox.getMessages()) {
            uids.add(inbox.getUID(m));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (inbox != null && inbox.isOpen()) {
            inbox.close(false);
        }
        store.close();
    }

    private String wire() {
        return debug.toString(StandardCharsets.UTF_8);
    }

    private List<String> subjects(String folder) throws Exception {
        IMAPFolder f = (IMAPFolder) store.getFolder(folder);
        f.open(Folder.READ_ONLY);
        try {
            List<String> out = new ArrayList<>();
            for (Message m : f.getMessages()) {
                out.add(m.getSubject());
            }
            return out;
        } finally {
            f.close(false);
        }
    }

    private List<Long> folderUids(String folder) throws Exception {
        IMAPFolder f = (IMAPFolder) store.getFolder(folder);
        f.open(Folder.READ_ONLY);
        try {
            List<Long> out = new ArrayList<>();
            for (Message m : f.getMessages()) {
                out.add(f.getUID(m));
            }
            return out;
        } finally {
            f.close(false);
        }
    }

    private void createTarget(String name) throws Exception {
        assertThat(UidCommands.create(inbox, name).ok()).isTrue();
    }

    @Test
    void moveReportsCopyUidAndMovesOnlyTheAddressedMessages() throws Exception {
        createTarget("Target");
        Result r = UidCommands.uidMove(inbox, List.of(uids.get(1), uids.get(2)), "Target");
        assertThat(r.ok()).isTrue();
        CopyUid c = r.copyUid().orElseThrow();
        // GreenMail reports overlapping sets for a MOVE (e.g. "2,2:3"), so compare as sets, not by position
        assertThat(c.sourceUids()).containsOnly(uids.get(1), uids.get(2));
        assertThat(folderUids("Target")).containsExactlyInAnyOrder(
                c.destinationUids().stream().distinct().toArray(Long[]::new));
        assertThat(subjects("Target")).containsExactlyInAnyOrder("m2", "m3");
        assertThat(subjects("INBOX")).containsExactlyInAnyOrder("m1", "m4");
        assertThat(wire()).contains("UID MOVE");
    }

    @Test
    void copyReturnsCopyUidFromTaggedOkAndLeavesSourceUnchanged() throws Exception {
        createTarget("Target");
        Result r = UidCommands.uidCopy(inbox, List.of(uids.get(0)), "Target");
        assertThat(r.ok()).isTrue();
        CopyUid c = r.copyUid().orElseThrow();
        assertThat(c.sourceUids()).containsExactly(uids.get(0));
        assertThat(folderUids("Target")).containsExactlyElementsOf(c.destinationUids());
        assertThat(subjects("INBOX")).hasSize(4);
        assertThat(wire()).contains("UID COPY");
    }

    @Test
    void storeAddsAndRemovesOnlyOnAddressedUids() throws Exception {
        assertThat(UidCommands.uidStore(inbox, List.of(uids.get(1)), FlagMode.ADD,
                new Flags(Flags.Flag.FLAGGED)).ok()).isTrue();
        assertThat(UidCommands.uidStore(inbox, List.of(uids.get(1)), FlagMode.ADD,
                new Flags(Flags.Flag.SEEN)).ok()).isTrue();
        assertThat(UidCommands.uidStore(inbox, List.of(uids.get(1)), FlagMode.REMOVE,
                new Flags(Flags.Flag.SEEN)).ok()).isTrue();
        IMAPFolder f = (IMAPFolder) store.getFolder("INBOX");
        f.open(Folder.READ_ONLY);
        try {
            for (Message m : f.getMessages()) {
                boolean target = f.getUID(m) == uids.get(1);
                assertThat(m.isSet(Flags.Flag.FLAGGED)).isEqualTo(target);
                assertThat(m.isSet(Flags.Flag.SEEN)).isFalse();
            }
        } finally {
            f.close(false);
        }
        String w = wire();
        assertThat(w).contains("+FLAGS.SILENT (\\Flagged)").contains("-FLAGS.SILENT (\\Seen)");
        assertThat(w).doesNotContainPattern("STORE [^\\n]* FLAGS \\(");
    }

    @Test
    void storeRejectsEmptyFlagsAndUnsafeKeywords() {
        assertThatThrownBy(() -> UidCommands.uidStore(inbox, List.of(1L), FlagMode.ADD, new Flags()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UidCommands.uidStore(inbox, List.of(1L), FlagMode.ADD, new Flags("a) b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storeAcceptsKeywords() throws Exception {
        assertThat(UidCommands.uidStore(inbox, List.of(uids.get(0)), FlagMode.ADD, new Flags("$Todo")).ok()).isTrue();
        assertThat(wire()).contains("+FLAGS.SILENT ($Todo)");
    }

    @Test
    void expungeRemovesOnlyTheGivenUidsAndSparesOtherDeletedMessages() throws Exception {
        UidCommands.uidStore(inbox, List.of(uids.get(0), uids.get(1)), FlagMode.ADD, new Flags(Flags.Flag.DELETED));
        Result r = UidCommands.uidExpunge(inbox, List.of(uids.get(1)));
        assertThat(r.ok()).isTrue();
        assertThat(subjects("INBOX")).containsExactlyInAnyOrder("m1", "m3", "m4");
        IMAPFolder f = (IMAPFolder) store.getFolder("INBOX");
        f.open(Folder.READ_ONLY);
        try {
            assertThat(f.getMessage(1).isSet(Flags.Flag.DELETED)).isTrue();
        } finally {
            f.close(false);
        }
        assertThat(wire()).contains("UID EXPUNGE");
    }

    @Test
    void createAndSubscribeReturnOkAndNoWithResponseCode() throws Exception {
        assertThat(UidCommands.create(inbox, "Fresh").ok()).isTrue();
        assertThat(UidCommands.subscribe(inbox, "Fresh").ok()).isTrue();
        Result again = UidCommands.create(inbox, "Fresh");
        assertThat(again.ok()).isFalse();
        assertThat(again.no()).isTrue();
        assertThat(again.tagged().isNO()).isTrue();
        assertThat(again.tagged().getRest()).isNotNull();
    }

    @Test
    void nonAsciiMailboxNameRoundTripsInModifiedUtf7() throws Exception {
        String name = "Entwürfe 日本";
        createTarget(name);
        assertThat(wire()).contains("CREATE \"Entw&APw-rfe &ZeVnLA-\"");
        Result r = UidCommands.uidCopy(inbox, List.of(uids.get(0)), name);
        assertThat(r.ok()).isTrue();
        assertThat(subjects(name)).containsExactly("m1");
    }

    @Test
    void emptyUidSetThrowsBeforeAnythingIsWritten() {
        int before = wire().length();
        assertThatThrownBy(() -> UidCommands.uidMove(inbox, List.of(), "X")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UidCommands.uidCopy(inbox, List.of(), "X")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UidCommands.uidStore(inbox, List.of(), FlagMode.ADD, new Flags(Flags.Flag.SEEN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UidCommands.uidExpunge(inbox, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UidCommands.uidExpunge(inbox, List.of(0L))).isInstanceOf(IllegalArgumentException.class);
        assertThat(wire().length()).isEqualTo(before);
    }

    @Test
    void verbSetIsClosedAndWireShowsOnlyUidForms() throws Exception {
        createTarget("T2");
        UidCommands.uidCopy(inbox, List.of(uids.get(0)), "T2");
        UidCommands.uidMove(inbox, List.of(uids.get(1)), "T2");
        UidCommands.uidStore(inbox, List.of(uids.get(2)), FlagMode.ADD, new Flags(Flags.Flag.DELETED));
        UidCommands.uidExpunge(inbox, List.of(uids.get(2)));
        UidCommands.subscribe(inbox, "T2");
        assertThat(wire()).doesNotContainPattern("(?m)\\bA\\d+ (COPY|MOVE|STORE|EXPUNGE|ENABLE)\\b");
        List<String> names = java.util.Arrays.stream(UidCommands.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName).toList();
        assertThat(names).containsExactlyInAnyOrder("uidMove", "uidCopy", "uidStore", "uidExpunge", "create",
                "subscribe");
    }

    @Test
    void expungeResponseDuringTheCallReachesAngusMessageCache() throws Exception {
        createTarget("T3");
        long moved = uids.get(0);
        assertThat(inbox.getMessageByUID(moved)).isNotNull();
        UidCommands.uidMove(inbox, List.of(moved), "T3");
        Message m = inbox.getMessageByUID(moved);
        assertThat(m == null || m.isExpunged()).isTrue();
        assertThat(inbox.getMessage(1).isExpunged()).isTrue();
    }

    @Test
    void noResultComesBackAsValueNotException() throws Exception {
        Result r = UidCommands.uidCopy(inbox, List.of(uids.get(0)), "DoesNotExist");
        assertThat(r.ok()).isFalse();
        assertThat(r.tagged().isNO()).isTrue();
        assertThat(r.copyUid()).isEmpty();
    }
}
