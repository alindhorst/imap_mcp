package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tools.write.BulkResult.Item;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC 4 v3a purge_messages: output shape, statuses and the fixed notes. */
class PurgeResultTest {

    private static final String RID = "req-1";

    private static final String P1 = "These messages are permanently removed from the mailbox; this server cannot restore them.";
    private static final String P2 = "Some messages were no longer in their folder; if this call repeats an earlier one, they were already removed.";
    private static final String P3 = "Some folders changed since the handles were issued; re-run search_messages for those folders.";
    private static final String P4 = "Some messages could not be removed and may now be marked as deleted; set_flags with deleted false clears the mark.";
    private static final String P5 = "The server refused the change for some messages; see the error of each item.";
    private static final String P6 = "The call stopped before some messages were processed; nothing was changed for them, so repeat the call with their handles.";
    private static final String P7 = "The outcome for some messages is unknown; repeating the call is safe because messages already removed are reported as already_gone.";

    private static BulkResult purge(Item... items) {
        return BulkResult.purge("acc_1", "elicited", List.of(items));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("results");
    }

    @Test
    void topLevelFieldsHaveConfirmationAndNoTargetFolder() {
        Map<String, Object> out = purge(Item.okPurged("h1")).toMap();
        assertThat(out.keySet()).containsExactly("account", "confirmation", "summary", "results", "notes");
        assertThat(out).containsEntry("confirmation", "elicited");
    }

    @Test
    void itemHasNoNewHandleAndNoNote() {
        Map<String, Object> item = results(purge(Item.okPurged("h1")).toMap()).get(0);
        assertThat(item.keySet()).containsExactly("handle", "status", "reason", "error");
        assertThat(item).containsEntry("status", "ok").containsEntry("reason", null).containsEntry("error", null);
    }

    @Test
    void stillPresentIsAFailedItemWithReasonAndError() {
        Map<String, Object> item = results(purge(Item.failedStillPresent("h", ToolError.upstreamError(null, RID)))
                .toMap()).get(0);
        assertThat(item).containsEntry("status", "failed").containsEntry("reason", "still_present");
        assertThat(item.get("error")).isNotNull();
    }

    @Test
    void plainFailedHasNullReason() {
        Map<String, Object> item = results(purge(Item.failed("h", ToolError.upstreamError(null, RID))).toMap()).get(0);
        assertThat(item).containsEntry("reason", null);
    }

    @Test
    void notesInSpecOrderWithExactText() {
        BulkResult r = purge(Item.unknown("u"), Item.notAttempted("na"), Item.failed("f", ToolError.timeout(RID)),
                Item.failedStillPresent("sp", ToolError.timeout(RID)),
                Item.stale("s", ToolError.StaleReason.UIDVALIDITY_CHANGED), Item.alreadyGone("g"), Item.okPurged("o"));
        assertThat(r.toMap().get("notes")).isEqualTo(List.of(P1, P2, P3, P4, P5, P6, P7));
        assertThat(BulkResult.PURGE_NOTES).containsExactly(P1, P2, P3, P4, P5, P6, P7);
    }

    @Test
    void stillPresentAloneGivesTheStillPresentNoteAndTheFailedNote() {
        assertThat(purge(Item.failedStillPresent("sp", ToolError.timeout(RID))).notes()).containsExactly(P4, P5);
    }

    @Test
    void noNotesWhenNothingOccurredBesidesNothing() {
        assertThat(purge().notes()).isEmpty();
    }

    @Test
    void impossibleStatusesAreRejected() {
        assertThatThrownBy(() -> purge(Item.alreadyInTarget("h"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purge(Item.notFound("h"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purge(Item.ok("h", "n"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BulkResult.purge("a", "other", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summaryCountsAllNineAndNotFoundStaysZero() {
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) purge(Item.okPurged("a"), Item.alreadyGone("b")).toMap()
                .get("summary");
        assertThat(s.keySet()).containsExactly("requested", "ok", "already_in_target", "already_gone", "not_found",
                "stale_reference", "failed", "not_attempted", "unknown");
        assertThat(s).containsEntry("requested", 2).containsEntry("ok", 1).containsEntry("already_gone", 1)
                .containsEntry("not_found", 0).containsEntry("already_in_target", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void outputSchemaMatchesTheShape() {
        Map<String, Object> schema = BulkOutputSchema.of(BulkResult.Kind.PURGE);
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props.keySet()).containsExactly("account", "confirmation", "summary", "results", "notes");
        assertThat(((Map<String, Object>) props.get("confirmation")).get("enum"))
                .isEqualTo(List.of("elicited", "session_approval"));
        Map<String, Object> item = (Map<String, Object>) ((Map<String, Object>) props.get("results")).get("items");
        Map<String, Object> itemProps = (Map<String, Object>) item.get("properties");
        assertThat(itemProps.keySet()).containsExactly("handle", "status", "reason", "error");
        assertThat(((Map<String, Object>) itemProps.get("reason")).get("enum").toString()).contains("still_present");
        assertThat(((Map<String, Object>) ((Map<String, Object>) props.get("notes")).get("items")).get("enum"))
                .isEqualTo(BulkResult.PURGE_NOTES);
    }
}
