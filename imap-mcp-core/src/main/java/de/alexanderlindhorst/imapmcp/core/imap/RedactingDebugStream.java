package de.alexanderlindhorst.imapmcp.core.imap;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug sink for Jakarta Mail: complete lines go to the logger {@value #TRACE_LOGGER} at DEBUG, never to stdout.
 * Lines carrying credentials (LOGIN, AUTHENTICATE, SASL continuations and the client response after one) are
 * replaced by {@code <redacted>}. Defence in depth: Angus already suppresses auth lines unless
 * {@code mail.debug.auth} is set.
 */
public final class RedactingDebugStream extends PrintStream {

    public static final String TRACE_LOGGER = "imapmcp.imap.trace";
    static final String REDACTED = "<redacted>";
    /** Bounds memory for huge FETCH literals; a longer line is emitted in chunks. */
    static final int MAX_LINE_BYTES = 64 * 1024;

    private static final Pattern AUTH_COMMAND = Pattern.compile(" (login|authenticate)( |$)", Pattern.CASE_INSENSITIVE);

    public RedactingDebugStream() {
        this(lineSink(LoggerFactory.getLogger(TRACE_LOGGER)));
    }

    RedactingDebugStream(Consumer<String> sink) {
        this(new LineOutput(sink));
    }

    private RedactingDebugStream(LineOutput out) {
        super(out, false, StandardCharsets.UTF_8); // no autoflush: a flush would emit partial lines
    }

    private static Consumer<String> lineSink(Logger logger) {
        return line -> logger.debug("{}", line);
    }

    private static final class LineOutput extends OutputStream {
        private final Consumer<String> sink;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private boolean redactNextClientLine;

        LineOutput(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit();
            } else {
                buf.write(b);
                if (buf.size() >= MAX_LINE_BYTES) {
                    emit();
                }
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
                write(b[i]);
            }
        }

        @Override
        public synchronized void flush() {
            if (buf.size() > 0) {
                emit();
            }
        }

        private void emit() {
            String line = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            sink.accept(redact(line));
        }

        private String redact(String line) {
            String body = line.startsWith("S: ") || line.startsWith("C: ") ? line.substring(3) : line;
            if (redactNextClientLine) {
                redactNextClientLine = false;
                return REDACTED;
            }
            if (body.equals("+") || body.startsWith("+ ")) {
                redactNextClientLine = true;
                return REDACTED;
            }
            if (AUTH_COMMAND.matcher(body).find()) {
                return REDACTED;
            }
            return line;
        }
    }
}
