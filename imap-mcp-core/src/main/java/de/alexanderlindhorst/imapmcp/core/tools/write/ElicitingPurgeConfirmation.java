package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import de.alexanderlindhorst.imapmcp.core.error.ToolError.ElicitationReason;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.tool.ElicitationChannel;
import de.alexanderlindhorst.imapmcp.core.tool.ElicitationChannel.Answer;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import jakarta.mail.MessagingException;
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
 * <p>Session approval (SPEC 4 "Session approval"): created only by an accepted answer with {@code allow_for_session}
 * true, and only once this call's {@code tryBeginMutation()} returned true ({@link ToolCallContext#onMutationBegun}),
 * so a discarded answer or a call that lost the race with the deadline creates none. It is bound to tenant, account,
 * the Trash full name and the Trash UIDVALIDITY and UIDNEXT read by a STATUS right before the request; it covers a
 * call only if every handle carries that UIDVALIDITY and a UID below that UIDNEXT, the call's handle count fits the
 * remaining budget of 500 (charged atomically, never in part, never refunded) and 15 minutes (fixed, not sliding)
 * have not passed. One per account in process memory; a new one replaces the old. It is tried first, before the
 * cooldown. It removes only the dialog, never a check.
 */
public final class ElicitingPurgeConfirmation implements PurgeConfirmation {

    static final Duration COOLDOWN = Duration.ofSeconds(60);
    static final Duration DEADLINE_MARGIN = Duration.ofSeconds(20);
    static final Duration MIN_WINDOW = Duration.ofSeconds(10);
    /** Window when the call carries no deadline (outside the executor). */
    static final Duration FALLBACK_WINDOW = Duration.ofSeconds(60);
    static final int NAME_MAX = 200;
    static final String ALLOW_FOR_SESSION = "allow_for_session";
    static final Duration APPROVAL_LIFETIME = Duration.ofMinutes(15);
    static final int APPROVAL_BUDGET = 500;

    /** The Trash state recorded right before the request, to which an approval is bound. */
    public record TrashState(long uidValidity, long uidNext) {
    }

    /** Reads {@code STATUS <trash> (UIDVALIDITY UIDNEXT)}; a failure is the call's v1 whole-call error. */
    @FunctionalInterface
    interface TrashStatus {
        TrashState read(ImapLease lease, String trashName) throws MessagingException;
    }

    private static final TrashStatus LIVE_STATUS = (lease, name) -> {
        long[] s = UidCommands.statusUidState(lease, name);
        return new TrashState(s[0], s[1]);
    };

    /** The in-memory approval of one account. The budget is the only mutable part, guarded by the instance. */
    private static final class Approval {
        final String trashName;
        final long uidValidity;
        final long uidNext;
        final Instant expiresAt;
        private int remaining = APPROVAL_BUDGET;

        Approval(String trashName, TrashState state, Instant expiresAt) {
            this.trashName = trashName;
            this.uidValidity = state.uidValidity();
            this.uidNext = state.uidNext();
            this.expiresAt = expiresAt;
        }

        /** Atomically charges {@code count}; false (and nothing charged) if it does not cover the whole call. */
        synchronized boolean covers(Request r, Instant now) {
            if (!now.isBefore(expiresAt) || !trashName.equals(r.trashName()) || r.items().size() > remaining) {
                return false;
            }
            for (WritePreflight.Item i : r.items()) {
                if (i.ref().uidValidity() != uidValidity || i.ref().uid() >= uidNext) {
                    return false;
                }
            }
            remaining -= r.items().size();
            return true;
        }
    }

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of(ALLOW_FOR_SESSION,
            Map.of("type", "boolean", "title", "Allow for this session", "description",
                    "Also allow permanent deletion of messages already in this Trash folder for the next 15 minutes, "
                            + "up to 500 messages, without asking again.", "default", false)));

    private final Clock clock;
    private final Map<String, Instant> declinedUntil = new ConcurrentHashMap<>();
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final TrashStatus status;

    public ElicitingPurgeConfirmation(Clock clock) {
        this(clock, LIVE_STATUS);
    }

    ElicitingPurgeConfirmation(Clock clock, TrashStatus status) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.status = Objects.requireNonNull(status, "status");
    }

    @Override
    public boolean available(ToolCallContext ctx) {
        return ctx.elicitation().map(ElicitationChannel::supportsFormMode).orElse(false);
    }

    @Override
    public Decision confirm(ToolCallContext ctx, Request request) throws MessagingException {
        ElicitationChannel channel = ctx.elicitation().orElse(null);
        if (channel == null) {
            return new Decision.Refused(ElicitationReason.ERROR);
        }
        Instant now = clock.instant();
        String key = ctx.accountContext().tenantId().value() + "/" + request.account().account().id().value();
        Approval approval = approvals.get(key);
        if (approval != null && approval.covers(request, now)) {
            return new Decision.Confirmed(BulkResult.SESSION_APPROVAL);
        }
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
        TrashState trashState = status.read(request.lease(), request.trashName()); // nothing asked if this fails
        Answer answer;
        try {
            answer = channel.ask(message(request), new LinkedHashMap<>(SCHEMA), window);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // deadline or cancel: the plain TIMEOUT, never not_confirmed
            return new Decision.Expired();
        }
        return switch (answer) {
            case Answer.Accepted a -> {
                if (!validAnswer(a.content())) {
                    yield new Decision.Refused(ElicitationReason.INVALID_ANSWER);
                }
                if (a.content() != null && Boolean.TRUE.equals(a.content().get(ALLOW_FOR_SESSION))) {
                    ctx.onMutationBegun(() -> createApproval(key, request.trashName(), trashState));
                }
                yield new Decision.Confirmed(BulkResult.ELICITED);
            }
            case Answer.Declined d -> declined(key, ElicitationReason.DECLINED);
            case Answer.Cancelled c -> declined(key, ElicitationReason.CANCELLED);
            case Answer.TimedOut t -> new Decision.Refused(ElicitationReason.TIMEOUT);
            case Answer.Failed f -> new Decision.Refused(ElicitationReason.ERROR);
        };
    }

    private void createApproval(String key, String trashName, TrashState state) {
        Instant expires = clock.instant().plus(APPROVAL_LIFETIME);
        approvals.put(key, new Approval(trashName, state, expires));
        PurgeAudit.approvalCreated(expires, APPROVAL_BUDGET);
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
