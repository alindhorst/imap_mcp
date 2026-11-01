package de.alexanderlindhorst.imapmcp.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class MetricsSummaryLogTest {

    static final TenantId TENANT = new TenantId("raw-tenant-id");
    final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    final InMemoryMetricsRecorder rec = new InMemoryMetricsRecorder(logIds, Clock.systemUTC());
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(InMemoryMetricsRecorder.class);
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        appender.start();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        rec.close();
    }

    List<String> lines() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void noActivityLogsNothing() {
        rec.logSummary();
        assertThat(lines()).isEmpty();
    }

    @Test
    void summaryHasOnlyHashedIdsAndIsSkippedWithoutNewActivity() {
        PoolKey key = InMemoryMetricsRecorderTest.key(TENANT, "raw-account-id");
        rec.leaseAcquired(key, Duration.ofMillis(5));
        rec.operation(TENANT, "search_messages", "search", Duration.ofMillis(12));
        rec.authFailure(TENANT);
        rec.busyRejection(key, "mailbox");
        rec.logSummary();

        assertThat(lines()).hasSize(1);
        String line = lines().getFirst();
        assertThat(line).contains(logIds.tenant(TENANT.value())).contains(logIds.account("raw-account-id"))
                .contains("auth_failures=1").contains("search_messages");
        assertThat(line).doesNotContain("raw-tenant-id").doesNotContain("raw-account-id")
                .doesNotContain("imap.example.org").doesNotContain("user@example.org");

        rec.logSummary(); // nothing new since the last one
        assertThat(lines()).hasSize(1);
        rec.timeout(TENANT);
        rec.logSummary();
        assertThat(lines()).hasSize(2);
    }

    @Test
    void scheduledSummaryRunsAndStopsOnClose() throws Exception {
        rec.operation(TENANT, "t", "o", Duration.ofMillis(1));
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1);
        ex.setRemoveOnCancelPolicy(true);
        try {
            rec.start(ex, Duration.ofMillis(20));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (lines().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(lines()).hasSize(1);
            rec.close();
            assertThat(ex.getQueue()).isEmpty(); // the periodic task is cancelled
            assertThat(ex.isShutdown()).isFalse(); // a borrowed scheduler is not shut down
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void privateVirtualThreadSchedulerIsShutDownOnClose() {
        rec.start();
        rec.close();
        rec.close(); // idempotent
    }
}
