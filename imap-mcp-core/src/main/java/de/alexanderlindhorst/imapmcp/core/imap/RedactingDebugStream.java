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

    private final LineOutput lineOut;

    private RedactingDebugStream(LineOutput out) {
        super(out, false, StandardCharsets.UTF_8); // no autoflush: a flush would emit partial lines
        this.lineOut = out;
    }

    /**
     * Angus traces complete lines through {@code println}. They are handled here in one call, on the calling thread
     * and without the shared byte buffer, so lines of concurrent connections can neither interleave nor inherit
     * each other's redaction state. The byte path ({@code write}) stays as a fallback.
     */
    @Override
    public void println(String x) {
        lineOut.logicalLines(String.valueOf(x));
    }

    @Override
    public void println(Object x) {
        lineOut.logicalLines(String.valueOf(x));
    }

    @Override
    public void println() {
        lineOut.logicalLines("");
    }

    private static Consumer<String> lineSink(Logger logger) {
        return line -> logger.debug("{}", line);
    }

    private static final class LineOutput extends OutputStream {
        private final Consumer<String> sink;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        /**
         * Set by a SASL continuation; per thread because one Session (and so one stream) serves several connections,
         * and a connection is only ever driven by one thread at a time: continuation and client reply share a thread.
         */
        private final ThreadLocal<boolean[]> redactNextClientLine = ThreadLocal.withInitial(() -> new boolean[1]);
        private boolean midLine;
        private boolean currentLineRedacted;
        private boolean continuedChunk;

        LineOutput(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                emit(true);
            } else {
                buf.write(b);
                if (buf.size() >= MAX_LINE_BYTES) {
                    emit(false);
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
                emit(false);
            }
        }

        /** Splits on {@code \n}, strips {@code \r}, decides once per line and emits it in chunks of at most MAX_LINE_BYTES. */
        void logicalLines(String text) {
            for (String line : text.split("\n", -1)) {
                String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
                boolean redacted = decide(l);
                int pos = 0;
                do {
                    int end = Math.min(l.length(), pos + MAX_LINE_BYTES);
                    if (end < l.length() && Character.isHighSurrogate(l.charAt(end - 1))) {
                        end--;
                    }
                    sink.accept(redacted ? REDACTED : l.substring(pos, end));
                    pos = end;
                } while (pos < l.length());
            }
        }

        /** Emits the buffered bytes; the redaction decision is made on the first chunk of a logical line only. */
        private void emit(boolean endOfLine) {
            String line = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (!midLine) {
                currentLineRedacted = decide(line);
            }
            midLine = !endOfLine;
            if (endOfLine && line.isEmpty() && continuedChunk) {
                continuedChunk = false;
                return;
            }
            continuedChunk = !endOfLine;
            sink.accept(currentLineRedacted ? REDACTED : line);
        }

        private boolean decide(String line) {
            String body = line.startsWith("S: ") || line.startsWith("C: ") ? line.substring(3) : line;
            boolean[] flag = redactNextClientLine.get();
            if (flag[0]) {
                flag[0] = false;
                return true;
            }
            if (body.equals("+") || body.startsWith("+ ")) {
                flag[0] = true;
                return true;
            }
            return AUTH_COMMAND.matcher(body).find();
        }
    }
}
