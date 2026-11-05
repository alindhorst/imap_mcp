package de.alexanderlindhorst.imapmcp.it;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;

/**
 * Runs actions at defined IMAP commands of the in-process server, on the thread that writes the command. The opt-in
 * IMAP trace ({@code imap.trace}) is written on the worker thread, and Angus Mail traces a command before its bytes
 * are written to the socket, so an action that installs or removes a Toxiproxy toxic is in place before that command
 * reaches the proxy: no sleeps, no race with the call. The trace content (test data only) never leaves the test JVM.
 * Same technique as in {@code WriteInterruptionIT}, as a class of its own for the v3a ITs.
 */
final class TraceScript extends AppenderBase<ILoggingEvent> {

    private static final String TRACE_LOGGER = "imapmcp.imap.trace";

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    private record Step(Predicate<String> match, Action action) {
    }

    private final Deque<Step> steps = new ConcurrentLinkedDeque<>();
    private final ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TRACE_LOGGER);

    TraceScript attach() {
        setContext(logger.getLoggerContext());
        start();
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false); // keep the (test data) trace out of the console
        logger.addAppender(this);
        return this;
    }

    void detach() {
        logger.detachAppender(this);
        logger.setLevel(null);
        logger.setAdditive(true);
        stop();
    }

    /** At the next traced line (tag included) that satisfies {@code match}. */
    TraceScript onLine(Predicate<String> match, Action action) {
        steps.add(new Step(match, action));
        return this;
    }

    /** At the next client command that starts with {@code command} (a verb sequence such as {@code UID EXPUNGE}). */
    TraceScript on(String command, Action action) {
        return onLine(line -> line.matches("[A-Z]+\\d+ " + command + "\\b.*"), action);
    }

    boolean done() {
        return steps.isEmpty();
    }

    @Override
    protected void append(ILoggingEvent event) {
        Step next = steps.peekFirst();
        if (next != null && next.match().test(event.getFormattedMessage())) {
            steps.pollFirst();
            try {
                next.action().run();
            } catch (Exception e) {
                throw new IllegalStateException("trace script action failed", e);
            }
        }
    }
}
