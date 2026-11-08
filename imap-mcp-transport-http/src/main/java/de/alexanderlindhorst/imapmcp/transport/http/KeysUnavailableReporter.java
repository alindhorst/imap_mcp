package de.alexanderlindhorst.imapmcp.transport.http;

import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports a request that was answered {@code 503} because the issuer's signing keys are unavailable (SPEC 5.3
 * "Key availability", D16): every one is counted ({@code oauth_keys_unavailable}), and a WARN is written at most
 * once per minute for the whole replica (an unreachable identity provider fails every request). Shared by the
 * {@code /mcp} token filter and the linking surface's callback. Never logs token content.
 */
public final class KeysUnavailableReporter {

    private static final Logger LOG = LoggerFactory.getLogger(KeysUnavailableReporter.class);
    private static final long WARN_INTERVAL_MS = 60_000;

    private final MetricsRecorder metrics;
    private final LongSupplier clockMillis;
    private final AtomicLong lastWarn = new AtomicLong(Long.MIN_VALUE);

    public KeysUnavailableReporter(MetricsRecorder metrics, LongSupplier clockMillis) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** Counts nothing and warns normally: for callers without a metrics recorder. */
    public static KeysUnavailableReporter withoutMetrics() {
        return new KeysUnavailableReporter(MetricsRecorder.NOOP, System::currentTimeMillis);
    }

    public void report() {
        metrics.oauthKeysUnavailable();
        long now = clockMillis.getAsLong();
        long last = lastWarn.get();
        if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS) && lastWarn.compareAndSet(last, now)) {
            LOG.warn("Token not validated: {}", InvalidTokenException.KEYS_UNAVAILABLE);
        }
    }
}
