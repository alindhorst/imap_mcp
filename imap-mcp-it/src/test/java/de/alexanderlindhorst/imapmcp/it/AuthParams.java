package de.alexanderlindhorst.imapmcp.it;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RFC 9110 section 11 parser for WWW-Authenticate values: a comma-separated list of challenges, each an auth-scheme
 * followed by auth-params ({@code token "=" (token / quoted-string)}); the token68 form is not supported (no
 * server challenge uses it). Parameter names are
 * case-insensitive (kept lower-case); a repeated name is rejected as ambiguous.
 */
final class AuthParams {

    private AuthParams() {
    }

    record Challenge(String scheme, Map<String, String> params) {
        /** The value of a parameter (name matched case-insensitively), or null. */
        String param(String name) {
            return params.get(name.toLowerCase(Locale.ROOT));
        }
    }

    static List<Challenge> parse(String header) {
        Scanner s = new Scanner(header);
        List<Challenge> out = new ArrayList<>();
        String scheme = null;
        Map<String, String> params = null;
        while (true) {
            s.skip(true);
            if (s.atEnd()) {
                break;
            }
            String token = s.token();
            s.skipSpaces();
            if (s.peek() == '=' && scheme != null) {
                s.next();
                s.skipSpaces();
                String value = s.peek() == '"' ? s.quoted() : s.token();
                if (params.put(token.toLowerCase(Locale.ROOT), value) != null) {
                    throw new IllegalArgumentException("repeated auth-param " + token);
                }
            } else {
                if (scheme != null) {
                    out.add(new Challenge(scheme, params));
                }
                scheme = token;
                params = new LinkedHashMap<>();
            }
        }
        if (scheme == null) {
            throw new IllegalArgumentException("no challenge");
        }
        out.add(new Challenge(scheme, params));
        return out;
    }

    private static final class Scanner {
        private final String in;
        private int pos;

        Scanner(String in) {
            this.in = in;
        }

        boolean atEnd() {
            return pos >= in.length();
        }

        char peek() {
            return atEnd() ? '\0' : in.charAt(pos);
        }

        char next() {
            return in.charAt(pos++);
        }

        void skipSpaces() {
            while (!atEnd() && (peek() == ' ' || peek() == '\t')) {
                pos++;
            }
        }

        /** Optional whitespace, and commas too when asked. */
        void skip(boolean commas) {
            while (!atEnd() && (peek() == ' ' || peek() == '\t' || (commas && peek() == ','))) {
                pos++;
            }
        }

        private static boolean tchar(char c) {
            return c > 32 && c < 127 && "\"(),/:;<=>?@[\\]{}".indexOf(c) < 0;
        }

        String token() {
            int start = pos;
            while (!atEnd() && tchar(peek())) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("token expected at " + start);
            }
            return in.substring(start, pos);
        }

        String quoted() {
            next();
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated quoted-string");
                }
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new IllegalArgumentException("dangling escape");
                    }
                    c = next();
                }
                sb.append(c);
            }
        }
    }
}
