package de.alexanderlindhorst.imapmcp.core.tools.write;

import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.error.ToolError.PermissionReason;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Steps 2 to 5 of the bulk write check order (SPEC 4 "v2 write contract"), with zero IMAP I/O and no lease: the
 * class has no IMAP dependency, so a bad call cannot cost a connection. Order: account, read-only account (before
 * the handles), handles (first failing position, one identical INVALID_HANDLE payload plus {@code handles[i]}),
 * duplicates by decoded reference, {@code too_many_folders}, and for copy a verbatim target equal to a source
 * folder. Identity comes only from the {@code ToolCallContext}; the account only from the tenant-scoped lookup.
 * Success yields the items grouped by source folder (verbatim full name, INBOX normalized as in v1), in order of
 * first appearance, each item keeping its input index.
 */
public final class WritePreflight {

    public static final int MAX_FOLDERS = 10;
    private static final String INBOX = "INBOX";

    public sealed interface Result {
        record Ok(ResolvedAccount account, List<Group> groups) implements Result {
        }

        record Failed(ToolError error) implements Result {
        }
    }

    /** One input handle: its position, the handle as given and the decoded reference (folder INBOX-normalized). */
    public record Item(int index, String handle, MessageRef ref) {
    }

    /** Items of one source folder in input order. */
    public record Group(String folder, List<Item> items) {
    }

    private final AccountArgument accounts;
    private final MessageLocator locator;

    public WritePreflight(AccountArgument accounts, MessageLocator locator) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    /**
     * @param targetFolder verbatim {@code target_folder} of copy_messages, or null (move/delete/set_flags or a
     *                     role target, which is checked after its resolution)
     */
    public Result check(ToolCallContext ctx, String account, List<String> handles, String targetFolder) {
        return check(ctx, account, handles, targetFolder, null);
    }

    /**
     * @param accountGate checks that come right after the read-only check and before the handles, with no I/O
     *                    (purge_messages: {@code purge_disabled}, {@code confirmation_unavailable}); returns the
     *                    refusal or null; may be null
     */
    public Result check(ToolCallContext ctx, String account, List<String> handles, String targetFolder,
            Function<ResolvedAccount, ToolError> accountGate) {
        Objects.requireNonNull(handles, "handles");
        String rid = ctx.requestId();
        ResolvedAccount resolved;
        switch (accounts.resolve(ctx, account)) {
            case AccountArgument.Result.NotFound nf -> {
                return new Result.Failed(nf.error());
            }
            case AccountArgument.Result.Ok ok -> resolved = ok.account();
        }
        if (resolved.account().readOnly()) {
            return new Result.Failed(ToolError.permissionDenied(PermissionReason.ACCOUNT_READ_ONLY, rid));
        }
        if (accountGate != null) {
            ToolError refusal = accountGate.apply(resolved);
            if (refusal != null) {
                return new Result.Failed(refusal);
            }
        }
        List<Item> items = new ArrayList<>(handles.size());
        for (int i = 0; i < handles.size(); i++) {
            switch (locator.check(ctx, resolved, handles.get(i))) {
                case MessageLocator.Checked.Invalid inv -> {
                    return new Result.Failed(ToolError.invalidHandle("handles[" + i + "]", rid));
                }
                case MessageLocator.Checked.Ok ok -> items.add(new Item(i, handles.get(i), normalized(ok.ref())));
            }
        }
        Set<MessageRef> seen = new HashSet<>();
        for (Item item : items) {
            if (!seen.add(item.ref())) {
                return new Result.Failed(ToolError.invalidArgument("handles[" + item.index() + "]", "duplicate",
                        null, rid));
            }
        }
        Map<String, List<Item>> byFolder = new LinkedHashMap<>();
        for (Item item : items) {
            byFolder.computeIfAbsent(item.ref().folder(), f -> new ArrayList<>()).add(item);
        }
        if (byFolder.size() > MAX_FOLDERS) {
            return new Result.Failed(ToolError.invalidArgument("handles", "too_many_folders", MAX_FOLDERS, rid));
        }
        if (targetFolder != null && byFolder.containsKey(normalized(targetFolder))) {
            return new Result.Failed(ToolError.invalidArgument("target_folder", "same_folder", null, rid));
        }
        List<Group> groups = new ArrayList<>(byFolder.size());
        byFolder.forEach((f, list) -> groups.add(new Group(f, List.copyOf(list))));
        return new Result.Ok(resolved, List.copyOf(groups));
    }

    private static String normalized(String folder) {
        return INBOX.equalsIgnoreCase(folder) ? INBOX : folder;
    }

    private static MessageRef normalized(MessageRef ref) {
        String f = normalized(ref.folder());
        return f.equals(ref.folder()) ? ref : new MessageRef(f, ref.uidValidity(), ref.uid());
    }
}
