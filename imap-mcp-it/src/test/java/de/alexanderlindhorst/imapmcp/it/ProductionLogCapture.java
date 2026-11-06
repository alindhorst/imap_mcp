package de.alexanderlindhorst.imapmcp.it;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/**
 * Captures what the server would log with {@code log.level: DEBUG}: the production {@code logback.xml} of the app
 * module (with its level caps for libraries, which are part of what is audited) is loaded at root level DEBUG, and a
 * list appender sees every event. {@link #close()} restores the default logging configuration of the test JVM.
 * Events of the in-process fixture servers ({@code com.icegreen}, the GreenMail IMAP server, which logs the wire it
 * receives) are not the system under test and are left out of {@link #ours()}.
 */
final class ProductionLogCapture implements AutoCloseable {

    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private final Logger root;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    ProductionLogCapture(Path logDir) throws JoranException {
        context.reset();
        context.putProperty("IMAP_MCP_LOG_DIR", logDir.toString());
        context.putProperty("IMAP_MCP_LOG_LEVEL", "DEBUG");
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(ProductionLogCapture.class.getResource("/logback.xml"));
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
    }

    List<ILoggingEvent> ours() {
        return appender.list.stream().filter(e -> !e.getLoggerName().startsWith("com.icegreen")).toList();
    }

    /** Message, MDC values and stack trace of every event of {@link #ours()}, one block per event. */
    String text() {
        return ours().stream().map(ProductionLogCapture::text).collect(Collectors.joining("\n"));
    }

    private static String text(ILoggingEvent e) {
        StringBuilder sb = new StringBuilder(e.getFormattedMessage());
        e.getMDCPropertyMap().forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        if (e.getThrowableProxy() != null) {
            sb.append('\n').append(ThrowableProxyUtil.asString(e.getThrowableProxy()));
        }
        return sb.toString();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        context.reset();
        try {
            new ContextInitializer(context).autoConfig();
        } catch (JoranException e) {
            throw new IllegalStateException("could not restore the default logging configuration", e);
        }
    }
}
