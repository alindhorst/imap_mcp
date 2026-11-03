package de.alexanderlindhorst.imapmcp.core.write;

import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import java.util.List;

public final class WritePreflight {

    public sealed interface Result {
        record Ok(ResolvedAccount account, List<Group> groups) implements Result {
        }

        record Failed(ToolError error) implements Result {
        }
    }

    public record Item(int index, String handle, MessageRef ref) {
    }

    public record Group(String folder, List<Item> items) {
    }

    public WritePreflight(AccountArgument accounts, MessageLocator locator) {
    }

    public Result check(ToolCallContext ctx, String account, List<String> handles, String targetFolder) {
        return new Result.Failed(ToolError.internalError(ctx.requestId()));
    }
}
