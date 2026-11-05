package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.LeaseTestSeams;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tools.write.FolderGroupRunner.AbsentStatus;
import de.alexanderlindhorst.imapmcp.core.tools.write.FolderGroupRunner.HookPoint;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.Test;

/** Group runner against scripted folders (no server): order, pre-check, budget, mark, partial results. */
class FolderGroupRunnerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long VALIDITY = 7;

    private final IMAPStore store = mock(IMAPStore.class);
    private final Map<String, IMAPFolder> folders = new HashMap<>();
    private Instant deadline = NOW.plus(Duration.ofSeconds(60));
    private Instant now = NOW;
    private ToolCallContext ctx;
    private ImapLease lease;
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    };

    private void init() throws Exception {
        ctx = new ToolCallContext(new AccountContext(new TenantId("t1")), "rid", deadline);
        lease = LeaseTestSeams.lease(store, ctx);
        when(store.getFolder(anyString())).thenAnswer(inv -> folders.get(inv.<String>getArgument(0)));
    }

    /** A folder holding the given UIDs. */
    private IMAPFolder folder(String name, long validity, long... present) throws Exception {
        IMAPFolder f = mock(IMAPFolder.class);
        when(f.exists()).thenReturn(true);
        when(f.getUIDValidity()).thenReturn(validity);
        when(f.isOpen()).thenReturn(true);
        when(f.getMessagesByUID(any(long[].class))).thenAnswer(inv -> {
            long[] asked = inv.getArgument(0);
            Message[] out = new Message[asked.length];
            for (int i = 0; i < asked.length; i++) {
                for (long p : present) {
                    if (p == asked[i]) {
                        out[i] = mock(Message.class);
                    }
                }
            }
            return out;
        });
        folders.put(name, f);
        return f;
    }

    private static WritePreflight.Group group(String folder, long... uids) {
        List<WritePreflight.Item> items = new ArrayList<>();
        for (long u : uids) {
            String h = "h-" + folder + "-" + u;
            items.add(new WritePreflight.Item(0, h, new MessageRef(folder, VALIDITY, u)));
        }
        return new WritePreflight.Group(folder, items);
    }

    private static String h(String folder, long uid) {
        return "h-" + folder + "-" + uid;
    }

    private static FolderGroupRunner.MutationStep okStep(List<List<Long>> seen) {
        return (folder, present, gate) -> {
            seen.add(present.stream().map(i -> i.ref().uid()).toList());
            gate.beforeWrite();
            return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
        };
    }

    private static Map<String, ItemStatus> statuses(List<BulkResult.Item> items) {
        Map<String, ItemStatus> m = new HashMap<>();
        items.forEach(i -> m.put(i.handle(), i.status()));
        return m;
    }

    private boolean broken() throws Exception {
        var f = ImapLease.class.getDeclaredField("broken");
        f.setAccessible(true);
        return ((AtomicBoolean) f.get(lease)).get();
    }

    private FolderGroupRunner runner() {
        return new FolderGroupRunner(clock);
    }

    private static MessagingException connectionLoss() {
        return new MessagingException("io", new IOException("reset"));
    }

    private FolderGroupRunner runnerWithHook(HookPoint at, int groupIndex, int commandIndex) {
        return new FolderGroupRunner(clock, (p, g, c) -> {
            if (p == at && g == groupIndex && c == commandIndex) {
                throw connectionLoss();
            }
        });
    }

    @Test
    void groupsRunInOrderPreCheckHandsOnlyPresentUidsAndEveryFolderIsClosedWithoutExpunge() throws Exception {
        init();
        IMAPFolder a = folder("A", VALIDITY, 1, 3);
        IMAPFolder b = folder("B", VALIDITY, 5);
        List<List<Long>> seen = new ArrayList<>();
        var out = runner().run(lease, ctx, List.of(group("A", 1, 2, 3), group("B", 5)), AbsentStatus.ALREADY_GONE,
                okStep(seen));
        assertThat(seen).containsExactly(List.of(1L, 3L), List.of(5L));
        assertThat(statuses(out)).containsEntry(h("A", 1), ItemStatus.OK).containsEntry(h("A", 2), ItemStatus.ALREADY_GONE)
                .containsEntry(h("A", 3), ItemStatus.OK).containsEntry(h("B", 5), ItemStatus.OK);
        verify(a).open(Folder.READ_WRITE);
        verify(a).close(false);
        verify(b).close(false);
        verify(a, never()).expunge();
        verify(a, never()).close(true);
        assertThat(broken()).isFalse();
    }

    @Test
    void absentUidIsNotFoundForCopyAndSetFlags() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        var out = runner().run(lease, ctx, List.of(group("A", 1, 2)), AbsentStatus.NOT_FOUND, okStep(new ArrayList<>()));
        assertThat(statuses(out)).containsEntry(h("A", 2), ItemStatus.NOT_FOUND);
    }

    @Test
    void missingFolderIsStaleFolderMissingWithoutSelectAndTheNextGroupRuns() throws Exception {
        init();
        IMAPFolder a = folder("A", VALIDITY, 1);
        when(a.exists()).thenReturn(false);
        folder("B", VALIDITY, 5);
        List<List<Long>> seen = new ArrayList<>();
        var out = runner().run(lease, ctx, List.of(group("A", 1), group("B", 5)), AbsentStatus.ALREADY_GONE, okStep(seen));
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.STALE_REFERENCE);
        assertThat(out.get(0).reason()).isEqualTo(ToolError.StaleReason.FOLDER_MISSING);
        verify(a, never()).open(anyInt());
        assertThat(seen).containsExactly(List.of(5L));
        assertThat(broken()).isFalse();
    }

    @Test
    void uidValidityMismatchIsStaleAndNothingIsMutated() throws Exception {
        init();
        IMAPFolder a = folder("A", VALIDITY + 1, 1);
        List<List<Long>> seen = new ArrayList<>();
        var out = runner().run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE, okStep(seen));
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.STALE_REFERENCE);
        assertThat(out.get(0).reason()).isEqualTo(ToolError.StaleReason.UIDVALIDITY_CHANGED);
        assertThat(seen).isEmpty();
        verify(a, never()).getMessagesByUID(any(long[].class));
        verify(a).close(false);
        assertThat(ctx.mutationStarted()).isFalse();
    }

    @Test
    void emptyPresentSetSkipsTheStepAndSendsNothing() throws Exception {
        init();
        folder("A", VALIDITY);
        List<List<Long>> seen = new ArrayList<>();
        var out = runner().run(lease, ctx, List.of(group("A", 1, 2)), AbsentStatus.ALREADY_GONE, okStep(seen));
        assertThat(seen).isEmpty();
        assertThat(out).extracting(BulkResult.Item::status).containsOnly(ItemStatus.ALREADY_GONE);
        assertThat(ctx.mutationStarted()).isFalse();
    }

    @Test
    void aCallCancelledBeforeTheMutationBeginsWritesNothingAndTheGroupIsNotAttempted() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        folder("B", VALIDITY, 5);
        List<String> commands = new ArrayList<>();
        FolderGroupRunner r = new FolderGroupRunner(clock, (p, g, c) -> {
            if (p == HookPoint.BEFORE_WRITE) {
                ctx.cancel(); // the executor decided the plain TIMEOUT between the hook and the mark
            }
        });
        var out = r.run(lease, ctx, List.of(group("A", 1), group("B", 5)), AbsentStatus.ALREADY_GONE,
                (f, present, gate) -> {
                    gate.beforeWrite();
                    commands.add("STORE");
                    return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
                });
        assertThat(commands).isEmpty();
        assertThat(ctx.mutationStarted()).isFalse();
        assertThat(statuses(out)).containsEntry(h("A", 1), ItemStatus.NOT_ATTEMPTED)
                .containsEntry(h("B", 5), ItemStatus.NOT_ATTEMPTED);
    }

    @Test
    void mutationIsMarkedAfterTheBeforeWriteHookAndNeverDuringPreCheck() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        AtomicReference<Boolean> markedAtHook = new AtomicReference<>();
        AtomicReference<Boolean> markedAtStepStart = new AtomicReference<>();
        FolderGroupRunner r = new FolderGroupRunner(clock, (p, g, c) -> {
            if (p == HookPoint.BEFORE_WRITE) {
                markedAtHook.set(ctx.mutationStarted());
            }
        });
        r.run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE, (f, present, gate) -> {
            markedAtStepStart.set(ctx.mutationStarted());
            gate.beforeWrite();
            assertThat(ctx.mutationStarted()).isTrue();
            return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
        });
        assertThat(markedAtHook.get()).isFalse();
        assertThat(markedAtStepStart.get()).isFalse();
    }

    @Test
    void connectionFailureInPreCheckIsRethrownUnmarkedAndBreaksTheLease() throws Exception {
        init();
        IMAPFolder a = folder("A", VALIDITY, 1);
        when(a.getMessagesByUID(any(long[].class))).thenThrow(connectionLoss());
        assertThatThrownBy(() -> runner().run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE,
                okStep(new ArrayList<>()))).isInstanceOf(MessagingException.class);
        assertThat(ctx.mutationStarted()).isFalse();
        assertThat(broken()).isTrue();
    }

    @Test
    void connectionLossBeforeTheMarkIsRethrown() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        assertThatThrownBy(() -> runnerWithHook(HookPoint.BEFORE_WRITE, 0, 0).run(lease, ctx,
                List.of(group("A", 1)), AbsentStatus.ALREADY_GONE, okStep(new ArrayList<>())))
                .isInstanceOf(MessagingException.class);
        assertThat(ctx.mutationStarted()).isFalse();
        assertThat(broken()).isTrue();
    }

    @Test
    void budgetBelowFifteenSecondsStopsTheGroupAndAllLaterOnesNormally() throws Exception {
        deadline = NOW.plus(Duration.ofSeconds(15)).minusMillis(1);
        init();
        folder("A", VALIDITY, 1);
        folder("B", VALIDITY, 5);
        List<List<Long>> seen = new ArrayList<>();
        var out = runner().run(lease, ctx, List.of(group("A", 1), group("B", 5)), AbsentStatus.ALREADY_GONE, okStep(seen));
        assertThat(out).extracting(BulkResult.Item::status).containsOnly(ItemStatus.NOT_ATTEMPTED);
        assertThat(out).hasSize(2);
        assertThat(ctx.mutationStarted()).isFalse();
        assertThat(broken()).isFalse();
    }

    @Test
    void exactlyFifteenSecondsRemainingStillRuns() throws Exception {
        deadline = NOW.plus(Duration.ofSeconds(15));
        init();
        folder("A", VALIDITY, 1);
        var out = runner().run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE, okStep(new ArrayList<>()));
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.OK);
    }

    @Test
    void budgetIsCheckedPerGroupAndFinishedGroupsKeepTheirResults() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        folder("B", VALIDITY, 5);
        folder("C", VALIDITY, 9);
        var out = runner().run(lease, ctx, List.of(group("A", 1), group("B", 5), group("C", 9)),
                AbsentStatus.ALREADY_GONE, (f, present, gate) -> {
                    gate.beforeWrite();
                    now = deadline.minusSeconds(14); // the first step consumed the budget
                    return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
                });
        assertThat(statuses(out)).containsEntry(h("A", 1), ItemStatus.OK)
                .containsEntry(h("B", 5), ItemStatus.NOT_ATTEMPTED).containsEntry(h("C", 9), ItemStatus.NOT_ATTEMPTED);
        assertThat(ctx.mutationStarted()).isTrue();
        assertThat(broken()).isFalse();
    }

    @Test
    void budgetStopKeepsWhatThePreCheckSettledInTheInFlightGroup() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        folder("B", VALIDITY, 5);
        var out = runner().run(lease, ctx, List.of(group("A", 1), group("B", 5, 6)), AbsentStatus.ALREADY_GONE,
                (f, present, gate) -> {
                    gate.beforeWrite();
                    now = deadline.minusSeconds(14);
                    return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
                });
        assertThat(statuses(out)).containsEntry(h("A", 1), ItemStatus.OK)
                .containsEntry(h("B", 5), ItemStatus.NOT_ATTEMPTED).containsEntry(h("B", 6), ItemStatus.ALREADY_GONE);
    }

    @Test
    void startedStepIsNotStoppedByTheBudgetBetweenItsCommands() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        var out = runner().run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE, (f, present, gate) -> {
            gate.beforeWrite();
            now = deadline.minusSeconds(1);
            gate.beforeWrite(); // second command of the same step
            return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
        });
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.OK);
    }

    @Test
    void noAnswerFailsTheGroupKeepsTheLeaseAndTheNextGroupRuns() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        folder("B", VALIDITY, 5);
        var out = runner().run(lease, ctx, List.of(group("A", 1), group("B", 5)), AbsentStatus.ALREADY_GONE,
                (f, present, gate) -> {
                    if (present.get(0).ref().folder().equals("A")) {
                        gate.beforeWrite();
                        throw new MessagingException("NO", new CommandFailedException(new org.eclipse.angus.mail.iap.Response("A1 NO [NOPERM] denied")));
                    }
                    return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
                });
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.FAILED);
        assertThat(out.get(0).error().code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(out.get(1).status()).isEqualTo(ItemStatus.OK);
        assertThat(broken()).isFalse();
    }

    @Test
    void readOnlySelectFailsTheGroupWithoutBreakingTheLease() throws Exception {
        init();
        IMAPFolder a = folder("A", VALIDITY, 1);
        folder("B", VALIDITY, 5);
        org.mockito.Mockito.doThrow(new jakarta.mail.ReadOnlyFolderException(a, "ro")).when(a).open(Folder.READ_WRITE);
        List<List<Long>> seen = new ArrayList<>();
        var out = runner().run(lease, ctx, List.of(group("A", 1), group("B", 5)), AbsentStatus.ALREADY_GONE, okStep(seen));
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.FAILED);
        assertThat(out.get(0).error().code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(seen).containsExactly(List.of(5L));
        assertThat(broken()).isFalse();
    }

    /** Group A completes and marks; the fault hits group B at the given point. */
    private List<BulkResult.Item> faultInSecondGroup(HookPoint at, int command) throws Exception {
        init();
        folder("A", VALIDITY, 1);
        folder("B", VALIDITY, 5);
        folder("C", VALIDITY, 9);
        return runnerWithHook(at, 1, command).run(lease, ctx, List.of(group("A", 1), group("B", 5), group("C", 9)),
                AbsentStatus.ALREADY_GONE, okStep(new ArrayList<>()));
    }

    @Test
    void lossBeforeWritingInTheSecondGroupIsNotAttemptedAndKeepsFinishedGroups() throws Exception {
        var out = faultInSecondGroup(HookPoint.BEFORE_WRITE, 0);
        assertThat(statuses(out)).containsEntry(h("A", 1), ItemStatus.OK)
                .containsEntry(h("B", 5), ItemStatus.NOT_ATTEMPTED).containsEntry(h("C", 9), ItemStatus.NOT_ATTEMPTED);
        assertThat(broken()).isTrue();
    }

    @Test
    void lossAfterWriteBeforeAnswerIsUnknownForTheInFlightGroupOnly() throws Exception {
        var out = faultInSecondGroup(HookPoint.AFTER_WRITE, 0);
        assertThat(statuses(out)).containsEntry(h("A", 1), ItemStatus.OK)
                .containsEntry(h("B", 5), ItemStatus.UNKNOWN).containsEntry(h("C", 9), ItemStatus.NOT_ATTEMPTED);
        assertThat(broken()).isTrue();
    }

    @Test
    void lossBetweenGroupsMakesTheFollowingGroupsNotAttempted() throws Exception {
        var out = faultInSecondGroup(HookPoint.BETWEEN_GROUPS, -1);
        assertThat(statuses(out)).containsEntry(h("A", 1), ItemStatus.OK)
                .containsEntry(h("B", 5), ItemStatus.NOT_ATTEMPTED).containsEntry(h("C", 9), ItemStatus.NOT_ATTEMPTED);
        assertThat(broken()).isTrue();
    }

    @Test
    void lossBetweenCommandsOfAStartedStepIsUnknown() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        var out = runnerWithHook(HookPoint.BEFORE_WRITE, 0, 1).run(lease, ctx, List.of(group("A", 1)),
                AbsentStatus.ALREADY_GONE, (f, present, gate) -> {
                    gate.beforeWrite();
                    gate.beforeWrite();
                    return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
                });
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.UNKNOWN);
        assertThat(ctx.mutationStarted()).isTrue();
        assertThat(broken()).isTrue();
    }

    @Test
    void connectionFailureRaisedByTheStepAfterItsFirstCommandIsUnknown() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        var out = runner().run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE, (f, present, gate) -> {
            gate.beforeWrite();
            throw connectionLoss();
        });
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.UNKNOWN);
        assertThat(broken()).isTrue();
    }
}
