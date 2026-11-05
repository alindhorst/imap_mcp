package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.mail.MessagingException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code download_attachment} against Dovecot 2.4.5 over stdio (SPEC 4 v3a, 5.4, 5.8): messages are APPENDed as raw
 * bytes, so the stored transfer encodings are exactly the ones the test wants (8bit octets, truncated base64, an
 * encapsulated {@code message/rfc822}). Asserted: byte-exact files (sha256) for base64 (several 1 MiB blocks),
 * quoted-printable, 8bit and 7bit parts, the encapsulated message with its headers, {@code decode_failed} for a
 * truncated base64 quantum without any file left, and a wire trace that shows EXAMINE and only partial
 * {@code BODY.PEEK[...]} fetches with all flags unchanged.
 */
@Testcontainers
class DownloadAttachmentDovecotIT {

    private static final Pattern CLIENT_COMMAND =
            Pattern.compile(" imapmcp\\.imap\\.trace - ([A-Z]+\\d+) (?!(?:OK|NO|BAD)\\b)([A-Z][A-Z.]*\\b.*)$");
    private static final String USER = "dl-user";
    private static final String BOUNDARY = "=_it_boundary_9f3a";

    @Container
    static final DovecotContainer DOVECOT = new DovecotContainer();

    @TempDir
    static Path dir;

    private static ServerConfig config;
    private static McpSyncClient client;
    private static Path downloads;
    private static String account;

