package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * GreenMail advertises neither AUTH=PLAIN nor AUTH=LOGIN, so the two exercised login paths are the LOGIN command
 * (empty mechanism list) and AUTHENTICATE XOAUTH2; PLAIN/LOGIN SASL continuations are covered in
 * {@link RedactingDebugStreamTest}. Two concurrent connections of one account share one Session and so one debug
 * stream. Angus's own auth suppression is switched off here so the redaction layer of
 * {@link RedactingDebugStream} is what is tested.
 */
class ImapTraceConcurrencyTest {

    private static final String USER = "tracemarkuser";
    private static final String PASSWORD = "tracemarkpass";
    private static final String FOLDER_A = "markerfoldera";
    private static final String FOLDER_B = "markerfolderb";
    private static final int RUNS = 100;
    private static final TenantId TENANT = new TenantId("t1");

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private static final class CollectingAppender extends AppenderBase<ILoggingEvent> {
        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            messages.add(event.getFormattedMessage());
        }
    }

    private final CollectingAppender appender = new CollectingAppender();
    private Logger traceLogger;

    @BeforeEach
    void attach() {
        traceLogger = (Logger) LoggerFactory.getLogger(RedactingDebugStream.TRACE_LOGGER);
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(appender);
        appender.start();
    }

    @AfterEach
    void detach() {
        traceLogger.detachAppender(appender);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private ImapSessionFactory factory(String mechanisms) {
        return new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            p.setProperty("mail.imaps.auth.mechanisms", mechanisms);
            // Switch Angus's own suppression of the auth exchange off: only our layer may keep credentials out of the
            // trace. (.username/.password stay off: they only gate Angus's own connect diagnostic, which is not ours.)
            p.setProperty("mail.debug.auth", "true");
            return p;
        }, true);
    }

    private void connectAndExamine(Session s, String folder) throws Exception {
        try (Store store = s.getStore("imaps")) {
            store.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), USER, PASSWORD);
            Folder f = store.getFolder(folder);
            f.open(Folder.READ_ONLY);
            f.close(false);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"XOAUTH2", ""})
    void concurrentConnectionsNeverLeakCredentialsNorMixLines(String mechanisms) throws Exception {
        var user = GREEN_MAIL.setUser(USER + "@localhost", USER, PASSWORD);
        GREEN_MAIL.getManagers().getImapHostManager().createMailbox(user, FOLDER_A);
        GREEN_MAIL.getManagers().getImapHostManager().createMailbox(user, FOLDER_B);
        MailAccount account = new MailAccount(new AccountId("a"), "label", null, "127.0.0.1",
                GREEN_MAIL.getImaps().getPort(), Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (ImapSessionFactory f = factory(mechanisms)) {
            Session session = f.sessionFor(
                    PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, account, "acc_test")), account);
            for (int run = 0; run < RUNS; run++) {
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> a = pool.submit(() -> {
                    start.await();
                    connectAndExamine(session, FOLDER_A);
                    return null;
                });
                Future<?> b = pool.submit(() -> {
                    start.await();
                    connectAndExamine(session, FOLDER_B);
                    return null;
                });
                a.get(30, TimeUnit.SECONDS);
                b.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> lines = List.copyOf(appender.messages);
        assertThat(lines).isNotEmpty();
        // AC1: no credential, in clear or as base64 (SASL initial response or continuation line), in the trace.
        assertThat(lines).noneMatch(m -> m.contains(PASSWORD) || m.contains(USER));
        assertThat(lines).noneMatch(m -> m.contains(b64(USER)) || m.contains(b64(PASSWORD))
                || m.contains(b64("user=" + USER + "\1auth=Bearer " + PASSWORD + "\1\1")));
        assertThat(lines).noneMatch(m -> m.matches("[A-Za-z0-9+/=]{16,}") || m.contains(" AUTHENTICATE "));
        // The sanity checks: the opposite error (redacting too much) and a trace that proves nothing.
        assertThat(lines).anyMatch("<redacted>"::equals);
        assertThat(lines).filteredOn(m -> m.contains("EXAMINE marker")).hasSize(2 * RUNS);
        // AC2: every line belongs to exactly one connection.
        assertThat(lines).noneMatch(m -> m.contains(FOLDER_A) && m.contains(FOLDER_B));
    }
}
