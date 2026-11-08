package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class KeysUnavailableReporterTest {

    @Test
    void everyReportIsCountedAndTheWarnIsOncePerMinute() {
        Logger log = (Logger) LoggerFactory.getLogger(KeysUnavailableReporter.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        log.addAppender(logs);
        try {
            AtomicInteger counted = new AtomicInteger();
            AtomicLong now = new AtomicLong(1_000_000);
            KeysUnavailableReporter r = new KeysUnavailableReporter(new MetricsRecorder() {
                @Override
                public void oauthKeysUnavailable() {
                    counted.incrementAndGet();
                }
            }, now::get);
            for (int i = 0; i < 5; i++) {
                r.report();
            }
            assertThat(counted.get()).isEqualTo(5);
            assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(1);
            now.addAndGet(61_000);
            r.report();
            assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(2);
        } finally {
            log.detachAppender(logs);
        }
    }
}
