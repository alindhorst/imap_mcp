package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import de.alexanderlindhorst.imapmcp.core.error.ToolError.ElicitationReason;
import de.alexanderlindhorst.imapmcp.core.tool.ElicitationChannel;
import de.alexanderlindhorst.imapmcp.core.tool.ElicitationChannel.Answer;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The user's consent through MCP elicitation (SPEC 4 v3a "Confirmation", step 9). Available when the session's
 * {@link ElicitationChannel} declared form mode. One form request with the fixed message (the number of messages,
 * the resolved Trash name and the account label from configuration, both single-line sanitized and capped; nothing
 * email-derived) and a schema with one optional boolean {@code allow_for_session}. Answers: accept without content
 * or with that boolean is {@code elicited}; accept with anything else {@code invalid_answer}; decline and cancel start
 * a 60 s cooldown per tenant and account in which no request is sent ({@code recently_declined}); no answer is
 * {@code timeout}; an error answer or a failed send is {@code error}. The window ends 20 s before the call's
 * deadline (the 15 s write budget plus a margin); with less than 10 s of window left no request is sent and the
 * decision is {@link Decision.Expired}, as it is when the worker is interrupted. Neither the message, the labels nor
 * the answer is logged.
 *
 * <p>Not yet here: the session approval (#285); an accepted {@code allow_for_session} is read and ignored.
 */
public final class ElicitingPurgeConfirmation implements PurgeConfirmation {

    static final Duration COOLDOWN = Duration.ofSeconds(60);
    static final Duration DEADLINE_MARGIN = Duration.ofSeconds(20);
    static final Duration MIN_WINDOW = Duration.ofSeconds(10);
    /** Window when the call carries no deadline (outside the executor). */
    static final Duration FALLBACK_WINDOW = Duration.ofSeconds(60);
    static final int NAME_MAX = 200;
    static final String ALLOW_FOR_SESSION = "allow_for_session";

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of(ALLOW_FOR_SESSION,
            Map.of("type", "boolean", "title", "Allow for this session", "description",
                    "Also allow permanent deletion of messages already in this Trash folder for the next 15 minutes, "
                            + "up to 500 messages, without asking again.", "default", false)));

    private final Clock clock;
    private final Map<String, Instant> declinedUntil = new ConcurrentHashMap<>();

    public ElicitingPurgeConfirmation(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean available(ToolCallContext ctx) {
        return ctx.elicitation().map(ElicitationChannel::supportsFormMode).orElse(false);
    }

    @Override
    public Decision confirm(ToolCallContext ctx, Request request) {
        ElicitationChannel channel = ctx.elicitation().orElse(null);
        if (channel == null) {
            return new Decision.Refused(ElicitationReason.ERROR);
        }
        Instant now = clock.instant();
        String key = ctx.accountContext().tenantId().value() + "/" + request.account().account().id().value();
        Instant until = declinedUntil.get(key);
        if (until != null) {
            if (now.isBefore(until)) {
                return new Decision.Refused(ElicitationReason.RECENTLY_DECLINED);
            }
            declinedUntil.remove(key, until);
        }
        Duration window = ctx.deadline().map(d -> Duration.between(now, d.minus(DEADLINE_MARGIN)))
                .orElse(FALLBACK_WINDOW);
        if (window.compareTo(MIN_WINDOW) < 0) {
            return new Decision.Expired();
        }
        Answer answer;
        try {
            answer = channel.ask(message(request), new LinkedHashMap<>(SCHEMA), window);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // deadline or cancel: the plain TIMEOUT, never not_confirmed
            return new Decision.Expired();
        }
        return switch (answer) {
            case Answer.Accepted a -> validAnswer(a.content())
                    ? new Decision.Confirmed(BulkResult.ELICITED)
                    : new Decision.Refused(ElicitationReason.INVALID_ANSWER);
            case Answer.Declined d -> declined(key, ElicitationReason.DECLINED);
            case Answer.Cancelled c -> declined(key, ElicitationReason.CANCELLED);
            case Answer.TimedOut t -> new Decision.Refused(ElicitationReason.TIMEOUT);
            case Answer.Failed f -> new Decision.Refused(ElicitationReason.ERROR);
        };
    }

    private Decision declined(String key, ElicitationReason reason) {
        declinedUntil.put(key, clock.instant().plus(COOLDOWN));
        return new Decision.Refused(reason);
    }

    /** No content, or only the optional boolean {@code allow_for_session}. */
    private static boolean validAnswer(Map<String, Object> content) {
        if (content == null || content.isEmpty()) {
            return true;
        }
        return content.size() == 1 && content.get(ALLOW_FOR_SESSION) instanceof Boolean;
    }

    private static String message(Request r) {
        int n = r.items().size();
        String count = n == 1 ? "1 message" : n + " messages";
        return "Permanently delete " + count + " from the Trash folder \"" + UntrustedText.singleLine(r.trashName(), NAME_MAX)
                + "\" of account \"" + UntrustedText.singleLine(r.account().account().label(), NAME_MAX)
                + "\"? This cannot be undone.";
    }
}
