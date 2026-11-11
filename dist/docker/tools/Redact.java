import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts a raw Caddy JSON access log (captured with caddy/capture.caddy) into the connector session fixture
 * (JSON lines; format in imap-mcp-it, ConnectorSessionFixture). Usage: {@code java Redact.java <raw> <out>} with the
 * environment variables MCP_HOST and IDP_HOST set (the host names of .env).
 *
 * Conversion is by allowlist: only the fields below are read, so a field the capture snippet did not anticipate never
 * reaches the output. Query values are kept only for {@link #KEEP_QUERY}; the User-Agent is kept verbatim unless it is
 * a browser's (the backend's value stays; review the result before committing it). Authorization and Mcp-Session-Id
 * become "present". Any problem (malformed line, a line that is no access entry, an unknown host) fails the whole
 * conversion with a non-zero exit and no output file; the output is written to a temporary file and moved into place.
 * Error messages name line numbers only, never content. Single file, no dependencies.
 */
public final class Redact {

    private static final Set<String> KEEP_QUERY = Set.of("response_type", "client_id", "redirect_uri", "scope",
            "resource", "code_challenge_method", "prompt");
    private static final List<String> VERBATIM_HEADERS = List.of("Origin", "Accept", "Content-Type",
            "MCP-Protocol-Version");
    private static final List<String> PRESENT_HEADERS = List.of("Authorization", "Mcp-Session-Id");
    private static final String REDACTED = "<redacted>";

    private Redact() {
    }

