package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** #27 (reopened) AC 10-14: the UID transfer per page is bounded by the page, not by the folder. */
class SearchBoundedFetchTest {

    static final AccountId A1 = new AccountId("a1");
    private static final Pattern TAGGED = Pattern.compile("^(?:C: )?[A-Z]+\\d+ (?!OK |NO |BAD ).*$");
    private static final Pattern FETCH_SET = Pattern.compile("FETCH ([\\d:,*]+) ");

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

    List<Long> uids(Map<String, Object> out) {
        return messages(out).stream()
                .map(m -> ((MessageHandles.Decoded.Ok) env.handles.decode(SearchEnv.T1, A1, (String) m.get("handle")))
                        .ref().uid())
                .toList();
    }

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

    /** Number of sequence numbers / UIDs a FETCH command addresses ("*" counts as one). */
    private static int covered(String fetchCommand) {
        Matcher m = FETCH_SET.matcher(fetchCommand);
        assertThat(m.find()).as(fetchCommand).isTrue();
        int n = 0;
        for (String part : m.group(1).split(",")) {
            String[] range = part.split(":");
            if (range.length == 2 && !range[0].equals("*") && !range[1].equals("*")) {
                n += Math.abs(Integer.parseInt(range[1]) - Integer.parseInt(range[0])) + 1;
            } else {
                n += range.length;
            }
        }
        return n;
    }

    // AC10
    @Test
    void withoutCriteriaNoSearchAndNoFetchCoversMoreThanThePage() throws Exception {
        env.deliver(40);
        env.ok(SearchEnv.T1, args()); // warm the pooled connection
        List<Long> all = new ArrayList<>();
        List<String> cmds = new ArrayList<>();
        Map<String, Object> out = null;
        do {
            Object cursor = out == null ? null : out.get("next_cursor");
            Map<String, Object> call = cursor == null ? args("page_size", 10) : args("page_size", 10, "cursor", cursor);
            Map<String, Object>[] holder = new Map[1];
            cmds.addAll(commands(() -> holder[0] = env.ok(SearchEnv.T1, call)));
            out = holder[0];
            assertThat(out).containsEntry("total", 40);
            all.addAll(uids(out));
        } while (out.get("next_cursor") != null);
        assertThat(all).hasSize(40).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(all.get(0) - all.get(39)).isEqualTo(39);
        assertThat(cmds).noneMatch(c -> c.contains("SEARCH"));
        assertThat(cmds.stream().filter(c -> c.contains("FETCH")).toList()).isNotEmpty()
                .allSatisfy(c -> assertThat(covered(c)).isLessThanOrEqualTo(10));
    }

    // AC11
    @Test
    void withCriteriaTheUidDataCoversOnlyThePageAndTotalIsTheMatchCount() throws Exception {
        env.deliver(40);
        env.ok(SearchEnv.T1, args()); // warm the pooled connection
        List<String> cmds = commands(() -> {
            Map<String, Object> out = env.ok(SearchEnv.T1, args("page_size", 10, "subject", "Subject"));
            assertThat(out).containsEntry("total", 40).containsEntry("returned", 10).containsEntry("has_more", true);
        });
        assertThat(cmds.stream().filter(c -> c.contains("SEARCH")).toList()).hasSize(1);
        List<String> fetches = cmds.stream().filter(c -> c.contains("FETCH")).toList();
        assertThat(fetches).hasSize(1).allSatisfy(c -> assertThat(covered(c)).isLessThanOrEqualTo(10));
    }

    // AC12
    @Test
    void expungeOfTheCursorMessageUsesTheFallbackBoundary() throws Exception {
        env.deliver(10);
        Map<String, Object> p1 = env.ok(SearchEnv.T1, args("page_size", 3));
        List<Long> first = uids(p1);
        long last = first.get(2);
        env.expungeUids(last);
        Map<String, Object>[] holder = new Map[1];
        List<String> cmds = commands(
                () -> holder[0] = env.ok(SearchEnv.T1, args("page_size", 3, "cursor", p1.get("next_cursor"))));
        assertThat(uids(holder[0])).containsExactly(last - 1, last - 2, last - 3);
        assertThat(cmds).anyMatch(c -> c.contains("UID FETCH " + last + " (UID)"))
                .anyMatch(c -> c.contains("UID FETCH " + last + ":* (UID)"));
    }

    // AC13
    @Test
    void expungeRightBelowTheCursorVisitsEveryRemainingMessageExactlyOnce() throws Exception {
        env.deliver(8);
        List<Long> seen = new ArrayList<>();
        Map<String, Object> out = env.ok(SearchEnv.T1, args("page_size", 1));
        seen.addAll(uids(out));
        env.expungeUids(seen.get(0) - 1);
        env.expungeUids(seen.get(0) - 3);
        while (out.get("next_cursor") != null) {
            out = env.ok(SearchEnv.T1, args("page_size", 1, "cursor", out.get("next_cursor")));
            seen.addAll(uids(out));
        }
        long top = seen.get(0);
        assertThat(seen).doesNotHaveDuplicates().containsExactly(top, top - 2, top - 4, top - 5, top - 6, top - 7);
    }

    // AC14: the normal path keeps a constant command count
    @Test
    void commandCountPerPageStaysConstantAcrossPageSizes() throws Exception {
        env.deliver(120);
        env.ok(SearchEnv.T1, args("page_size", 1));
        List<String> one = commands(() -> env.ok(SearchEnv.T1, args("page_size", 1)));
        List<String> hundred = commands(() -> env.ok(SearchEnv.T1, args("page_size", 100)));
        assertThat(one).hasSameSizeAs(hundred).hasSize(3); // EXAMINE, FETCH page, CLOSE
        List<String> oneC = commands(() -> env.ok(SearchEnv.T1, args("page_size", 1, "subject", "Subject")));
        List<String> hundredC = commands(() -> env.ok(SearchEnv.T1, args("page_size", 100, "subject", "Subject")));
        assertThat(oneC).hasSameSizeAs(hundredC).hasSize(4); // EXAMINE, SEARCH, FETCH page, CLOSE
    }

    // deficit fetch: the folder view is stale (messages expunged after EXISTS was cached), so the boundary is too high
    @Test
    void staleBoundaryIsCompensatedByADeficitFetch() throws Exception {
        env.deliver(10);
        java.util.Properties props = new java.util.Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        try (jakarta.mail.Store st = jakarta.mail.Session.getInstance(props).getStore("imaps")) {
            st.connect("127.0.0.1", env.greenMail.getImaps().getPort(), SearchEnv.USER, "pw");
            org.eclipse.angus.mail.imap.IMAPFolder f = (org.eclipse.angus.mail.imap.IMAPFolder) st.getFolder("INBOX");
            f.open(jakarta.mail.Folder.READ_ONLY);
            long top = ((jakarta.mail.UIDFolder) f).getUID(f.getMessage(10));
            assertThat(f.getMessageCount()).isEqualTo(10);
            env.expungeUids(top, top - 1);
            de.alexanderlindhorst.imapmcp.core.search.PageFetcher.Page page =
                    de.alexanderlindhorst.imapmcp.core.search.PageFetcher.page(f, null, 0, 3);
            assertThat(page.rows()).extracting(r -> r.uid()).containsExactly(top - 2, top - 3, top - 4);
            assertThat(page.hasMore()).isTrue();
            f.close(false);
        }
    }
}
