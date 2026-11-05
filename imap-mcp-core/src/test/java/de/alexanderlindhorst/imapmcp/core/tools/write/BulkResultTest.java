package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tools.write.BulkResult.Item;
import de.alexanderlindhorst.imapmcp.core.tools.write.BulkResult.Kind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BulkResultTest {

    private static final String RID = "req-1";

    private static BulkResult move(Item... items) {
        return BulkResult.of(Kind.MOVE, "acc_1", "Archive", Arrays.asList(items));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> out) {
        return (Map<String, Object>) out.get("summary");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("results");
    }

    @Test
    void itemStatusHasExactlyTheEightWireValues() {
        assertThat(Arrays.stream(ItemStatus.values()).map(ItemStatus::wire).toList()).containsExactly("ok",
                "already_in_target", "already_gone", "not_found", "stale_reference", "failed", "not_attempted",
                "unknown");
    }

    @Test
    void successStatusesAreOkAlreadyInTargetAlreadyGone() {
        assertThat(ItemStatus.SUCCESS).containsExactlyInAnyOrder(ItemStatus.OK, ItemStatus.ALREADY_IN_TARGET,
                ItemStatus.ALREADY_GONE);
        assertThat(ItemStatus.FAILED.isSuccess()).isFalse();
        assertThat(ItemStatus.ALREADY_GONE.isSuccess()).isTrue();
    }

    @Test
    void summaryAlwaysHasAllNineIntegersInSpecOrder() {
        Map<String, Object> out = move(Item.ok("h1", "n1"), Item.alreadyGone("h2"), Item.ok("h3", "n3")).toMap();
        assertThat(summary(out).keySet()).containsExactly("requested", "ok", "already_in_target", "already_gone",
                "not_found", "stale_reference", "failed", "not_attempted", "unknown");
        assertThat(summary(out)).containsEntry("requested", 3).containsEntry("ok", 2).containsEntry("already_gone", 1)
                .containsEntry("failed", 0).containsEntry("unknown", 0);
    }

    @Test
    void topLevelFieldOrderAndTargetFolder() {
        Map<String, Object> out = move(Item.ok("h1", "n1")).toMap();
        assertThat(out.keySet()).containsExactly("account", "target_folder", "summary", "results", "notes");
        assertThat(out).containsEntry("account", "acc_1").containsEntry("target_folder", "Archive");
    }

    @Test
    void setFlagsHasNoTargetFolderNewHandleOrNote() {
        BulkResult r = BulkResult.of(Kind.SET_FLAGS, "acc_1", null, List.of(Item.okFlags("h1"), Item.notFound("h2")));
        Map<String, Object> out = r.toMap();
        assertThat(out.keySet()).containsExactly("account", "summary", "results", "notes");
        assertThat(results(out).get(0).keySet()).containsExactly("handle", "status", "reason", "error");
    }

    @Test
    void moveCopyDeleteItemsCarryAllSixFields() {
        Map<String, Object> out = move(Item.ok("h1", "n1")).toMap();
        assertThat(results(out).get(0).keySet()).containsExactly("handle", "status", "new_handle", "note", "reason",
                "error");
        assertThat(results(out).get(0)).containsEntry("handle", "h1").containsEntry("status", "ok")
                .containsEntry("new_handle", "n1").containsEntry("note", null).containsEntry("reason", null)
                .containsEntry("error", null);
    }

    @Test
    void targetFolderRequiredExactlyWhenNotSetFlags() {
        assertThatThrownBy(() -> BulkResult.of(Kind.MOVE, "a", null, List.of(Item.ok("h", "n"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BulkResult.of(Kind.SET_FLAGS, "a", "X", List.of(Item.okFlags("h"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void okWithoutCopyuidHasNullNewHandleAndNote() {
        Map<String, Object> out = move(Item.okNewUidUnknown("h1")).toMap();
        assertThat(results(out).get(0)).containsEntry("status", "ok").containsEntry("new_handle", null)
                .containsEntry("note", "new_uid_unknown");
    }

    @Test
    void alreadyInTargetEchoesTheHandleAsNewHandle() {
        Map<String, Object> out = move(Item.alreadyInTarget("h1")).toMap();
        assertThat(results(out).get(0)).containsEntry("status", "already_in_target").containsEntry("new_handle", "h1");
    }

    @Test
    void alreadyGoneHasNullNewHandle() {
        assertThat(results(move(Item.alreadyGone("h1")).toMap()).get(0)).containsEntry("new_handle", null);
    }

    @Test
    void staleReferenceCarriesReasonOnly() {
        Map<String, Object> out = move(Item.stale("h1", ToolError.StaleReason.UIDVALIDITY_CHANGED),
                Item.stale("h2", ToolError.StaleReason.FOLDER_MISSING)).toMap();
        assertThat(results(out).get(0)).containsEntry("reason", "uidvalidity_changed").containsEntry("error", null);
        assertThat(results(out).get(1)).containsEntry("reason", "folder_missing");
    }

    @Test
    void failedCarriesV1ErrorObjectWithoutRequestId() {
        ToolError err = ToolError.upstreamError("NO denied", RID);
        Map<String, Object> out = move(Item.failed("h1", err)).toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> e = (Map<String, Object>) results(out).get(0).get("error");
        assertThat(e).isEqualTo(err.toItemMap()).doesNotContainKey("request_id");
        assertThat(results(out).get(0)).containsEntry("reason", null);
    }

    @Test
    void failedMayCarryTheNewHandleOfACopyThatWasNotRemoved() {
        ToolError err = ToolError.upstreamError("NO denied", RID);
        Item item = Item.failedCopied("h1", "n1", err);
        assertThat(item.status()).isEqualTo(ItemStatus.FAILED);
        Map<String, Object> out = move(item).toMap();
        assertThat(results(out).get(0)).containsEntry("new_handle", "n1").containsEntry("note", null);
        assertThatThrownBy(() -> new Item("h", ItemStatus.NOT_FOUND, "n", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itemInvariantsAreEnforced() {
        assertThatThrownBy(() -> Item.failed("h", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Item("h", ItemStatus.OK, "n", null, ToolError.StaleReason.FOLDER_MISSING, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Item("h", ItemStatus.OK, "n", null, null, ToolError.internalError(RID)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Item("h", ItemStatus.NOT_FOUND, null, "new_uid_unknown", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Item("h", ItemStatus.OK, "n", "other", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Item("h", ItemStatus.FAILED, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Item("h", ItemStatus.STALE_REFERENCE, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusMustBeValidForTheTool() {
        assertThatThrownBy(() -> BulkResult.of(Kind.SET_FLAGS, "a", null, List.of(Item.alreadyGone("h"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BulkResult.of(Kind.COPY, "a", "X", List.of(Item.alreadyInTarget("h"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BulkResult.of(Kind.MOVE, "a", "X", List.of(Item.notFound("h"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BulkResult.of(Kind.SET_FLAGS, "a", null, List.of(Item.ok("h", "n"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultsFollowInputOrderRegardlessOfGroupOrder() {
        List<String> input = List.of("h1", "h2", "h3", "h4");
        // processed in group order: h3, h1 (group A), h4, h2 (group B)
        List<Item> processed = List.of(Item.ok("h3", "n3"), Item.ok("h1", "n1"), Item.alreadyGone("h4"),
                Item.ok("h2", "n2"));
        BulkResult r = BulkResult.inInputOrder(Kind.MOVE, "a", "X", input, processed);
        assertThat(results(r.toMap()).stream().map(m -> m.get("handle")).toList()).containsExactlyElementsOf(input);
        assertThat(summary(r.toMap())).containsEntry("requested", 4);
    }

    @Test
    void inInputOrderRejectsMissingExtraOrDuplicateItems() {
        List<String> input = List.of("h1", "h2");
        assertThatThrownBy(() -> BulkResult.inInputOrder(Kind.MOVE, "a", "X", input, List.of(Item.ok("h1", "n"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BulkResult.inInputOrder(Kind.MOVE, "a", "X", input,
                List.of(Item.ok("h1", "n"), Item.ok("h1", "n"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BulkResult.inInputOrder(Kind.MOVE, "a", "X", input,
                List.of(Item.ok("h1", "n"), Item.ok("h3", "n"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // notes: exact text from SPEC 4

    private static final String N1 = "Some messages were already in the target folder and were left unchanged.";
    private static final String N2 = "Some messages were already in Trash and were left unchanged.";
    private static final String N3 = "Some messages are no longer in their source folder; if this call repeats an earlier one, they were already moved. Search the target folder for their new handles.";
    private static final String N4 = "The server did not report the new UIDs of some messages; search the target folder for their handles.";
    private static final String N5 = "Some messages no longer exist; re-run search_messages.";
    private static final String N6 = "Some folders changed since the handles were issued; re-run search_messages for those folders.";
    private static final String N7 = "The server refused the change for some messages; see the error of each item.";
    private static final String N8 = "The call stopped before some messages were processed; nothing was changed for them, so repeat the call with their handles.";
    private static final String N9 = "The outcome for some messages is unknown; check the source and target folders before repeating the call for them.";

    @SuppressWarnings("unchecked")
    private static List<String> notes(BulkResult r) {
        return (List<String>) r.toMap().get("notes");
    }

    @Test
    void notesAreEmptyWhenNothingOccurred() {
        assertThat(notes(move(Item.ok("h1", "n1")))).isEmpty();
        assertThat(notes(BulkResult.of(Kind.SET_FLAGS, "a", null, List.of(Item.okFlags("h"))))).isEmpty();
    }

    @Test
    void allNineNotesInSpecOrderWithExactText() {
        // MOVE: already_in_target (move sentence), already_gone, ok+new_uid_unknown, stale, failed, not_attempted, unknown
        List<Item> items = new ArrayList<>(List.of(Item.unknown("u"), Item.notAttempted("na"),
                Item.failed("f", ToolError.timeout(RID)), Item.stale("s", ToolError.StaleReason.FOLDER_MISSING),
                Item.okNewUidUnknown("nu"), Item.alreadyGone("g"), Item.alreadyInTarget("t")));
        assertThat(notes(move(items.toArray(Item[]::new)))).containsExactly(N1, N3, N4, N6, N7, N8, N9);
        assertThat(notes(BulkResult.of(Kind.DELETE, "a", "Trash", items))).containsExactly(N2, N3, N4, N6, N7, N8, N9);
        assertThat(notes(BulkResult.of(Kind.SET_FLAGS, "a", null,
                List.of(Item.notFound("x"), Item.unknown("y"))))).containsExactly(N5, N9);
        assertThat(notes(BulkResult.of(Kind.COPY, "a", "X", List.of(Item.notFound("x"), Item.okNewUidUnknown("y")))))
                .containsExactly(N4, N5);
    }

    @Test
    void eachNoteAppearsOnceNoMatterHowOftenTheConditionOccurred() {
        assertThat(notes(move(Item.alreadyGone("a"), Item.alreadyGone("b"), Item.alreadyGone("c")))).containsExactly(N3);
    }

    @Test
    void noteSentencesMatchTheSpecListVerbatim() {
        assertThat(BulkResult.NOTES).containsExactly(N1, N2, N3, N4, N5, N6, N7, N8, N9);
    }

    @Test
    void noUntrustedOrContentNoticeInOutputOrSchema() {
        Map<String, Object> out = move(Item.ok("a", "n")).toMap();
        assertThat(out).doesNotContainKeys("untrusted", "content_notice");
        assertThat(BulkOutputSchema.of(Kind.MOVE).toString()).doesNotContain("untrusted").doesNotContain("content_notice");
    }

    @Test
    void requestedIsNumberOfInputHandlesAndSetsAreUnmodifiable() {
        BulkResult r = move(Item.ok("a", "n"), Item.ok("b", "m"));
        assertThat(summary(r.toMap())).containsEntry("requested", 2);
        assertThat(Set.copyOf(ItemStatus.SUCCESS)).hasSize(3);
    }
}
