package de.alexanderlindhorst.imapmcp.core.tools;

import de.alexanderlindhorst.imapmcp.core.content.UntrustedText;
import de.alexanderlindhorst.imapmcp.core.error.ImapErrorMapper;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.mime.AttachmentClassifier;
import de.alexanderlindhorst.imapmcp.core.mime.AttachmentList;
import de.alexanderlindhorst.imapmcp.core.mime.BodyPartSelector;
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindow;
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader;
import de.alexanderlindhorst.imapmcp.core.mime.HeaderFields;
import de.alexanderlindhorst.imapmcp.core.mime.SelectedPart;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHints;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import jakarta.mail.Flags;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.protocol.BODY;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;
import org.eclipse.angus.mail.imap.protocol.ENVELOPE;
import org.eclipse.angus.mail.imap.protocol.FLAGS;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.INTERNALDATE;
import org.eclipse.angus.mail.imap.protocol.RFC822SIZE;

/**
 * {@code get_message} (SPEC 4): envelope, optional raw headers, one window of the text body and the attachment
 * list of a message. Everything comes from one {@code FETCH} with {@code BODY.PEEK} sections (never a plain
 * {@code BODY[}), so nothing can be marked read. Order: account, handle (no I/O), then the lease. Every
 * email-derived value sits under {@code untrusted}; flags, size and the received time are server data.
 */
public final class GetMessageTool implements ToolHandler {

    public static final String NAME = "get_message";
    public static final int DEFAULT_MAX_CHARS = 20_000;
    public static final int MAX_CHARS_CEILING = 100_000;
    static final int MAX_RECIPIENTS = 100;
    static final int MAX_FLAGS = 50;

    private static final Map<String, Object> NULLABLE_STRING = Schemas.of("type", List.of("string", "null"));
    private static final Map<String, Object> ADDRESS = Schemas.of("type", List.of("object", "null"),
            "properties", Schemas.of("name", NULLABLE_STRING, "address", Schemas.of("type", "string")),
            "required", List.of("name", "address"));
    private static final Map<String, Object> ADDRESS_LIST = Schemas.of("type", "array",
            "items", Schemas.of("type", "object",
                    "properties", Schemas.of("name", NULLABLE_STRING, "address", Schemas.of("type", "string")),
                    "required", List.of("name", "address")));
    private static final Map<String, Object> BODY_SCHEMA = Schemas.of("type", "object",
            "properties", Schemas.of("content_type", NULLABLE_STRING,
                    "converted_from_html", Schemas.of("type", "boolean"),
                    "charset", NULLABLE_STRING,
                    "offset", Schemas.of("type", "integer"),
                    "returned_chars", Schemas.of("type", "integer"),
                    "total_chars", Schemas.of("type", List.of("integer", "null")),
                    "text", Schemas.of("type", "string"),
                    "truncated", Schemas.of("type", "boolean"),
                    "next_offset", Schemas.of("type", List.of("integer", "null")),
                    "source_truncated", Schemas.of("type", "boolean")),
            "required", List.of("content_type", "converted_from_html", "charset", "offset", "returned_chars",
                    "total_chars", "text", "truncated", "next_offset", "source_truncated"));
    private static final Map<String, Object> UNTRUSTED_SCHEMA = Schemas.of("type", "object",
            "properties", Schemas.of("from", ADDRESS, "to", ADDRESS_LIST, "cc", ADDRESS_LIST,
                    "reply_to", ADDRESS_LIST,
                    "recipients_truncated", Schemas.of("type", "boolean"),
                    "subject", NULLABLE_STRING, "sent_at", NULLABLE_STRING,
                    "message_id", NULLABLE_STRING, "in_reply_to", NULLABLE_STRING,
                    "headers", Schemas.of("type", "array", "items", Schemas.of("type", "object",
                            "properties", Schemas.of("name", Schemas.of("type", "string"),
                                    "value", Schemas.of("type", "string")),
                            "required", List.of("name", "value"))),
                    "headers_truncated", Schemas.of("type", "boolean"),
                    "body", BODY_SCHEMA),
            "required", List.of("from", "to", "cc", "reply_to", "recipients_truncated", "subject", "sent_at",
                    "message_id", "in_reply_to", "body"));

