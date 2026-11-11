package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The connector contract (#430, SPEC 5.3 and 5.8 "Connector contract") against the deployed stack: the steps the
 * Claude connector performs, from the challenge of an unauthenticated {@code POST /mcp} to the authenticated MCP
 * session, every request through the forward proxy and Caddy, recorded in the client's own trace. Users are created
 * per test with kcadm and a generated password; no realm setting is changed.
 */
class ConnectorClientDeployedIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCOPE = "imap-mcp:read imap-mcp:write";
    private static final Set<String> HTTP_PUBLIC_TOOLS = Set.of("list_accounts", "list_folders", "search_messages",
            "get_message", "list_attachments", "download_attachment", "set_flags", "move_messages", "copy_messages",
            "delete_messages", "create_folder");

    private static DeployedStack stack;
    private static KeycloakAdmin admin;
    private static String resource;
    private static String issuer;

    @BeforeAll
    static void start() {
        stack = DeployedStack.instance();
        admin = new KeycloakAdmin(stack);
        resource = stack.configValue("oauth", "resource");
        issuer = stack.configValue("oauth", "issuer");
    }

    // ---- the connector's path, in order ----------------------------------------------------------------------

    @Test
    void theConnectorContractFromTheChallengeToAnAuthenticatedSession() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        KeycloakAdmin.TestUser user = admin.createUser();
        List<String> secrets = new ArrayList<>(List.of(user.password()));

        // 1. the challenge of an unauthenticated initialize
        HttpResponse<String> unauthorized = client.initializeWithoutToken();
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.headers().allValues("WWW-Authenticate")).hasSize(1);
        AuthParams.Challenge challenge = ConnectorClient.bearerChallenge(unauthorized);
        String metadataUrl = challenge.param("resource_metadata");
        assertThat(metadataUrl).isEqualTo(stack.mcpOrigin() + "/.well-known/oauth-protected-resource/mcp");
        String scope = challenge.param("scope");
        assertThat(scope).isEqualTo(SCOPE);

        // 2. the protected resource metadata, at the URL of the challenge
        HttpResponse<String> prm = client.get(metadataUrl);
        assertThat(prm.statusCode()).isEqualTo(200);
        JsonNode metadata = JSON.readTree(prm.body());
        assertThat(metadata.get("resource").asString()).isEqualTo(resource);
        assertThat(metadata.get("authorization_servers")).hasSize(1);
        String authorizationServer = metadata.get("authorization_servers").get(0).asString();
        assertThat(strings(metadata.get("bearer_methods_supported"))).contains("header");
        assertThat(strings(metadata.get("scopes_supported"))).doesNotContain("offline_access");

        // 3. the authorization server metadata (the third URL of the MCP order)
        assertThat(authorizationServer).isEqualTo(issuer);
        HttpResponse<String> asm = client.get(authorizationServer + "/.well-known/openid-configuration");
        assertThat(asm.statusCode()).isEqualTo(200);
        JsonNode as = JSON.readTree(asm.body());
        assertThat(as.get("issuer").asString()).isEqualTo(authorizationServer);
        String authorizationEndpoint = as.get("authorization_endpoint").asString();
        String tokenEndpoint = as.get("token_endpoint").asString();

        // 4. client id claude-connector, no secret, no registration: nothing to send here (steps 5 and 6 carry it)

        // 5. the authorization request, the login form and the consent page; the redirect is read, never followed
        String verifier = Pkce.newVerifier();
        Map<String, String> params = client.authorizationParams(ConnectorClient.CLAUDE_AI_CALLBACK, scope, resource,
                verifier);
        secrets.addAll(List.of(verifier, params.get("state")));
        ConnectorClient.AuthorizationOutcome outcome = client.authorize(authorizationEndpoint, params, user);
        assertThat(outcome.consentShown()).as("a new user is asked for consent").isTrue();
        URI back = outcome.redirect();
        assertThat(back).isNotNull();
        assertThat(back.toString()).startsWith(ConnectorClient.CLAUDE_AI_CALLBACK + "?");
        Map<String, String> q = query(back);
        assertThat(q.get("state")).isEqualTo(params.get("state"));
        assertThat(q).containsKey("code").doesNotContainKey("error");
        secrets.add(q.get("code"));

        // 6. the token request
        HttpResponse<String> tokenResponse = client.token(tokenEndpoint, tokenForm(q.get("code"), verifier,
                ConnectorClient.CLAUDE_AI_CALLBACK));
        assertThat(tokenResponse.statusCode()).isEqualTo(200);
        JsonNode token = JSON.readTree(tokenResponse.body());
        assertThat(token.get("token_type").asString()).isEqualToIgnoringCase("Bearer");
        String accessToken = token.get("access_token").asString();
        String refreshToken = token.get("refresh_token").asString();
        secrets.addAll(List.of(accessToken, refreshToken));
        assertThat(refreshToken).isNotBlank();
        assertThat(token.get("expires_in").asInt()).isEqualTo(admin.accessTokenLifespan());
        JsonNode claims = claims(accessToken);
        assertThat(claims.get("iss").asString()).isEqualTo(issuer);
        assertThat(audiences(claims)).contains(resource);
        assertThat(claims.get("azp").asString()).isEqualTo(ConnectorClient.CLIENT_ID);
        assertThat(List.of(claims.get("scope").asString().split(" "))).contains("imap-mcp:read", "imap-mcp:write");

        // 7. the SDK client over Streamable HTTP with that token
        try (McpSyncClient mcp = client.mcp(accessToken)) {
            mcp.initialize();
            assertThat(mcp.listTools().tools()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrderElementsOf(expectedTools());
            Map<String, Object> accounts = ToolResults.payload(
                    mcp.callTool(new McpSchema.CallToolRequest("list_accounts", Map.of())));
            assertThat(accounts.get("accounts")).isEqualTo(List.of());
            assertThat(accounts.get("link_url")).isEqualTo(stack.configValue("public", "baseUrl") + "/accounts");
        }

        // the trace: the steps in order, in the fixture format, and not one secret of this run in it
        ConnectorTrace trace = client.trace();
        List<ConnectorSessionFixture.Request> rs = trace.fixture().requests();
        assertThat(rs.getFirst().method()).isEqualTo("POST");
        assertThat(rs.getFirst().path()).isEqualTo("/mcp");
        assertThat(rs.getFirst().status()).isEqualTo(401);
        assertThat(rs.getFirst().wwwAuthenticate()).isTrue();
        assertThat(rs.getFirst().headers()).doesNotContainKeys("Authorization", "MCP-Protocol-Version", "Origin");
        assertThat(rs).extracting(r -> r.method() + " " + r.host() + " " + r.path()).containsSubsequence(
                "POST mcp /mcp",
                "GET mcp /.well-known/oauth-protected-resource/mcp",
                "GET idp /realms/imap-mcp/.well-known/openid-configuration",
                "GET idp /realms/imap-mcp/protocol/openid-connect/auth",
                "POST idp /realms/imap-mcp/login-actions/authenticate",
                "POST idp /realms/imap-mcp/protocol/openid-connect/token",
                "POST mcp /mcp");
        assertThat(rs.getLast().headers()).containsEntry("Authorization", "present");
        assertThat(ConnectorSessionFixture.read(trace.text()).requests()).isEqualTo(rs);
        secrets.addAll(client.cookieValues());
        assertThat(secrets).allSatisfy(s -> assertThat(trace.text()).doesNotContain(s));
        assertThat(trace.text()).doesNotContain("eyJ");
    }

    @Test
    void aGetOnTheEndpointWithoutATokenIs405WithoutAChallenge() throws Exception {
        HttpResponse<String> r = new ConnectorClient(stack).getMcpWithoutToken();
        assertThat(r.statusCode()).isEqualTo(405);
        assertThat(r.headers().allValues("WWW-Authenticate")).isEmpty();
    }

    @Test
    void theRootMetadataIsIdenticalAndHeadWorksOnBoth() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        String pathed = stack.mcpOrigin() + "/.well-known/oauth-protected-resource/mcp";
        String root = stack.mcpOrigin() + "/.well-known/oauth-protected-resource";
        assertThat(JSON.readTree(client.get(root).body())).isEqualTo(JSON.readTree(client.get(pathed).body()));
        assertThat(client.head(pathed).statusCode()).isEqualTo(200);
        assertThat(client.head(root).statusCode()).isEqualTo(200);
    }

    @Test
    void authorizationServerDiscoveryFollowsTheMcpOrderAndCharacterizesTheOptionalFields() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        URI iss = URI.create(issuer);
        String origin = stack.idpOrigin();
        for (String url : List.of(origin + "/.well-known/oauth-authorization-server" + iss.getPath(),
                origin + "/.well-known/openid-configuration" + iss.getPath())) {
            int status = client.get(url).statusCode();
            assertThat(status).as(url).isNotEqualTo(200);
            assertThat(status / 100).as(url).isNotEqualTo(3);
        }
        JsonNode as = JSON.readTree(client.get(issuer + "/.well-known/openid-configuration").body());
        assertThat(as.get("authorization_endpoint").asString()).startsWith("https://").startsWith(origin + "/");
        assertThat(as.get("token_endpoint").asString()).startsWith("https://").startsWith(origin + "/");
        assertThat(strings(as.get("response_types_supported"))).contains("code");
        assertThat(strings(as.get("grant_types_supported"))).contains("authorization_code", "refresh_token");
        assertThat(strings(as.get("code_challenge_methods_supported"))).contains("S256");
        assertThat(strings(as.get("token_endpoint_auth_methods_supported"))).contains("none");
        System.out.println("characterized: authorization_response_iss_parameter_supported="
                + as.get("authorization_response_iss_parameter_supported") + " registration_endpoint="
                + as.get("registration_endpoint"));
    }

    // ---- negatives -------------------------------------------------------------------------------------------

    @Test
    void anAuthorizationWithoutAChallengeOrWithPlainIsRefused() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        KeycloakAdmin.TestUser user = admin.createUser();
        String endpoint = discover(client).get("authorization_endpoint").asString();
        Map<String, String> none = client.authorizationParams(ConnectorClient.CLAUDE_AI_CALLBACK, SCOPE, resource,
                Pkce.newVerifier());
        none.remove("code_challenge");
        none.remove("code_challenge_method");
        assertRefused(client.authorize(endpoint, none, user));
        Map<String, String> plain = client.authorizationParams(ConnectorClient.CLAUDE_AI_CALLBACK, SCOPE, resource,
                Pkce.newVerifier());
        plain.put("code_challenge_method", "plain");
        assertRefused(client.authorize(endpoint, plain, user));
    }

    @Test
    void anUnregisteredRedirectUriGivesAnErrorPageNeverARedirectToIt() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        KeycloakAdmin.TestUser user = admin.createUser();
        String endpoint = discover(client).get("authorization_endpoint").asString();
        ConnectorClient.AuthorizationOutcome outcome = client.authorize(endpoint, client.authorizationParams(
                "https://evil.example/cb", SCOPE, resource, Pkce.newVerifier()), user);
        assertThat(outcome.redirect()).isNull();
        assertThat(outcome.status()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void theClaudeComCallbackWorksToo() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        KeycloakAdmin.TestUser user = admin.createUser();
        JsonNode as = discover(client);
        String verifier = Pkce.newVerifier();
        ConnectorClient.AuthorizationOutcome outcome = client.authorize(as.get("authorization_endpoint").asString(),
                client.authorizationParams(ConnectorClient.CLAUDE_COM_CALLBACK, SCOPE, resource, verifier), user);
        assertThat(outcome.redirect()).isNotNull();
        assertThat(outcome.redirect().toString()).startsWith(ConnectorClient.CLAUDE_COM_CALLBACK + "?");
        Map<String, String> q = query(outcome.redirect());
        assertThat(q).containsKey("code").doesNotContainKey("error");
        HttpResponse<String> token = client.token(as.get("token_endpoint").asString(),
                tokenForm(q.get("code"), verifier, ConnectorClient.CLAUDE_COM_CALLBACK));
        assertThat(token.statusCode()).isEqualTo(200);
    }

    @Test
    void aWrongVerifierAndAReplayedCodeBothGiveInvalidGrant() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        KeycloakAdmin.TestUser user = admin.createUser();
        JsonNode as = discover(client);
        String endpoint = as.get("authorization_endpoint").asString();
        String tokenEndpoint = as.get("token_endpoint").asString();

        String verifier = Pkce.newVerifier();
        String code = codeFor(client, endpoint, user, verifier);
        HttpResponse<String> bad = client.token(tokenEndpoint, tokenForm(code, Pkce.newVerifier(),
                ConnectorClient.CLAUDE_AI_CALLBACK));
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(bad.body()).get("error").asString()).isEqualTo("invalid_grant");

        String second = Pkce.newVerifier();
        String code2 = codeFor(client, endpoint, user, second);
        assertThat(client.token(tokenEndpoint, tokenForm(code2, second, ConnectorClient.CLAUDE_AI_CALLBACK))
                .statusCode()).isEqualTo(200);
        HttpResponse<String> replay = client.token(tokenEndpoint, tokenForm(code2, second,
                ConnectorClient.CLAUDE_AI_CALLBACK));
        assertThat(replay.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(replay.body()).get("error").asString()).isEqualTo("invalid_grant");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static JsonNode discover(ConnectorClient client) throws Exception {
        return JSON.readTree(client.get(issuer + "/.well-known/openid-configuration").body());
    }

    private static String codeFor(ConnectorClient client, String endpoint, KeycloakAdmin.TestUser user,
            String verifier) throws Exception {
        ConnectorClient.AuthorizationOutcome o = client.authorize(endpoint, client.authorizationParams(
                ConnectorClient.CLAUDE_AI_CALLBACK, SCOPE, resource, verifier), user);
        assertThat(o.redirect()).isNotNull();
        return query(o.redirect()).get("code");
    }

    /** A refusal: an error redirect (no code) or an error page, never a code. */
    private static void assertRefused(ConnectorClient.AuthorizationOutcome o) {
        if (o.redirect() != null) {
            Map<String, String> q = query(o.redirect());
            assertThat(q).containsKey("error").doesNotContainKey("code");
        } else {
            assertThat(o.status()).isGreaterThanOrEqualTo(400);
        }
    }

    private static Map<String, String> tokenForm(String code, String verifier, String redirectUri) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("redirect_uri", redirectUri);
        f.put("client_id", ConnectorClient.CLIENT_ID);
        f.put("code_verifier", verifier);
        f.put("resource", resource);
        return f;
    }

    private static Set<String> expectedTools() {
        Set<String> tools = new java.util.HashSet<>(HTTP_PUBLIC_TOOLS);
        try {
            if ("true".equals(stack.configValue("purge", "enabled"))) {
                tools.add("purge_messages");
            }
        } catch (IllegalStateException e) {
            // no purge section: purge is off
        }
        return tools;
    }

    private static List<String> strings(JsonNode array) {
        assertThat(array).as("array field").isNotNull();
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    private static List<String> audiences(JsonNode claims) {
        JsonNode aud = claims.get("aud");
        return aud.isArray() ? strings(aud) : List.of(aud.asString());
    }

    private static JsonNode claims(String jwt) {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> q = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }
}
