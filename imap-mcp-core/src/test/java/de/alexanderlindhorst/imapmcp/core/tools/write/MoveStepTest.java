package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.angus.mail.iap.Response;
import org.junit.jupiter.api.Test;

/** The move step against scripted server answers (no server). */
class MoveStepTest {

    private static final long VALIDITY = 5;

    private final AtomicInteger gateCalls = new AtomicInteger();
    private final AtomicInteger moves = new AtomicInteger();
    private final FolderGroupRunner.Gate gate = gateCalls::incrementAndGet;

    private static List<WritePreflight.Item> present(long... uids) {
        List<WritePreflight.Item> items = new ArrayList<>();
        for (long u : uids) {
            items.add(new WritePreflight.Item(0, "h" + u, new MessageRef("Src", VALIDITY, u)));
        }
        return items;
    }

    private MoveStep step(boolean move, String... lines) {
        Response[] responses = new Response[lines.length];
        for (int i = 0; i < lines.length; i++) {
            responses[i] = new Response(lines[i]);
        }
        UidCommands.Result result = new UidCommands.Result(responses[responses.length - 1], responses);
        return new MoveStep("Target", move, ref -> ref.folder() + "/" + ref.uidValidity() + "/" + ref.uid(), "rid",
                (folder, uids, target) -> {
                    moves.incrementAndGet();
                    return result;
                });
    }

    @Test
    void copyUidPairsNewHandlesFromTheTargetValidityAndUid() throws Exception {
        var out = step(true, "* OK [COPYUID 9 3:4 30,31] moved", "A1 OK done").apply(null, present(3, 4), gate);
        assertThat(out).extracting(BulkResult.Item::newHandle).containsExactly("Target/9/30", "Target/9/31");
        assertThat(out).extracting(BulkResult.Item::status).containsOnly(ItemStatus.OK);
        assertThat(gateCalls).hasValue(1);
    }

    @Test
    void presentUidMissingFromTheCopyUidSourceSetIsAlreadyGone() throws Exception {
        var out = step(true, "* OK [COPYUID 9 3 30] moved", "A1 OK done").apply(null, present(3, 4), gate);
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.OK);
        assertThat(out.get(1).status()).isEqualTo(ItemStatus.ALREADY_GONE);
        assertThat(out.get(1).newHandle()).isNull();
    }

    @Test
    void missingCopyUidIsOkWithNewUidUnknown() throws Exception {
        var out = step(true, "A1 OK done").apply(null, present(3, 4), gate);
        assertThat(out).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(ItemStatus.OK);
            assertThat(i.newHandle()).isNull();
            assertThat(i.note()).isEqualTo(BulkResult.NEW_UID_UNKNOWN);
        });
    }

    @Test
    void trycreateFailsTheGroupWithFolderNotFound() throws Exception {
        var out = step(true, "A1 NO [TRYCREATE] no such mailbox").apply(null, present(3, 4), gate);
        assertThat(out).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(ItemStatus.FAILED);
            assertThat(i.error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        });
    }

    @Test
    void overquotaFailsTheGroupWithQuotaExceeded() throws Exception {
        var out = step(true, "A1 NO [OVERQUOTA] full").apply(null, present(3), gate);
        assertThat(out.get(0).error().code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    void withoutMoveCapabilityTheGroupFailsUnsupportedWithoutCommandOrGate() throws Exception {
        // interim until #191 replaces this branch with COPY + STORE \Deleted + UID EXPUNGE
        var out = step(false, "A1 OK done").apply(null, present(3, 4), gate);
        assertThat(out).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(ItemStatus.FAILED);
            assertThat(i.error().code()).isEqualTo(ErrorCode.UNSUPPORTED);
            assertThat(i.error().details()).containsEntry("capability", "MOVE");
        });
        assertThat(moves).hasValue(0);
        assertThat(gateCalls).hasValue(0);
    }

    @Test
    void severalUntaggedCopyUidResponsesOfOneMoveAreCombined() throws Exception {
        var out = step(true, "* OK [COPYUID 9 3:4 30:31] chunk", "* OK [COPYUID 9 5 32] chunk", "A1 OK done")
                .apply(null, present(3, 4, 5), gate);
        assertThat(out).extracting(BulkResult.Item::newHandle)
                .containsExactly("Target/9/30", "Target/9/31", "Target/9/32");
    }

    @Test
    void copyUidResponsesWithDifferentUidValidityCountAsNoCopyUid() throws Exception {
        var out = step(true, "* OK [COPYUID 9 3 30] chunk", "* OK [COPYUID 8 4 31] chunk", "A1 OK done")
                .apply(null, present(3, 4), gate);
        assertThat(out).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(ItemStatus.OK);
            assertThat(i.note()).isEqualTo(BulkResult.NEW_UID_UNKNOWN);
        });
    }
}
