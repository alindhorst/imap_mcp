package de.alexanderlindhorst.imapmcp.it;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;

/**
 * Taps the opt-in IMAP trace logger ({@code imap.trace: true}) of an in-process server: records the client commands
 * (tag stripped, see {@link ImapWireAudit#clientCommand}) and runs scripted actions synchronously on the worker thread
 * at defined commands. Angus traces a command before its bytes are written, so an action (e.g. adding a Toxiproxy
 * toxic) is in place before the command reaches the proxy: no sleeps, no race with the call. The trace content (test
 * data only) never leaves the test JVM (the logger is detached from the console while tapped).
 */
final class TraceTap extends AppenderBase<ILoggingEvent> {

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    private record Step(Predicate<String> match, Action action) {
    }

    private final Logger logger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
    private final List<String> commands = new ArrayList<>();
    private final Deque<Step> steps = new ConcurrentLinkedDeque<>();

    TraceTap attach() {
        setContext(logger.getLoggerContext());
        start();
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
        logger.addAppender(this);
        return this;
    }

    void detach() {
        logger.detachAppender(this);
        logger.setLevel(null);
        logger.setAdditive(true);
        stop();
    }

    /** Runs {@code action} when the next not yet consumed step's command (e.g. {@code UID MOVE}) is traced. */
    TraceTap on(String command, Action action) {
        steps.add(new Step(c -> c.equals(command) || c.startsWith(command + " "), action));
        return this;
    }

    boolean done() {
        return steps.isEmpty();
    }

    synchronized int mark() {
        return commands.size();
    }

    synchronized List<String> since(int mark) {
        return List.copyOf(commands.subList(mark, commands.size()));
    }

    synchronized List<String> all() {
        return List.copyOf(commands);
    }

    @Override
    protected void append(ILoggingEvent event) {
        String command = ImapWireAudit.clientCommand(event.getFormattedMessage()).orElse(null);
        if (command == null) {
            return;
        }
        synchronized (this) {
            commands.add(command);
        }
        Step next = steps.peekFirst();
        if (next != null && next.match().test(command)) {
            steps.pollFirst();
            try {
                next.action().run();
            } catch (Exception e) {
                throw new IllegalStateException("trace script action failed", e);
            }
        }
    }
}
