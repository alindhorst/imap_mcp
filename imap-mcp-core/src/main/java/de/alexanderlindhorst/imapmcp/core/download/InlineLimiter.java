package de.alexanderlindhorst.imapmcp.core.download;

/** At most {@code max} embedded downloads at once per replica (SPEC 4 {@code tools.download.inlineConcurrency}). */
public final class InlineLimiter {

    public InlineLimiter(int max) {
        throw new UnsupportedOperationException();
    }

    /** Non-blocking. A true result must be followed by exactly one {@link #release()}. */
    public boolean tryAcquire() {
        throw new UnsupportedOperationException();
    }

    public void release() {
        throw new UnsupportedOperationException();
    }
}
