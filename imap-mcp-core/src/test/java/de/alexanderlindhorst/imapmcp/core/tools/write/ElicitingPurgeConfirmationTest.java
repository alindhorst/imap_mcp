package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ToolError.ElicitationReason;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ElicitationChannel;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tools.write.PurgeConfirmation.Decision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** SPEC 4 v3a "Confirmation (step 9)": request text and schema, answer mapping, cooldown, wait window. */
class ElicitingPurgeConfirmationTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final TenantId T2 = new TenantId("t2");
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    /** Scripted channel: records every request, answers with the next scripted answer. */
    private static final class Script implements ElicitationChannel {
        boolean form = true;
        final List<Duration> windows = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<Map<String, Object>> schemas = new ArrayList<>();
        final List<Answer> answers = new ArrayList<>();
        boolean interrupt;

        @Override
        public boolean supportsFormMode() {
            return form;
        }

        @Override
        public Answer ask(String message, Map<String, Object> requestedSchema, Duration window)
                throws InterruptedException {
            messages.add(message);
            schemas.add(requestedSchema);
            windows.add(window);
            if (interrupt) {
                throw new InterruptedException();
            }
            return answers.remove(0);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final Script script = new Script();
    private final ElicitingPurgeConfirmation confirmation = new ElicitingPurgeConfirmation(clock);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger root;

    private static final class MutableClock extends Clock {
        Instant now = NOW;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private ToolCallContext ctx(TenantId t, Duration remaining) {
        ToolCallContext c = remaining == null ? new ToolCallContext(new AccountContext(t), "rid")
                : new ToolCallContext(new AccountContext(t), "rid", NOW.plus(remaining));
        c.bindElicitation(script);
        return c;
    }

    private static PurgeConfirmation.Request request(String accountId, String label, String trash, int n) {
        MailAccount a = new MailAccount(new AccountId(accountId), label, null, "h", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "u", new SecretRef("env:X"), false,
                true);
        List<WritePreflight.Item> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add(new WritePreflight.Item(i, "h" + i, new MessageRef(trash, 1, 10 + i)));
        }
        return new PurgeConfirmation.Request(new ResolvedAccount(T1, a, "acc_1"), mock(ImapLease.class), trash, items);
    }

    private Decision confirm(ToolCallContext c, PurgeConfirmation.Request r) throws Exception {
        return confirmation.confirm(c, r);
    }

    @AfterEach
    void detach() {
        if (root != null) {
            root.detachAppender(logs);
        }
    }

    // ---- availability (step 3) ----

    @Test
    void availableOnlyWithAChannelThatSupportsFormMode() {
        assertThat(confirmation.available(new ToolCallContext(new AccountContext(T1), "r"))).isFalse();
        script.form = false;
        assertThat(confirmation.available(ctx(T1, null))).isFalse();
        script.form = true;
        assertThat(confirmation.available(ctx(T1, null))).isTrue();
    }

    // ---- the request ----

    @Test
    void messageAndSchemaAreTheFixedTemplate() throws Exception {
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        confirm(ctx(T1, Duration.ofSeconds(90)), request("a1", "Work", "Trash", 3));
        script.answers.add(new ElicitationChannel.Answer.Accepted(Map.of()));
        confirm(ctx(T1, Duration.ofSeconds(90)), request("a1", "Work", "Trash", 1));
        assertThat(script.messages).containsExactly(
                "Permanently delete 3 messages from the Trash folder \"Trash\" of account \"Work\"? This cannot be undone.",
                "Permanently delete 1 message from the Trash folder \"Trash\" of account \"Work\"? This cannot be undone.");
        Map<String, Object> expected = Map.of("type", "object", "properties", Map.of("allow_for_session",
                Map.of("type", "boolean", "title", "Allow for this session", "description",
                        "Also allow permanent deletion of messages already in this Trash folder for the next 15 "
                                + "minutes, up to 500 messages, without asking again.",
                        "default", false)));
        assertThat(script.schemas.get(0)).isEqualTo(expected);
    }

    @Test
    void trashNameAndLabelAreSingleLineAndCappedAt200CodePoints() throws Exception {
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        String longName = "T" + "x".repeat(300);
        confirm(ctx(T1, null), request("a1", "Wo\nrk\r‮", longName, 1));
        String m = script.messages.get(0);
        assertThat(m).doesNotContain("\n").doesNotContain("\r").doesNotContain("‮");
        assertThat(m).contains("of account \"Wo rk\"");
        assertThat(m).doesNotContain("x".repeat(201)).contains("x".repeat(150));
    }

    // ---- answers ----

    @Test
    void acceptWithoutContentOrWithAllowForSessionIsElicited() throws Exception {
        for (Map<String, Object> content : java.util.Arrays.<Map<String, Object>>asList(null, Map.of(),
                Map.of("allow_for_session", false), Map.of("allow_for_session", true))) {
            script.answers.add(new ElicitationChannel.Answer.Accepted(content));
            assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 1)))
                    .isEqualTo(new Decision.Confirmed("elicited"));
        }
    }

    @Test
    void acceptWithContentOutsideTheSchemaIsInvalidAnswer() throws Exception {
        List<Map<String, Object>> bad = List.of(Map.of("other", true), Map.of("allow_for_session", "yes"),
                Map.of("allow_for_session", true, "x", 1), Map.of("allow_for_session", 1));
        for (Map<String, Object> content : bad) {
            script.answers.add(new ElicitationChannel.Answer.Accepted(content));
            assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 1)))
                    .as(content.toString()).isEqualTo(new Decision.Refused(ElicitationReason.INVALID_ANSWER));
        }
    }

    @Test
    void declineCancelTimeoutAndErrorMapToTheirReasons() throws Exception {
        script.answers.add(new ElicitationChannel.Answer.TimedOut());
        assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 1)))
                .isEqualTo(new Decision.Refused(ElicitationReason.TIMEOUT));
        script.answers.add(new ElicitationChannel.Answer.Failed());
        assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 1)))
                .isEqualTo(new Decision.Refused(ElicitationReason.ERROR));
        script.answers.add(new ElicitationChannel.Answer.Declined());
        assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 1)))
                .isEqualTo(new Decision.Refused(ElicitationReason.DECLINED));
        clock.now = NOW.plusSeconds(61);
        script.answers.add(new ElicitationChannel.Answer.Cancelled());
        assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 1)))
                .isEqualTo(new Decision.Refused(ElicitationReason.CANCELLED));
    }

    // ---- cooldown ----

    @Test
    void afterDeclineOrCancelFurtherCallsWithin60sSendNoRequest() throws Exception {
        script.answers.add(new ElicitationChannel.Answer.Declined());
        confirm(ctx(T1, null), request("a1", "L", "Trash", 1));
        clock.now = NOW.plusSeconds(59);
        assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 1)))
                .isEqualTo(new Decision.Refused(ElicitationReason.RECENTLY_DECLINED));
        assertThat(script.messages).hasSize(1);
        // a different account and a different tenant are not affected
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(confirm(ctx(T1, null), request("a2", "L", "Trash", 1))).isInstanceOf(Decision.Confirmed.class);
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(confirm(ctx(T2, null), request("a1", "L", "Trash", 1))).isInstanceOf(Decision.Confirmed.class);
        // after the 60 s the dialog may open again
        clock.now = NOW.plusSeconds(61);
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 1))).isInstanceOf(Decision.Confirmed.class);
    }

    @Test
    void cancelStartsTheCooldownToo() throws Exception {
        script.answers.add(new ElicitationChannel.Answer.Cancelled());
        confirm(ctx(T1, null), request("a1", "L", "Trash", 1));
        assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 1)))
                .isEqualTo(new Decision.Refused(ElicitationReason.RECENTLY_DECLINED));
    }

    @Test
    void timeoutErrorAndInvalidAnswerStartNoCooldown() throws Exception {
        script.answers.add(new ElicitationChannel.Answer.TimedOut());
        script.answers.add(new ElicitationChannel.Answer.Failed());
        script.answers.add(new ElicitationChannel.Answer.Accepted(Map.of("x", 1)));
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        for (int i = 0; i < 3; i++) {
            confirm(ctx(T1, null), request("a1", "L", "Trash", 1));
        }
        assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 1))).isInstanceOf(Decision.Confirmed.class);
    }

    // ---- window ----

    @Test
    void windowEndsTwentySecondsBeforeTheDeadline() throws Exception {
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        confirm(ctx(T1, Duration.ofSeconds(100)), request("a1", "L", "Trash", 1));
        assertThat(script.windows.get(0)).isEqualTo(Duration.ofSeconds(80));
    }

    @Test
    void lessThanTenSecondsOfWindowSendsNoRequestAndIsExpired() throws Exception {
        assertThat(confirm(ctx(T1, Duration.ofSeconds(29)), request("a1", "L", "Trash", 1)))
                .isEqualTo(new Decision.Expired());
        assertThat(script.messages).isEmpty();
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(confirm(ctx(T1, Duration.ofSeconds(30)), request("a1", "L", "Trash", 1)))
                .isInstanceOf(Decision.Confirmed.class);
        assertThat(script.windows.get(0)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void interruptedWaitIsExpiredNotNotConfirmedAndKeepsTheInterruptFlag() throws Exception {
        script.interrupt = true;
        try {
            assertThat(confirm(ctx(T1, Duration.ofSeconds(90)), request("a1", "L", "Trash", 1)))
                    .isEqualTo(new Decision.Expired());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        // and no cooldown from it
        script.interrupt = false;
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(confirm(ctx(T1, Duration.ofSeconds(90)), request("a1", "L", "Trash", 1)))
                .isInstanceOf(Decision.Confirmed.class);
    }

    // ---- no logging of dialog or answer ----

    @Test
    void nothingIsLoggedAtAnyLevelNotTheDialogNorTheAnswer() throws Exception {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        root.setLevel(Level.TRACE);
        logs.start();
        root.addAppender(logs);
        try {
            script.answers.add(new ElicitationChannel.Answer.Accepted(Map.of("allow_for_session", "SECRET")));
            confirm(ctx(T1, null), request("a1", "LABEL-CANARY", "TRASH-CANARY", 1));
            script.answers.add(new ElicitationChannel.Answer.Declined());
            confirm(ctx(T1, null), request("a1", "LABEL-CANARY", "TRASH-CANARY", 1));
        } finally {
            root.setLevel(before);
        }
        assertThat(logs.list).isEmpty();
    }
}
