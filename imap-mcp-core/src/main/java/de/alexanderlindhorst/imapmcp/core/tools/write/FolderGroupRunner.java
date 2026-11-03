package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.MessagingException;
import java.time.Clock;
import java.util.List;
import org.eclipse.angus.mail.imap.IMAPFolder;

/** Skeleton for the red commit. */
public final class FolderGroupRunner {

    public static final java.time.Duration WRITE_BUDGET = java.time.Duration.ofSeconds(15);

    /** Status of a UID that the pre-check did not find. */
    public enum AbsentStatus { ALREADY_GONE, NOT_FOUND }

    /** Points at which tests inject faults. */
    enum HookPoint { BEFORE_WRITE, AFTER_WRITE, BETWEEN_GROUPS }

    /** Package-private fault-injection seam. */
    interface StepHook {
        void at(HookPoint point, int groupIndex, int commandIndex) throws MessagingException;
    }

    /** Handed to the mutation step. */
    public interface Gate {
        void beforeWrite() throws MessagingException;
    }

    /** The tool-specific mutating step of one group. */
    @FunctionalInterface
    public interface MutationStep {
        List<BulkResult.Item> apply(IMAPFolder folder, List<WritePreflight.Item> present, Gate gate)
                throws MessagingException;
    }

    public FolderGroupRunner(Clock clock) {
        this(clock, (p, g, c) -> { });
    }

    FolderGroupRunner(Clock clock, StepHook hook) {
    }

    public List<BulkResult.Item> run(ImapLease lease, ToolCallContext ctx, List<WritePreflight.Group> groups,
            AbsentStatus absent, MutationStep step) throws MessagingException {
        throw new UnsupportedOperationException();
    }
}
