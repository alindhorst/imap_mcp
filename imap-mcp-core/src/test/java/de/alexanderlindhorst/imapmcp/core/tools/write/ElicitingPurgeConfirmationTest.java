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
    private final class Script implements ElicitationChannel {
        boolean form = true;
        final List<Duration> windows = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        final List<Map<String, Object>> schemas = new ArrayList<>();
        final List<Answer> answers = new ArrayList<>();
        boolean interrupt;
        String session = "s1";

        @Override
        public String sessionId() {
            return session;
        }

        @Override
        public boolean supportsFormMode() {
            return form;
        }

        @Override
        public Answer ask(String message, Map<String, Object> requestedSchema, Duration window)
                throws InterruptedException {
            order.add("ask");
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
    private ElicitingPurgeConfirmation.TrashState state = new ElicitingPurgeConfirmation.TrashState(1, 1000);
    private final List<String> order = new ArrayList<>();
    private boolean statusFails;
    private final ElicitingPurgeConfirmation confirmation = new ElicitingPurgeConfirmation(clock, (lease, trash) -> {
        order.add("status");
        if (statusFails) {
            throw new jakarta.mail.MessagingException("status refused");
        }
        return state;
    });
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
        confirm(ctx(T1, null), request("a1", "Wo\nrk\r\u202e", longName, 1));
        String m = script.messages.get(0);
        assertThat(m).doesNotContain("\n").doesNotContain("\r").doesNotContain("\u202e");
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

    // ---- session approval (SPEC 4 v3a "Session approval", #285) ----

    private static final ElicitationChannel.Answer ALLOW = new ElicitationChannel.Answer.Accepted(Map.of("allow_for_session", true));

    /** Asks once with "allow for session" true and lets the call begin its mutation, as the group gate would. */
    private void giveApproval(String accountId, int count) throws Exception {
        ToolCallContext c = ctx(T1, null);
        script.answers.add(ALLOW);
        assertThat(confirm(c, request(accountId, "L", "Trash", count))).isEqualTo(new Decision.Confirmed("elicited"));
        assertThat(c.tryBeginMutation()).isTrue();
    }

    private Decision covered(String accountId, int count) throws Exception {
        return confirm(ctx(T1, null), request(accountId, "L", "Trash", count));
    }

    @Test
    void statusIsReadImmediatelyBeforeTheRequestAndAFailedStatusSendsNoRequest() throws Exception {
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        confirm(ctx(T1, null), request("a1", "L", "Trash", 1));
        assertThat(order).containsExactly("status", "ask");
        order.clear();
        statusFails = true;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> confirm(ctx(T1, null), request("a1", "L", "Trash", 1)))
                .isInstanceOf(jakarta.mail.MessagingException.class);
        assertThat(order).containsExactly("status");
    }

    @Test
    void anApprovalExistsOnlyOnceTheCallsMutationBeganAndOnlyForAllowTrue() throws Exception {
        script.answers.add(ALLOW);
        ToolCallContext c = ctx(T1, null);
        confirm(c, request("a1", "L", "Trash", 1));
        // answer given, but the group has not begun its mutation: nothing yet
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("elicited"));
        assertThat(script.messages).hasSize(2);
        c.tryBeginMutation();
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("session_approval"));
        assertThat(script.messages).hasSize(2);
    }

    @Test
    void allowFalseOrAbsentCreatesNoApprovalEvenAfterTheMutationBegan() throws Exception {
        for (Map<String, Object> content : java.util.Arrays.<Map<String, Object>>asList(null, Map.of(),
                Map.of("allow_for_session", false))) {
            ToolCallContext c = ctx(T1, null);
            script.answers.add(new ElicitationChannel.Answer.Accepted(content));
            confirm(c, request("a1", "L", "Trash", 1));
            c.tryBeginMutation();
        }
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("elicited"));
    }

    @Test
    void aCallThatLostTheRaceWithTheDeadlineCreatesNoApproval() throws Exception {
        ToolCallContext c = ctx(T1, null);
        script.answers.add(ALLOW);
        confirm(c, request("a1", "L", "Trash", 1));
        c.cancel(); // the deadline or a cancel was decided first
        assertThat(c.tryBeginMutation()).isFalse();
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("elicited"));
        assertThat(script.messages).hasSize(2);
    }

    @Test
    void anApprovalNeverCoversAnotherAccountOrTenantOrTrashName() throws Exception {
        giveApproval("a1", 1);
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(covered("a2", 1)).isEqualTo(new Decision.Confirmed("elicited")); // other account
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(confirm(ctx(T2, null), request("a1", "L", "Trash", 1))).isEqualTo(new Decision.Confirmed("elicited"));
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(confirm(ctx(T1, null), request("a1", "L", "Papierkorb", 1)))
                .isEqualTo(new Decision.Confirmed("elicited")); // Trash resolves to another name
        assertThat(script.messages).hasSize(4);
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("session_approval")); // still there
    }

    @Test
    void onlyHandlesBelowTheRecordedUidnextAndOfTheRecordedValidityAreCovered() throws Exception {
        state = new ElicitingPurgeConfirmation.TrashState(1, 12); // handles are uid 10, 11 for n = 2, 12 for n = 3
        giveApproval("a1", 2);
        assertThat(covered("a1", 2)).isEqualTo(new Decision.Confirmed("session_approval")); // uids 10, 11
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(covered("a1", 3)).isEqualTo(new Decision.Confirmed("elicited")); // uid 12 arrived later
        // a handle of another UIDVALIDITY is not covered either
        PurgeConfirmation.Request other = request("a1", "L", "Trash", 1);
        PurgeConfirmation.Request reValidated = new PurgeConfirmation.Request(other.account(), other.lease(), "Trash",
                List.of(new WritePreflight.Item(0, "h0", new MessageRef("Trash", 2, 10))));
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(confirm(ctx(T1, null), reValidated)).isEqualTo(new Decision.Confirmed("elicited"));
    }

    @Test
    void theBudgetOf500IsChargedPerCallAndNeverUsedInPart() throws Exception {
        state = new ElicitingPurgeConfirmation.TrashState(1, 100_000);
        giveApproval("a1", 1);
        assertThat(covered("a1", 100)).isEqualTo(new Decision.Confirmed("session_approval")); // 400 left
        assertThat(covered("a1", 100)).isEqualTo(new Decision.Confirmed("session_approval")); // 300 left
        assertThat(covered("a1", 100)).isEqualTo(new Decision.Confirmed("session_approval")); // 200 left
        assertThat(covered("a1", 100)).isEqualTo(new Decision.Confirmed("session_approval")); // 100 left
        assertThat(covered("a1", 100)).isEqualTo(new Decision.Confirmed("session_approval")); // 0 left
        assertThat(script.messages).hasSize(1); // only the creating dialog so far
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("elicited")); // spent: ask again
        assertThat(script.messages).hasSize(2);
    }

    @Test
    void aCallLargerThanTheRemainingBudgetAsksAndLeavesTheBudgetAlone() throws Exception {
        state = new ElicitingPurgeConfirmation.TrashState(1, 100_000);
        giveApproval("a1", 1);
        for (int i = 0; i < 4; i++) {
            assertThat(covered("a1", 100)).isEqualTo(new Decision.Confirmed("session_approval")); // 100 left
        }
        PurgeConfirmation.Request big = request("a1", "L", "Trash", 100);
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(confirm(ctx(T1, null), request("a1", "L", "Trash", 101))).isEqualTo(new Decision.Confirmed("elicited"));
        assertThat(confirm(ctx(T1, null), big)).isEqualTo(new Decision.Confirmed("session_approval")); // 100 still there
    }

    @Test
    void theApprovalEndsAfter15MinutesAndIsNotExtendedByUse() throws Exception {
        giveApproval("a1", 1);
        clock.now = NOW.plus(Duration.ofMinutes(14)).plusSeconds(59);
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("session_approval"));
        clock.now = NOW.plus(Duration.ofMinutes(15));
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("elicited"));
    }

    @Test
    void oneApprovalPerAccountAndANewOneReplacesTheOldWithAFreshBudget() throws Exception {
        state = new ElicitingPurgeConfirmation.TrashState(1, 100_000);
        giveApproval("a1", 1);
        for (int i = 0; i < 4; i++) {
            covered("a1", 100); // 100 left
        }
        // a call the old approval cannot cover asks and creates the replacement
        ToolCallContext c = ctx(T1, null);
        script.answers.add(ALLOW);
        confirm(c, request("a1", "L", "Trash", 150));
        c.tryBeginMutation();
        assertThat(covered("a1", 150)).isEqualTo(new Decision.Confirmed("session_approval"));
        assertThat(covered("a1", 150)).isEqualTo(new Decision.Confirmed("session_approval"));
        assertThat(covered("a1", 150)).isEqualTo(new Decision.Confirmed("session_approval")); // 450 charged
    }

    @Test
    void twoConcurrentCallsTogetherAboveTheBudgetAreNotBothCovered() throws Exception {
        state = new ElicitingPurgeConfirmation.TrashState(1, 100_000);
        giveApproval("a1", 1);
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        List<Decision> out = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    ToolCallContext c = ctx(T1, null);
                    PurgeConfirmation.Request r = request("a1", "L", "Trash", 300);
                    barrier.await();
                    out.add(confirmation.confirm(c, r));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(out).filteredOn(d -> d.equals(new Decision.Confirmed("session_approval"))).hasSize(1);
        assertThat(out).filteredOn(d -> d.equals(new Decision.Confirmed("elicited"))).hasSize(1);
    }

    @Test
    void theApprovalIsUsedBeforeTheCooldownAndADeclineOfAnUncoveredCallKeepsIt() throws Exception {
        state = new ElicitingPurgeConfirmation.TrashState(1, 100_000);
        giveApproval("a1", 1);
        script.answers.add(new ElicitationChannel.Answer.Declined());
        assertThat(covered("a1", 501)).isEqualTo(new Decision.Refused(ElicitationReason.DECLINED));
        assertThat(covered("a1", 501)).isEqualTo(new Decision.Refused(ElicitationReason.RECENTLY_DECLINED));
        assertThat(covered("a1", 10)).isEqualTo(new Decision.Confirmed("session_approval"));
    }

    @Test
    void creatingAnApprovalIsOneAuditLineWithBudgetAndExpiryAndNoNames() throws Exception {
        Logger audit = (Logger) LoggerFactory.getLogger("imapmcp.audit");
        audit.setLevel(Level.INFO);
        logs.start();
        audit.addAppender(logs);
        try {
            giveApproval("a1", 1);
        } finally {
            audit.detachAppender(logs);
        }
        assertThat(logs.list).hasSize(1);
        String m = logs.list.get(0).getFormattedMessage();
        assertThat(m).contains("purge approval created").contains("budget=500")
                .contains("expires_at=" + NOW.plus(Duration.ofMinutes(15))).doesNotContain("Trash");
    }

    // ---- approvals per session (SPEC 4 v3b "Purge over HTTP") ----

    @Test
    void anApprovalBelongsToTheSessionThatGaveItAndNotToAnotherSessionOfTheSameAccount() throws Exception {
        script.answers.add(ALLOW);
        ToolCallContext c = ctx(T1, null);
        confirm(c, request("a1", "L", "Trash", 1));
        c.tryBeginMutation();
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("session_approval"));
        script.session = "s2";
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("elicited")); // asked again
        assertThat(script.messages).hasSize(2);
        script.session = "s1";
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("session_approval")); // s1 still has its own
    }

    @Test
    void sessionEndedDropsExactlyThatSessionsApprovals() throws Exception {
        for (String s : new String[] {"s1", "s2"}) {
            script.session = s;
            script.answers.add(ALLOW);
            ToolCallContext c = ctx(T1, null);
            confirm(c, request("a1", "L", "Trash", 1));
            c.tryBeginMutation();
        }
        assertThat(confirmation.approvalCount()).isEqualTo(2);
        confirmation.sessionEnded("s1");
        assertThat(confirmation.approvalCount()).isEqualTo(1);
        script.session = "s1";
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("elicited"));
        script.session = "s2";
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Confirmed("session_approval"));
        confirmation.sessionEnded("unknown"); // harmless
        confirmation.sessionEnded(null);
    }

    @Test
    void theDeclineCooldownStaysKeyedByTenantAndAccountAcrossSessions() throws Exception {
        script.answers.add(new ElicitationChannel.Answer.Declined());
        confirm(ctx(T1, null), request("a1", "L", "Trash", 1));
        script.session = "s2";
        assertThat(covered("a1", 1)).isEqualTo(new Decision.Refused(ElicitationReason.RECENTLY_DECLINED));
    }

    // ---- bounded maps (SPEC 5.5, #309) ----

    @Test
    void expiredApprovalsAndCooldownsAreDroppedOnLaterCalls() throws Exception {
        script.answers.add(new ElicitationChannel.Answer.Declined());
        confirm(ctx(T1, null), request("a1", "L", "Trash", 1)); // cooldown for t1/a1
        ToolCallContext approving = ctx(T1, null);
        script.answers.add(new ElicitationChannel.Answer.Accepted(Map.of("allow_for_session", true)));
        confirm(approving, request("a2", "L", "Trash", 1));
        approving.tryBeginMutation(); // creates the approval for t1/a2
        assertThat(confirmation.cooldownCount()).isEqualTo(1);
        assertThat(confirmation.approvalCount()).isEqualTo(1);
        clock.now = clock.now.plus(Duration.ofHours(1));
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        confirm(ctx(T2, null), request("a9", "L", "Trash", 1)); // an unrelated key triggers the sweep
        assertThat(confirmation.cooldownCount()).isZero();
        assertThat(confirmation.approvalCount()).isZero();
    }

    @Test
    void manyTenantsLeaveNothingBehindOnceCooldownsAndApprovalsExpire() throws Exception {
        int tenants = 20_000;
        for (int i = 0; i < tenants; i++) {
            script.answers.add(new ElicitationChannel.Answer.Declined());
            confirm(ctx(new TenantId("soak-" + i), null), request("a" + i, "L", "Trash", 1));
        }
        assertThat(confirmation.cooldownCount()).isEqualTo(tenants);
        clock.now = clock.now.plus(Duration.ofHours(1));
        script.answers.add(new ElicitationChannel.Answer.Accepted(null));
        confirm(ctx(T2, null), request("a-last", "L", "Trash", 1)); // any later call sweeps
        assertThat(confirmation.cooldownCount()).isZero();
        assertThat(confirmation.approvalCount()).isZero();
    }
}