    static final Map<String, Object> OUTPUT_SCHEMA = Schemas.of("type", "object",
            "properties", Schemas.of("account", Schemas.of("type", "string"),
                    "handle", Schemas.of("type", "string"),
                    "folder", Schemas.of("type", "string"),
                    "received_at", NULLABLE_STRING,
                    "size", Schemas.of("type", List.of("integer", "null")),
                    "flags", Schemas.of("type", "array", "items", Schemas.of("type", "string")),
                    "content_notice", Schemas.of("type", "string"),
                    "untrusted", UNTRUSTED_SCHEMA,
                    "attachments", AttachmentEntries.ARRAY_SCHEMA,
                    "attachments_total", Schemas.of("type", "integer"),
                    "attachments_truncated", Schemas.of("type", "boolean")),
            "required", List.of("account", "handle", "folder", "received_at", "size", "flags", "content_notice",
                    "untrusted", "attachments", "attachments_total", "attachments_truncated"));

    /** The definition with the published {@code max_chars} maximum equal to the configured cap. */
    public static ToolDefinition definition(int maxCharsCap) {
        checkCap(maxCharsCap);
        return new ToolDefinition(NAME, "Read message",
                "Read one message: envelope (from, to, cc, reply-to, subject, dates), the text body as a window of "
                        + "max_chars characters starting at offset (HTML is converted to text; use next_offset to "
                        + "continue), the attachment list and optionally all headers. Read-only; does not change "
                        + "flags or mark messages as read.",
                inputSchema(maxCharsCap), OUTPUT_SCHEMA, ToolHints.READ_ONLY_OPEN_WORLD);
    }

    static Map<String, Object> inputSchema(int maxCharsCap) {
        return Schemas.of("type", "object",
                "properties", Schemas.of(
                        "account", Schemas.of("type", "string", "description", "Account handle from list_accounts."),
                        "message", Schemas.of("type", "string", "minLength", 1, "maxLength", 1400,
                                "description", "Message handle from search_messages."),
                        "max_chars", Schemas.of("type", "integer", "minimum", 1, "maximum", maxCharsCap,
                                "default", Math.min(DEFAULT_MAX_CHARS, maxCharsCap),
                                "description", "Characters (Unicode code points) of body text to return."),
                        "offset", Schemas.of("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE,
                                "default", 0,
                                "description", "Character offset into the body text; use next_offset to continue."),
                        "include_headers", Schemas.of("type", "boolean", "default", false,
                                "description", "Also return all header fields (decoded, size-capped).")),
                "required", List.of("account", "message"), "additionalProperties", false);
    }

    private static void checkCap(int cap) {
        if (cap < 1 || cap > MAX_CHARS_CEILING) {
            throw new IllegalArgumentException("maxCharsCap must be in 1.." + MAX_CHARS_CEILING);
        }
    }

    private final AccountArgument accounts;
    private final MessageLocator locator;
    private final StoreLeases leases;
    private final BodyWindowReader reader;
    private final int maxCharsCap;

