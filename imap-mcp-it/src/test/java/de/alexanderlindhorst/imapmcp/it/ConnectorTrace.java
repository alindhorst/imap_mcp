package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The connector client's own trace in the #434 fixture format ({@link ConnectorSessionFixture}), recorded by
 * allowlist exactly as {@code dist/docker/tools/Redact.java} converts a capture: query values only for a few names,
 * headers reduced, {@code Authorization} and {@code Mcp-Session-Id} only as {@code present}, hosts as placeholders.
 * Nothing that is not on a list is ever read, so a token, code, verifier, state, cookie or password cannot get in.
 */
final class ConnectorTrace {

    private static final Set<String> KEEP_QUERY = Set.of("response_type", "client_id", "redirect_uri", "scope",
            "resource", "code_challenge_method", "prompt");
    private static final List<String> VERBATIM_HEADERS = List.of("Origin", "Accept", "Content-Type",
            "MCP-Protocol-Version");
    private static final String REDACTED = "<redacted>";

    private final String mcpAuthority;
    private final String idpAuthority;
    private final List<ConnectorSessionFixture.Request> requests = new ArrayList<>();
    private long firstStart = Long.MIN_VALUE;

    ConnectorTrace(String mcpAuthority, String idpAuthority) {
        this.mcpAuthority = mcpAuthority;
        this.idpAuthority = idpAuthority;
    }

    /** Records one exchange; {@code startNanos} is {@code System.nanoTime()} when the request was sent. */
    synchronized void record(String method, URI uri, Map<String, List<String>> requestHeaders, int status,
            boolean wwwAuthenticate, long startNanos) {
        String host = uri.getAuthority().equals(mcpAuthority) ? "mcp"
                : uri.getAuthority().equals(idpAuthority) ? "idp" : null;
        if (host == null) {
            throw new IllegalArgumentException("request to a host that is neither the MCP nor the IdP host");
        }
        if (firstStart == Long.MIN_VALUE) {
            firstStart = startNanos;
        }
        requests.add(new ConnectorSessionFixture.Request(requests.size() + 1, (startNanos - firstStart) / 1_000_000L,
                host, method, uri.getRawPath() == null ? "" : uri.getRawPath(), query(uri.getRawQuery()),
                headers(requestHeaders), status, wwwAuthenticate));
    }

    synchronized ConnectorSessionFixture fixture() {
        return new ConnectorSessionFixture(
                new ConnectorSessionFixture.Header(ConnectorSessionFixture.FORMAT, ConnectorSessionFixture.VERSION,
                        "assumed"), List.copyOf(requests));
    }

    /** The trace as fixture text (header line, then one line per request). */
    String text() {
        StringWriter w = new StringWriter();
        try {
            fixture().write(w);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return w.toString();
    }

    private List<ConnectorSessionFixture.Param> query(String raw) {
        List<ConnectorSessionFixture.Param> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.add(new ConnectorSessionFixture.Param(name, KEEP_QUERY.contains(name) ? placeholders(value) : REDACTED));
        }
        return out;
    }

    private Map<String, String> headers(Map<String, List<String>> in) {
        Map<String, List<String>> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ci.putAll(in);
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : List.of("Origin", "Accept", "Content-Type", "MCP-Protocol-Version")) {
            if (VERBATIM_HEADERS.contains(name) && ci.containsKey(name) && !ci.get(name).isEmpty()) {
                out.put(name, placeholders(ci.get(name).getFirst()));
            }
        }
        if (ci.containsKey("User-Agent") && !ci.get("User-Agent").isEmpty()) {
            String ua = ci.get("User-Agent").getFirst();
            out.put("User-Agent", ua.startsWith("Mozilla/") ? "browser" : ua);
        }
        if (ci.containsKey("Authorization")) {
            out.put("Authorization", "present");
        }
        if (ci.containsKey("Mcp-Session-Id")) {
            out.put("Mcp-Session-Id", "present");
        }
        return out;
    }

    private String placeholders(String value) {
        return value.replace(mcpAuthority, "<MCP_HOST>").replace(idpAuthority, "<IDP_HOST>")
                .replace(bare(mcpAuthority), "<MCP_HOST>").replace(bare(idpAuthority), "<IDP_HOST>");
    }

    private static String bare(String authority) {
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
