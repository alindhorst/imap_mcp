package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.core.audit.AuditWriteException;
import de.alexanderlindhorst.imapmcp.core.audit.ItemRef;
import de.alexanderlindhorst.imapmcp.core.audit.ItemResult;
import de.alexanderlindhorst.imapmcp.core.audit.RecordingAuditLog;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.LeaseTestSeams;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tools.write.FolderGroupRunner.AbsentStatus;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.Test;

/** SPEC 5.3: the audit rows are written before the first mutating command and completed after the groups. */
class FolderGroupRunnerAuditTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final long VALIDITY = 7;

    private final IMAPStore store = mock(IMAPStore.class);
    private final Map<String, IMAPFolder> folders = new HashMap<>();
    private final RecordingAuditLog audit = new RecordingAuditLog();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private ToolCallContext ctx;
    private ImapLease lease;

    private void init() throws Exception {
        ctx = new ToolCallContext(new AccountContext(new TenantId("t1")), "rid", NOW.plusSeconds(60));
        ctx.attachAudit(audit, "move_messages");
        ctx.bindAccount(new AccountId("acc-1"));
        lease = LeaseTestSeams.lease(store, ctx);
        when(store.getFolder(anyString())).thenAnswer(inv -> folders.get(inv.<String>getArgument(0)));
    }

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
            items.add(new WritePreflight.Item(0, "h-" + folder + "-" + u, new MessageRef(folder, VALIDITY, u)));
        }
        return new WritePreflight.Group(folder, items);
    }

    private FolderGroupRunner.MutationStep okStep(List<String> events) {
        return (f, present, gate) -> {
            gate.beforeWrite();
            audit.order.add("write");
            events.add("write");
            return present.stream().map(i -> BulkResult.Item.okFlags(i.handle())).toList();
        };
    }

    @Test
    void rowsAreWrittenBeforeTheFirstMutatingCommandForAllGroups() throws Exception {
        init();
        folder("A", VALIDITY, 1, 2);
        folder("B", VALIDITY, 5);
        new FolderGroupRunner(clock).run(lease, ctx, List.of(group("A", 1, 2), group("B", 5)),
                AbsentStatus.ALREADY_GONE, okStep(new ArrayList<>()));
        assertThat(audit.order).containsExactly("begin", "write", "write", "complete");
        assertThat(audit.begun.get(0)).containsExactly(new ItemRef("A", VALIDITY, 1), new ItemRef("A", VALIDITY, 2),
                new ItemRef("B", VALIDITY, 5));
        assertThat(audit.calls.get(0).account()).isEqualTo(new AccountId("acc-1"));
    }

    @Test
    void completionCarriesPerItemStatusesInBeginOrder() throws Exception {
        init();
        folder("A", VALIDITY, 1); // uid 2 is absent
        folder("B", VALIDITY + 1, 5); // stale
        ctx.audit().confirmation("elicited");
        new FolderGroupRunner(clock).run(lease, ctx, List.of(group("A", 1, 2), group("B", 5)),
                AbsentStatus.ALREADY_GONE, okStep(new ArrayList<>()));
        assertThat(audit.completions).singleElement().satisfies(c -> {
            assertThat(c.items()).containsExactly(new ItemResult("ok", null), new ItemResult("already_gone", null),
                    new ItemResult("stale_reference", null));
            assertThat(c.result()).isEqualTo("partial");
            assertThat(c.confirmation()).isEqualTo("elicited");
        });
    }

    @Test
    void aFailedItemRecordsTheErrorCode() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        new FolderGroupRunner(clock).run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE,
                (f, present, gate) -> {
                    gate.beforeWrite();
                    return present.stream().map(i -> BulkResult.Item.failed(i.handle(),
                            ToolError.permissionDenied(ToolError.PermissionReason.SERVER_DENIED, "rid"))).toList();
                });
        assertThat(audit.completions.get(0).items()).containsExactly(new ItemResult("failed", "PERMISSION_DENIED"));
    }

    @Test
    void allSuccessfulIsComplete() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        new FolderGroupRunner(clock).run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE,
                okStep(new ArrayList<>()));
        assertThat(audit.completions.get(0).result()).isEqualTo("complete");
    }

    @Test
    void failedAuditWriteStopsBeforeAnyIoAndMutation() throws Exception {
        init();
        IMAPFolder a = folder("A", VALIDITY, 1);
        audit.failBegin = true;
        List<String> events = new ArrayList<>();
        assertThatThrownBy(() -> new FolderGroupRunner(clock).run(lease, ctx, List.of(group("A", 1)),
                AbsentStatus.ALREADY_GONE, okStep(events))).isInstanceOf(AuditWriteException.class);
        assertThat(events).isEmpty();
        assertThat(ctx.mutationStarted()).isFalse();
        verify(a, never()).getMessagesByUID(any(long[].class));
        verify(store, never()).getFolder(anyString());
    }

    @Test
    void failedCompletionStillReturnsTheResult() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        audit.failComplete = true;
        var out = new FolderGroupRunner(clock).run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE,
                okStep(new ArrayList<>()));
        assertThat(out).extracting(BulkResult.Item::status).containsExactly(ItemStatus.OK);
    }

    @Test
    void connectionFailureAfterTheMarkCompletesTheInFlightItemsAsUnknown() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        new FolderGroupRunner(clock).run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE,
                (f, present, gate) -> {
                    gate.beforeWrite();
                    throw new MessagingException("io", new IOException("reset"));
                });
        assertThat(audit.completions).singleElement().satisfies(c -> {
            assertThat(c.items()).containsExactly(new ItemResult("unknown", null));
            assertThat(c.result()).isEqualTo("unknown");
        });
    }

    @Test
    void connectionFailureBeforeTheMarkIsRethrownAndLeavesTheCompletionToTheExecutor() throws Exception {
        init();
        IMAPFolder a = folder("A", VALIDITY, 1);
        when(a.getUIDValidity()).thenThrow(new MessagingException("io", new IOException("reset")));
        assertThatThrownBy(() -> new FolderGroupRunner(clock).run(lease, ctx, List.of(group("A", 1)),
                AbsentStatus.ALREADY_GONE, okStep(new ArrayList<>()))).isInstanceOf(MessagingException.class);
        // the mapped whole-call code is only known to the executor, which completes the rows with it
        assertThat(audit.order).containsExactly("begin");
    }

    @Test
    void settledItemsAreAuditedInTheSameBatchWithTheirStatus() throws Exception {
        init();
        folder("A", VALIDITY, 1);
        WritePreflight.Item inTarget = new WritePreflight.Item(1, "h-T-9", new MessageRef("T", VALIDITY, 9));
        new FolderGroupRunner(clock).run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE,
                okStep(new ArrayList<>()),
                List.of(new FolderGroupRunner.Settled(inTarget, BulkResult.Item.alreadyInTarget("h-T-9"))));
        assertThat(audit.begun).hasSize(1);
        assertThat(audit.begun.get(0)).containsExactly(new ItemRef("A", VALIDITY, 1), new ItemRef("T", VALIDITY, 9));
        assertThat(audit.completions.get(0).items()).containsExactly(new ItemResult("ok", null),
                new ItemResult("already_in_target", null));
    }

    @Test
    void onlySettledItemsStillWriteTheirRows() throws Exception {
        init();
        WritePreflight.Item inTarget = new WritePreflight.Item(0, "h-T-9", new MessageRef("T", VALIDITY, 9));
        new FolderGroupRunner(clock).run(lease, ctx, List.of(), AbsentStatus.ALREADY_GONE, okStep(new ArrayList<>()),
                List.of(new FolderGroupRunner.Settled(inTarget, BulkResult.Item.alreadyInTarget("h-T-9"))));
        assertThat(audit.order).containsExactly("begin", "complete");
        assertThat(audit.completions.get(0).result()).isEqualTo("complete");
    }

    @Test
    void noItemsMeansNoRow() throws Exception {
        init();
        new FolderGroupRunner(clock).run(lease, ctx, List.of(), AbsentStatus.ALREADY_GONE, okStep(new ArrayList<>()));
        assertThat(audit.order).isEmpty();
    }

    @Test
    void withoutAnAuditLogNothingChanges() throws Exception {
        init();
        ctx = new ToolCallContext(new AccountContext(new TenantId("t1")), "rid", NOW.plusSeconds(60));
        lease = LeaseTestSeams.lease(store, ctx);
        folder("A", VALIDITY, 1);
        var out = new FolderGroupRunner(clock).run(lease, ctx, List.of(group("A", 1)), AbsentStatus.ALREADY_GONE,
                okStep(new ArrayList<>()));
        assertThat(out).hasSize(1);
        assertThat(audit.order).containsExactly("write");
    }
}
