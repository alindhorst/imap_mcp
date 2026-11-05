package de.alexanderlindhorst.imapmcp.core.tool;

import java.time.Duration;
import java.util.Map;

/**
 * The way back to the user's client for one tool call of a stateful session (MCP elicitation, SPEC 5.5). SDK-free:
 * only {@code ..core.mcp} implements it, so tool code does not know the MCP SDK or the transport mode. The
 * stateless server supplies none. Answers carry the user's content only; nothing here is ever logged.
 */
public interface ElicitationChannel {

    /** The client declared form-mode elicitation (URL mode alone does not count). */
    boolean supportsFormMode();

    /**
     * Sends exactly one form-mode request and waits at most {@code window} for the answer.
     *
     * @throws InterruptedException the calling worker was interrupted (deadline or cancel); the request is disposed
     */
    Answer ask(String message, Map<String, Object> requestedSchema, Duration window) throws InterruptedException;

    sealed interface Answer {
        /** @param content the form content, or null when the client sent none */
        record Accepted(Map<String, Object> content) implements Answer {
        }

        record Declined() implements Answer {
        }

        record Cancelled() implements Answer {
        }

        /** No answer within the window. */
        record TimedOut() implements Answer {
        }

        /** A JSON-RPC error answer, or the request could not be sent. */
        record Failed() implements Answer {
        }
    }
}
