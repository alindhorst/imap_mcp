package de.alexanderlindhorst.imapmcp.core.net;

/**
 * Remembers, per thread, that {@link PinnedSocketFactory} refused a connect. Angus drops the cause when a folder
 * connection fails (it throws a bare {@code MessagingException("connection failure")}), so the error mapper uses
 * this marker to still report {@code address_blocked}. Tool calls run on a thread of their own, so a marker cannot
 * leak into another call; it expires after a short time anyway.
 */
public final class RefusalMarker {

    private static final long WINDOW_NANOS = 30_000_000_000L;
    private static final ThreadLocal<Long> LAST = new ThreadLocal<>();

    private RefusalMarker() {
    }

    public static void mark() {
        LAST.set(System.nanoTime());
    }

    /** True if this thread was refused within the window; always clears the marker. */
    public static boolean takeRecent() {
        Long t = LAST.get();
        LAST.remove();
        return t != null && System.nanoTime() - t < WINDOW_NANOS;
    }
}
