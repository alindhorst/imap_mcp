package de.alexanderlindhorst.imapmcp.core.download;

import java.util.concurrent.Semaphore;

/** At most {@code max} embedded downloads at once per replica (SPEC 4 {@code tools.download.inlineConcurrency}). */
public final class InlineLimiter {

    private final Semaphore permits;

    public InlineLimiter(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max");
        }
        this.permits = new Semaphore(max);
    }

    /** Non-blocking. A true result must be followed by exactly one {@link #release()}. */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }
}
