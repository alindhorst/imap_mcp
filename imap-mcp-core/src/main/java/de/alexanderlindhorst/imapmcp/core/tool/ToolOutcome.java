package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.util.Objects;

/** Result of a tool handler: structured output matching the tool's outputSchema, or a structured error. */
public sealed interface ToolOutcome {

    record Success(Object structured) implements ToolOutcome {
        public Success {
            Objects.requireNonNull(structured, "structured");
        }
    }

    record Failure(ToolError error) implements ToolOutcome {
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }
}