    public GetMessageTool(AccountArgument accounts, MessageLocator locator, StoreLeases leases,
            BodyWindowReader reader, int maxCharsCap) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.locator = Objects.requireNonNull(locator, "locator");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.reader = Objects.requireNonNull(reader, "reader");
        checkCap(maxCharsCap);
        this.maxCharsCap = maxCharsCap;
    }

    /** What one FETCH returns; the header block is the full block or just the Date field. */
    private record Fetched(ENVELOPE envelope, Flags flags, Date internalDate, long size, BODYSTRUCTURE structure,
            byte[] headers) {
    }

    @Override
    public ToolOutcome handle(ToolCallContext ctx, Map<String, Object> args) {
        String accountHandle = (String) args.get("account");
        String messageHandle = (String) args.get("message");
        int maxChars = args.get("max_chars") instanceof Number n ? n.intValue()
                : Math.min(DEFAULT_MAX_CHARS, maxCharsCap);
        long offset = args.get("offset") instanceof Number n ? n.longValue() : 0L;
        boolean includeHeaders = Boolean.TRUE.equals(args.get("include_headers"));
        AccountArgument.Result resolved = accounts.resolve(ctx, accountHandle);
        if (!(resolved instanceof AccountArgument.Result.Ok ok)) {
            return new ToolOutcome.Failure(((AccountArgument.Result.NotFound) resolved).error());
        }
        MessageLocator.Checked checked = locator.check(ctx, ok.account(), messageHandle);
        if (!(checked instanceof MessageLocator.Checked.Ok ref)) {
            return new ToolOutcome.Failure(((MessageLocator.Checked.Invalid) checked).error());
        }
        try {
            return leases.withLease(ok.key(), ctx, lease -> {
                try {
                    MessageLocator.Located located = locator.locate(lease, ctx, ref.ref());
                    if (!(located instanceof MessageLocator.Located.Found found)) {
                        return new ToolOutcome.Failure(((MessageLocator.Located.Failed) located).error());
                    }
                    // The sequence number is internal to this open folder and never leaves this call.
                    int seq = found.message().getMessageNumber();
                    Fetched f = fetch(found.folder(), seq, includeHeaders);
                    Optional<SelectedPart> part = BodyPartSelector.select(f.structure());
                    AttachmentList attachments = AttachmentClassifier.classify(f.structure(),
                            part.map(SelectedPart::sectionPath).orElse(null));
                    BodyWindow window = part.isPresent()
                            ? reader.read(found.folder(), ref.ref().uid(), part.get(), offset, maxChars) : null;
                    return new ToolOutcome.Success(output(accountHandle, messageHandle, ref.ref().folder(), f,
                            includeHeaders, window, offset, attachments));
                } catch (MessagingException e) {
                    ImapErrorMapper.MappedError mapped = ImapErrorMapper.map(e, ctx.requestId());
                    if (mapped.discardConnection()) {
                        lease.markBroken();
                    }
                    return new ToolOutcome.Failure(mapped.error());
                }
            });
        } catch (MessagingException | RuntimeException e) {
            return new ToolOutcome.Failure(ImapErrorMapper.map(e, ctx.requestId()).error());
        }
    }

    private static Fetched fetch(org.eclipse.angus.mail.imap.IMAPFolder folder, int seq, boolean allHeaders)
            throws MessagingException {
        String section = allHeaders ? "HEADER" : "HEADER.FIELDS (DATE)";
        String what = "ENVELOPE FLAGS INTERNALDATE RFC822.SIZE BODYSTRUCTURE BODY.PEEK[" + section + "]";
        return (Fetched) folder.doCommand(p -> {
            Response[] r = p.fetch(seq, what);
            p.notifyResponseHandlers(r);
            Response last = r[r.length - 1];
            if (!last.isOK()) {
                p.handleResult(last);
                throw new ProtocolException("fetch failed");
            }
            ENVELOPE env = FetchResponse.getItem(r, seq, ENVELOPE.class);
            FLAGS flags = FetchResponse.getItem(r, seq, FLAGS.class);
            INTERNALDATE date = FetchResponse.getItem(r, seq, INTERNALDATE.class);
            RFC822SIZE size = FetchResponse.getItem(r, seq, RFC822SIZE.class);
            BODYSTRUCTURE bs = FetchResponse.getItem(r, seq, BODYSTRUCTURE.class);
            BODY hdr = FetchResponse.getItem(r, seq, BODY.class);
            byte[] headers = hdr == null || hdr.getByteArray() == null ? new byte[0]
                    : hdr.getByteArray().getNewBytes();
            return new Fetched(env, flags, date == null ? null : date.getDate(), size == null ? -1 : size.size, bs,
                    headers);
        });
    }

    private Map<String, Object> output(String account, String handle, String folder, Fetched f,
            boolean includeHeaders, BodyWindow window, long offset, AttachmentList attachments) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", account);
        out.put("handle", handle);
        out.put("folder", folder);
        out.put("received_at", f.internalDate() == null ? null : f.internalDate().toInstant().truncatedTo(ChronoUnit.SECONDS).toString());
        out.put("size", f.size() < 0 ? null : f.size());
        out.put("flags", flags(f.flags()));
        out.put("content_notice", UntrustedText.CONTENT_NOTICE);
        out.put("untrusted", untrusted(f, includeHeaders, window, offset));
        out.put("attachments", AttachmentEntries.toJson(attachments));
        out.put("attachments_total", attachments.total());
        out.put("attachments_truncated", attachments.truncated());
        return out;
    }

    private static Map<String, Object> untrusted(Fetched f, boolean includeHeaders, BodyWindow window, long offset) {
        ENVELOPE env = f.envelope();
        Map<String, Object> u = new LinkedHashMap<>();
        boolean[] cut = new boolean[1];
        List<Map<String, Object>> from = addresses(env == null ? null : env.from, cut);
        u.put("from", from.isEmpty() ? null : from.get(0));
        u.put("to", addresses(env == null ? null : env.to, cut));
        u.put("cc", addresses(env == null ? null : env.cc, cut));
        u.put("reply_to", addresses(env == null ? null : env.replyTo, cut));
        u.put("recipients_truncated", cut[0]);
        u.put("subject", env == null ? null
                : UntrustedText.singleLine(decode(env.subject), UntrustedText.SUBJECT_MAX));
        u.put("sent_at", sentAt(f));
        u.put("message_id", env == null ? null : header(env.messageId));
        u.put("in_reply_to", env == null ? null : header(env.inReplyTo));
        if (includeHeaders) {
            HeaderFields.Result h = HeaderFields.parse(f.headers());
            List<Map<String, Object>> fields = new ArrayList<>();
            for (HeaderFields.Field field : h.fields()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", field.name());
                m.put("value", field.value());
                fields.add(m);
            }
            u.put("headers", fields);
            u.put("headers_truncated", h.truncated());
        }
        u.put("body", body(window, offset));
        return u;
    }

    private static Map<String, Object> body(BodyWindow w, long offset) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (w == null) { // no text part in this message
            b.put("content_type", null);
            b.put("converted_from_html", false);
            b.put("charset", null);
            b.put("offset", offset);
            b.put("returned_chars", 0);
            b.put("total_chars", 0L);
            b.put("text", "");
            b.put("truncated", false);
            b.put("next_offset", null);
            b.put("source_truncated", false);
            return b;
        }
        b.put("content_type", w.contentType());
        b.put("converted_from_html", w.convertedFromHtml());
        b.put("charset", w.charset());
        b.put("offset", w.offset());
        b.put("returned_chars", w.returnedChars());
        b.put("total_chars", w.totalChars());
        b.put("text", w.text());
        b.put("truncated", w.truncated());
        b.put("next_offset", w.nextOffset());
        b.put("source_truncated", w.sourceTruncated());
        return b;
    }

    private static String header(String raw) {
        return raw == null ? null : UntrustedText.singleLine(decode(raw), UntrustedText.HEADER_VALUE_MAX);
    }

    private static List<Map<String, Object>> addresses(InternetAddress[] in, boolean[] cut) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        for (InternetAddress a : in) {
            if (out.size() >= MAX_RECIPIENTS) {
                cut[0] = true;
                break;
            }
            String address = UntrustedText.singleLine(a.getAddress(), UntrustedText.ADDRESS_MAX);
            if (address == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", UntrustedText.singleLine(decode(a.getPersonal()), UntrustedText.DISPLAY_NAME_MAX));
            m.put("address", address);
            out.add(m);
        }
        return out;
    }

    /** The Date header with its original offset; falls back to the envelope instant (UTC), else null. */
    private static String sentAt(Fetched f) {
        Optional<String> raw = HeaderFields.firstValue(f.headers(), "Date");
        if (raw.isPresent()) {
            try {
                String v = raw.get().replaceAll("\\([^)]*\\)\\s*$", "").strip();
                return OffsetDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (DateTimeParseException e) {
                // fall through to the envelope instant
            }
        }
        Date d = f.envelope() == null ? null : f.envelope().date;
        return d == null ? null
                : OffsetDateTime.ofInstant(Instant.ofEpochMilli(d.getTime()), ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String decode(String s) {
        if (s == null) {
            return null;
        }
        try {
            return MimeUtility.decodeText(s);
        } catch (UnsupportedEncodingException | RuntimeException e) {
            return s;
        }
    }

    private static final String[] SYSTEM_NAMES = {"\\Answered", "\\Deleted", "\\Draft", "\\Flagged", "\\Recent",
        "\\Seen"};

    private static List<String> flags(Flags flags) {
        List<String> out = new ArrayList<>();
        if (flags == null) {
            return out;
        }
        Flags.Flag[] system = {Flags.Flag.ANSWERED, Flags.Flag.DELETED, Flags.Flag.DRAFT, Flags.Flag.FLAGGED,
            Flags.Flag.RECENT, Flags.Flag.SEEN};
        for (int i = 0; i < system.length; i++) {
            if (flags.contains(system[i])) {
                out.add(SYSTEM_NAMES[i]);
            }
        }
        String[] user = flags.getUserFlags();
        Arrays.sort(user);
        for (String k : user) {
            if (out.size() >= MAX_FLAGS) {
                break;
            }
            out.add(UntrustedText.singleLine(k, 64));
        }
        return out;
    }
}
