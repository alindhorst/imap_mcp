package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tool.DescriptionRules;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SearchMessagesToolTest {

    static final AccountId A1 = new AccountId("a1");

    SearchEnv env;

    @BeforeEach
    void setUp() {
        env = new SearchEnv();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", env.handleT1);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> messages(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("messages");
    }

    MessageRef decode(String handle) {
        return ((MessageHandles.Decoded.Ok) env.handles.decode(SearchEnv.T1, A1, handle)).ref();
    }

    List<Long> uids(Map<String, Object> out) {
        return messages(out).stream().map(m -> decode((String) m.get("handle")).uid()).toList();
    }

    // AC1
    @Test
    void sixtyMessagesPageInto25_25_10NewestFirstWithoutGapsAndTotalOnEveryPage() throws Exception {
        env.deliver(60);
        Map<String, Object> p1 = env.ok(SearchEnv.T1, args());
        assertThat(p1).containsEntry("total", 60).containsEntry("returned", 25).containsEntry("has_more", true)
                .containsEntry("folder", "INBOX").containsEntry("account", env.handleT1)
                .containsEntry("content_notice", UntrustedText.CONTENT_NOTICE);
        assertThat(p1.get("next_cursor")).isInstanceOf(String.class);
        Map<String, Object> p2 = env.ok(SearchEnv.T1, args("cursor", p1.get("next_cursor")));
        assertThat(p2).containsEntry("total", 60).containsEntry("returned", 25).containsEntry("has_more", true);
        Map<String, Object> p3 = env.ok(SearchEnv.T1, args("cursor", p2.get("next_cursor")));
        assertThat(p3).containsEntry("total", 60).containsEntry("returned", 10).containsEntry("has_more", false)
                .containsEntry("next_cursor", null);

        List<Long> all = new ArrayList<>(uids(p1));
        all.addAll(uids(p2));
        all.addAll(uids(p3));
        assertThat(all).hasSize(60).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(all.get(0) - all.get(59)).isEqualTo(59); // consecutive UIDs: no gaps
    }

    // AC2
    @Test
    void mailArrivingBetweenPagesDoesNotShiftPageTwo() throws Exception {
        env.deliver(30);
        Map<String, Object> p1 = env.ok(SearchEnv.T1, args("page_size", 10));
        List<Long> first = uids(p1);
        env.deliver(7);
        Map<String, Object> p2 = env.ok(SearchEnv.T1, args("page_size", 10, "cursor", p1.get("next_cursor")));
        assertThat(uids(p2)).hasSize(10).allMatch(u -> u < first.get(9));
        assertThat(uids(p2).get(0)).isEqualTo(first.get(9) - 1);
        assertThat(p2).containsEntry("total", 37); // matches at this page's SEARCH time
    }

    // AC8
    @Test
    void handlesDecodeToUidValidityAndFolderAndNothingElseLooksLikeASequenceNumber() throws Exception {
        env.deliver(3);
        Map<String, Object> out = env.ok(SearchEnv.T1, args());
        long validity = env.withInbox(Folder.READ_ONLY, f -> {
            try {
                return ((org.eclipse.angus.mail.imap.IMAPFolder) f).getUIDValidity();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        for (Map<String, Object> m : messages(out)) {
            MessageRef ref = decode((String) m.get("handle"));
            assertThat(ref.folder()).isEqualTo("INBOX");
            assertThat(ref.uidValidity()).isEqualTo(validity);
            assertThat(m.keySet()).containsExactly("handle", "received_at", "size", "flags", "has_attachments",
                    "untrusted");
        }
        assertThat(out.keySet()).containsExactly("account", "folder", "as_of", "total", "returned", "has_more",
                "next_cursor", "content_notice", "messages");
    }

    // AC10 and the as_of formula
    @Test
    void asOfOnFirstPageOnlyAndIsClockMinusOverlapFlooredToSecond() throws Exception {
        env.deliver(30);
        Map<String, Object> p1 = env.ok(SearchEnv.T1, args("page_size", 10));
        assertThat((String) p1.get("as_of")).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
                .isEqualTo("2026-03-05T09:55:07Z"); // 10:00:07.900 floored, minus 300 s
        Map<String, Object> p2 = env.ok(SearchEnv.T1, args("page_size", 10, "cursor", p1.get("next_cursor")));
        assertThat(p2).containsEntry("as_of", null);
    }

    @Test
    void emptyFolderYieldsEmptyPage() {
        Map<String, Object> out = env.ok(SearchEnv.T1, args());
        assertThat(out).containsEntry("total", 0).containsEntry("returned", 0).containsEntry("has_more", false)
                .containsEntry("next_cursor", null);
        assertThat(messages(out)).isEmpty();
    }

    @Test
    void criteriaFilterTotalAndFolderArgument() throws Exception {
        env.deliver(5);
        env.createFolder("Work");
        env.storeIn("Work", 2);
        assertThat(env.ok(SearchEnv.T1, args("subject", "Subject 3"))).containsEntry("total", 1);
        Map<String, Object> work = env.ok(SearchEnv.T1, args("folder", "Work"));
        assertThat(work).containsEntry("total", 2).containsEntry("folder", "Work");
        assertThat(decode((String) messages(work).get(0).get("handle")).folder()).isEqualTo("Work");
        assertThat(env.error(SearchEnv.T1, args("folder", "Nope")).code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
    }

    @Test
    void summaryFieldsFlagsSizeReceivedAttachmentAndSentAtOffset() throws Exception {
        MimeMessage plain = env.message("Jürgen Müller", "j@example.org", "Grüße");
        plain.setHeader("Date", "Thu, 5 Mar 2026 10:00:07 +0100");
        plain.addRecipient(jakarta.mail.Message.RecipientType.CC, new jakarta.mail.internet.InternetAddress("cc@example.org"));
        env.user.deliver(plain);
        MimeMessage withAtt = env.withAttachment(env.message("B", "b@example.org", "with attachment"));
        env.user.deliver(withAtt);
        env.withInbox(Folder.READ_WRITE, f -> {
            try {
                f.setFlags(1, 1, new Flags(Flags.Flag.FLAGGED), true);
                f.setFlags(1, 1, new Flags("proj"), true);
                return null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        List<Map<String, Object>> msgs = messages(env.ok(SearchEnv.T1, args()));
        Map<String, Object> att = msgs.get(0);
        Map<String, Object> pl = msgs.get(1);
        assertThat(att).containsEntry("has_attachments", true);
        assertThat(pl).containsEntry("has_attachments", false).containsEntry("flags", List.of("\\Flagged", "proj"));
        assertThat((Long) ((Number) pl.get("size")).longValue()).isGreaterThan(0);
        assertThat((String) pl.get("received_at")).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");
        @SuppressWarnings("unchecked")
        Map<String, Object> u = (Map<String, Object>) pl.get("untrusted");
        assertThat(u.get("subject")).isEqualTo("Grüße");
        assertThat(u.get("from")).isEqualTo(Map.of("name", "Jürgen Müller", "address", "j@example.org"));
        assertThat(u.get("sent_at")).isEqualTo("2026-03-05T10:00:07+01:00");
        assertThat(u).containsEntry("recipients_truncated", false);
        assertThat((List<?>) u.get("cc")).hasSize(1);
        assertThat((List<?>) u.get("to")).hasSize(1);
    }

    // AC7
    @Test
    void markerStringsAppearOnlyUnderUntrustedPaths() throws Exception {
        MimeMessage m = env.message("MARKERNAME Person", "from@example.org", "MARKERSUBJECT hello");
        m.setRecipient(jakarta.mail.Message.RecipientType.TO,
                new jakarta.mail.internet.InternetAddress("t@example.org", "MARKERTO Person"));
        env.user.deliver(m);
        Map<String, Object> out = env.ok(SearchEnv.T1, args());
        List<String> hits = new ArrayList<>();
        walk("$", out, hits);
        assertThat(hits).hasSize(3).allMatch(p -> p.contains("untrusted"));
    }

    private static void walk(String path, Object node, List<String> hits) {
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> walk(path + "." + k, v, hits));
        } else if (node instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                walk(path + "[" + i + "]", l.get(i), hits);
            }
        } else if (node instanceof String s && s.contains("MARKER")) {
            hits.add(path);
        }
    }

    @Test
    void untrustedStringsAreCapped() throws Exception {
        env.user.deliver(env.message("n".repeat(500), "from@example.org", "s".repeat(3000)));
        @SuppressWarnings("unchecked")
        Map<String, Object> u = (Map<String, Object>) messages(env.ok(SearchEnv.T1, args())).get(0).get("untrusted");
        assertThat(((String) u.get("subject")).codePointCount(0, ((String) u.get("subject")).length()))
                .isEqualTo(UntrustedText.SUBJECT_MAX);
        @SuppressWarnings("unchecked")
        String name = (String) ((Map<String, Object>) u.get("from")).get("name");
        assertThat(name.codePointCount(0, name.length())).isEqualTo(UntrustedText.DISPLAY_NAME_MAX);
    }

    // AC9
    @Test
    void sinceAndGmailRawAreRejectedAsNotImplemented() {
        ToolError since = env.error(SearchEnv.T1, args("since", "2026-03-01T00:00:00Z"));
        assertThat(since.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(since.details()).containsEntry("field", "since").containsEntry("reason", "not_implemented");
        ToolError raw = env.error(SearchEnv.T1, args("gmail_raw", "has:attachment"));
        assertThat(raw.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(raw.details()).containsEntry("field", "gmail_raw").containsEntry("reason", "not_implemented");
    }

    // AC5
    @Test
    void cursorWithOtherCriteriaTamperedOrStaleIsRejected() throws Exception {
        env.deliver(30);
        Map<String, Object> p1 = env.ok(SearchEnv.T1, args("page_size", 10, "subject", "Subject"));
        String cursor = (String) p1.get("next_cursor");
        ToolError mismatch = env.error(SearchEnv.T1, args("page_size", 10, "subject", "Other", "cursor", cursor));
        assertThat(mismatch.code()).isEqualTo(ErrorCode.INVALID_CURSOR);
        assertThat(mismatch.details()).containsEntry("reason", "criteria_mismatch");

        char last = cursor.charAt(cursor.length() - 2);
        String tampered = cursor.substring(0, cursor.length() - 2) + (last == 'A' ? 'B' : 'A') + cursor.charAt(cursor.length() - 1);
        ToolError bad = env.error(SearchEnv.T1, args("page_size", 10, "subject", "Subject", "cursor", tampered));
        assertThat(bad.code()).isEqualTo(ErrorCode.INVALID_CURSOR);
        assertThat(bad.details()).isNull();
    }

    @Test
    void recreatedFolderYieldsStaleReference() throws Exception {
        env.createFolder("Work");
        env.storeIn("Work", 5);
        Map<String, Object> p1 = env.ok(SearchEnv.T1, args("folder", "Work", "page_size", 2));
        env.deleteFolder("Work");
        Thread.sleep(1100); // GreenMail derives UIDVALIDITY from the creation time
        env.createFolder("Work");
        env.storeIn("Work", 5);
        ToolError e = env.error(SearchEnv.T1, args("folder", "Work", "page_size", 2, "cursor", p1.get("next_cursor")));
        assertThat(e.code()).isEqualTo(ErrorCode.STALE_REFERENCE);
        assertThat(e.details()).containsEntry("reason", "uidvalidity_changed");
    }

    // AC6
    @Test
    void cursorFromOtherTenantIsInvalidCursorWithIdenticalPayloadAndForeignAccountIsNotFound() throws Exception {
        env.deliver(30);
        Map<String, Object> p1 = env.ok(SearchEnv.T1, args("page_size", 10));
        Map<String, Object> asT2 = args("page_size", 10, "cursor", p1.get("next_cursor"));
        asT2.put("account", env.handleT2);
        ToolError foreign = env.error(SearchEnv.T2, asT2);
        ToolError garbage = env.error(SearchEnv.T1, args("page_size", 10, "cursor", "c1.AAAA"));
        assertThat(foreign.code()).isEqualTo(ErrorCode.INVALID_CURSOR);
        assertThat(strip(foreign)).isEqualTo(strip(garbage));

        ToolError notFound = env.error(SearchEnv.T2, args()); // tenant 1's account handle under tenant 2
        assertThat(notFound.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    private static Map<String, Object> strip(ToolError e) {
        return new ToolError(e.code(), e.message(), e.hint(), e.retryAfterMs(), e.details(), "x").toMap();
    }

    // AC3 + AC4
    @Test
    void commandCountPerPageIsConstantAndTheFolderIsOnlyExaminedAndPeeked() throws Exception {
        env.deliver(120);
        env.ok(SearchEnv.T1, args("page_size", 1)); // warm the pooled connection
        long unseenBefore = env.unseenCount();
        List<String> one = commands(() -> env.ok(SearchEnv.T1, args("page_size", 1, "subject", "Subject")));
        List<String> hundred = commands(() -> env.ok(SearchEnv.T1, args("page_size", 100, "subject", "Subject")));
        assertThat(one).hasSize(5).hasSameSizeAs(hundred); // EXAMINE, SEARCH, FETCH UID, FETCH page, CLOSE
        assertThat(hundred.stream().filter(c -> c.contains("FETCH")).count()).isEqualTo(2);
        assertThat(hundred).anyMatch(c -> c.contains("EXAMINE")).noneMatch(c -> c.contains("SELECT"))
                .noneMatch(c -> c.contains("STORE")).noneMatch(c -> c.contains("EXPUNGE"));
        for (String c : hundred) {
            if (c.contains("FETCH")) {
                String rest = c.replace("BODY.PEEK[", "");
                assertThat(rest).doesNotContain("BODY[").doesNotContainPattern("RFC822(?![.\\w])");
            }
        }
        assertThat(env.unseenCount()).isEqualTo(unseenBefore).isEqualTo(120);
    }

    private static final Pattern TAGGED = Pattern.compile("^(?:C: )?[A-Z]+\\d+ (?!OK |NO |BAD ).*$");

    private List<String> commands(Runnable call) {
        Logger trace = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Level old = trace.getLevel();
        trace.setLevel(Level.DEBUG);
        trace.addAppender(appender);
        appender.start();
        try {
            call.run();
        } finally {
            trace.detachAppender(appender);
            trace.setLevel(old);
        }
        String me = Thread.currentThread().getName();
        List<String> cmds = new ArrayList<>();
        for (ILoggingEvent e : appender.list) {
            if (e.getThreadName().equals(me) && TAGGED.matcher(e.getFormattedMessage()).matches()) {
                cmds.add(e.getFormattedMessage());
            }
        }
        return cmds;
    }

    @Test
    void definitionFollowsTheGuidelinesAndUsesTheSharedInputSchema() {
        assertThat(SearchMessagesTool.DEFINITION.name()).isEqualTo("search_messages");
        assertThat(SearchMessagesTool.DEFINITION.title()).isEqualTo("Search messages");
        assertThat(DescriptionRules.violations(SearchMessagesTool.DEFINITION)).isEmpty();
        assertThat(SearchMessagesTool.DEFINITION.hints().readOnly()).isTrue();
        assertThat(SearchMessagesTool.DEFINITION.inputSchema())
                .isEqualTo(de.alexanderlindhorst.imapmcp.core.search.SearchSchema.inputSchema());
        assertThat(SearchMessagesTool.SINCE_OVERLAP).isEqualTo(java.time.Duration.ofSeconds(300));
    }
}
