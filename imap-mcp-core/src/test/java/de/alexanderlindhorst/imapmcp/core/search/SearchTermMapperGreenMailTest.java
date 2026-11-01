package de.alexanderlindhorst.imapmcp.core.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.SearchTerm;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SearchTermMapperGreenMailTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private final ByteArrayOutputStream debug = new ByteArrayOutputStream();
    private final TimeZone originalZone = TimeZone.getDefault();
    private GreenMailUser user;
    private Store store;
    private IMAPFolder folder;
    private final List<Long> uids = new ArrayList<>();

    @BeforeEach
    void seed() throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        user = GREEN_MAIL.setUser("user@localhost", "user", "secret");
        deliver("alice@localhost", "one");
        deliver("alice@localhost", "two");
        deliver("alice@localhost", "three");
        deliver("bob@localhost", "four");
        // flags: m1 unseen+proj, m2 seen+proj, m3 unseen, m4 unseen+proj
        Session s = session();
        try (Store st = s.getStore("imaps")) {
            st.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
            Folder f = st.getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            f.getMessage(1).setFlag(Flags.Flag.SEEN, false);
            f.setFlags(new int[] {1, 2, 4}, new Flags("proj"), true);
            f.setFlags(new int[] {2}, new Flags(Flags.Flag.SEEN), true);
            f.close(false);
        }
        store = session().getStore("imaps");
        store.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), "user", "secret");
        folder = (IMAPFolder) store.getFolder("INBOX");
        folder.open(Folder.READ_ONLY);
        for (int i = 1; i <= 4; i++) {
            uids.add(folder.getUID(folder.getMessage(i)));
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        TimeZone.setDefault(originalZone);
        if (folder != null && folder.isOpen()) {
            folder.close(false);
        }
        if (store != null) {
            store.close();
        }
    }

    private Session session() {
        Properties props = new Properties();
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.ssl.checkserveridentity", "false");
        Session session = Session.getInstance(props);
        session.setDebug(true);
        session.setDebugOut(new PrintStream(debug, true, StandardCharsets.UTF_8));
        return session;
    }

    private void deliver(String from, String subject) throws Exception {
        MimeMessage m = new MimeMessage((Session) null);
        m.setFrom(new InternetAddress(from));
        m.setRecipient(Message.RecipientType.TO, new InternetAddress("user@localhost"));
        m.setSubject(subject);
        m.setText("body of " + subject);
        user.deliver(m);
    }

    private List<Long> search(SearchTerm term) throws Exception {
        List<Long> found = new ArrayList<>();
        for (Message m : folder.search(term)) {
            found.add(folder.getUID(m));
        }
        return found;
    }

    @Test
    void combinationReturnsExactlyTheExpectedUids() throws Exception {
        SearchCriteria c = new SearchCriteria(null, null, null, null, null, null, null, false, null, null, null,
                List.of("proj"), null).withFrom("alice");
        List<Long> found = search(new SearchTermMapper().map(c));
        assertThat(found).containsExactly(uids.get(0));
    }

    @Test
    void individualCriteriaAgainstServer() throws Exception {
        SearchTermMapper mapper = new SearchTermMapper();
        assertThat(search(mapper.map(SearchCriteria.empty().withSeen(true)))).containsExactly(uids.get(1));
        assertThat(search(mapper.map(SearchCriteria.empty().withFrom("bob")))).containsExactly(uids.get(3));
        assertThat(search(mapper.map(SearchCriteria.empty().withSubject("thr")))).containsExactly(uids.get(2));
        assertThat(search(mapper.map(SearchCriteria.empty().withKeywords(List.of("proj", "nope"))))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Pacific/Kiritimati", "America/Los_Angeles", "Pacific/Pago_Pago"})
    void dateBoundsProduceTheIntendedCalendarDateInAnyZone(String zone) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        SearchTermMapper mapper = new SearchTermMapper(ZoneId.of(zone));
        SearchCriteria from = new SearchCriteria(null, null, null, null, LocalDate.of(2026, 3, 5), null, null, null,
                null, null, null, null, null);
        SearchCriteria to = new SearchCriteria(null, null, null, null, null, LocalDate.of(2026, 3, 5), null, null,
                null, null, null, null, null);
        search(mapper.map(from));
        assertThat(debug.toString(StandardCharsets.UTF_8)).contains("SEARCH SINCE 5-Mar-2026");
        search(mapper.map(to));
        assertThat(debug.toString(StandardCharsets.UTF_8)).contains("SEARCH BEFORE 6-Mar-2026");
    }

    @Test
    void dateRangeSelectsTodaysMessages() throws Exception {
        LocalDate today = LocalDate.now();
        SearchTermMapper mapper = new SearchTermMapper();
        SearchCriteria in = new SearchCriteria(null, null, null, null, today.minusDays(1), today, null, null, null,
                null, null, null, null);
        SearchCriteria out = new SearchCriteria(null, null, null, null, today.minusDays(10), today.minusDays(5),
                null, null, null, null, null, null, null);
        assertThat(search(mapper.map(in))).hasSize(4);
        assertThat(search(mapper.map(out))).isEmpty();
    }
}
