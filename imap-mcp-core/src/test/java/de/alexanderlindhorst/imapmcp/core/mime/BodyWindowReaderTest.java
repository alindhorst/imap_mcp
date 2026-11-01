package de.alexanderlindhorst.imapmcp.core.mime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.activation.DataHandler;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

class BodyWindowReaderTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private static final Session MAIL = Session.getInstance(new Properties());
    private static final int WHOLE = 100_000_000;

    private GreenMailUser user;
    private Store store;
    private IMAPFolder folder;
    private final ByteArrayOutputStream debug = new ByteArrayOutputStream();
    private long uid;
    private SelectedPart part;

    @BeforeEach
    void purge() throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        user = GREEN_MAIL.setUser("user@localhost", "user", "secret");
    }

    @AfterEach
    void closeAll() throws Exception {
        if (folder != null && folder.isOpen()) {
            folder.close(false);
        }
        if (store != null) {
            store.close();
        }
    }

    // ---- fixtures ----

    private static MimeMessage single(String subtype, String text, String charset, String cte) throws Exception {
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom("a@localhost");
        m.setSubject("fixture");
        m.setText(text, charset, subtype);
        m.setHeader("Content-Transfer-Encoding", cte);
        m.saveChanges();
        return m;
    }

    private static MimeMessage raw(String contentType, byte[] bytes, String cte) throws Exception {
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom("a@localhost");
        m.setSubject("fixture");
        m.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, contentType)));
        m.setHeader("Content-Transfer-Encoding", cte);
        m.saveChanges();
        return m;
    }

    /** Delivers the message, opens INBOX READ_ONLY with debug capture and selects the body part. */
    private void open(MimeMessage msg) throws Exception {
        user.deliver(msg);
        Properties props = new Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Session session = Session.getInstance(props);
        session.setDebug(true);
        session.setDebugOut(new PrintStream(debug, true, StandardCharsets.UTF_8));
        store = session.getStore("imaps");
        store.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
        folder = (IMAPFolder) store.getFolder("INBOX");
        folder.open(Folder.READ_ONLY);
        uid = folder.getUID(folder.getMessage(1));
        int seq = folder.getMessageByUID(uid).getMessageNumber();
        BODYSTRUCTURE bs = (BODYSTRUCTURE) folder.doCommand(p -> p.fetchBodyStructure(seq));
        part = BodyPartSelector.select(bs).orElseThrow();
    }

    private BodyWindow read(BodyWindowReader r, long offset, int max) throws Exception {
        return r.read(folder, uid, part, offset, max);
    }

    /** Follows next_offset until null; returns the windows. */
    private List<BodyWindow> follow(BodyWindowReader r, int window) throws Exception {
        List<BodyWindow> all = new ArrayList<>();
        long next = 0;
        while (true) {
            BodyWindow w = read(r, next, window);
            all.add(w);
            assertThat(w.returnedChars()).isEqualTo(w.text().codePointCount(0, w.text().length()));
            assertThat(w.offset()).isEqualTo(next);
            if (w.nextOffset() == null) {
                return all;
            }
            assertThat(w.truncated()).isTrue();
            assertThat(w.nextOffset()).isEqualTo(next + w.returnedChars());
            next = w.nextOffset();
            assertThat(all.size()).isLessThan(100_000);
        }
    }

    private static String concat(List<BodyWindow> ws) {
        StringBuilder sb = new StringBuilder();
        ws.forEach(w -> sb.append(w.text()));
        return sb.toString();
    }

    private void assertWindowsEqualWhole(int window) throws Exception {
        BodyWindowReader r = new BodyWindowReader();
        BodyWindow whole = read(r, 0, WHOLE);
        assertThat(whole.truncated()).isFalse();
        assertThat(whole.nextOffset()).isNull();
        assertThat(whole.totalChars()).isEqualTo((long) whole.returnedChars());
        List<BodyWindow> ws = follow(r, window);
        assertThat(concat(ws)).isEqualTo(whole.text());
        assertThat(ws.size()).isGreaterThan(1);
        // no window splits a surrogate pair
        for (BodyWindow w : ws) {
            assertThat(new String(w.text().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .isEqualTo(w.text());
        }
    }

    private static String repeat(String unit, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(unit.replace("#", Integer.toString(i)));
        }
        return sb.toString();
    }

    // ---- AC1 ----

    @Test
    void asciiPlainWindowsConcatenateToWhole() throws Exception {
        open(single("plain", repeat("line number # of the body\r\n", 300), "UTF-8", "7bit"));
        assertWindowsEqualWhole(97);
    }

    @Test
    void utf8EmojiBase64WindowsConcatenateToWhole() throws Exception {
        open(single("plain", repeat("a😀bä€ #\r\n", 400), "UTF-8", "base64"));
        assertWindowsEqualWhole(7);
        BodyWindow whole = read(new BodyWindowReader(), 0, WHOLE);
        assertThat(whole.charset()).isEqualTo("UTF-8");
        assertThat(whole.text()).startsWith("a😀bä€ 0\n");
        assertThat(whole.totalChars()).isEqualTo(whole.text().codePoints().count());
    }

    @Test
    void quotedPrintableLatin1WindowsConcatenateToWhole() throws Exception {
        open(single("plain", repeat("café üß # = sign and a rather long line to force soft breaks "
                + "in the quoted printable encoding, ok?\r\n", 200), "ISO-8859-1", "quoted-printable"));
        assertWindowsEqualWhole(61);
        BodyWindow whole = read(new BodyWindowReader(), 0, WHOLE);
        assertThat(whole.charset()).isEqualTo("ISO-8859-1");
        assertThat(whole.text()).startsWith("café üß 0 = sign");
    }

    @Test
    void htmlWindowsConcatenateToWhole() throws Exception {
        open(single("html", repeat("<p>Hello ä <b>world</b> #</p>", 300), "UTF-8", "quoted-printable"));
        assertWindowsEqualWhole(50);
        BodyWindow whole = read(new BodyWindowReader(), 0, WHOLE);
        assertThat(whole.convertedFromHtml()).isTrue();
        assertThat(whole.contentType()).isEqualTo("text/html");
        assertThat(whole.text()).startsWith("Hello ä world 0\n\nHello");
    }

    // ---- AC2 ----

    @Test
    void identicalCallsReturnIdenticalWindows() throws Exception {
        open(single("plain", repeat("café line #\r\n", 500), "UTF-8", "base64"));
        BodyWindowReader r = new BodyWindowReader();
        assertThat(read(r, 123, 77)).isEqualTo(read(r, 123, 77));
        assertThat(read(r, 0, 1000)).isEqualTo(read(r, 0, 1000));
    }

    @Test
    void windowContentDoesNotDependOnWindowSize() throws Exception {
        open(single("plain", repeat("😀 line #\r\n", 800), "UTF-8", "base64"));
        BodyWindowReader r = new BodyWindowReader();
        String whole = read(r, 0, WHOLE).text();
        int[] cps = whole.codePoints().toArray();
        BodyWindow w = read(r, 1234, 300);
        assertThat(w.text()).isEqualTo(new String(cps, 1234, 300));
        assertThat(w.nextOffset()).isEqualTo(1534L);
        assertThat(w.truncated()).isTrue();
        assertThat(w.totalChars()).isNull();
    }

    // ---- AC3 ----

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void hugeBase64PartIsFetchedPartially() throws Exception {
        byte[] block = "The quick brown fox jumps over the lazy dog. 0123456789\n".getBytes(StandardCharsets.US_ASCII);
        byte[] big = new byte[15 * 1024 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = block[i % block.length];
        }
        open(raw("text/plain; charset=UTF-8", big, "base64"));
        assertThat(part.encodedSize()).isGreaterThan(19_000_000L);

        BodyWindow w = read(new BodyWindowReader(), 0, 1000);
        assertThat(w.returnedChars()).isEqualTo(1000);
        assertThat(w.truncated()).isTrue();
        assertThat(w.nextOffset()).isEqualTo(1000L);
        assertThat(w.text()).startsWith("The quick brown fox");

        Matcher m = Pattern.compile("BODY\\.PEEK\\[1]<0\\.(\\d+)>").matcher(debug.toString(StandardCharsets.UTF_8));
        int found = 0;
        while (m.find()) {
            found++;
            assertThat(Long.parseLong(m.group(1))).isLessThan(200_000L);
        }
        assertThat(found).isPositive();
    }

    // ---- AC4 ----

    @Test
    void htmlBeyondMaxBodyPartBytesIsSourceTruncated() throws Exception {
        open(single("html", repeat("<p>paragraph number # with some words</p>", 6000), "UTF-8", "7bit"));
        assertThat(part.encodedSize()).isGreaterThan(65536L);
        BodyWindowReader r = new BodyWindowReader(65536);
        List<BodyWindow> ws = follow(r, 5000);
        assertThat(ws).allSatisfy(w -> assertThat(w.sourceTruncated()).isTrue());
        BodyWindow last = ws.get(ws.size() - 1);
        assertThat(last.truncated()).isTrue();
        assertThat(last.nextOffset()).isNull();
        assertThat(last.totalChars()).isNull();
        assertThat(ws.subList(0, ws.size() - 1)).allSatisfy(w -> assertThat(w.truncated()).isTrue());
        // only the capped prefix is fetched
        Matcher m = Pattern.compile("BODY\\.PEEK\\[1]<0\\.(\\d+)>").matcher(debug.toString(StandardCharsets.UTF_8));
        while (m.find()) {
            assertThat(Long.parseLong(m.group(1))).isLessThanOrEqualTo(65536L);
        }
    }

    @Test
    void smallPartIsNotSourceTruncated() throws Exception {
        open(single("html", "<p>hi</p>", "UTF-8", "7bit"));
        assertThat(read(new BodyWindowReader(65536), 0, 100).sourceTruncated()).isFalse();
    }

    // ---- AC5 ----

    @Test
    void onlyBodyPeekIsUsedAndSeenIsUnchanged() throws Exception {
        open(single("plain", repeat("line #\r\n", 200), "UTF-8", "base64"));
        BodyWindowReader r = new BodyWindowReader();
        read(r, 0, 50);
        read(r, 50, 5000);
        folder.close(false);
        String log = debug.toString(StandardCharsets.UTF_8);
        assertThat(log).contains("BODY.PEEK[1]<0.");
        for (String line : log.split("\n")) {
            if (line.contains("FETCH") && line.contains("BODY") && line.matches(".*\\b(A|a)\\d+ (UID )?FETCH.*")) {
                assertThat(line).contains("BODY.PEEK[");
            }
        }
        assertThat(log).doesNotContainPattern("\\d FETCH \\d+ \\(BODY\\[");
        assertThat(log).doesNotContain("RFC822");
        store.close();
        Properties props = new Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        try (Store s = Session.getInstance(props).getStore("imaps")) {
            s.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
            Folder f = s.getFolder("INBOX");
            f.open(Folder.READ_ONLY);
            Message msg = f.getMessage(1);
            assertThat(msg.getFlags().contains(Flags.Flag.SEEN)).isFalse();
            f.close(false);
        }
    }

    // ---- AC6 ----

    @Test
    void offsetBeyondEndIsEmptyAndNotTruncated() throws Exception {
        open(single("plain", "hello", "UTF-8", "7bit"));
        BodyWindowReader r = new BodyWindowReader();
        for (long off : new long[] {5, 6, 1000, Long.MAX_VALUE / 2}) {
            BodyWindow w = read(r, off, 10);
            assertThat(w.text()).isEmpty();
            assertThat(w.returnedChars()).isZero();
            assertThat(w.truncated()).isFalse();
            assertThat(w.nextOffset()).isNull();
            assertThat(w.totalChars()).isEqualTo(5L);
        }
    }

    @Test
    void offsetBeyondEndOfHtmlIsEmpty() throws Exception {
        open(single("html", "<p>hello</p>", "UTF-8", "7bit"));
        BodyWindow w = read(new BodyWindowReader(), 99, 10);
        assertThat(w.text()).isEmpty();
        assertThat(w.truncated()).isFalse();
        assertThat(w.nextOffset()).isNull();
    }

    // ---- further ----

    @Test
    void metaCharsetInHtmlWinsOverHeader() throws Exception {
        byte[] html = "<html><head><meta charset=\"ISO-8859-1\"></head><body>café</body></html>"
                .getBytes(StandardCharsets.ISO_8859_1);
        open(raw("text/html; charset=UTF-8", html, "8bit"));
        BodyWindow w = read(new BodyWindowReader(), 0, 100);
        assertThat(w.text()).isEqualTo("café");
        assertThat(w.charset()).isEqualTo("ISO-8859-1");
    }

    @Test
    void invalidMetaCharsetIsIgnored() throws Exception {
        open(raw("text/html; charset=UTF-8", "<meta charset=\"nonsense-9\"><p>café</p>"
                .getBytes(StandardCharsets.UTF_8), "8bit"));
        BodyWindow w = read(new BodyWindowReader(), 0, 100);
        assertThat(w.text()).isEqualTo("café");
        assertThat(w.charset()).isEqualTo("UTF-8");
    }

    @Test
    void controlCharactersAreNormalized() throws Exception {
        open(single("plain", "a‮b\r\nc", "UTF-8", "base64"));
        assertThat(read(new BodyWindowReader(), 0, 100).text()).isEqualTo("ab\nc");
    }

    @Test
    void rejectsBadArguments() throws Exception {
        open(single("plain", "hello", "UTF-8", "7bit"));
        BodyWindowReader r = new BodyWindowReader();
        assertThatThrownBy(() -> read(r, -1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> read(r, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
