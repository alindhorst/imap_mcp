package de.alexanderlindhorst.imapmcp.core.testsupport;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;

/**
 * Reads what a test captured from the global IMAP trace logger ({@code imapmcp.imap.trace}) and keeps the protocol
 * lines only. Jakarta Mail's own session chatter ({@code DEBUG: ...}, {@code DEBUG IMAPS: ...}, for example
 * "IMAPStore cleanup, not connected") is not IMAP traffic, and it also arrives from Stores of other tests that are
 * closed or collected while this test's appender is attached.
 */
public final class WireTrace {

    private WireTrace() {
    }

    public static List<String> lines(ListAppender<ILoggingEvent> trace) {
        return trace.list.stream().map(ILoggingEvent::getFormattedMessage).filter(l -> !l.startsWith("DEBUG")).toList();
    }
}
