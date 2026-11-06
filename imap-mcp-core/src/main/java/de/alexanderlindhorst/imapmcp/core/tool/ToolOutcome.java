package de.alexanderlindhorst.imapmcp.core.tool;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import java.util.List;
import java.util.Objects;

/** Result of a tool handler: structured output matching the tool's outputSchema, or a structured error. */
public sealed interface ToolOutcome {

    /**
     * {@code extraBlocks} follow the text block that carries {@code structured}; only {@code download_attachment}
     * produces any (SPEC 4, enforced by an architecture rule). The blocks are SDK-free; the adapter maps them.
     */
    record Success(Object structured, List<ContentBlock> extraBlocks) implements ToolOutcome {
        public Success {
            Objects.requireNonNull(structured, "structured");
            extraBlocks = List.copyOf(Objects.requireNonNull(extraBlocks, "extraBlocks"));
        }

        public Success(Object structured) {
            this(structured, List.of());
        }
    }

    record Failure(ToolError error) implements ToolOutcome {
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }

    /** An additional content block of a success result. */
    sealed interface ContentBlock {
    }

    /** A {@code resource} block with a base64 {@code blob} (the adapter encodes {@code data}). */
    record EmbeddedResource(String uri, String mimeType, byte[] data) implements ContentBlock {
        public EmbeddedResource {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(data, "data");
        }

        /** Size only: the bytes are mail content and must never reach a log. */
        @Override
        public String toString() {
            return "EmbeddedResource[" + data.length + " bytes]";
        }
    }
}
