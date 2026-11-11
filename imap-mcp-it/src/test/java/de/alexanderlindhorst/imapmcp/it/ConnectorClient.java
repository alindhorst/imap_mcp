package de.alexanderlindhorst.imapmcp.it;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * A test client that performs, in order, what the Claude connector performs against an MCP server with OAuth (MCP
 * authorization specification 2026-07-28, SPEC 5.3 and 5.8 "Connector contract"): the challenge of an unauthenticated
 * {@code POST}, the protected resource metadata, the authorization server metadata, the authorization code flow with
 * PKCE S256 for the public client {@code claude-connector} (login form and consent page played like a browser), the
 * token request with {@code resource}, and the MCP session with the SDK client. Every request goes through the
 * stack's forward proxy and local CA, and every request of this instance is recorded in its own
 * {@link ConnectorTrace}, in the #434 fixture format.
 *
 * <p>The assertions are the test's; this class only performs the steps. The redirect to claude.ai or claude.com is
 * read from {@code Location} and never followed (the proxy ACL refuses those hosts anyway).
 *
 * <p>Assumptions: steps that rely on undocumented Claude behaviour [C] follow the MCP specification instead.
 * <ul>
 *   <li>The first request is {@code POST <resource>} with an {@code initialize} body, {@code Content-Type:
 *       application/json}, {@code Accept: application/json, text/event-stream}, and no {@code Authorization},
 *       {@code MCP-Protocol-Version} or {@code Origin} header.</li>
 *   <li>The protected resource metadata is fetched from the {@code resource_metadata} URL of the challenge, with
 *       {@code Accept: application/json}; the root fallback and {@code HEAD} are probed separately by the test.</li>
 *   <li>The authorization server metadata is read at {@code <issuer>/.well-known/openid-configuration}, the third
 *       URL of the MCP discovery order; the other two are probed by the test.</li>
 *   <li>The public client sends no secret and does not register; the authorization request carries {@code scope}
 *       of the challenge, {@code state}, {@code resource} and PKCE S256; the token request is a form POST with
 *       {@code resource} and without {@code Authorization}.</li>
 *   <li>Backend requests carry {@code User-Agent: Claude-User}, a value taken from the assumed fixture, not from a
 *       recording; the browser side sends a browser User-Agent (traced as {@code browser}).</li>
 *   <li>The MCP calls send {@code Authorization: Bearer} and the protocol version the SDK negotiates.</li>
 * </ul>
 * Nothing secret is traced: see {@link ConnectorTrace}. Not thread-safe; one instance per scenario.
 */
final class ConnectorClient {

    static final String CLIENT_ID = "claude-connector";
    static final String CLAUDE_AI_CALLBACK = "https://claude.ai/api/mcp/auth_callback";
    static final String CLAUDE_COM_CALLBACK = "https://claude.com/api/mcp/auth_callback";

    private static final String CONNECTOR_UA = "Claude-User";
    private static final String BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0";
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"connector-test\","
            + "\"version\":\"1\"}}}";
    private static final Pattern FORM = Pattern.compile("(?s)<form\\b([^>]*)>(.*?)</form>");
    private static final Pattern ACTION = Pattern.compile("action=\"([^\"]*)\"");
    private static final Pattern INPUT = Pattern.compile("<input\\b[^>]*>");
    private static final int MAX_HOPS = 12;

    /** Where a browser-side authorization ended: a redirect that was read (not followed) or a page. */
    record AuthorizationOutcome(int status, URI redirect, boolean consentShown, String body) {
        @Override
        public String toString() {
            return "AuthorizationOutcome[status=" + status + ", redirect=" + (redirect != null)
                    + ", consentShown=" + consentShown + "]";
        }
    }

    private final DeployedStack stack;
    private final ConnectorTrace trace;
    private final HttpClient http;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final List<String> everSeenCookieValues = new ArrayList<>();

    ConnectorClient(DeployedStack stack) {
        this.stack = stack;
        this.trace = new ConnectorTrace(URI.create(stack.mcpOrigin()).getAuthority(),
                URI.create(stack.idpOrigin()).getAuthority());
        this.http = new TracingHttpClient(stack.httpClient(), trace);
    }

    ConnectorTrace trace() {
        return trace;
    }

    // ---- steps 1 and 2: challenge and metadata -----------------------------------------------------------------

