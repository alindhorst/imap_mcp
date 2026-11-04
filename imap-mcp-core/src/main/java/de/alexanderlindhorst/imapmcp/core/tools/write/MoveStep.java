package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
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
 * <p>Interim (#190): when the connection advertises no MOVE the group fails {@code UNSUPPORTED} ({@code MOVE})
 * without a command and without calling the gate. #191 replaces exactly that branch with UID COPY, UID STORE
 * +FLAGS.SILENT (\Deleted) and UID EXPUNGE of the copied UIDs. Nothing is logged.
 */
public final class MoveStep implements FolderGroupRunner.MutationStep {

    /** Sends the move; tests substitute scripted answers. */
    @FunctionalInterface
    interface Mover {
        UidCommands.Result move(IMAPFolder folder, Collection<Long> uids, String target) throws MessagingException;
    }

    private final String target;
    private final boolean moveAdvertised;
    private final Function<MessageRef, String> mint;
    private final String requestId;
    private final Mover mover;

    /**
     * @param target the resolved target full name
     * @param mint   mints a handle for a message in the target (tenant and account bound by the caller)
     */
    public MoveStep(String target, boolean moveAdvertised, Function<MessageRef, String> mint, String requestId) {
        this(target, moveAdvertised, mint, requestId, UidCommands::uidMove);
    }

    MoveStep(String target, boolean moveAdvertised, Function<MessageRef, String> mint, String requestId,
            Mover mover) {
        this.target = Objects.requireNonNull(target, "target");
        this.moveAdvertised = moveAdvertised;
        this.mint = Objects.requireNonNull(mint, "mint");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.mover = Objects.requireNonNull(mover, "mover");
    }

    @Override
    public List<BulkResult.Item> apply(IMAPFolder folder, List<WritePreflight.Item> present,
            FolderGroupRunner.Gate gate) throws MessagingException {
        if (!moveAdvertised) {
            return failAll(present, ToolError.unsupported("MOVE", requestId));
        }
        List<Long> uids = present.stream().map(i -> i.ref().uid()).toList();
        gate.beforeWrite();
        UidCommands.Result r = mover.move(folder, uids, target);
        if (!r.ok()) {
            ToolError e = WriteResponses.map(r.tagged(), target, requestId)
                    .orElseGet(() -> ToolError.upstreamError(r.tagged().getRest(), requestId));
            return failAll(present, e);
        }
        Optional<CopyUidParser.CopyUid> copyUid = CopyUidParser.parseAll(r.responses());
        List<BulkResult.Item> out = new ArrayList<>(present.size());
        for (WritePreflight.Item i : present) {
            if (copyUid.isEmpty()) {
                out.add(BulkResult.Item.okNewUidUnknown(i.handle()));
                continue;
            }
            OptionalLong dst = copyUid.get().destinationOf(i.ref().uid());
            out.add(dst.isEmpty() ? BulkResult.Item.alreadyGone(i.handle())
                    : BulkResult.Item.ok(i.handle(),
                            mint.apply(new MessageRef(target, copyUid.get().uidValidity(), dst.getAsLong()))));
        }
        return out;
    }

    private static List<BulkResult.Item> failAll(List<WritePreflight.Item> present, ToolError e) {
        return present.stream().map(i -> BulkResult.Item.failed(i.handle(), e)).toList();
    }
}
