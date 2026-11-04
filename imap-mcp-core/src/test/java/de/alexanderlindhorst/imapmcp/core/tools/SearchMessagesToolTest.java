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
import java.time.Instant;
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
        MimeMessage plain = env.message("J\u00fcrgen M\u00fcller", "j@example.org", "Gr\u00fc\u00dfe");
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
        assertThat(u.get("subject")).isEqualTo("Gr\u00fc\u00dfe");
        assertThat(u.get("from")).isEqualTo(Map.of("name", "J\u00fcrgen M\u00fcller", "address", "j@example.org"));
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
        assertThat(one).hasSize(4).hasSameSizeAs(hundred); // EXAMINE, SEARCH, FETCH page, CLOSE (#27: no UID FETCH)
        assertThat(hundred.stream().filter(c -> c.contains("FETCH")).count()).isEqualTo(1);
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

    // ---- #28 since ----

    private static final Instant D = Instant.parse("2026-03-05T00:00:00Z");

    private List<String> subjects(Map<String, Object> out) {
        return messages(out).stream().map(m -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> u = (Map<String, Object>) m.get("untrusted");
            return (String) u.get("subject");
        }).toList();
    }

    // #28 AC1
    @Test
    void sinceIsExactToTheSecondAndOffsetIndependent() throws Exception {
        env.deliverAt(D.plusSeconds(10 * 3600), "a-10:00:00");
        env.deliverAt(D.plusSeconds(10 * 3600 + 1), "b-10:00:01");
        env.deliverAt(D.plusSeconds(11 * 3600), "c-11:00:00");
        Map<String, Object> utc = env.ok(SearchEnv.T1, args("since", "2026-03-05T10:00:01Z"));
        assertThat(subjects(utc)).containsExactly("c-11:00:00", "b-10:00:01");
        assertThat(utc).containsEntry("total", 2);
        assertThat(messages(utc).get(1)).containsEntry("received_at", "2026-03-05T10:00:01Z");
        Map<String, Object> plus2 = env.ok(SearchEnv.T1, args("since", "2026-03-05T12:00:01+02:00"));
        assertThat(subjects(plus2)).containsExactly("c-11:00:00", "b-10:00:01");
        assertThat(plus2).containsEntry("total", 2);
    }

    // #28 AC2
    @Test
    void sinceDayBoundaryInPlus14StillFindsMessagesOfThePreviousUtcDay() throws Exception {
        env.deliverAt(Instant.parse("2026-03-03T23:59:59Z"), "too-old");
        env.deliverAt(Instant.parse("2026-03-04T10:29:59Z"), "just-before");
        env.deliverAt(Instant.parse("2026-03-04T10:30:00Z"), "at-since");
        env.deliverAt(Instant.parse("2026-03-04T23:30:00Z"), "later-same-utc-day");
        // 2026-03-05T00:30+14:00 is 2026-03-04T10:30:00Z
        Map<String, Object> out = env.ok(SearchEnv.T1, args("since", "2026-03-05T00:30:00+14:00"));
        assertThat(subjects(out)).containsExactly("later-same-utc-day", "at-since");
        assertThat(out).containsEntry("total", 2);
    }

    // #28 AC3: the candidates' FETCH carries INTERNALDATE together with UID, so since costs exactly one FETCH more
    // than a search without it (EXAMINE, SEARCH, FETCH, FETCH, CLOSE vs EXAMINE, SEARCH, FETCH, CLOSE).
    @Test
    void sinceAddsNoCommandBecauseInternaldateRidesOnTheUidFetch() throws Exception {
        for (int i = 0; i < 6; i++) {
            env.deliverAt(D.plusSeconds(10 * 3600 + i), "m" + i);
        }
        env.ok(SearchEnv.T1, args()); // warm the pooled connection
        List<String> without = commands(() -> env.ok(SearchEnv.T1, args("subject", "m")));
        List<String> with = commands(() -> env.ok(SearchEnv.T1,
                args("subject", "m", "since", "2026-03-05T10:00:02Z", "page_size", 1)));
        assertThat(without).hasSize(4);
        assertThat(with).hasSize(5);
        List<String> fetches = with.stream().filter(c -> c.contains("FETCH")).toList();
        assertThat(fetches).hasSize(2);
        assertThat(fetches.get(0)).contains("(UID INTERNALDATE)").doesNotContain("ENVELOPE");
        assertThat(with).noneMatch(c -> c.contains("SELECT")).noneMatch(c -> c.contains("STORE"));
        assertThat(with.stream().filter(c -> c.contains("SEARCH")).toList()).hasSize(1)
                .allMatch(c -> c.contains("SINCE"));
    }

    // #28 AC4
    @Test
    void moreCandidatesThanTheCapIsInvalidArgumentTooManyCandidates() throws Exception {
        env.close();
        env = new SearchEnv(5);
        for (int i = 0; i < 5; i++) {
            env.deliverAt(D.plusSeconds(10 * 3600 + i), "m" + i);
        }
        assertThat(env.ok(SearchEnv.T1, args("since", "2026-03-05T10:00:00Z"))).containsEntry("total", 5);
        env.deliverAt(D.plusSeconds(10 * 3600 + 5), "m5");
        ToolError e = env.error(SearchEnv.T1, args("since", "2026-03-05T10:00:00Z"));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details()).containsEntry("field", "since").containsEntry("reason", "too_many_candidates");
        assertThat(e.hint()).isEqualTo("Narrow `since` or add criteria.");
        // adding a criterion brings the candidates under the cap again
        assertThat(env.ok(SearchEnv.T1, args("since", "2026-03-05T10:00:00Z", "subject", "m5")))
                .containsEntry("total", 1);
    }

    // #28 AC5 + AC7
    @Test
    void whatsNewFlowReturnsExactlyTheMessageAppendedAfterTheFirstSearch() throws Exception {
        env.deliverAt(Instant.parse("2026-03-05T07:00:00Z"), "old1");
        env.deliverAt(Instant.parse("2026-03-05T08:00:00Z"), "old2");
        Map<String, Object> first = env.ok(SearchEnv.T1, args());
        String asOf = (String) first.get("as_of");
        assertThat(asOf).isEqualTo("2026-03-05T09:55:07Z");
        env.deliverAt(env.clock.now, "M");
        Map<String, Object> next = env.ok(SearchEnv.T1, args("since", asOf));
        assertThat(subjects(next)).containsExactly("M");
        assertThat(next).containsEntry("total", 1);
    }

    // #28 AC8
    @Test
    void windowsOverlap_aMessageReceivedASecondBeforeTheSearchIsStillFound() throws Exception {
        env.deliverAt(Instant.parse("2026-03-05T07:00:00Z"), "old");
        String asOf = (String) env.ok(SearchEnv.T1, args()).get("as_of");
        env.deliverAt(env.clock.now.minusSeconds(60), "late-arrival");
        assertThat(subjects(env.ok(SearchEnv.T1, args("since", asOf)))).containsExactly("late-arrival");
    }

    // #28 AC9
    @Test
    void messageArrivingBetweenPagesIsMissedByPageTwoButFoundBySinceAsOfOfPageOne() throws Exception {
        for (int i = 0; i < 30; i++) {
            env.deliverAt(Instant.parse("2026-03-05T08:00:00Z").plusSeconds(i), "old" + i);
        }
        Map<String, Object> p1 = env.ok(SearchEnv.T1, args("page_size", 10));
        env.deliverAt(env.clock.now, "N");
        Map<String, Object> p2 = env.ok(SearchEnv.T1, args("page_size", 10, "cursor", p1.get("next_cursor")));
        assertThat(subjects(p2)).doesNotContain("N");
        assertThat(subjects(env.ok(SearchEnv.T1, args("since", p1.get("as_of"))))).containsExactly("N");
    }

    @Test
    void sinceCursorPagesKeepTheExactTotalAndAChangedSinceIsACriteriaMismatch() throws Exception {
        for (int i = 0; i < 4; i++) {
            env.deliverAt(Instant.parse("2026-03-05T05:00:00Z").plusSeconds(i), "before" + i);
        }
        for (int i = 0; i < 12; i++) {
            env.deliverAt(Instant.parse("2026-03-05T10:00:00Z").plusSeconds(i), "after" + i);
        }
        Map<String, Object> p1 = env.ok(SearchEnv.T1, args("since", "2026-03-05T09:00:00Z", "page_size", 5));
        Map<String, Object> p2 = env.ok(SearchEnv.T1,
                args("since", "2026-03-05T09:00:00Z", "page_size", 5, "cursor", p1.get("next_cursor")));
        Map<String, Object> p3 = env.ok(SearchEnv.T1,
                args("since", "2026-03-05T09:00:00Z", "page_size", 5, "cursor", p2.get("next_cursor")));
        assertThat(List.of(p1, p2, p3)).allSatisfy(p -> assertThat(p).containsEntry("total", 12));
        assertThat(messages(p1)).hasSize(5);
        assertThat(messages(p2)).hasSize(5);
        assertThat(messages(p3)).hasSize(2);
        assertThat(p3).containsEntry("has_more", false);
        ToolError e = env.error(SearchEnv.T1,
                args("since", "2026-03-05T09:00:01Z", "page_size", 5, "cursor", p1.get("next_cursor")));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CURSOR);
        assertThat(e.details()).containsEntry("reason", "criteria_mismatch");
    }

    // #28 AC6
    @Test
    void sinceSearchLeavesMessagesUnseen() throws Exception {
        env.deliver(3);
        long before = env.unseenCount();
        assertThat(env.ok(SearchEnv.T1, args("since", "2000-01-01T00:00:00Z"))).containsEntry("returned", 3);
        assertThat(env.unseenCount()).isEqualTo(before).isEqualTo(3);
    }

    // #28 AC10
    @Test
    @SuppressWarnings("unchecked")
    void asOfGuidanceIsSplitAcrossDescriptionAndSchemaProperties() {
        String d = SearchMessagesTool.DEFINITION.description();
        assertThat(d).contains("For new mail, pass the first page's as_of as since in a later search with the same "
                + "criteria (see as_of).").hasSizeLessThanOrEqualTo(600);
        Map<String, Object> inProps = (Map<String, Object>) SearchMessagesTool.DEFINITION.inputSchema()
                .get("properties");
        assertThat(((Map<String, Object>) inProps.get("since")).get("description")).isEqualTo(SINCE_TEXT);
        Map<String, Object> outProps = (Map<String, Object>) SearchMessagesTool.DEFINITION.outputSchema()
                .get("properties");
        assertThat(((Map<String, Object>) outProps.get("as_of")).get("description")).isEqualTo(AS_OF_TEXT);
        assertThat(SINCE_TEXT.length()).isLessThanOrEqualTo(400);
        assertThat(AS_OF_TEXT.length()).isLessThanOrEqualTo(400);
        assertThat(DescriptionRules.violations(SearchMessagesTool.DEFINITION)).isEmpty();
    }

    static final String SINCE_TEXT = "Only messages received (received_at, mail server time) at or after this "
            + "instant. For new mail, pass the first-page as_of of an earlier search with the same criteria. "
            + "Windows overlap by about 5 minutes, so already-seen messages may reappear; deduplicate by handle. "
            + "Messages moved or copied into the folder keep their original received time and are not reported "
            + "as new.";

    static final String AS_OF_TEXT = "Set only on the first page (no cursor), otherwise null. Pass it as since in "
            + "your next search with the same criteria to get messages received since this search; results may "
            + "repeat messages already seen, so deduplicate by handle.";

    // ---- #29 gmail_raw ----

    private void assertUnsupported(ToolError e) {
        assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED);
        assertThat(e.details()).containsEntry("capability", "X-GM-EXT-1");
    }

    // #25 rework: a keyword with a trailing line terminator never reaches the wire
    @Test
    void keywordWithTrailingLineTerminatorIsInvalidArgumentWithoutImapIo() throws Exception {
        env.ok(SearchEnv.T1, args()); // warm the pooled connection
        for (String bad : new String[] {"abc\n", "abc\r\n", "abc\r"}) {
            List<String> cmds = commands(() -> {
                ToolError e = env.error(SearchEnv.T1, args("keywords", List.of(bad)));
                assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
                assertThat(e.details()).containsEntry("field", "keywords[0]").containsEntry("reason", "format");
                assertThat(e.toString()).doesNotContain("abc");
            });
            assertThat(cmds).isEmpty();
        }
    }

    // #29 AC1
    @Test
    void gmailRawOnAServerWithoutXGmExt1IsUnsupportedAndNoSearchIsSent() throws Exception {
        env.deliver(2);
        env.ok(SearchEnv.T1, args()); // warm the pooled connection
        List<String> cmds = commands(() -> assertUnsupported(env.error(SearchEnv.T1,
                args("gmail_raw", "has:attachment", "subject", "Subject"))));
        assertThat(cmds).noneMatch(c -> c.contains("SEARCH")).noneMatch(c -> c.contains("FETCH"))
                .noneMatch(c -> c.contains("EXAMINE")).noneMatch(c -> c.contains("SELECT"));
    }

    // the capability alone is not enough: the account must also be a Gmail-flavored (gimaps) one
    @Test
    void gmailRawOnAGenericAccountIsUnsupportedEvenIfTheCacheSaysGmail() throws Exception {
        env.ok(SearchEnv.T1, args()); // creates the store, which records the capabilities
        env.advertiseGmail(true);
        List<String> cmds = commands(() -> assertUnsupported(env.error(SearchEnv.T1, args("gmail_raw", "x"))));
        assertThat(cmds).noneMatch(c -> c.contains("SEARCH"));
    }

    @Test
    void gmailRawOnAGmailAccountWithoutTheCapabilityIsUnsupported() throws Exception {
        env.close();
        env = new SearchEnv(SearchMessagesTool.DEFAULT_SINCE_MAX_CANDIDATES,
                de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor.GMAIL);
        List<String> cmds = commands(() -> assertUnsupported(env.error(SearchEnv.T1, args("gmail_raw", "x"))));
        assertThat(cmds).noneMatch(c -> c.contains("SEARCH"));
    }

    // #29 AC3: Gmail account (gimaps) + advertised capability: the query is sent as X-GM-RAW; GreenMail does not
    // know the key and answers NO/BAD, which must surface as UPSTREAM_ERROR, not as a crash or empty result.
    @Test
    void aNoOrBadAnswerToTheGmailQueryIsUpstreamError() throws Exception {
        env.close();
        env = new SearchEnv(SearchMessagesTool.DEFAULT_SINCE_MAX_CANDIDATES,
                de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor.GMAIL);
        env.deliver(2);
        env.ok(SearchEnv.T1, args()); // creates the store, which records the capabilities
        env.advertiseGmail(true);
        List<String> cmds = new ArrayList<>();
        ToolError e = env.error(SearchEnv.T1, args("gmail_raw", "has:attachment"));
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        cmds.addAll(commands(() -> env.error(SearchEnv.T1, args("gmail_raw", "has:attachment"))));
        assertThat(cmds).anyMatch(c -> c.contains("SEARCH") && c.contains("X-GM-RAW has:attachment"));
    }

    /**
     * #137 AC5: a stale capability entry is not trusted. The capabilities are re-read from the leased Store (local,
     * no IMAP command) and the cache entry is refreshed; the stale "gmail" claim must not let the query through.
     */
    @Test
    void gmailRawWithAnExpiredCapabilityEntryRereadsTheLeasedStoreAndRefreshesTheCache() throws Exception {
        env.close();
        env = new SearchEnv(SearchMessagesTool.DEFAULT_SINCE_MAX_CANDIDATES,
                de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor.GMAIL);
        SearchEnv.TestClock clock = new SearchEnv.TestClock();
        de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache cache =
                new de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache(clock);
        SearchMessagesTool tool = new SearchMessagesTool(new AccountArgument(env.repo), env.leases,
                new de.alexanderlindhorst.imapmcp.core.search.SearchTermMapper(), env.cursors, env.handles,
                env.clock, SearchMessagesTool.SINCE_OVERLAP, SearchMessagesTool.DEFAULT_SINCE_MAX_CANDIDATES, cache);
        de.alexanderlindhorst.imapmcp.core.tool.ValidatingToolHandler handler =
                new de.alexanderlindhorst.imapmcp.core.tool.ValidatingToolHandler(SearchMessagesTool.DEFINITION, tool);
        de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext ctx =
                new de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext(
                        new de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext(SearchEnv.T1), "rid");
        de.alexanderlindhorst.imapmcp.core.account.PoolKey key = de.alexanderlindhorst.imapmcp.core.account.PoolKey
                .of(new de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext(SearchEnv.T1),
                        new de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount(SearchEnv.T1, env.account,
                                env.handleT1));
        handler.handle(ctx, args()); // warm the pooled connection
        cache.put(key, de.alexanderlindhorst.imapmcp.core.imap.ImapCapabilities.fromNames(List.of("X-GM-EXT-1")));
        clock.now = clock.now.plus(java.time.Duration.ofMinutes(11));
        assertThat(cache.get(key)).isInstanceOf(de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache.Lookup.Expired.class);

        List<String> cmds = new ArrayList<>();
        de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome[] out = new de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome[1];
        cmds.addAll(commands(() -> out[0] = handler.handle(ctx, args("gmail_raw", "x"))));

        assertThat(out[0]).isInstanceOf(de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome.Failure.class);
        assertUnsupported(((de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome.Failure) out[0]).error());
        assertThat(cmds).isEmpty(); // no extra IMAP command: the capabilities come from the leased Store
        assertThat(cache.get(key)).isInstanceOf(de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache.Lookup.Fresh.class);
        assertThat(((de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache.Lookup.Fresh) cache.get(key))
                .capabilities().gmail()).isFalse();
    }
}
