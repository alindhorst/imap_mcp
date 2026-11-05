package de.alexanderlindhorst.imapmcp.core.download;

/**
 * A local storage failure of the download sink (SPEC 5.4 "Errors"). The message is the reason only; the cause may
 * carry a path (hence the email-derived name) and must be logged by class only, never by message.
 */
public final class DownloadStorageException extends RuntimeException {

    /** Maps to {@code LOCAL_STORAGE_ERROR} {@code details.reason} in lower case. */
    public enum Reason { DIRECTORY_UNAVAILABLE, NAME_EXHAUSTED, NO_SPACE, IO_ERROR }

    private final Reason reason;

    DownloadStorageException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
