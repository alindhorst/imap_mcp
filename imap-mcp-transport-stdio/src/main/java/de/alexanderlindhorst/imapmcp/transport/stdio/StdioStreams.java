package de.alexanderlindhorst.imapmcp.transport.stdio;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Reserves the real stdin/stdout for the MCP protocol (SPEC 5.6). After {@link #capture()}, anything
 * written to {@code System.out} goes to stderr, so stray output cannot corrupt the protocol.
 *
 * <p>Runs before logging is initialised: no logger, no logging calls, and nothing may use
 * {@code System.out} before it is redirected here.
 */
public final class StdioStreams {

    private static ProtocolStreams captured;

    private StdioStreams() {
    }

    /** Idempotent: later calls return the first result without touching the streams again. */
    public static synchronized ProtocolStreams capture() {
        if (captured == null) {
            ProtocolStreams streams = new ProtocolStreams(
                    new FileInputStream(FileDescriptor.in),
                    new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)));
            System.setOut(System.err);
            System.setIn(InputStream.nullInputStream());
            captured = streams;
        }
        return captured;
    }
}