    /** Step 1: {@code POST <resource>} with an initialize body and no credentials. */
    HttpResponse<String> initializeWithoutToken() throws Exception {
        return send(HttpRequest.newBuilder(URI.create(stack.configValue("oauth", "resource")))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("User-Agent", CONNECTOR_UA).POST(HttpRequest.BodyPublishers.ofString(INITIALIZE)));
    }

    /** {@code GET <resource>} without a token. */
    HttpResponse<String> getMcpWithoutToken() throws Exception {
        return send(HttpRequest.newBuilder(URI.create(stack.configValue("oauth", "resource")))
                .header("Accept", "text/event-stream").header("User-Agent", CONNECTOR_UA).GET());
    }

    /** The one Bearer challenge of a response with exactly one WWW-Authenticate header, parsed per RFC 9110. */
    static AuthParams.Challenge bearerChallenge(HttpResponse<?> response) {
        List<String> values = response.headers().allValues("WWW-Authenticate");
        if (values.size() != 1) {
            throw new IllegalStateException("expected exactly one WWW-Authenticate header, got " + values.size());
        }
        List<AuthParams.Challenge> bearer = AuthParams.parse(values.getFirst()).stream()
                .filter(c -> c.scheme().equalsIgnoreCase("Bearer")).toList();
        if (bearer.size() != 1) {
            throw new IllegalStateException("expected exactly one Bearer challenge, got " + bearer.size());
        }
        return bearer.getFirst();
    }

