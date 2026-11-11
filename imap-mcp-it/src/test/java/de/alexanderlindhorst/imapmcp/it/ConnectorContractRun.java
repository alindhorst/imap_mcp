package de.alexanderlindhorst.imapmcp.it;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The connector sequence only (#435): the happy path the Claude connector performs, in the order of the fixture, with
 * its own {@link ConnectorClient} and so its own {@link ConnectorTrace}. Probes (HEAD, the other authorization
 * server metadata URLs) and negative cases are other tests with other client instances and never enter this trace.
 *
 * <p>Replay: for each step the header values (Accept, Content-Type, MCP-Protocol-Version, Origin, backend
 * User-Agent) are taken from the next fixture request of the same (host role, method, path); a step without such a
 * request falls back to the document defaults, and the comparison then shows the difference. The MCP steps are plain
 * JSON-RPC posts through the tracing client, not the SDK client, so that the requests are exactly the connector's
 * (no SDK-only GET or DELETE). {@code Authorization} and {@code Mcp-Session-Id} are set by the client where the
 * protocol requires them (a bearer on authenticated steps; the session id once the server has assigned one).
 *
 * <p>Steps: challenge, protected resource metadata, authorization server metadata (the first URL of the MCP
 * discovery order, {@code /.well-known/oauth-authorization-server} with the issuer path inserted), authorization
 * (login and consent as a browser), token, {@code initialize}, {@code notifications/initialized}, {@code tools/list},
 * {@code tools/call list_accounts}, then the refresh: SIMULATED expiry (an invalid bearer gets the same 401 challenge
 * an expired one does, SPEC 5.3), the {@code refresh_token} grant with the real refresh token, and the retry. Real
 * expiry is the subject of the lifecycle test (#432); no realm setting is changed here.
 */
final class ConnectorContractRun {

    static final String PROPERTY = "imapmcp.connectorFixture";
    static final String DEFAULT_FIXTURE = "connector/assumed-claude-session.jsonl";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCOPE_FALLBACK = "imap-mcp:read imap-mcp:write";
    private static final String DEFAULT_PROTOCOL = "2025-06-18";

    /** What a run produced: the client's trace and every secret it handled (for the canary scan). */
    record Result(ConnectorSessionFixture trace, List<String> secrets) {
    }

    private final DeployedStack stack;
    private final ConnectorClient client;
    private final Map<String, Deque<Map<String, String>>> replay = new HashMap<>();
    private final String mcpAuthority;
    private final String idpAuthority;
    private final String protocolVersion;
    private String sessionId;

    private ConnectorContractRun(DeployedStack stack, ConnectorSessionFixture fixture) {
        this.stack = stack;
        this.client = new ConnectorClient(stack);
        this.mcpAuthority = URI.create(stack.mcpOrigin()).getAuthority();
        this.idpAuthority = URI.create(stack.idpOrigin()).getAuthority();
        String version = DEFAULT_PROTOCOL;
        for (ConnectorSessionFixture.Request r : fixture.requests()) {
            replay.computeIfAbsent(kind(r.host(), r.method(), r.path()), k -> new ArrayDeque<>()).add(r.headers());
            if (r.headers().containsKey("MCP-Protocol-Version")) {
                version = r.headers().get("MCP-Protocol-Version");
            }
        }
        this.protocolVersion = version;
    }

    /** The fixture named by the system property (a classpath resource, else a file); the default is the assumed one. */
    static ConnectorSessionFixture loadFixture(String spec) {
        String name = spec == null || spec.isBlank() ? DEFAULT_FIXTURE : spec;
        try {
            try (InputStream in = ConnectorContractRun.class.getClassLoader().getResourceAsStream(name)) {
                if (in != null) {
                    return ConnectorSessionFixture.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            Path file = Path.of(name);
            if (Files.isRegularFile(file)) {
                return ConnectorSessionFixture.read(Files.readString(file));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read the fixture " + name, e);
        }
        throw new IllegalArgumentException("no such fixture (classpath resource or file): " + name);
    }

    static Result run(DeployedStack stack, KeycloakAdmin admin, ConnectorSessionFixture fixture) throws Exception {
        return new ConnectorContractRun(stack, fixture).perform(admin.createUser());
    }

    private Result perform(KeycloakAdmin.TestUser user) throws Exception {
        List<String> secrets = new ArrayList<>(List.of(user.password()));
        String resource = stack.configValue("oauth", "resource");
        URI resourceUri = URI.create(resource);

        // 1. the challenge
        HttpResponse<String> challenge = step("mcp", "POST", resourceUri, Map.of("Accept",
                "application/json, text/event-stream", "Content-Type", "application/json", "User-Agent",
                "Claude-User"), initialize(), null);
        AuthParams.Challenge bearer = ConnectorClient.bearerChallenge(challenge);
        String scope = bearer.param("scope") == null ? SCOPE_FALLBACK : bearer.param("scope");

        // 2. protected resource metadata, at the URL of the challenge
        HttpResponse<String> prm = step("mcp", "GET", URI.create(bearer.param("resource_metadata")),
                Map.of("Accept", "application/json", "User-Agent", "Claude-User"), null, null);
        String authorizationServer = JSON.readTree(prm.body()).get("authorization_servers").get(0).asString();

        // 3. authorization server metadata, the first URL of the MCP discovery order (issuer path inserted)
        URI as = URI.create(authorizationServer);
        HttpResponse<String> asm = step("idp", "GET", URI.create(as.getScheme() + "://" + as.getAuthority()
                + "/.well-known/oauth-authorization-server" + as.getPath()), Map.of("Accept", "application/json",
                "User-Agent", "Claude-User"), null, null);
        JsonNode meta = JSON.readTree(asm.body());
        String authorizationEndpoint = meta.get("authorization_endpoint").asString();
        String tokenEndpoint = meta.get("token_endpoint").asString();

        // 4. authorization (the browser's part), then 5. the token request
        String verifier = Pkce.newVerifier();
        Map<String, String> params = client.authorizationParams(ConnectorClient.CLAUDE_AI_CALLBACK, scope, resource,
                verifier);
        secrets.addAll(List.of(verifier, params.get("state")));
        ConnectorClient.AuthorizationOutcome outcome = client.authorize(authorizationEndpoint, params, user);
        if (outcome.redirect() == null) {
            throw new IllegalStateException("the authorization did not end in a redirect (status "
                    + outcome.status() + ")");
        }
        String code = queryValue(outcome.redirect(), "code");
        secrets.add(code);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", ConnectorClient.CLAUDE_AI_CALLBACK);
        form.put("client_id", ConnectorClient.CLIENT_ID);
        form.put("code_verifier", verifier);
        form.put("resource", resource);
        JsonNode token = JSON.readTree(tokenStep(tokenEndpoint, form).body());
        String accessToken = token.get("access_token").asString();
        String refreshToken = token.get("refresh_token").asString();
        secrets.addAll(List.of(accessToken, refreshToken));

        // 6. the MCP session
        HttpResponse<String> init = mcp(resourceUri, accessToken, initialize());
        init.headers().firstValue("Mcp-Session-Id").ifPresent(id -> {
            sessionId = id;
            secrets.add(id);
        });
        mcp(resourceUri, accessToken, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        mcp(resourceUri, accessToken, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        mcp(resourceUri, accessToken, call(3));

        // 7. refresh after a (simulated) expiry
        String invalid = "invalid-" + Pkce.newState();
        secrets.add(invalid);
        mcp(resourceUri, invalid, call(4));
        Map<String, String> refresh = new LinkedHashMap<>();
        refresh.put("grant_type", "refresh_token");
        refresh.put("refresh_token", refreshToken);
        refresh.put("client_id", ConnectorClient.CLIENT_ID);
        refresh.put("resource", resource);
        JsonNode renewed = JSON.readTree(tokenStep(tokenEndpoint, refresh).body());
        secrets.add(renewed.get("access_token").asString());
        if (renewed.hasNonNull("refresh_token")) {
            secrets.add(renewed.get("refresh_token").asString());
        }
        mcp(resourceUri, renewed.get("access_token").asString(), call(5));

        secrets.addAll(client.cookieValues());
        return new Result(client.trace().fixture(), secrets);
    }

    // ---- steps ---------------------------------------------------------------------------------------------

    private HttpResponse<String> tokenStep(String tokenEndpoint, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        return step("idp", "POST", URI.create(tokenEndpoint), Map.of("Accept", "application/json", "Content-Type",
                "application/x-www-form-urlencoded", "User-Agent", "Claude-User"), body, null);
    }

    private HttpResponse<String> mcp(URI resource, String bearer, String body) throws Exception {
        return step("mcp", "POST", resource, Map.of("Accept", "application/json, text/event-stream", "Content-Type",
                "application/json", "User-Agent", "Claude-User", "MCP-Protocol-Version", protocolVersion), body,
                bearer);
    }

    /**
     * One request: the headers of the next fixture request of the same kind, else the defaults. Only the allowlisted
     * header names are applied; {@code Authorization} and {@code Mcp-Session-Id} are the client's.
     */
    private HttpResponse<String> step(String host, String method, URI uri, Map<String, String> defaults, String body,
            String bearer) throws Exception {
        Deque<Map<String, String>> queue = replay.get(kind(host, method, uri.getRawPath()));
        Map<String, String> headers = queue == null || queue.isEmpty() ? defaults : queue.poll();
        HttpRequest.Builder b = HttpRequest.newBuilder(uri);
        for (String name : List.of("Origin", "Accept", "Content-Type", "MCP-Protocol-Version", "User-Agent")) {
            String value = headers.get(name);
            if (value != null) {
                b.header(name, value.replace("<MCP_HOST>", mcpAuthority).replace("<IDP_HOST>", idpAuthority));
            }
        }
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (sessionId != null && host.equals("mcp") && method.equals("POST")) {
            b.header("Mcp-Session-Id", sessionId);
        }
        return client.send(body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(body)));
    }

    private static String kind(String host, String method, String path) {
        return host + " " + method + " " + path;
    }

    private String initialize() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                + protocolVersion + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"connector-test\","
                + "\"version\":\"1\"}}}";
    }

    private static String call(int id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":"
                + "\"list_accounts\",\"arguments\":{}}}";
    }

    private static String queryValue(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("no " + name + " in the redirect");
    }
}
