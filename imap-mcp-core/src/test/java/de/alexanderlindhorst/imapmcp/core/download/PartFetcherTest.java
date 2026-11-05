package de.alexanderlindhorst.imapmcp.core.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.testsupport.FaultingImapProxy;
import jakarta.activation.DataHandler;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayInputStream;
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

    private static final Session MAIL = Session.getInstance(new Properties());
    private static final int BLOCK = 1024 * 1024;

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

    private static MimeMessage raw(String contentType, byte[] bytes, String cte) throws Exception {
        MimeMessage m = new MimeMessage(MAIL);
        m.setFrom("a@localhost");
        m.setSubject("fixture");
        m.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, contentType)));
        m.setHeader("Content-Transfer-Encoding", cte);
        m.saveChanges();
        return m;
    }

    /** A message with a hand-written single-part body, as it is on the wire. */
    private static MimeMessage wire(String cte, String body) throws Exception {
        String text = "From: a@localhost\r\nSubject: fixture\r\nMIME-Version: 1.0\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Transfer-Encoding: " + cte + "\r\n\r\n" + body;
        return new MimeMessage(MAIL, new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

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

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    @Test
    void base64PartIsByteExactAndFetchedInOneMiBPeekBlocks() throws Exception {
        byte[] data = random(2 * BLOCK + 12345);
        open(raw("application/octet-stream", data, "base64"), false);

        assertThat(fetchAndPublish("base64", 100L * BLOCK)).isEqualTo(sha(data));

        List<String> peeks = peekLines();
        assertThat(peeks).hasSizeGreaterThanOrEqualTo(4);
        assertThat(peeks.get(0)).contains("BODY.PEEK[1]<0.1048576>");
        assertThat(peeks.get(1)).contains("BODY.PEEK[1]<1048576.1048576>");
        assertThat(peeks).allMatch(l -> l.matches(".*BODY\\.PEEK\\[1\\]<\\d+\\.1048576>.*"));
        assertThat(debug.toString(StandardCharsets.UTF_8).lines()).noneMatch(
                l -> l.contains("FETCH") && l.contains("BODY[") && !l.contains("BODY.PEEK[") || l.contains("BINARY"));
    }

    @Test
    void quotedPrintablePartIsByteExact() throws Exception {
        byte[] data = new byte[20000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 7 == 0 ? '=' : i % 5 == 0 ? 0xE9 : 'a' + i % 20);
        }
        open(raw("application/octet-stream", data, "quoted-printable"), false);

        assertThat(fetchAndPublish("quoted-printable", 100L * BLOCK)).isEqualTo(sha(data));
    }

    @Test
    void eightBitAndSevenBitPartsAreWrittenAsTheServerSendsThem() throws Exception {
        byte[] data = "plain 7bit text\r\nsecond line\r\n".getBytes(StandardCharsets.US_ASCII);
        open(raw("text/plain", data, "7bit"), false);

        assertThat(fetchAndPublish("7bit", 1000)).isEqualTo(sha(data));
    }

    @Test
    void aPartOfZeroOctetsGivesAnEmptyResult() throws Exception {
        open(wire("base64", ""), false);

        try (DownloadSink s = sink(1000)) {
            PartFetcher.stream(folder, seq, "1", "base64", s);
            assertThat(s.publish(AttachmentFileName.of("x", "1", false)).bytes()).isZero();
        }
    }

    @Test
    void aPartOfExactlyOneBlockEndsAtTheEmptyFollowingBlock() throws Exception {
        byte[] data = random(BLOCK);
        open(raw("application/octet-stream", data, "binary"), false);

        assertThat(fetchAndPublish("binary", 100L * BLOCK)).isEqualTo(sha(data));
        assertThat(peekLines()).anyMatch(l -> l.contains("<1048576.1048576>"));
    }

    @Test
    void aTruncatedBase64QuantumIsDecodeFailed() throws Exception {
        open(wire("base64", "AAAABB"), false);

        try (DownloadSink s = sink(1000)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "base64", s))
                    .isInstanceOf(PartFetcher.DecodeFailedException.class);
        }
    }

    @Test
    void charactersOutsideTheBase64AlphabetAreSkipped() throws Exception {
        open(wire("base64", "aGVs bG8h\r\n!!\r\n"), false);

        assertThat(fetchAndPublish("base64", 1000)).isEqualTo(sha("hello!".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void theDecodedCountIsEnforcedWhileStreaming() throws Exception {
        byte[] data = random(5000);
        open(raw("application/octet-stream", data, "base64"), false);

        try (DownloadSink s = sink(4000)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "base64", s))
                    .isInstanceOf(DownloadTooLargeException.class);
        }
    }

    @Test
    void aNoAnswerAtOffsetZeroIsAMissingMessage() throws Exception {
        open(raw("application/octet-stream", random(100), "binary"), true);
        proxy.rejectWithNo("BODY.PEEK", "gone");

        try (DownloadSink s = sink(1000)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "binary", s))
                    .isInstanceOf(PartFetcher.BlockMissingException.class)
                    .satisfies(e -> assertThat(((PartFetcher.BlockMissingException) e).atStart()).isTrue());
        }
    }

    @Test
    void aNilBodyAtOffsetZeroIsAMissingMessage() throws Exception {
        open(raw("application/octet-stream", random(100), "binary"), true);
        proxy.answerNilBodyAfter("BODY.PEEK", 0);

        try (DownloadSink s = sink(1000)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "binary", s))
                    .isInstanceOf(PartFetcher.BlockMissingException.class)
                    .satisfies(e -> assertThat(((PartFetcher.BlockMissingException) e).atStart()).isTrue());
        }
    }

    @Test
    void aNoOrNilAnswerLaterIsNotAtStart() throws Exception {
        open(raw("application/octet-stream", random(2 * BLOCK + 10), "binary"), true);
        proxy.rejectWithNoAfter("BODY.PEEK", "gone", 1);

        try (DownloadSink s = sink(100L * BLOCK)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "binary", s))
                    .isInstanceOf(PartFetcher.BlockMissingException.class)
                    .satisfies(e -> assertThat(((PartFetcher.BlockMissingException) e).atStart()).isFalse());
        }
        proxy.answerNilBodyAfter("BODY.PEEK", 1);
    }

    @Test
    void aConnectionResetMidPartIsAMessagingException() throws Exception {
        open(raw("application/octet-stream", random(2 * BLOCK + 10), "binary"), true);
        proxy.dropConnectionAfter("BODY.PEEK", 1);

        try (DownloadSink s = sink(100L * BLOCK)) {
            assertThatThrownBy(() -> PartFetcher.stream(folder, seq, "1", "binary", s))
                    .isInstanceOf(MessagingException.class);
        }
    }
}