    private static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: java Redact.java <raw-caddy-log> <fixture-out>  (MCP_HOST and IDP_HOST set)");
            System.exit(2);
        }
        String mcp = System.getenv("MCP_HOST");
        String idp = System.getenv("IDP_HOST");
        if (mcp == null || mcp.isBlank() || idp == null || idp.isBlank()) {
            System.err.println("MCP_HOST and IDP_HOST must be set and non-empty");
            System.exit(2);
        }
        try {
            convert(Path.of(args[0]), Path.of(args[1]), mcp.trim(), idp.trim());
        } catch (Failure | IOException | RuntimeException e) {
            System.err.println("conversion failed: " + (e instanceof RuntimeException ? "internal error" : e.getMessage()));
            System.exit(1);
        }
    }

    static void convert(Path raw, Path out, String mcp, String idp) throws Failure, IOException {
        List<String> lines = Files.readAllLines(raw, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"format\":\"imap-mcp-connector-session\",\"version\":1,\"source\":")
                .append(quote("recorded " + LocalDate.now())).append("}\n");
        double base = 0;
        int seq = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            Object parsed;
            try {
                parsed = new Json(lines.get(i)).parseDocument();
            } catch (RuntimeException e) {
                throw new Failure("line " + (i + 1) + " is not valid JSON");
            }
            try {
                Map<String, Object> entry = object(parsed);
                double ts = number(entry.get("ts"));
                if (seq == 0) {
                    base = ts;
                }
                seq++;
                sb.append(entryToLine(seq, Math.round((ts - base) * 1000), entry, mcp, idp)).append('\n');
            } catch (RuntimeException e) {
                throw new Failure("line " + (i + 1) + ": " + e.getMessage());
            }
        }
        if (seq == 0) {
            throw new Failure("no access entries in the raw log");
        }
        Path dir = out.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, ".fixture-", ".tmp");
        try {
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String entryToLine(int seq, long relativeMs, Map<String, Object> e, String mcp, String idp) {
        Map<String, Object> req = object(e.get("request"));
        String host = string(req.get("host"));
        String bare = host.replaceFirst(":\\d+$", "");
        String role;
        if (bare.equalsIgnoreCase(mcp)) {
            role = "mcp";
        } else if (bare.equalsIgnoreCase(idp)) {
            role = "idp";
        } else {
            throw new IllegalArgumentException("request host is neither MCP_HOST nor IDP_HOST");
        }
        String method = string(req.get("method"));
        if (!method.matches("[A-Z]{1,16}")) {
            throw new IllegalArgumentException("unexpected method");
        }
        String uri = string(req.get("uri"));
        int q = uri.indexOf('?');
        String path = mask(decodePath(q < 0 ? uri : uri.substring(0, q)), mcp, idp);

        StringBuilder query = new StringBuilder("[");
        if (q >= 0) {
            boolean first = true;
            for (String pair : uri.substring(q + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = decode(eq < 0 ? pair : pair.substring(0, eq));
                String value = KEEP_QUERY.contains(name) ? mask(decode(eq < 0 ? "" : pair.substring(eq + 1)), mcp, idp)
                        : REDACTED;
                query.append(first ? "" : ",").append("{\"name\":").append(quote(name)).append(",\"value\":")
                        .append(quote(value)).append('}');
                first = false;
            }
        }
        query.append(']');

        Map<String, Object> reqHeaders = object(req.get("headers"));
        StringBuilder headers = new StringBuilder("{");
        boolean first = true;
        for (String name : List.of("Origin", "Accept", "Content-Type", "MCP-Protocol-Version", "User-Agent",
                "Authorization", "Mcp-Session-Id")) {
            Object values = lookup(reqHeaders, name);
            if (values == null) {
                continue;
            }
            String value;
            if (PRESENT_HEADERS.contains(name)) {
                value = "present";
            } else if (name.equals("User-Agent")) {
                value = userAgent(firstValue(values), mcp, idp);
            } else if (VERBATIM_HEADERS.contains(name)) {
                value = mask(firstValue(values), mcp, idp);
            } else {
                continue;
            }
            headers.append(first ? "" : ",").append(quote(name)).append(':').append(quote(value));
            first = false;
        }
        headers.append('}');

        Object respHeaders = e.get("resp_headers");
        boolean www = respHeaders != null && lookup(object(respHeaders), "WWW-Authenticate") != null;
        long status = (long) number(e.get("status"));
        return "{\"seq\":" + seq + ",\"relativeMs\":" + relativeMs + ",\"host\":" + quote(role) + ",\"method\":"
                + quote(method) + ",\"path\":" + quote(path) + ",\"query\":" + query + ",\"headers\":" + headers
                + ",\"status\":" + status + ",\"wwwAuthenticate\":" + www + "}";
    }

    private static String userAgent(String ua, String mcp, String idp) {
        if (ua.startsWith("Mozilla/")) {
            return "browser";
        }
        return ua.matches("[\\x20-\\x7e]{1,200}") ? mask(ua, mcp, idp) : "other";
    }

    private static String mask(String s, String mcp, String idp) {
        return s.replace(mcp, "<MCP_HOST>").replace(idp, "<IDP_HOST>");
    }

    /** Percent-decoding of a path ('+' stays a plus). */
    private static String decodePath(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /** Decoding of a query name or value ('+' is a space). */
    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static Object lookup(Map<String, Object> headers, String name) {
        for (Map.Entry<String, Object> h : headers.entrySet()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                return h.getValue();
            }
        }
        return null;
    }

    private static String firstValue(Object values) {
        if (values instanceof List<?> l && !l.isEmpty()) {
            return string(l.getFirst());
        }
        throw new IllegalArgumentException("unexpected header shape");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("not a Caddy access entry");
    }

    private static String string(Object o) {
        if (o instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("not a Caddy access entry");
    }

    private static double number(Object o) {
        if (o instanceof Double d) {
            return d;
        }
        throw new IllegalArgumentException("not a Caddy access entry");
    }

    /** JSON string with every non-ASCII and control character escaped, so the output is plain ASCII. */
    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** Strict recursive-descent JSON reader; numbers are Doubles. Throws on anything malformed. */
    private static final class Json {
        private final String s;
        private int pos;

        Json(String s) {
            this.s = s;
        }

        Object parseDocument() {
            Object v = value(0);
            ws();
            if (pos != s.length()) {
                throw new IllegalStateException("trailing content");
            }
            return v;
        }

        private void ws() {
            while (pos < s.length() && " \t\r\n".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
        }

        private char peek() {
            ws();
            if (pos >= s.length()) {
                throw new IllegalStateException("unexpected end");
            }
            return s.charAt(pos);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new IllegalStateException("unexpected character");
            }
            pos++;
        }

        private Object value(int depth) {
            if (depth > 64) {
                throw new IllegalStateException("too deep");
            }
            char c = peek();
            if (c == '{') {
                pos++;
                Map<String, Object> m = new LinkedHashMap<>();
                if (peek() == '}') {
                    pos++;
                    return m;
                }
                while (true) {
                    String k = string();
                    expect(':');
                    m.put(k, value(depth + 1));
                    if (peek() == ',') {
                        pos++;
                    } else {
                        expect('}');
                        return m;
                    }
                }
            }
            if (c == '[') {
                pos++;
                List<Object> l = new ArrayList<>();
                if (peek() == ']') {
                    pos++;
                    return l;
                }
                while (true) {
                    l.add(value(depth + 1));
                    if (peek() == ',') {
                        pos++;
                    } else {
                        expect(']');
                        return l;
                    }
                }
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalStateException("unexpected character");
            }
            return Double.valueOf(s.substring(start, pos));
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new IllegalStateException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c < 0x20) {
                    throw new IllegalStateException("control character in string");
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw new IllegalStateException("bad escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw new IllegalStateException("bad escape");
                        }
                        b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalStateException("bad escape");
                }
            }
        }
    }
}
