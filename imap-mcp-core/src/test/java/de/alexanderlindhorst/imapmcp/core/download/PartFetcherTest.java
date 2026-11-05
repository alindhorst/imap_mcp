package de.alexanderlindhorst.imapmcp.core.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.testsupport.FaultingImapProxy;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.activation.DataHandler;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** SPEC 5.4 "Downloads", fetch: BODY.PEEK partial blocks of 1 MiB decoded while streaming. */
class PartFetcherTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private static final int BLOCK = 1024 * 1024;

    private static final Session MAIL = Session.getInstance(new Properties());

    @TempDir
    Path tmp;

    private GreenMailUser user;
    private Store store;
    private IMAPFolder folder;
    private FaultingImapProxy proxy;
    private final ByteArrayOutputStream debug = new ByteArrayOutputStream();
    private int seq;

    @BeforeEach
    void setUp() throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        user = GREEN_MAIL.setUser("user@localhost", "user", "secret");
    }

    @AfterEach
    void closeAll() throws Exception {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(false);
            } catch (MessagingException ignored) {
                // connection already gone
            }
        }
        if (store != null) {
            store.close();
        }
        if (proxy != null) {
            proxy.close();
        }
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static MimeMessage message(DataHandler data, String contentType) throws Exception {
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom("a@localhost");
        m.setSubject("fixture");
        m.setDataHandler(data);
        m.saveChanges();
        return m;
    }

    private static MimeMessage binaryMessage(byte[] data) throws Exception {
        return message(new DataHandler(new ByteArrayDataSource(data, "application/octet-stream")), null);
    }

    /** Delivers the message and opens INBOX READ_ONLY with the wire captured (directly or through the proxy). */
    private void open(MimeMessage msg, boolean viaProxy) throws Exception {
        user.deliver(msg);
        int port = GREEN_MAIL.getImaps().getPort();
        if (viaProxy) {
            proxy = new FaultingImapProxy(port);
            port = proxy.port();
        }
        Properties props = new Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Session session = Session.getInstance(props);
        session.setDebug(true);
        session.setDebugOut(new PrintStream(debug, true, StandardCharsets.UTF_8));
        store = session.getStore("imaps");
        store.connect("127.0.0.1", port, "user", "secret");
        folder = (IMAPFolder) store.getFolder("INBOX");
        folder.open(Folder.READ_ONLY);
        seq = folder.getMessage(1).getMessageNumber();
    }

    /** The part bytes come from the script, not from GreenMail (which re-encodes bodies): exact wire control. */
    private void openScripted(byte[] partOnTheWire) throws Exception {
        open(binaryMessage(new byte[] {1}), true);
        proxy.serveBody("BODY.PEEK", partOnTheWire);
    }

    private DownloadSink sink(long max) throws Exception {
        Path dir = Files.createDirectory(tmp.resolve("d" + System.nanoTime()));
        return DownloadSink.open(DownloadDirectory.of(dir.toRealPath(), true, max, null));
    }

    private String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private String fetchAndPublish(String encoding, long max) throws Exception {
        try (DownloadSink s = sink(max)) {
            PartFetcher.stream(folder, seq, "1", encoding, s);
            return s.publish(AttachmentFileName.of("x.bin", "1", false)).sha256();
        }
    }

    private List<String> peekLines() {
        return debug.toString(StandardCharsets.UTF_8).lines().filter(l -> l.contains("BODY.PEEK[")).toList();
    }

    private static byte[] textBytes(int n) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, (byte) 'y');
        return b;
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    @Test
    void base64PartIsByteExactAndFetchedInOneMiBPeekBlocks() throws Exception {
        byte[] data = random(2 * BLOCK + 12345);
        open(binaryMessage(data), false);

        assertThat(fetchAndPublish("base64", 100L * BLOCK)).isEqualTo(sha(data));

        List<String> peeks = peekLines();
        assertThat(peeks).hasSize(3);
        assertThat(peeks.get(0)).contains("BODY.PEEK[1]<0.1048576>");
        assertThat(peeks.get(1)).contains("BODY.PEEK[1]<1048576.1048576>");
        assertThat(peeks).allMatch(l -> l.matches(".*BODY\\.PEEK\\[1\\]<\\d+\\.1048576>.*"));
        // client commands only (responses echo BODY[...] labels): never BODY[...] without PEEK, never BINARY
        assertThat(debug.toString(StandardCharsets.UTF_8).lines().filter(l -> l.matches("[A-Z]+\\d+ .*")))
                .noneMatch(l -> l.contains("BODY[") || l.contains("BINARY"));
    }

    @Test
    void quotedPrintablePartIsByteExact() throws Exception {
        byte[] expected = ascii("caf\u00e9 = ok");
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom("a@localhost");
        m.setText("caf\u00e9 = ok", "ISO-8859-1");
        m.saveChanges();
        open(m, false);

        assertThat(fetchAndPublish("quoted-printable", 1000)).isEqualTo(sha(expected));
    }

    @Test
    void quotedPrintableEscapesAndSoftBreaksOnTheWireAreDecoded() throws Exception {
        byte[] expected = {'a', 'b', 'c', '=', 'd', 'e', 'f', (byte) 0xE9, '\r', '\n', 's', 'e', 'c', 'o', 'n', 'd', ' ',
            'l', 'i', 'n', 'e', '\r', '\n'};
        openScripted(ascii("abc=3Ddef=E9\r\nsecond =\r\nline\r\n"));

        assertThat(fetchAndPublish("quoted-printable", 1000)).isEqualTo(sha(expected));
    }

    @Test
    void sevenBitPartIsWrittenAsTheServerSendsIt() throws Exception {
        byte[] data = ascii("plain text, one line");
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom("a@localhost");
        m.setText("plain text, one line");
        m.saveChanges();
        open(m, false);

        assertThat(fetchAndPublish("7bit", 1000)).isEqualTo(sha(data));
    }

    @Test
    void eightBitPartIsWrittenAsTheServerSendsIt() throws Exception {
        byte[] eight = {'c', 'a', 'f', (byte) 0xE9, '\r', '\n', (byte) 0xFF, 'x', '\r', '\n'};
        openScripted(eight);

        assertThat(fetchAndPublish("8bit", 1000)).isEqualTo(sha(eight));
    }

    @Test
    void aPartOfZeroOctetsGivesAnEmptyResult() throws Exception {
        openScripted(new byte[0]);

        try (DownloadSink s = sink(1000)) {
            PartFetcher.stream(folder, seq, "1", "base64", s);
            assertThat(s.publish(AttachmentFileName.of("x", "1", false)).bytes()).isZero();
        }
    }

    @Test
    void aPartOfExactlyOneBlockEndsAtTheEmptyFollowingBlock() throws Exception {
        byte[] data = textBytes(BLOCK);
        openScripted(data);

        assertThat(fetchAndPublish("8bit", 100L * BLOCK)).isEqualTo(sha(data));
        assertThat(peekLines()).anyMatch(l -> l.contains("<1048576.1048576>"));
    }

    @Test
    void aTruncatedBase64QuantumIsDecodeFailed() throws Exception {
        openScripted(ascii("AAAABB"));

        try (DownloadSink s = sink(1000)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "base64", s))
                    .isInstanceOf(PartFetcher.DecodeFailedException.class);
        }
    }

    @Test
    void charactersOutsideTheBase64AlphabetAreSkipped() throws Exception {
        openScripted(ascii("aGVs bG8h\r\n!!\r\n"));

        assertThat(fetchAndPublish("base64", 1000)).isEqualTo(sha("hello!".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void theDecodedCountIsEnforcedWhileStreaming() throws Exception {
        byte[] data = random(5000);
        open(binaryMessage(data), false);

        try (DownloadSink s = sink(4000)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "base64", s))
                    .isInstanceOf(DownloadTooLargeException.class);
        }
    }

    @Test
    void aNoAnswerAtOffsetZeroIsAMissingMessage() throws Exception {
        open(binaryMessage(random(100)), true);
        proxy.rejectWithNo("BODY.PEEK", "gone");

        try (DownloadSink s = sink(1000)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "8bit", s))
                    .isInstanceOf(PartFetcher.BlockMissingException.class)
                    .satisfies(e -> assertThat(((PartFetcher.BlockMissingException) e).atStart()).isTrue());
        }
    }

    @Test
    void aNilBodyAtOffsetZeroIsAMissingMessage() throws Exception {
        open(binaryMessage(random(100)), true);
        proxy.answerNilBodyAfter("BODY.PEEK", 0);

        try (DownloadSink s = sink(1000)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "8bit", s))
                    .isInstanceOf(PartFetcher.BlockMissingException.class)
                    .satisfies(e -> assertThat(((PartFetcher.BlockMissingException) e).atStart()).isTrue());
        }
    }

    @Test
    void aNoAnswerLaterIsNotAtStart() throws Exception {
        open(binaryMessage(random(2 * BLOCK + 10)), true);
        proxy.rejectWithNoAfter("BODY.PEEK", "gone", 1);

        try (DownloadSink s = sink(100L * BLOCK)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "8bit", s))
                    .isInstanceOf(PartFetcher.BlockMissingException.class)
                    .satisfies(e -> assertThat(((PartFetcher.BlockMissingException) e).atStart()).isFalse());
        }
    }

    @Test
    void aConnectionResetMidPartIsAMessagingException() throws Exception {
        open(binaryMessage(random(2 * BLOCK + 10)), true);
        proxy.dropConnectionAfter("BODY.PEEK", 1);

        try (DownloadSink s = sink(100L * BLOCK)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "8bit", s))
                    .isInstanceOf(MessagingException.class);
        }
    }
}
