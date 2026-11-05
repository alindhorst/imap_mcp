package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import jakarta.mail.Flags;
import jakarta.mail.MessagingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * The mutating step of the one Trash group of a purge (SPEC 5.4), run by {@link FolderGroupRunner} with the present
 * UIDs only: {@code UID STORE +FLAGS.SILENT (\Deleted)}, then {@code UID EXPUNGE} of exactly those UIDs, then one
 * post-check {@code UID FETCH} of the same set. The gate is called before each of the two mutating commands. A
 * refused STORE fails the group and sends nothing more (nothing changed). After a successful STORE the post-check
 * always runs, also when the UID EXPUNGE was refused: an absent UID is {@code ok}, a UID still present is
 * {@code failed} with {@code still_present} and the refused UID EXPUNGE's mapped error, otherwise
 * {@code UPSTREAM_ERROR}. {@code \Deleted} set here is never cleared; other {@code \Deleted} messages are never
 * expunged; never a plain EXPUNGE. Nothing is logged.
 */
final class PurgeStep implements FolderGroupRunner.MutationStep {

    /** Sends the commands; tests substitute scripted answers. */
    interface Wire {
        UidCommands.Result store(IMAPFolder folder, Collection<Long> uids) throws MessagingException;

        UidCommands.Result expunge(IMAPFolder folder, Collection<Long> uids) throws MessagingException;

        /** The subset of {@code uids} still in the folder. */
        Set<Long> present(IMAPFolder folder, Collection<Long> uids) throws MessagingException;
    }

    static final Wire LIVE = new Wire() {
        @Override
        public UidCommands.Result store(IMAPFolder f, Collection<Long> uids) throws MessagingException {
            return UidCommands.uidStore(f, uids, UidCommands.FlagMode.ADD, new Flags(Flags.Flag.DELETED));
        }

        @Override
        public UidCommands.Result expunge(IMAPFolder f, Collection<Long> uids) throws MessagingException {
            return UidCommands.uidExpunge(f, uids);
        }

        @Override
        public Set<Long> present(IMAPFolder f, Collection<Long> uids) throws MessagingException {
            return UidCommands.uidFetchPresent(f, uids);
        }
    };

    private final String trashName;
    private final String requestId;
    private final Wire wire;

    PurgeStep(String trashName, String requestId, Wire wire) {
        this.trashName = Objects.requireNonNull(trashName, "trashName");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.wire = Objects.requireNonNull(wire, "wire");
    }

    @Override
    public List<BulkResult.Item> apply(IMAPFolder folder, List<WritePreflight.Item> present,
            FolderGroupRunner.Gate gate) throws MessagingException {
        List<Long> uids = present.stream().map(i -> i.ref().uid()).toList();
        gate.beforeWrite();
        UidCommands.Result stored = wire.store(folder, uids);
        if (!stored.ok()) {
            ToolError e = refusal(stored);
            return present.stream().map(i -> BulkResult.Item.failed(i.handle(), e)).toList();
        }
        gate.beforeWrite();
        UidCommands.Result expunged = wire.expunge(folder, uids);
        ToolError expungeError = expunged.ok() ? null : refusal(expunged);
        Set<Long> left = wire.present(folder, uids);
        List<BulkResult.Item> out = new ArrayList<>(present.size());
        for (WritePreflight.Item i : present) {
            if (left.contains(i.ref().uid())) {
                out.add(BulkResult.Item.failedStillPresent(i.handle(),
                        expungeError != null ? expungeError : ToolError.upstreamError(null, requestId)));
            } else {
                out.add(BulkResult.Item.okPurged(i.handle()));
            }
        }
        return out;
    }

    private ToolError refusal(UidCommands.Result r) {
        return WriteResponses.map(r.tagged(), trashName, requestId)
                .orElseGet(() -> ToolError.upstreamError(r.tagged().getRest(), requestId));
    }
}
