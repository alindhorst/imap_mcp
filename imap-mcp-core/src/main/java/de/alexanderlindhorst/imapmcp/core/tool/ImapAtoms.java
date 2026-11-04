package de.alexanderlindhorst.imapmcp.core.tool;

/** IMAP syntax constants shared by tool schemas and the wire layer. */
public final class ImapAtoms {

    /**
     * RFC 3501 ATOM-CHAR as an ECMA-262 {@code ^...$} pattern (keywords of search_messages and set_flags, also
     * enforced by UidCommands). The validator matches the whole value, so a trailing line terminator never passes.
     */
    public static final String KEYWORD_PATTERN =
            "^[\\x21\\x23\\x24\\x26\\x27\\x2b-\\x5b\\x5e-\\x7a\\x7c-\\x7e]+$";

    private ImapAtoms() {
    }
}
