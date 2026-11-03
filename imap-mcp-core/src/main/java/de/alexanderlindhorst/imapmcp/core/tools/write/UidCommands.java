package de.alexanderlindhorst.imapmcp.core.tools.write;

import jakarta.mail.Flags;
import jakarta.mail.MessagingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.eclipse.angus.mail.iap.Argument;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.BASE64MailboxEncoder;

/**
 * The one class that sends write commands (SPEC 5.4): a closed verb set (UID MOVE, UID COPY, UID STORE
 * +FLAGS.SILENT / -FLAGS.SILENT, UID EXPUNGE, CREATE, SUBSCRIBE) through {@link IMAPFolder#doCommand}, so Angus's
 * response handlers see every response (an EXPUNGE keeps its message cache right). There is deliberately no method
 * that sends an arbitrary command. The tagged result is returned as a value, NO and BAD included, carrying the
 * response code for {@link WriteResponses}; only a connection-level failure (BYE, I/O) is thrown, by Angus's
 * {@code handleResult} / {@code doCommand}. Mailbox names are modified UTF-7 as in Angus's writeMailboxName (we
 * never ENABLE UTF8=ACCEPT). UID sets are never empty. Nothing is logged.
 */
public final class UidCommands {

    /** Adds or removes flags; a replace of the flag set does not exist. */
    public enum FlagMode {
        ADD("+FLAGS.SILENT"), REMOVE("-FLAGS.SILENT");

        private final String item;

        FlagMode(String item) {
            this.item = item;
        }
    }

    /**
     * The tagged response and all responses of the command (untagged ones first, the tagged one last).
     */
    public record Result(Response tagged, Response[] responses) {

        public boolean ok() {
            return tagged.isOK();
        }

        public boolean no() {
            return tagged.isNO();
        }

        public boolean bad() {
            return tagged.isBAD();
        }

        /** COPYUID of an untagged OK (UID MOVE) or the tagged OK (UID COPY); empty if absent or malformed. */
        public Optional<CopyUidParser.CopyUid> copyUid() {
            return ok() ? CopyUidParser.parse(responses) : Optional.empty();
        }
    }

    private static final long MAX_UINT32 = 4_294_967_295L;
    /** Same atom set as set_flags (anchored), so a keyword can never break out of the parenthesized list. */
    private static final Pattern KEYWORD = Pattern.compile(SetFlagsTool.KEYWORD_PATTERN);
    private static final int MAX_KEYWORD = 64;

    private UidCommands() {
    }

    public static Result uidMove(IMAPFolder folder, Collection<Long> uids, String targetFolder)
            throws MessagingException {
        return send(folder, "UID MOVE " + uidSet(uids) + " ", mailbox(targetFolder));
    }

    public static Result uidCopy(IMAPFolder folder, Collection<Long> uids, String targetFolder)
            throws MessagingException {
        return send(folder, "UID COPY " + uidSet(uids) + " ", mailbox(targetFolder));
    }

    public static Result uidStore(IMAPFolder folder, Collection<Long> uids, FlagMode mode, Flags flags)
            throws MessagingException {
        String set = uidSet(uids);
        return send(folder, "UID STORE " + set + " " + mode.item + " " + flagList(flags), null);
    }

    public static Result uidExpunge(IMAPFolder folder, Collection<Long> uids) throws MessagingException {
        return send(folder, "UID EXPUNGE " + uidSet(uids), null);
    }

    public static Result create(IMAPFolder folder, String name) throws MessagingException {
        return send(folder, "CREATE ", mailbox(name));
    }

    public static Result subscribe(IMAPFolder folder, String name) throws MessagingException {
        return send(folder, "SUBSCRIBE ", mailbox(name));
    }

    private static Argument mailbox(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("mailbox name");
        }
        Argument a = new Argument();
        a.writeString(BASE64MailboxEncoder.encode(name));
        return a;
    }

    private static Result send(IMAPFolder folder, String command, Argument args) throws MessagingException {
        // trailing space of the command is the separator before the argument
        String cmd = args == null ? command : command.stripTrailing();
        return (Result) folder.doCommand(p -> {
            Response[] r = p.command(cmd, args);
            p.notifyResponseHandlers(r);
            Response tagged = r[r.length - 1];
            if (tagged.isBYE()) {
                p.handleResult(tagged); // throws the connection-level failure
            }
            return new Result(tagged, r);
        });
    }

    /** Sorted, de-duplicated, range-compressed UID set; never empty, every UID in 1..2^32-1. */
    static String uidSet(Collection<Long> uids) {
        if (uids == null || uids.isEmpty()) {
            throw new IllegalArgumentException("uid set is empty");
        }
        TreeSet<Long> sorted = new TreeSet<>();
        for (Long u : uids) {
            if (u == null || u < 1 || u > MAX_UINT32) {
                throw new IllegalArgumentException("uid out of range");
            }
            sorted.add(u);
        }
        List<String> parts = new ArrayList<>();
        long from = -1;
        long prev = -1;
        for (long u : sorted) {
            if (from < 0) {
                from = prev = u;
            } else if (u == prev + 1) {
                prev = u;
            } else {
                parts.add(range(from, prev));
                from = prev = u;
            }
        }
        parts.add(range(from, prev));
        return String.join(",", parts);
    }

    private static String range(long from, long to) {
        return from == to ? Long.toString(from) : from + ":" + to;
    }

    private static String flagList(Flags flags) {
        List<String> out = new ArrayList<>();
        for (Flags.Flag f : flags.getSystemFlags()) {
            out.add(systemFlag(f));
        }
        for (String k : flags.getUserFlags()) {
            if (k.length() > MAX_KEYWORD || !KEYWORD.matcher(k).matches()) {
                throw new IllegalArgumentException("keyword");
            }
            out.add(k);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no flags");
        }
        return "(" + String.join(" ", out) + ")";
    }

    private static String systemFlag(Flags.Flag f) {
        if (f == Flags.Flag.ANSWERED) {
            return "\\Answered";
        } else if (f == Flags.Flag.DELETED) {
            return "\\Deleted";
        } else if (f == Flags.Flag.DRAFT) {
            return "\\Draft";
        } else if (f == Flags.Flag.FLAGGED) {
            return "\\Flagged";
        } else if (f == Flags.Flag.SEEN) {
            return "\\Seen";
        }
        throw new IllegalArgumentException("system flag");
    }
}
