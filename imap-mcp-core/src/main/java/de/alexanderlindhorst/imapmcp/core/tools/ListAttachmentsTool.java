package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.mime.AttachmentClassifier;
import de.alexanderlindhorst.imapmcp.core.mime.AttachmentList;
import de.alexanderlindhorst.imapmcp.core.mime.BodyPartSelector;
import de.alexanderlindhorst.imapmcp.core.mime.SelectedPart;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import jakarta.mail.MessagingException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;

/**
 * {@code list_attachments} (SPEC 4): name, type and size of a message's attachments, from BODYSTRUCTURE alone
 * (no body is fetched, nothing can be marked read). Order: account, handle (no I/O), then the lease.
 * Email-derived values only appear under {@code untrusted}.
 */
public final class ListAttachmentsTool implements ToolHandler {

    public static final String NAME = "list_attachments";

    static final Map<String, Object> INPUT_SCHEMA = schema("type", "object",
            "properties", schema(
                    "account", schema("type", "string", "description", "Account handle from list_accounts."),
                    "message", schema("type", "string", "minLength", 1, "maxLength", 1400,
                            "description", "Message handle from search_messages or get_message.")),
            "required", List.of("account", "message"), "additionalProperties", false);

    static final Map<String, Object> OUTPUT_SCHEMA = schema("type", "object",
            "properties", schema("account", schema("type", "string"),
                    "handle", schema("type", "string"),
                    "content_notice", schema("type", "string"),
                    "attachments", AttachmentEntries.ARRAY_SCHEMA,
                    "total", schema("type", "integer"),
                    "truncated", schema("type", "boolean")),
            "required", List.of("account", "handle", "content_notice", "attachments", "total", "truncated"));

    public static final ToolDefinition DEFINITION = new ToolDefinition(NAME, "List attachments",
            "List the attachments of one message: part, size, inline flag, file name and content type. Metadata "
                    + "only; nothing is downloaded. Read-only; does not change flags or mark messages as read.",
            INPUT_SCHEMA, OUTPUT_SCHEMA, ToolHints.READ_ONLY_OPEN_WORLD);

    private final AccountArgument accounts;
    private final MessageLocator locator;
    private final StoreLeases leases;

    public ListAttachmentsTool(AccountArgument accounts, MessageLocator locator, StoreLeases leases) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.locator = Objects.requireNonNull(locator, "locator");
        this.leases = Objects.requireNonNull(leases, "leases");
    }

    @Override
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        String accountHandle = (String) args.get("account");
        String messageHandle = (String) args.get("message");
        AccountArgument.Result resolved = accounts.resolve(ctx, accountHandle);
        if (!(resolved instanceof AccountArgument.Result.Ok ok)) {
            return new ToolOutcome.Failure(((AccountArgument.Result.NotFound) resolved).error());
        }
        MessageLocator.Checked checked = locator.check(ctx, ok.account(), messageHandle);
        if (!(checked instanceof MessageLocator.Checked.Ok ref)) {
            return new ToolOutcome.Failure(((MessageLocator.Checked.Invalid) checked).error());
        }
        try (ImapLease lease = leases.lease(ok.key(), ctx)) {
            try {
                MessageLocator.Located located = locator.locate(lease, ctx, ref.ref());
                if (!(located instanceof MessageLocator.Located.Found found)) {
                    return new ToolOutcome.Failure(((MessageLocator.Located.Failed) located).error());
                }
                // FETCH n (BODYSTRUCTURE) only; the sequence number is internal to this open folder.
                int seq = found.message().getMessageNumber();
                BODYSTRUCTURE bs = (BODYSTRUCTURE) found.folder().doCommand(p -> p.fetchBodyStructure(seq));
                String bodyPath = BodyPartSelector.select(bs).map(SelectedPart::sectionPath).orElse(null);
                return new ToolOutcome.Success(output(accountHandle, messageHandle,
                        AttachmentClassifier.classify(bs, bodyPath)));
            } catch (MessagingException | RuntimeException e) {
                ImapErrorMapper.MappedError mapped = ImapErrorMapper.map(e, ctx.requestId());
                if (mapped.discardConnection()) {
                    lease.markBroken();
                }
                return new ToolOutcome.Failure(mapped.error());
            }
        } catch (RuntimeException e) {
            return new ToolOutcome.Failure(ImapErrorMapper.map(e, ctx.requestId()).error());
        }
    }

    private static Map<String, Object> output(String account, String handle, AttachmentList list) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", account);
        out.put("handle", handle);
        out.put("content_notice", UntrustedText.CONTENT_NOTICE);
        out.put("attachments", AttachmentEntries.toJson(list));
        out.put("total", list.total());
        out.put("truncated", list.truncated());
        return out;
    }

    private static Map<String, Object> schema(Object... kv) {
        return Schemas.of(kv);
    }
}
