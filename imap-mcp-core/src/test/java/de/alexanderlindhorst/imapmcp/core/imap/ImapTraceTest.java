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
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

class ImapTraceTest {

    private static final String WARN_TEXT = "IMAP protocol trace enabled: the log may contain message content "
            + "(subjects, addresses, bodies). Disable after diagnosis.";
    private static final String USER = "tracemarkuser";
    private static final String PASSWORD = "tracemarkpass";
    private static final TenantId TENANT = new TenantId("t1");

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private final OwnThreadEvents traceEvents = new OwnThreadEvents();
    private final OwnThreadEvents factoryEvents = new OwnThreadEvents();
    private Logger traceLogger;
    private Logger factoryLogger;
    private PrintStream originalOut;
    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();

    /**
     * Collects the events of the test thread only, thread-safely. The loggers are global: a leftover thread of another
     * test may log to them at any time, which a plain {@code ListAppender} (an ArrayList) does not survive (#207).
     */
    private static final class OwnThreadEvents extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> list = new CopyOnWriteArrayList<>();
        private final String thread = Thread.currentThread().getName();

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getThreadName().equals(thread)) {
                list.add(event);
            }
        }
    }

    @BeforeEach
    void attach() {
        traceLogger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(traceEvents);
        traceEvents.start();
        factoryLogger = (Logger) LoggerFactory.getLogger(ImapSessionFactory.class);
        factoryLogger.setLevel(Level.INFO);
        factoryLogger.addAppender(factoryEvents);
        factoryEvents.start();
        originalOut = System.out;
        System.setOut(new PrintStream(capturedOut));
    }

    @AfterEach
    void detach() {
        System.setOut(originalOut);
        traceLogger.detachAppender(traceEvents);
        factoryLogger.detachAppender(factoryEvents);
    }

    private static MailAccount account() {
        return new MailAccount(new AccountId("a"), "label", null, "127.0.0.1",
                GREEN_MAIL.getImaps().getPort(), Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
    }

    private ImapSessionFactory factory(boolean trace) {
        return new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, trace);
    }

    private Session session(ImapSessionFactory f) {
        MailAccount a = account();
        return f.sessionFor(PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, a, "acc_test")), a);
    }

    private void login(Session s) throws Exception {
        GREEN_MAIL.setUser(USER + "@localhost", USER, PASSWORD);
        try (Store store = s.getStore("imaps")) {
            store.connect("127.0.0.1", GREEN_MAIL.getImaps().getPort(), USER, PASSWORD);
        }
    }

    private List<String> traceMessages() {
        return traceEvents.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void debugOutIsAlwaysRedactingStreamAndTraceOffIsSilent() throws Exception {
        traceOffScenario();
    }

    /**
     * The trace logger is global, so a leftover thread of another test (a store being closed) can log to it while this
     * test's appender is attached. The collector must neither fail on that nor count the foreign events (#207).
     */
    @Test
    void traceOffStaysSilentWhileAnotherThreadLogsToTheTraceLogger() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Thread foreign = Thread.ofPlatform().name("foreign-tracer").start(() -> {
            while (!stop.get()) {
                traceLogger.debug("FOREIGN-EVENT");
            }
        });
        try {
            for (int i = 0; i < 20; i++) {
                traceOffScenario();
            }
        } finally {
            stop.set(true);
            foreign.join();
        }
    }

    private void traceOffScenario() throws Exception {
        try (ImapSessionFactory f = factory(false)) {
            Session s = session(f);
            assertThat(s.getDebugOut()).isInstanceOf(RedactingDebugStream.class);
            assertThat(s.getDebug()).isFalse();
            traceEvents.list.clear();
            login(s);
            assertThat(traceEvents.list).isEmpty();
            assertThat(factoryEvents.list).noneMatch(e -> e.getFormattedMessage().contains("trace enabled"));
            assertThat(capturedOut.size()).isZero();
        }
    }

    @Test
    void traceOnLogsRedactedTraceAndOneWarn() throws Exception {
        try (ImapSessionFactory f = factory(true)) {
            Session s = session(f);
            assertThat(s.getDebugOut()).isInstanceOf(RedactingDebugStream.class);
            assertThat(s.getDebug()).isTrue();
            login(s);
            MailAccount other = new MailAccount(new AccountId("b"), "l", null, "127.0.0.1",
                    GREEN_MAIL.getImaps().getPort(), Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
            f.sessionFor(PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, other, "acc_test")), other);

            assertThat(traceEvents.list).isNotEmpty().allMatch(e -> e.getLevel() == Level.DEBUG);
            assertThat(traceMessages()).noneMatch(m -> m.contains(PASSWORD) || m.contains(USER));
            assertThat(factoryEvents.list.stream().filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)).containsExactly(WARN_TEXT);
            assertThat(capturedOut.size()).isZero();
        }
    }
}