    /** A metadata GET as a client sends it ({@code Accept: application/json}); also used for probes. */
    HttpResponse<String> get(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json")
                .header("User-Agent", CONNECTOR_UA).GET());
    }

    HttpResponse<String> head(String url) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json")
                .header("User-Agent", CONNECTOR_UA).method("HEAD", HttpRequest.BodyPublishers.noBody()));
    }

    // ---- steps 5 and 6: authorization and token ----------------------------------------------------------------

    /** The authorization request parameters of step 5 (mutable, so a negative test can drop or change one). */
    Map<String, String> authorizationParams(String redirectUri, String scope, String resource, String verifier) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("response_type", "code");
        p.put("client_id", CLIENT_ID);
        p.put("redirect_uri", redirectUri);
        p.put("scope", scope);
        p.put("state", Pkce.newState());
        p.put("code_challenge", Pkce.challenge(verifier));
        p.put("code_challenge_method", "S256");
        p.put("resource", resource);
        return p;
    }

    /**
     * The browser's part: open the authorization URL, fill the login form, accept the consent page, and stop at the
     * first redirect that leaves the IdP (read, never followed). A page that is neither form nor redirect ends it
     * (an error page); a login form that comes back after the credentials were sent ends it as well.
     */
    AuthorizationOutcome authorize(String authorizationEndpoint, Map<String, String> params,
            KeycloakAdmin.TestUser user) throws Exception {
        cookies.clear();
        String idp = stack.idpOrigin();
        URI next = URI.create(authorizationEndpoint + "?" + form(params));
        String method = "GET";
        String body = null;
        boolean credentialsSent = false;
        boolean consent = false;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(next).header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml");
            if (!cookies.isEmpty()) {
                b.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("; ")));
            }
            if (body != null) {
                b.header("Content-Type", "application/x-www-form-urlencoded").header("Origin", idp)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            } else {
                b.GET();
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            remember(r);
            if (r.statusCode() / 100 == 3) {
                URI location = next.resolve(r.headers().firstValue("Location").orElseThrow());
                if (!location.toString().startsWith(idp + "/")) {
                    return new AuthorizationOutcome(r.statusCode(), location, consent, null);
                }
                next = location;
                method = "GET";
                body = null;
                continue;
            }
            if (r.statusCode() != 200) {
                return new AuthorizationOutcome(r.statusCode(), null, consent, null);
            }
            Page form = page(r.body(), next);
            if (form == null || (form.login() && credentialsSent)) {
                return new AuthorizationOutcome(200, null, consent, null);
            }
            Map<String, String> fields = new LinkedHashMap<>(form.hidden());
            if (form.login()) {
                fields.put("username", user.username());
                fields.put("password", user.password());
                fields.put("credentialId", "");
                credentialsSent = true;
            } else {
                fields.put("accept", "Yes");
                consent = true;
            }
            next = form.action();
            method = "POST";
            body = form(fields);
        }
        throw new IllegalStateException("authorization did not end within " + MAX_HOPS + " steps (last " + method + ")");
    }

    /** Step 6: a form POST to the token endpoint, as the public client sends it. */
    HttpResponse<String> token(String tokenEndpoint, Map<String, String> form) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(tokenEndpoint)).header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", CONNECTOR_UA)
                .POST(HttpRequest.BodyPublishers.ofString(form(form))));
    }

    // ---- step 7: the SDK client ----------------------------------------------------------------------------------

    /** An (uninitialized) MCP client over Streamable HTTP with the bearer token; its requests are traced too. */
    McpSyncClient mcp(String accessToken) {
        URI resource = URI.create(stack.configValue("oauth", "resource"));
        String base = resource.getScheme() + "://" + resource.getAuthority();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint(resource.getPath()).clientBuilder(new TracingBuilder(http))
                .httpRequestCustomizer((b, m, u, body, ctx) -> b.header("Authorization", "Bearer " + accessToken)
                        .header("User-Agent", CONNECTOR_UA))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(30)).build();
    }

    /** Cookie values the browser side held, for the canary scan of the trace. */
    Collection<String> cookieValues() {
        return List.copyOf(everSeenCookieValues);
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    /** Sends one request through the tracing client (package-private for the contract run, #435). */
    HttpResponse<String> send(HttpRequest.Builder b) throws IOException, InterruptedException {
        return http.send(b.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void remember(HttpResponse<?> r) {
        for (String c : r.headers().allValues("Set-Cookie")) {
            String pair = c.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if (value.isEmpty() || c.toLowerCase(java.util.Locale.ROOT).contains("max-age=0")) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
                everSeenCookieValues.add(value);
            }
        }
    }

    /** A login or consent form: where it posts and its hidden fields. */
    private record Page(boolean login, URI action, Map<String, String> hidden) {
    }

    private static Page page(String html, URI base) {
        Matcher forms = FORM.matcher(html);
        while (forms.find()) {
            String inner = forms.group(2);
            boolean login = inner.contains("name=\"password\"");
            boolean consent = inner.contains("name=\"accept\"");
            if (!login && !consent) {
                continue;
            }
            Matcher a = ACTION.matcher(forms.group(1));
            if (!a.find()) {
                return null;
            }
            Map<String, String> hidden = new LinkedHashMap<>();
            Matcher in = INPUT.matcher(inner);
            while (in.find()) {
                String tag = in.group();
                String type = attr(tag, "type");
                String name = attr(tag, "name");
                if ("hidden".equals(type) && name != null) {
                    hidden.put(name, unescape(attr(tag, "value") == null ? "" : attr(tag, "value")));
                }
            }
            return new Page(login, base.resolve(unescape(a.group(1))), hidden);
        }
        return null;
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    /** Lets the SDK transport use the tracing client: every setter is ignored, {@code build} returns it. */
    private static final class TracingBuilder implements HttpClient.Builder {
        private final HttpClient client;

        TracingBuilder(HttpClient client) {
            this.client = client;
        }

        @Override
        public HttpClient.Builder cookieHandler(CookieHandler h) {
            return this;
        }

        @Override
        public HttpClient.Builder connectTimeout(Duration d) {
            return this;
        }

        @Override
        public HttpClient.Builder sslContext(SSLContext c) {
            return this;
        }

        @Override
        public HttpClient.Builder sslParameters(SSLParameters p) {
            return this;
        }

        @Override
        public HttpClient.Builder executor(Executor e) {
            return this;
        }

        @Override
        public HttpClient.Builder followRedirects(HttpClient.Redirect r) {
            return this;
        }

        @Override
        public HttpClient.Builder version(HttpClient.Version v) {
            return this;
        }

        @Override
        public HttpClient.Builder priority(int p) {
            return this;
        }

        @Override
        public HttpClient.Builder proxy(ProxySelector p) {
            return this;
        }

        @Override
        public HttpClient.Builder authenticator(Authenticator a) {
            return this;
        }

        @Override
        public HttpClient.Builder localAddress(InetAddress a) {
            return this;
        }

        @Override
        public HttpClient build() {
            return client;
        }
    }
}
