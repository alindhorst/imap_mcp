package de.alexanderlindhorst.imapmcp.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.write.BulkOutputSchema;
import de.alexanderlindhorst.imapmcp.core.write.BulkResult;
import de.alexanderlindhorst.imapmcp.core.write.BulkResult.Item;
import de.alexanderlindhorst.imapmcp.core.write.BulkResult.Kind;
import io.modelcontextprotocol.json.McpJsonDefaults;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The bulk output schema against the SDK's validator (SDK types stay in core.mcp). */
class BulkOutputSchemaSdkTest {

    private static final String RID = "req-1";

    private static BulkResult move(Item... items) {
        return BulkResult.of(Kind.MOVE, "acc_1", "Archive", List.of(items));
    }

    @Test
    void outputSchemaValidatesEveryProducedResultWithAllStatusesPresent() {
        var validator = McpJsonDefaults.getSchemaValidator();
        BulkResult all = move(Item.ok("a", "n"), Item.okNewUidUnknown("b"), Item.alreadyInTarget("c"),
                Item.alreadyGone("d"), Item.stale("e", ToolError.StaleReason.UIDVALIDITY_CHANGED),
                Item.failed("f", ToolError.permissionDenied(ToolError.PermissionReason.SERVER_DENIED, RID)),
                Item.notAttempted("g"), Item.unknown("h"));
        assertThat(validator.validate(BulkOutputSchema.of(Kind.MOVE), all.toMap()).valid()).isTrue();
        BulkResult copy = BulkResult.of(Kind.COPY, "a", "X", List.of(Item.notFound("n"), Item.ok("o", "p")));
        assertThat(validator.validate(BulkOutputSchema.of(Kind.COPY), copy.toMap()).valid()).isTrue();
        BulkResult flags = BulkResult.of(Kind.SET_FLAGS, "a", null,
                List.of(Item.okFlags("a"), Item.notFound("b"), Item.unknown("c")));
        assertThat(validator.validate(BulkOutputSchema.of(Kind.SET_FLAGS), flags.toMap()).valid()).isTrue();
        BulkResult empty = move(Item.ok("a", "n"));
        assertThat(validator.validate(BulkOutputSchema.of(Kind.DELETE), empty.toMap()).valid()).isTrue();
    }

    @Test
    void outputSchemaRejectsMalformedResults() {
        var validator = McpJsonDefaults.getSchemaValidator();
        Map<String, Object> good = move(Item.ok("a", "n")).toMap();
        Map<String, Object> badStatus = new java.util.LinkedHashMap<>(good);
        badStatus.put("results", List.of(Map.of("handle", "a", "status", "bogus", "new_handle", "n", "note", "x",
                "reason", "y", "error", "z")));
        assertThat(validator.validate(BulkOutputSchema.of(Kind.MOVE), badStatus).valid()).isFalse();
        Map<String, Object> noSummary = new java.util.LinkedHashMap<>(good);
        noSummary.remove("summary");
        assertThat(validator.validate(BulkOutputSchema.of(Kind.MOVE), noSummary).valid()).isFalse();
        // set_flags schema has no target_folder
        assertThat(validator.validate(BulkOutputSchema.of(Kind.SET_FLAGS), good).valid()).isFalse();
    }
}
