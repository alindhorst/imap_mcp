package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/**
 * Proves in the normal build that the live smoke classes (#258) are skipped, not failed, when the environment has no
 * credentials. They are launched in-process through the JUnit launcher; this test steps aside itself when the
 * environment does carry smoke variables, so it can never touch a real account.
 */
class SmokeSkipSelfTest {

    @Test
    void everyLiveSmokeClassIsAbortedWithAReasonAndNothingFailsWithoutCredentials() {
        assumeTrue(System.getenv().keySet().stream().noneMatch(k -> k.startsWith("IMAP_MCP_SMOKE_")),
                "IMAP_MCP_SMOKE_* is set in this environment; the self-test must not start live tests");

        for (Class<?> live : List.of(GmailReadLiveSmoke.class, M365ReadLiveSmoke.class, GmailWriteLiveSmoke.class)) {
            SummaryGeneratingListener summary = new SummaryGeneratingListener();
            List<String> abortReasons = new ArrayList<>();
            TestExecutionListener reasons = new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                    if (result.getStatus() == TestExecutionResult.Status.ABORTED) {
                        abortReasons.add(result.getThrowable().map(Throwable::getMessage).orElse(""));
                    }
                }
            };
            LauncherFactory.create().execute(
                    LauncherDiscoveryRequestBuilder.request().selectors(selectClass(live)).build(),
                    summary, reasons);

            var s = summary.getSummary();
            assertThat(s.getTestsFailedCount()).as(live.getSimpleName() + " failed tests").isZero();
            assertThat(s.getContainersFailedCount()).as(live.getSimpleName() + " failed containers").isZero();
            assertThat(s.getTestsSucceededCount()).as(live.getSimpleName() + " ran tests").isZero();
            assertThat(abortReasons).as(live.getSimpleName() + " skip reason").hasSize(1).first().asString()
                    .contains("IMAP_MCP_SMOKE_").contains("skipped");
        }
    }
}
