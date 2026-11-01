package de.alexanderlindhorst.imapmcp.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolDefinitionTest {

    static final String GOOD = "List the folders of an account. Read-only; does not change flags or mark messages as read.";
    static final Map<String, Object> SCHEMA = Map.of("type", "object");

    private static ToolDefinition def(String description) {
        return new ToolDefinition("list_folders", "List folders", description, SCHEMA, SCHEMA, ToolHints.READ_ONLY_OPEN_WORLD);
    }

    @Test
    void readOnlyHintsPreset() {
        ToolHints h = ToolHints.READ_ONLY_OPEN_WORLD;
        assertThat(h.readOnly()).isTrue();
        assertThat(h.destructive()).isFalse();
        assertThat(h.idempotent()).isTrue();
        assertThat(h.openWorld()).isTrue();
    }

    @Test
    void rejectsMissingParts() {
        assertThatThrownBy(() -> new ToolDefinition(null, "t", GOOD, SCHEMA, SCHEMA, ToolHints.READ_ONLY_OPEN_WORLD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolDefinition("n", "t", GOOD, SCHEMA, null, ToolHints.READ_ONLY_OPEN_WORLD))
                .as("output schema is mandatory (SPEC 4)").isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolDefinition("n", "t", GOOD, SCHEMA, SCHEMA, null))
                .as("hints are mandatory").isInstanceOf(NullPointerException.class);
    }

    @Test
    void goodDescriptionHasNoViolations() {
        assertThat(DescriptionRules.violations(def(GOOD))).isEmpty();
    }

    @Test
    void tooLongDescriptionIsFlagged() {
        String longer = "List " + "x".repeat(600) + " Read-only; does not change flags or mark messages as read.";
        assertThat(DescriptionRules.violations(def(longer))).anyMatch(v -> v.contains("600"));
    }

    @Test
    void descriptionMustStartWithVerb() {
        assertThat(DescriptionRules.violations(def("This tool lists folders. Read-only; does not change flags or mark messages as read.")))
                .anyMatch(v -> v.contains("verb"));
        assertThat(DescriptionRules.violations(def("folders. Read-only; does not change flags or mark messages as read.")))
                .anyMatch(v -> v.contains("verb"));
    }

    @Test
    void readOnlyToolMustStateNoSideEffects() {
        assertThat(DescriptionRules.violations(def("List the folders of an account.")))
                .anyMatch(v -> v.contains("Read-only; does not change flags or mark messages as read"));
    }

    @Test
    void outcomesCarryPayload() {
        ToolOutcome ok = new ToolOutcome.Success(Map.of("a", 1));
        ToolOutcome bad = new ToolOutcome.Failure(ToolError.internalError("r"));
        assertThat(ok).isInstanceOf(ToolOutcome.Success.class);
        assertThat(((ToolOutcome.Failure) bad).error().requestId()).isEqualTo("r");
    }
}
