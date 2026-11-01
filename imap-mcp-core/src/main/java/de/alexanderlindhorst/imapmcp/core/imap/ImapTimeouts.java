package de.alexanderlindhorst.imapmcp.core.imap;

import java.time.Duration;
import java.util.Objects;

/** IMAP socket timeouts (SPEC 5.5 defaults 10 s / 60 s / 60 s). */
public record ImapTimeouts(Duration connect, Duration read, Duration write) {

    public static final ImapTimeouts DEFAULTS =
            new ImapTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(60));

    public ImapTimeouts {
        Objects.requireNonNull(connect, "connect");
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(write, "write");
    }
}
