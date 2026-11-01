package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.MessagingException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Folder counts for {@code list_folders} (SPEC 4): exactly one {@code STATUS <name> (MESSAGES UNSEEN)} per
 * selectable entry, as a store-level command on a folder object that is never opened (no SELECT/EXAMINE, so
 * nothing is marked read). A NO/BAD answer for one folder leaves that folder's counts null; any other failure
 * (connection) marks the lease broken and stops counting. Counting also stops at 75% of the time that remains
 * until the call deadline; the rest stays null. Either way the result is "not complete", never an error.
 * Non-selectable entries always keep null counts and do not make the result incomplete.
 */
public final class StatusCountEnricher implements ListFoldersTool.CountEnricher {

    private static final Logger LOG = LoggerFactory.getLogger(StatusCountEnricher.class);
    private static final String[] ITEMS = {"MESSAGES", "UNSEEN"};

    @Override
    public boolean enrich(ImapLease lease, ToolCallContext ctx, List<Map<String, Object>> entries)
            throws MessagingException {
        Optional<Instant> cutoff = cutoff(Instant.now(), ctx.deadline());
        boolean complete = true;
        boolean stopped = false;
        for (Map<String, Object> e : entries) {
            if (!Boolean.TRUE.equals(e.get("selectable"))) {
                continue;
            }
            stopped = stopped || ctx.isCancelled() || Thread.currentThread().isInterrupted()
                    || cutoff.isPresent() && !Instant.now().isBefore(cutoff.get());
            if (stopped) {
                complete = false;
                continue;
            }
            Status s;
            try {
                s = status(lease, (String) e.get("folder"));
            } catch (MessagingException ex) {
                complete = false;
                if (!commandRejected(ex)) {
                    lease.markBroken();
                    stopped = true;
                }
                LOG.debug("STATUS failed: {}", ex.getClass().getSimpleName());
                continue;
            }
            if (s == null || s.total < 0 || s.unseen < 0) {
                complete = false;
                continue;
            }
            e.put("total", s.total);
            e.put("unread", s.unseen);
        }
        return complete;
    }

    private static Status status(ImapLease lease, String name) throws MessagingException {
        IMAPFolder f = (IMAPFolder) lease.store().getFolder(name);
        return (Status) f.doCommand(p -> p.status(name, ITEMS));
    }

    private static boolean commandRejected(Throwable t) {
        for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
            if (t instanceof CommandFailedException) {
                return true;
            }
        }
        return false;
    }

    /** 75% of the remaining time until {@code deadline}; empty when there is no deadline. */
    static Optional<Instant> cutoff(Instant now, Optional<Instant> deadline) {
        return deadline.map(d -> {
            Duration remaining = Duration.between(now, d);
            return remaining.isNegative() ? now : now.plus(remaining.multipliedBy(3).dividedBy(4));
        });
    }
}
