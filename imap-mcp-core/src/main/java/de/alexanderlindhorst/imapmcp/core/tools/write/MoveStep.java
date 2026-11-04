package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import jakarta.mail.Flags;
import jakarta.mail.MessagingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * The mutating step of one source-folder group of a move (SPEC 5.4), run by {@link FolderGroupRunner} with the
 * present UIDs only: one {@code UID MOVE} to the target (never selected). New handles come from the COPYUID codes of the command, merged (destination
 * UIDVALIDITY and UID, paired by position); a present UID missing from the COPYUID source set is
 * {@code already_gone}; without COPYUID every present UID went and is {@code ok} with {@code new_uid_unknown}. A
 * refused command fails the group's items with the mapped error ({@code [TRYCREATE]}, {@code [OVERQUOTA]}, ...).
 *
 * <p>Without MOVE (#191): {@code UID COPY} of the present UIDs, then, only after the COPY succeeded, {@code UID STORE
 * +FLAGS.SILENT (\Deleted)} and {@code UID EXPUNGE} of exactly the copied UIDs (the COPYUID source set, or the
 * present set if COPYUID is absent); other {@code \Deleted} messages are never expunged, never a plain EXPUNGE. The
 * gate is called before each of the three commands. A refused COPY fails the group and sends nothing more; a
 * refused STORE skips the EXPUNGE; either way the copied items are failed ({@link #copiedButNotMoved}). Nothing is
 * logged.
 */
public final class MoveStep implements FolderGroupRunner.MutationStep {

    /** Sends the commands; tests substitute scripted answers. */
    interface Wire {
        UidCommands.Result move(IMAPFolder folder, Collection<Long> uids, String target) throws MessagingException;

        UidCommands.Result copy(IMAPFolder folder, Collection<Long> uids, String target) throws MessagingException;

        UidCommands.Result markDeleted(IMAPFolder folder, Collection<Long> uids) throws MessagingException;

        UidCommands.Result expunge(IMAPFolder folder, Collection<Long> uids) throws MessagingException;
    }

    /** Sends the move only; the fallback verbs are unavailable. */
    @FunctionalInterface
    interface Mover {
        UidCommands.Result move(IMAPFolder folder, Collection<Long> uids, String target) throws MessagingException;
    }

    private static final Wire LIVE = new Wire() {
        @Override
        public UidCommands.Result move(IMAPFolder f, Collection<Long> uids, String target)
                throws MessagingException {
            return UidCommands.uidMove(f, uids, target);
        }

        @Override
        public UidCommands.Result copy(IMAPFolder f, Collection<Long> uids, String target)
                throws MessagingException {
            return UidCommands.uidCopy(f, uids, target);
        }

        @Override
        public UidCommands.Result markDeleted(IMAPFolder f, Collection<Long> uids) throws MessagingException {
            return UidCommands.uidStore(f, uids, UidCommands.FlagMode.ADD, new Flags(Flags.Flag.DELETED));
        }

        @Override
        public UidCommands.Result expunge(IMAPFolder f, Collection<Long> uids) throws MessagingException {
            return UidCommands.uidExpunge(f, uids);
        }
    };

    private final String target;
    private final boolean moveAdvertised;
    private final Function<MessageRef, String> mint;
    private final String requestId;
    private final Wire wire;
    private volatile boolean usedFallback;

    /**
     * @param target the resolved target full name
     * @param mint   mints a handle for a message in the target (tenant and account bound by the caller)
     */
    public MoveStep(String target, boolean moveAdvertised, Function<MessageRef, String> mint, String requestId) {
        this(target, moveAdvertised, mint, requestId, LIVE);
    }

    MoveStep(String target, boolean moveAdvertised, Function<MessageRef, String> mint, String requestId,
            Mover mover) {
        this(target, moveAdvertised, mint, requestId, new Wire() {
            @Override
            public UidCommands.Result move(IMAPFolder f, Collection<Long> uids, String t) throws MessagingException {
                return mover.move(f, uids, t);
            }

            @Override
            public UidCommands.Result copy(IMAPFolder f, Collection<Long> uids, String t) {
                throw new UnsupportedOperationException();
            }

            @Override
            public UidCommands.Result markDeleted(IMAPFolder f, Collection<Long> uids) {
                throw new UnsupportedOperationException();
            }

            @Override
            public UidCommands.Result expunge(IMAPFolder f, Collection<Long> uids) {
                throw new UnsupportedOperationException();
            }
        });
    }

    MoveStep(String target, boolean moveAdvertised, Function<MessageRef, String> mint, String requestId,
            Wire wire) {
        this.target = Objects.requireNonNull(target, "target");
        this.moveAdvertised = moveAdvertised;
        this.mint = Objects.requireNonNull(mint, "mint");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.wire = Objects.requireNonNull(wire, "wire");
    }

    @Override
    public List<BulkResult.Item> apply(IMAPFolder folder, List<WritePreflight.Item> present,
            FolderGroupRunner.Gate gate) throws MessagingException {
        List<Long> uids = present.stream().map(i -> i.ref().uid()).toList();
        gate.beforeWrite();
        UidCommands.Result r = moveAdvertised ? wire.move(folder, uids, target) : wire.copy(folder, uids, target);
        if (!r.ok()) {
            return failAll(present, refusal(r));
        }
        Optional<CopyUidParser.CopyUid> copyUid = CopyUidParser.parseAll(r.responses());
        ToolError notMoved = null;
        if (!moveAdvertised) {
            usedFallback = true;
            List<Long> copied = copyUid.map(CopyUidParser.CopyUid::sourceUids).orElse(uids);
            gate.beforeWrite();
            UidCommands.Result stored = wire.markDeleted(folder, copied);
            if (stored.ok()) {
                gate.beforeWrite();
                UidCommands.Result expunged = wire.expunge(folder, copied);
                notMoved = expunged.ok() ? null : refusal(expunged);
            } else {
                notMoved = refusal(stored);
            }
        }
        List<BulkResult.Item> out = new ArrayList<>(present.size());
        for (WritePreflight.Item i : present) {
            if (copyUid.isEmpty()) {
                out.add(notMoved != null ? copiedButNotMoved(i, notMoved) : BulkResult.Item.okNewUidUnknown(i.handle()));
                continue;
            }
            OptionalLong dst = copyUid.get().destinationOf(i.ref().uid());
            out.add(dst.isEmpty() ? BulkResult.Item.alreadyGone(i.handle())
                    : notMoved != null ? copiedButNotMoved(i, notMoved)
                    : BulkResult.Item.ok(i.handle(),
                            mint.apply(new MessageRef(target, copyUid.get().uidValidity(), dst.getAsLong()))));
        }
        return out;
    }

    /** Whether the fallback was used by any group of this step (for the call log path). */
    public boolean usedFallback() {
        return usedFallback;
    }

    /** The one place deciding what an item is whose COPY succeeded but whose STORE or UID EXPUNGE was refused. */
    private static BulkResult.Item copiedButNotMoved(WritePreflight.Item i, ToolError e) {
        return BulkResult.Item.failed(i.handle(), e);
    }

    private ToolError refusal(UidCommands.Result r) {
        return WriteResponses.map(r.tagged(), target, requestId)
                .orElseGet(() -> ToolError.upstreamError(r.tagged().getRest(), requestId));
    }

    private static List<BulkResult.Item> failAll(List<WritePreflight.Item> present, ToolError e) {
        return present.stream().map(i -> BulkResult.Item.failed(i.handle(), e)).toList();
    }
}
