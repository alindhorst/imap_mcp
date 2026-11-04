package de.alexanderlindhorst.imapmcp.it;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wire-trace audit rules (SPEC 5.4, 5.8; #207) over the client commands of the opt-in IMAP trace, tag stripped.
 * Commands of one trace are assumed sequential (one call at a time); every call leaves its mailbox unselected, so the
 * CLOSE state machine also holds across the pooled and re-created connections of the sequence.
 */
final class ImapWireAudit {

    private static final Pattern CLIENT_LINE = Pattern.compile("^[A-Z]+\\d+ (?!(?:OK|NO|BAD)\\b)([A-Z][A-Z.]*\\b.*)$");

    private ImapWireAudit() {
    }

    /** The command of a traced client line without its tag; empty for server lines (untagged, tagged result). */
    static Optional<String> clientCommand(String traceLine) {
        Matcher m = CLIENT_LINE.matcher(traceLine);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private static String verb(String command) {
        int sp = command.indexOf(' ');
        return sp < 0 ? command : command.substring(0, sp);
    }

    /**
     * No non-UID COPY, MOVE or STORE, no plain EXPUNGE, no CLOSE while a read-write mailbox is selected, no
     * {@code BODY[} without {@code .PEEK}.
     */
    static List<String> violations(List<String> commands) {
        List<String> out = new ArrayList<>();
        boolean readWrite = false;
        for (String c : commands) {
            String v = verb(c);
            switch (v) {
                case "COPY", "MOVE", "STORE" -> out.add("non-UID " + v);
                case "EXPUNGE" -> out.add("plain EXPUNGE");
                case "SELECT" -> readWrite = true;
                case "EXAMINE", "UNSELECT", "LOGIN", "AUTHENTICATE", "LOGOUT" -> readWrite = false;
                case "CLOSE" -> {
                    if (readWrite) {
                        out.add("CLOSE while a read-write mailbox is selected");
                    }
                    readWrite = false;
                }
                default -> {
                }
            }
            if (c.contains("BODY[")) {
                out.add("BODY[ without .PEEK: " + v);
            }
        }
        return out;
    }

    /**
     * ENABLE rule. Strict (servers not advertising UTF8=ACCEPT, where Angus has nothing to enable): no ENABLE at all.
     * Relaxed (servers advertising it, where Angus 2.0.5 enables it itself at login, SPEC 5.4, #179): only
     * {@code ENABLE UTF8=ACCEPT}, never CONDSTORE or QRESYNC.
     */
    static List<String> enableViolations(List<String> commands, boolean strict) {
        List<String> out = new ArrayList<>();
        for (String c : commands) {
            if (!verb(c).equals("ENABLE")) {
                continue;
            }
            if (strict || !c.strip().equals("ENABLE UTF8=ACCEPT")) {
                out.add(c);
            }
        }
        return out;
    }

    /** SELECT/EXAMINE commands naming one of the (ASCII) folders. */
    static List<String> selectedFolders(List<String> commands, List<String> folders) {
        List<String> out = new ArrayList<>();
        for (String c : commands) {
            String v = verb(c);
            if (v.equals("SELECT") || v.equals("EXAMINE")) {
                String arg = c.substring(v.length()).strip();
                if (arg.startsWith("\"") && arg.endsWith("\"") && arg.length() >= 2) {
                    arg = arg.substring(1, arg.length() - 1);
                }
                if (folders.contains(arg)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    static Optional<String> firstFolderCommand(List<String> commands) {
        return commands.stream().filter(c -> verb(c).equals("SELECT") || verb(c).equals("EXAMINE")).findFirst();
    }
}
