package de.alexanderlindhorst.imapmcp.core.download;

/** The decoded byte count passed {@code tools.download.maxBytes} while writing (SPEC 4, step 11). */
public final class DownloadTooLargeException extends RuntimeException {

    DownloadTooLargeException() {
        super("download exceeds the configured limit");
    }
}
