package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
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

    // ---- fallback without MOVE (#191) ----

    /** Scripted wire: one answer per verb, every call recorded with its UID set. */
    private final List<String> wire = new ArrayList<>();

    private static UidCommands.Result answer(String... lines) {
        Response[] responses = new Response[lines.length];
        for (int i = 0; i < lines.length; i++) {
            responses[i] = new Response(lines[i]);
        }
        return new UidCommands.Result(responses[responses.length - 1], responses);
    }

    private MoveStep fallback(UidCommands.Result copy, UidCommands.Result store, UidCommands.Result expunge) {
        return new MoveStep("Target", false, ref -> ref.folder() + "/" + ref.uidValidity() + "/" + ref.uid(), "rid",
                new MoveStep.Wire() {
                    @Override
                    public UidCommands.Result move(org.eclipse.angus.mail.imap.IMAPFolder f,
                            java.util.Collection<Long> uids, String target) {
                        throw new AssertionError("UID MOVE must not be sent");
                    }

                    @Override
                    public UidCommands.Result copy(org.eclipse.angus.mail.imap.IMAPFolder f,
                            java.util.Collection<Long> uids, String target) {
                        wire.add("COPY " + UidCommands.uidSet(uids) + " " + target);
                        return copy;
                    }

                    @Override
                    public UidCommands.Result markDeleted(org.eclipse.angus.mail.imap.IMAPFolder f,
                            java.util.Collection<Long> uids) {
                        wire.add("STORE " + UidCommands.uidSet(uids));
                        return store;
                    }

                    @Override
                    public UidCommands.Result expunge(org.eclipse.angus.mail.imap.IMAPFolder f,
                            java.util.Collection<Long> uids) {
                        wire.add("EXPUNGE " + UidCommands.uidSet(uids));
                        return expunge;
                    }
                });
    }

    @Test
    void fallbackSendsCopyThenStoreThenExpungeOfTheCopyUidSourceSetWithAGateCallEach() throws Exception {
        MoveStep s = fallback(answer("A1 OK [COPYUID 9 3:4 30:31] copied"), answer("A2 OK stored"),
                answer("A3 OK expunged"));
        var out = s.apply(null, present(3, 4), gate);
        assertThat(wire).containsExactly("COPY 3:4 Target", "STORE 3:4", "EXPUNGE 3:4");
        assertThat(gateCalls).hasValue(3);
        assertThat(out).extracting(BulkResult.Item::newHandle).containsExactly("Target/9/30", "Target/9/31");
        assertThat(out).extracting(BulkResult.Item::status).containsOnly(ItemStatus.OK);
        assertThat(s.usedFallback()).isTrue();
    }

    @Test
    void fallbackWithoutCopyUidUsesThePresentSetAndIsOkWithNewUidUnknown() throws Exception {
        var out = fallback(answer("A1 OK copied"), answer("A2 OK"), answer("A3 OK")).apply(null, present(3, 7), gate);
        assertThat(wire).containsExactly("COPY 3,7 Target", "STORE 3,7", "EXPUNGE 3,7");
        assertThat(out).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(ItemStatus.OK);
            assertThat(i.note()).isEqualTo(BulkResult.NEW_UID_UNKNOWN);
        });
    }

    @Test
    void fallbackStoresAndExpungesOnlyTheCopiedUidsAndTheRestIsAlreadyGone() throws Exception {
        var out = fallback(answer("A1 OK [COPYUID 9 3 30] copied"), answer("A2 OK"), answer("A3 OK"))
                .apply(null, present(3, 4), gate);
        assertThat(wire).containsExactly("COPY 3:4 Target", "STORE 3", "EXPUNGE 3");
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.OK);
        assertThat(out.get(1).status()).isEqualTo(ItemStatus.ALREADY_GONE);
    }

    @Test
    void fallbackCopyRefusedSendsNeitherStoreNorExpungeAndFailsTheGroup() throws Exception {
        var out = fallback(answer("A1 NO [OVERQUOTA] full"), answer("A2 OK"), answer("A3 OK"))
                .apply(null, present(3, 4), gate);
        assertThat(wire).containsExactly("COPY 3:4 Target");
        assertThat(gateCalls).hasValue(1);
        assertThat(out).allSatisfy(i -> {
            assertThat(i.status()).isEqualTo(ItemStatus.FAILED);
            assertThat(i.error().code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
        });
    }

    @Test
    void fallbackCopyRefusedWithTrycreateFailsFolderNotFound() throws Exception {
        var out = fallback(answer("A1 NO [TRYCREATE] none"), answer("A2 OK"), answer("A3 OK"))
                .apply(null, present(3), gate);
        assertThat(wire).containsExactly("COPY 3 Target");
        assertThat(out.get(0).error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
    }

    @Test
    void fallbackStoreRefusedSkipsExpungeAndFailsTheCopiedItemsKeepingTheirNewHandle() throws Exception {
        var out = fallback(answer("A1 OK [COPYUID 9 3 30] copied"), answer("A2 NO [READ-ONLY] no"), answer("A3 OK"))
                .apply(null, present(3, 4), gate);
        assertThat(wire).containsExactly("COPY 3:4 Target", "STORE 3");
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.FAILED);
        assertThat(out.get(0).newHandle()).isEqualTo("Target/9/30");
        assertThat(out.get(0).error().code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(out.get(0).error().hint()).contains("copied to the target folder").contains("copies them again");
        assertThat(out.get(1).status()).isEqualTo(ItemStatus.ALREADY_GONE);
    }

    @Test
    void fallbackExpungeRefusedSendsNothingMoreAndFailsTheCopiedItemsKeepingTheirNewHandle() throws Exception {
        var out = fallback(answer("A1 OK [COPYUID 9 3 30] copied"), answer("A2 OK"), answer("A3 NO nope"))
                .apply(null, present(3), gate);
        assertThat(wire).containsExactly("COPY 3 Target", "STORE 3", "EXPUNGE 3");
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.FAILED);
        assertThat(out.get(0).newHandle()).isEqualTo("Target/9/30");
        assertThat(out.get(0).error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
    }

    @Test
    void fallbackRefusalWithoutCopyUidFailsWithoutNewHandle() throws Exception {
        var out = fallback(answer("A1 OK copied"), answer("A2 NO nope"), answer("A3 OK")).apply(null, present(3), gate);
        assertThat(out.get(0).status()).isEqualTo(ItemStatus.FAILED);
        assertThat(out.get(0).newHandle()).isNull();
    }

    @Test
    void aRefusalThatWouldBeRetryableBecomesUpstreamErrorKeepingTheResponseCode() throws Exception {
        var out = fallback(answer("A1 OK [COPYUID 9 3 30] copied"), answer("A2 OK"),
                answer("A3 NO [UNAVAILABLE] busy")).apply(null, present(3), gate);
        ToolError e = out.get(0).error();
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(e.retryable()).isFalse();
        assertThat(e.details()).containsEntry("response_code", "UNAVAILABLE");
    }

    @Test
    void theMovePathDoesNotMarkTheFallbackAsUsed() throws Exception {
        MoveStep s = step(true, "* OK [COPYUID 9 3 30] moved", "A1 OK done");
        s.apply(null, present(3), gate);
        assertThat(s.usedFallback()).isFalse();
    }
}
