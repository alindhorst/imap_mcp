package de.alexanderlindhorst.imapmcp.core.tool;

/**
 * Accepts only the portable subset of ECMA-262 that a published schema {@code pattern} may use (valid with and
 * without the {@code u} flag, identical meaning in java.util.regex): {@code ^BODY$} where BODY is a sequence of atoms
 * with optional greedy {@code * + ?}. Atoms are {@code [A-Za-z0-9]}, hex escapes of two or four digits (no
 * surrogates), an
 * escaped syntax character, a flat character class, or a non-capturing group. Everything else is rejected.
 */
final class EcmaPattern {

    private static final String ESCAPABLE = "^$\\.*+?()[]{}|/";

    private final String p;
    private int i;

    private EcmaPattern(String p) {
        this.p = p;
    }

    /** @throws IllegalArgumentException if the pattern is outside the subset (the message never quotes it) */
    static void check(String pattern) {
        EcmaPattern c = new EcmaPattern(pattern);
        c.parse();
    }

    private void parse() {
        if (!p.startsWith("^") || !p.endsWith("$") || p.length() < 3) {
            throw bad("pattern must be ^...$");
        }
        i = 1;
        body(false);
        if (i != p.length() - 1 || p.charAt(i) != '$') {
            throw bad("pattern must end with an unescaped $");
        }
    }

    /** Parses ITEM+ up to the closing ')' (nested) or the final '$' (top level), leaving i on the terminator. */
    private void body(boolean nested) {
        int items = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (nested && c == ')' || !nested && c == '$') {
                break;
            }
            atom();
            if (i < p.length() && "*+?".indexOf(p.charAt(i)) >= 0) {
                i++;
                if (i < p.length() && "*+?".indexOf(p.charAt(i)) >= 0) {
                    throw bad("possessive or lazy quantifier");
                }
            }
            items++;
        }
        if (items == 0) {
            throw bad("empty body");
        }
        if (i >= p.length()) {
            throw bad("unterminated group");
        }
    }

    private void atom() {
        char c = p.charAt(i);
        if (isAlnum(c)) {
            i++;
        } else if (c == '\\') {
            escape(false);
        } else if (c == '[') {
            charClass();
        } else if (c == '(') {
            if (!p.startsWith("(?:", i)) {
                throw bad("only non-capturing groups");
            }
            i += 3;
            body(true);
            i++; // ')'
        } else {
            throw bad("unsupported syntax");
        }
    }

    /** Escape at i; returns the code unit it denotes for ranges, or -1 for a syntax-character escape. */
    private int escape(boolean inClass) {
        i++; // backslash
        if (i >= p.length()) {
            throw bad("dangling escape");
        }
        char c = p.charAt(i++);
        if (c == 'x') {
            return hex(2);
        }
        if (c == 'u') {
            int v = hex(4);
            if (v >= 0xD800 && v <= 0xDFFF) {
                throw bad("surrogate escape");
            }
            return v;
        }
        if (ESCAPABLE.indexOf(c) >= 0 && !inClass) {
            return -1;
        }
        if (inClass && ESCAPABLE.indexOf(c) >= 0) {
            // keep classes to hex escapes: escaped punctuation inside a class is not in the subset
            throw bad("punctuation in class");
        }
        throw bad("unsupported escape");
    }

    private int hex(int digits) {
        if (i + digits > p.length()) {
            throw bad("short hex escape");
        }
        int v = 0;
        for (int k = 0; k < digits; k++) {
            int d = Character.digit(p.charAt(i++), 16);
            if (d < 0 || p.charAt(i - 1) > 'f') {
                throw bad("bad hex escape");
            }
            v = v * 16 + d;
        }
        return v;
    }

    private void charClass() {
        i++; // '['
        if (i < p.length() && p.charAt(i) == '^') {
            i++;
        }
        int members = 0;
        while (true) {
            if (i >= p.length()) {
                throw bad("unterminated class");
            }
            if (p.charAt(i) == ']') {
                i++;
                break;
            }
            int low = classChar();
            if (i < p.length() && p.charAt(i) == '-') {
                i++;
                if (i >= p.length() || p.charAt(i) == ']') {
                    throw bad("dangling range");
                }
                int high = classChar();
                if (low > high) {
                    throw bad("reversed range");
                }
            }
            members++;
        }
        if (members == 0) {
            throw bad("empty class");
        }
    }

    private int classChar() {
        char c = p.charAt(i);
        if (c == '\\') {
            return escape(true);
        }
        if (isAlnum(c)) {
            i++;
            return c;
        }
        throw bad("unsupported class member");
    }

    private static boolean isAlnum(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }

    private static IllegalArgumentException bad(String why) {
        return new IllegalArgumentException("pattern outside the portable ECMA-262 subset: " + why);
    }
}
