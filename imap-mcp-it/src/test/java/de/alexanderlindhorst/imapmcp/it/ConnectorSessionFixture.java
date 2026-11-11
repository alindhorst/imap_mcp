package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Format of a connector session fixture (#434): JSON lines in {@code src/test/resources/connector/}. The first line is
 * the header ({@code format}, {@code version}, {@code source}: {@code assumed} or {@code recorded <date>}), then one
 * object per request: {@code seq}, {@code relativeMs}, {@code host} ({@code mcp} or {@code idp}), {@code method},
 * {@code path} (host names replaced by {@code <MCP_HOST>} / {@code <IDP_HOST>}), {@code query} (ordered array of
 * {@code name}/{@code value}; values only for the allowlist, else {@code <redacted>}), {@code headers} (the allowlisted
 * ones; {@code Authorization} and {@code Mcp-Session-Id} only as {@code present}; a browser User-Agent as
 * {@code browser}), {@code status} and {@code wwwAuthenticate} (whether the response carried the header).
 * The backend User-Agent is kept verbatim by the converter; whether a recorded one is committed is an open owner
 * decision (#436/#437). Written by {@code dist/docker/tools/Redact.java}; read by the trace test (H6) and H11.
 */
record ConnectorSessionFixture(Header header, List<Request> requests) {

    static final String FORMAT = "imap-mcp-connector-session";
    static final int VERSION = 1;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    record Header(String format, int version, String source) {
    }

    record Param(String name, String value) {
    }

    record Request(int seq, long relativeMs, String host, String method, String path, List<Param> query,
            Map<String, String> headers, int status, boolean wwwAuthenticate) {
    }

    /** Parses a whole fixture; the header line is mandatory. */
    static ConnectorSessionFixture read(String text) {
        List<String> lines = text.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("fixture has no header line");
        }
        JsonNode h = parse(lines.getFirst());
        if (!h.isObject() || !h.hasNonNull("format") || !h.hasNonNull("version") || !h.hasNonNull("source")) {
            throw new IllegalArgumentException("first line is not a fixture header");
        }
        if (!FORMAT.equals(h.get("format").asString())) {
            throw new IllegalArgumentException("unknown format: " + h.get("format").asString());
        }
        if (h.get("version").asInt(-1) != VERSION) {
            throw new IllegalArgumentException("unsupported version: " + h.get("version"));
        }
        String source = h.get("source").asString();
        if (!source.equals("assumed") && !source.matches("recorded \\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("bad source: " + source);
        }
        List<Request> requests = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            requests.add(request(parse(line)));
        }
        return new ConnectorSessionFixture(new Header(FORMAT, VERSION, source), List.copyOf(requests));
    }

    void write(Writer out) throws IOException {
        ObjectNode h = MAPPER.createObjectNode();
        h.put("format", header.format());
        h.put("version", header.version());
        h.put("source", header.source());
        out.write(MAPPER.writeValueAsString(h) + "\n");
        for (Request r : requests) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("seq", r.seq());
            n.put("relativeMs", r.relativeMs());
            n.put("host", r.host());
            n.put("method", r.method());
            n.put("path", r.path());
            ArrayNode q = n.putArray("query");
            for (Param p : r.query()) {
                q.addObject().put("name", p.name()).put("value", p.value());
            }
            ObjectNode hs = n.putObject("headers");
            r.headers().forEach(hs::put);
            n.put("status", r.status());
            n.put("wwwAuthenticate", r.wwwAuthenticate());
            out.write(MAPPER.writeValueAsString(n) + "\n");
        }
    }

    private static JsonNode parse(String line) {
        try {
            return MAPPER.readTree(line);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("fixture line is not JSON", e);
        }
    }

    private static Request request(JsonNode n) {
        for (String f : List.of("seq", "relativeMs", "host", "method", "path", "query", "headers", "status",
                "wwwAuthenticate")) {
            if (!n.hasNonNull(f)) {
                throw new IllegalArgumentException("request without field " + f);
            }
        }
        if (!n.get("query").isArray() || !n.get("headers").isObject()) {
            throw new IllegalArgumentException("query must be an array and headers an object");
        }
        List<Param> query = new ArrayList<>();
        for (JsonNode p : n.get("query")) {
            if (!p.hasNonNull("name") || !p.hasNonNull("value")) {
                throw new IllegalArgumentException("query entry needs name and value");
            }
            query.add(new Param(p.get("name").asString(), p.get("value").asString()));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        n.get("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
        return new Request(n.get("seq").asInt(), n.get("relativeMs").asLong(), n.get("host").asString(),
                n.get("method").asString(), n.get("path").asString(), List.copyOf(query), headers,
                n.get("status").asInt(), n.get("wwwAuthenticate").asBoolean());
    }
}