    @BeforeAll
    static void start() throws Exception {
        downloads = Files.createDirectory(dir.resolve("downloads"));
        Files.setPosixFilePermissions(downloads, PosixFilePermissions.fromString("rwx------"));
        config = new ServerConfigWriter(dir).trustStore(TestTrust.writeTruststore(dir))
                .account("dl", DOVECOT.getHost(), DOVECOT.imapsPort(), MailAccount.Security.IMPLICIT_TLS, USER,
                        DovecotContainer.PASSWORD)
                .extraYaml("imap:\n  trace: true\ntools:\n  deadline: 60s\n  download:\n    dir: " + downloads + "\n")
                .write();
        List<String> cmd = JarLauncher.builder(config, "stdio").command();
        ServerParameters params = ServerParameters.builder(cmd.get(0)).args(cmd.subList(1, cmd.size()))
                .env(config.env()).build();
        client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                .requestTimeout(Duration.ofSeconds(120)).initializationTimeout(Duration.ofSeconds(60)).build();
        client.initialize();
        McpSchema.CallToolResult accounts = client.callTool(new McpSchema.CallToolRequest("list_accounts", Map.of()));
        account = (String) list(ToolResults.payload(accounts), "accounts").get(0).get("account");
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private record Result(boolean error, Map<String, Object> structured) {

        String errorCode() {
            return (String) map(structured, "error").get("code");
        }

        Object detail(String key) {
            return map(map(structured, "error"), "details").get(key);
        }
    }

    private static Result call(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = client.callTool(new McpSchema.CallToolRequest(tool, args));
        return new Result(Boolean.TRUE.equals(r.isError()), ToolResults.payload(r));
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    private static String handleOf(String subject) {
        Result search = call("search_messages", args("account", account, "folder", "INBOX", "page_size", 100));
        assertThat(search.error()).isFalse();
        for (Map<String, Object> m : list(search.structured, "messages")) {
            if (subject.equals(map(m, "untrusted").get("subject"))) {
                return (String) m.get("handle");
            }
        }
        throw new AssertionError("no message with subject " + subject);
    }

    private static Result download(String subject, String part) {
        return call("download_attachment", args("account", account, "message", handleOf(subject), "part", part));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    /** One multipart/mixed message: a text part, then the given parts (each already holding its headers and body). */
    private static byte[] mixed(String subject, byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("From: sender@example.org\r\nTo: recipient@example.org\r\nSubject: " + subject
                + "\r\nDate: Wed, 04 Nov 2026 10:00:00 +0000\r\nMessage-ID: <" + subject + "@it.example>\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"" + BOUNDARY + "\"\r\n\r\n"));
        out.writeBytes(ascii("--" + BOUNDARY + "\r\nContent-Type: text/plain; charset=us-ascii\r\n\r\nsee attached\r\n"));
        for (byte[] p : parts) {
            out.writeBytes(ascii("--" + BOUNDARY + "\r\n"));
            out.writeBytes(p);
            out.writeBytes(ascii("\r\n"));
        }
        out.writeBytes(ascii("--" + BOUNDARY + "--\r\n"));
        return out.toByteArray();
    }

    private static byte[] part(String headers, byte[] body) {
        return concat(ascii(headers + "\r\n\r\n"), body);
    }

    private static void seed(String subject, byte[] raw) throws MessagingException {
        try (MailSeeder s = DOVECOT.seeder(USER)) {
            s.appendRfc822("INBOX", raw);
        }
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static long logSize() throws IOException {
        return Files.exists(config.logFile()) ? Files.size(config.logFile()) : 0;
    }

    private static List<String> commandsSince(long offset) throws IOException {
        byte[] all = Files.readAllBytes(config.logFile());
        String text = new String(all, (int) offset, all.length - (int) offset, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            Matcher m = CLIENT_COMMAND.matcher(line);
            if (m.find()) {
                out.add(m.group(2).strip());
            }
        }
        return out;
    }

    private static List<String> filesInDownloads() throws IOException {
        try (Stream<Path> s = Files.list(downloads)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** The saved file must hold exactly {@code expected}; the result must report its length and digest. */
    private static Path assertSavedExactly(Result r, byte[] expected) throws Exception {
        assertThat(r.error()).as("%s", r.structured).isFalse();
        assertThat(r.structured).containsEntry("delivery", "file").containsEntry("directory", downloads.toString())
                .containsEntry("sha256", sha256(expected));
        assertThat(((Number) r.structured.get("bytes")).longValue()).isEqualTo(expected.length);
        Path file = Path.of((String) map(r.structured, "untrusted").get("path"));
        assertThat(file.getParent()).isEqualTo(downloads);
        assertThat(Files.readAllBytes(file)).isEqualTo(expected);
        assertThat(sha256(Files.readAllBytes(file))).isEqualTo(r.structured.get("sha256"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        return file;
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void base64QuotedPrintableSevenBitAndEightBitPartsAreByteExactAgainstTheOriginal() throws Exception {
        byte[] big = new byte[2_500_000]; // three 1 MiB blocks of encoded text
        new Random(42).nextBytes(big);
        byte[] b64 = Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encode(big);
        ByteArrayOutputStream e8 = new ByteArrayOutputStream();
        for (int line = 0; line < 4; line++) {
            for (int i = 0x80; i <= 0xFF; i++) {
                e8.write(i);
            }
            e8.writeBytes(ascii("\r\n"));
        }
        byte[] eight = e8.toByteArray();
        String qp = "Gr=C3=BC=C3=9Fe aus K=C3=B6ln\r\nzweite =\r\nZeile\r\n";
        byte[] qpDecoded = "Grüße aus Köln\r\nzweite Zeile\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] seven = ascii("plain seven bit text\r\nsecond line\r\n");
        seed("encodings", mixed("encodings",
                part("Content-Type: application/octet-stream; name=\"big.bin\"\r\n"
                        + "Content-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"big.bin\"",
                        b64),
                part("Content-Type: text/plain; charset=utf-8; name=\"qp.txt\"\r\n"
                        + "Content-Transfer-Encoding: quoted-printable\r\n"
                        + "Content-Disposition: attachment; filename=\"qp.txt\"", ascii(qp)),
                part("Content-Type: application/octet-stream; name=\"eight.bin\"\r\n"
                        + "Content-Transfer-Encoding: 8bit\r\nContent-Disposition: attachment; filename=\"eight.bin\"",
                        eight),
                part("Content-Type: text/plain; charset=us-ascii; name=\"seven.txt\"\r\n"
                        + "Content-Transfer-Encoding: 7bit\r\nContent-Disposition: attachment; filename=\"seven.txt\"",
                        seven)));

        assertSavedExactly(download("encodings", "2"), big);
        assertSavedExactly(download("encodings", "3"), qpDecoded);
        assertSavedExactly(download("encodings", "4"), eight);
        assertSavedExactly(download("encodings", "5"), seven);
        assertThat(filesInDownloads()).containsExactlyInAnyOrder("big.bin", "qp.txt", "eight.bin", "seven.txt");
    }

    /** GreenMail serves only the text of such a part; Dovecot serves the encapsulated message with its headers. */
    @Test
    void aMessageRfc822PartIsSavedAsTheEncapsulatedMessageWithItsHeaders() throws Exception {
        byte[] inner = ascii("From: inner@example.org\r\nTo: inner-to@example.org\r\nSubject: inner subject\r\n"
                + "Date: Tue, 03 Nov 2026 09:00:00 +0000\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=us-ascii\r\n\r\ninner body line\r\nsecond line\r\n");
        seed("rfc822", mixed("rfc822", part("Content-Type: message/rfc822\r\nContent-Disposition: attachment",
                inner)));

        Result r = download("rfc822", "2");

        Path file = assertSavedExactly(r, inner);
        assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).startsWith("From: inner@example.org\r\n")
                .contains("Subject: inner subject\r\n");
        assertThat((String) map(r.structured, "untrusted").get("saved_as")).isEqualTo("attachment-2.eml");
        assertThat(map(r.structured, "untrusted").get("content_type")).isEqualTo("message/rfc822");
        assertThat(r.structured.get("renamed")).isEqualTo(true);
    }

    @Test
    void aTruncatedBase64QuantumIsDecodeFailedAndNoFileRemains() throws Exception {
        seed("truncated", mixed("truncated", part("Content-Type: application/octet-stream; name=\"cut.bin\"\r\n"
                + "Content-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"cut.bin\"",
                ascii("AAAABB"))));
        List<String> before = filesInDownloads();

        Result r = download("truncated", "2");

        assertThat(r.error()).isTrue();
        assertThat(r.errorCode()).isEqualTo("UNSUPPORTED");
        assertThat(r.detail("reason")).isEqualTo("decode_failed");
        assertThat(filesInDownloads()).isEqualTo(before);
    }

    @Test
    void theWireShowsExamineAndOnlyPartialPeekFetchesAndNoFlagChanges() throws Exception {
        byte[] data = new byte[1_500_000];
        new Random(7).nextBytes(data);
        seed("peek", mixed("peek", part("Content-Type: application/octet-stream; name=\"peek.bin\"\r\n"
                + "Content-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"peek.bin\"",
                Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encode(data))));
        String handle = handleOf("peek");
        MailboxSnapshot before;
        try (MailSeeder s = DOVECOT.seeder(USER)) {
            before = MailboxSnapshot.of(s.store(), "INBOX");
        }
        long log = logSize();

        Result r = call("download_attachment", args("account", account, "message", handle, "part", "2"));

        assertSavedExactly(r, data);
        List<String> commands = commandsSince(log);
        assertThat(commands).anyMatch(c -> c.startsWith("EXAMINE "));
        assertThat(commands).noneMatch(c -> c.startsWith("SELECT "));
        List<String> bodyFetches = commands.stream().filter(c -> c.contains("BODY")).toList();
        List<String> peeks = bodyFetches.stream().filter(c -> c.contains("BODY.PEEK[2]")).toList();
        assertThat(peeks).as("1 MiB blocks of a 2 MB encoded part: at least two").hasSizeGreaterThanOrEqualTo(2);
        assertThat(peeks).allMatch(c -> c.matches(".*BODY\\.PEEK\\[2\\]<\\d+\\.1048576>.*"));
        assertThat(commands).noneMatch(c -> c.contains("BODY[") || c.contains("BINARY"));
        assertThat(commands).noneMatch(c -> c.startsWith("STORE ") || c.startsWith("UID STORE ")
                || c.contains("EXPUNGE") || c.startsWith("APPEND") || c.startsWith("UID COPY")
                || c.startsWith("UID MOVE"));
        assertThat(ImapWireAudit.violations(commands)).isEmpty();
        try (MailSeeder s = DOVECOT.seeder(USER)) {
            assertThat(MailboxSnapshot.diff(before, MailboxSnapshot.of(s.store(), "INBOX")).isEmpty())
                    .as("no flag, in particular no \\Seen, and no UID changed").isTrue();
        }
    }
}
