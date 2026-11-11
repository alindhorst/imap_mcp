package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The token lifecycle against the deployed stack (#432, SPEC 5.3 "Discovery and challenges", token checks with the
 * 60 s skew, D3, D19; DEPLOYMENT section 4 "Refresh token rotation"): expiry and refresh, rotation and its reuse
 * handling, parallel refresh, the scope challenge and re-authorization, and how the shipped realm answers scope
 * strings a connector may send. The realm is the shipped one (rotation on, no reuse); the only setting a test
 * changes is {@code accessTokenLifespan}, and it restores it before it waits.
 *
 * <p>Tests marked "characterization" pin what Keycloak 26.4 was observed to do; they exist so that an upgrade that
 * changes it fails loudly, and their values are the evidence for the README and DEPLOYMENT text.
 *
 * <p>No token, code, verifier or password is ever put in an assertion message or left in a trace: comparisons of
 * secrets are made as booleans, and every client's trace is scanned for the secrets of its scenario.
 */
class DeployedTokenLifecycleIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BOTH = "imap-mcp:read imap-mcp:write";
    private static final String CALLBACK = ConnectorClient.CLAUDE_AI_CALLBACK;
    private static final int SKEW_SECONDS = 60;

    private static DeployedStack stack;
    private static KeycloakAdmin admin;
    private static String resource;
    private static String issuer;
    private static String authorizationEndpoint;
    private static String tokenEndpoint;
    private static String metadataUrl;

    @BeforeAll
    static void start() throws Exception {
        stack = DeployedStack.instance();
        admin = new KeycloakAdmin(stack);
        resource = stack.configValue("oauth", "resource");
        issuer = stack.configValue("oauth", "issuer");
        metadataUrl = stack.mcpOrigin() + "/.well-known/oauth-protected-resource/mcp";
        JsonNode as = JSON.readTree(new ConnectorClient(stack).get(issuer + "/.well-known/openid-configuration")
                .body());
        authorizationEndpoint = as.get("authorization_endpoint").asString();
        tokenEndpoint = as.get("token_endpoint").asString();
    }

    // ---- expiry -> 401 -> refresh ---------------------------------------------------------------------------------

    /**
     * The window of the lowered lifespan is the issue of one token pair; the realm is restored before the wait. The
     * token is still accepted inside the 60 s skew and refused beyond it with the {@code invalid_token} challenge,
     * verbatim; the client then refreshes and the retried request succeeds.
     */
    @Test
    void anExpiredAccessTokenGets401InvalidTokenAndARefreshMakesTheRetrySucceed() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        KeycloakAdmin.TestUser user = admin.createUser();
        List<String> secrets = new ArrayList<>(List.of(user.password()));
        int shipped = admin.accessTokenLifespan();
        Tokens issued;
        try {
            admin.setAccessTokenLifespan(2);
            issued = obtain(client, user, BOTH, secrets);
        } finally {
            admin.setAccessTokenLifespan(shipped);
        }
        assertThat(admin.accessTokenLifespan()).as("lifespan restored").isEqualTo(shipped);
        assertThat(issued.expiresIn()).isEqualTo(2);
        long expMillis = claims(issued.access()).get("exp").asLong() * 1000L;

        sleepUntil(expMillis + 10_000L);
        assertThat(client.initializeWithToken(issued.access()).statusCode())
                .as("expired by 10 s, inside the skew").isEqualTo(200);

        sleepUntil(expMillis + (SKEW_SECONDS + 3) * 1000L);
        HttpResponse<String> expired = client.initializeWithToken(issued.access());
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(expired.headers().allValues("WWW-Authenticate")).containsExactly(invalidTokenChallenge());

        HttpResponse<String> refreshed = client.token(tokenEndpoint, refreshForm(issued.refresh()));
        assertThat(refreshed.statusCode()).isEqualTo(200);
        Tokens next = tokens(refreshed, secrets);
        assertThat(next.expiresIn()).isEqualTo(shipped);
        assertThat(client.initializeWithToken(next.access()).statusCode()).isEqualTo(200);
        assertTraceClean(client, secrets);
    }

    // ---- rotation ---------------------------------------------------------------------------------------------------

    /**
     * Characterization of the shipped {@code revokeRefreshToken: true} with {@code refreshTokenMaxReuse: 0}: every
     * refresh returns a new refresh token, the old one is refused with {@code invalid_grant}, and what a reuse of the
     * old one does to its successor is pinned below.
     */
    @Test
    void aRefreshRotatesTheRefreshTokenAndReusingTheOldOneIsInvalidGrant() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        KeycloakAdmin.TestUser user = admin.createUser();
        List<String> secrets = new ArrayList<>(List.of(user.password()));
        Tokens first = obtain(client, user, BOTH, secrets);

        HttpResponse<String> r1 = client.token(tokenEndpoint, refreshForm(first.refresh()));
        assertThat(r1.statusCode()).isEqualTo(200);
        Tokens second = tokens(r1, secrets);
        assertThat(second.refresh().equals(first.refresh())).as("RT2 differs from RT1").isFalse();
        assertThat(client.initializeWithToken(second.access()).statusCode()).isEqualTo(200);

        HttpResponse<String> reuse = client.token(tokenEndpoint, refreshForm(first.refresh()));
        assertThat(reuse.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(reuse.body()).get("error").asString()).isEqualTo("invalid_grant");

        // characterization: the reuse of RT1 revokes the whole session, so RT2 is refused too
        HttpResponse<String> afterReuse = client.token(tokenEndpoint, refreshForm(second.refresh()));
        assertThat(afterReuse.statusCode()).as("RT2 after the reuse of RT1").isEqualTo(400);
        assertThat(JSON.readTree(afterReuse.body()).get("error").asString()).isEqualTo("invalid_grant");
        assertTraceClean(client, secrets);
    }

    /**
     * Characterization, the "sporadic reconnect" mechanism of DEPLOYMENT section 4: two refreshes with the same refresh
     * token at the same moment, repeated. Observed: usually one {@code 200} and one {@code invalid_grant} (and the
     * loser's attempt, a reuse of the old token, revokes the session, so the winner's new token is refused at its next
     * use); now and then (a race window in Keycloak) both succeed (then both new tokens were observed to work when used in turn; not asserted, the
     * race gives no stable rule beyond the status pair). Both refused never happens. The test pins these rules, not the frequency, which is printed.
     */
    @Test
    void twoParallelRefreshesWithTheSameTokenHaveThePinnedOutcome() throws Exception {
        List<String> secrets = new ArrayList<>();
        Map<String, Integer> outcomes = new java.util.TreeMap<>();
        for (int round = 0; round < 8; round++) {
            ConnectorClient client = new ConnectorClient(stack);
            KeycloakAdmin.TestUser user = admin.createUser();
            secrets.add(user.password());
            Tokens first = obtain(client, user, BOTH, secrets);
            List<HttpResponse<String>> responses = parallelRefresh(client, first.refresh());
            List<Integer> statuses = new ArrayList<>(responses.stream().map(HttpResponse::statusCode).toList());
            Collections.sort(statuses);
            List<Integer> successors = new ArrayList<>();
            for (HttpResponse<String> r : responses) {
                if (r.statusCode() == 200) {
                    successors.add(client.token(tokenEndpoint, refreshForm(tokens(r, secrets).refresh()))
                            .statusCode());
                } else {
                    assertThat(JSON.readTree(r.body()).get("error").asString()).isEqualTo("invalid_grant");
                }
            }
            outcomes.merge("parallel " + statuses + ", then the new tokens used in turn " + successors, 1,
                    Integer::sum);
            if (statuses.equals(List.of(200, 400))) {
                assertThat(successors).as("the winner's new token after the loser's reuse").containsExactly(400);
            } else {
                assertThat(statuses).as("a pair other than one success and one refusal").containsExactly(200, 200);
            }
            assertTraceClean(client, secrets);
        }
        System.out.println("DeployedTokenLifecycleIT parallel refresh outcomes: " + outcomes);
    }

    // ---- scopes (D3, D19) -------------------------------------------------------------------------------------------

    /**
     * A token with {@code imap-mcp:write} only has no read scope: {@code 403} with the {@code insufficient_scope}
     * challenge, verbatim; the client re-authorizes with the union of its scope and the challenge's, and the retry
     * succeeds (SPEC 5.3, the Claude behaviour).
     */
    @Test
    void aWriteOnlyTokenGets403InsufficientScopeAndTheUnionAuthorizationSucceeds() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        KeycloakAdmin.TestUser user = admin.createUser();
        List<String> secrets = new ArrayList<>(List.of(user.password()));
        Tokens writeOnly = obtain(client, user, "imap-mcp:write", secrets);
        assertThat(scopes(claims(writeOnly.access()))).contains("imap-mcp:write").doesNotContain("imap-mcp:read");

        HttpResponse<String> refused = client.initializeWithToken(writeOnly.access());
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.headers().allValues("WWW-Authenticate")).containsExactly("Bearer realm=\"imap-mcp\", "
                + "error=\"insufficient_scope\", resource_metadata=\"" + metadataUrl + "\", scope=\"" + BOTH + "\"");
        String challengeScope = ConnectorClient.bearerChallenge(refused).param("scope");

        String union = String.join(" ", new java.util.LinkedHashSet<>(
                List.of(("imap-mcp:write " + challengeScope).split(" "))));
        Tokens reauthorized = obtain(client, user, union, secrets);
        assertThat(scopes(claims(reauthorized.access()))).contains("imap-mcp:read", "imap-mcp:write");
        assertThat(client.initializeWithToken(reauthorized.access()).statusCode()).isEqualTo(200);
        assertTraceClean(client, secrets);
    }

    /**
     * A token with the read scope only passes the filter and is refused per call as a tool error (a {@code 200}), and
     * nothing in the exchange asks the client to re-authorize (D19): no challenge, no further authorization request.
     */
    @Test
    void aReadOnlyTokenIsPermissionDeniedInsufficientScopeOnSetFlagsWithoutAReauthorization() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        KeycloakAdmin.TestUser user = admin.createUser();
        List<String> secrets = new ArrayList<>(List.of(user.password()));
        Tokens readOnly = obtain(client, user, "imap-mcp:read", secrets);
        assertThat(scopes(claims(readOnly.access()))).contains("imap-mcp:read").doesNotContain("imap-mcp:write");
        String account = seedTenantWithAccount(claims(readOnly.access()).get("sub").asString());

        int before = client.trace().fixture().requests().size();
        try (McpSyncClient mcp = client.mcp(readOnly.access())) {
            mcp.initialize();
            McpSchema.CallToolResult result = mcp.callTool(new McpSchema.CallToolRequest("set_flags",
                    Map.of("account", account, "handles", List.of("m1.x"), "seen", true)));
            assertThat(result.isError()).isTrue();
            Map<String, Object> error = error(ToolResults.payload(result));
            assertThat(error).containsEntry("code", "PERMISSION_DENIED").containsEntry("retryable", false);
            assertThat(error.get("details").toString()).contains("insufficient_scope").contains("imap-mcp:write");
        }
        List<ConnectorSessionFixture.Request> after = client.trace().fixture().requests()
                .subList(before, client.trace().fixture().requests().size());
        assertThat(after).as("MCP requests of the session").isNotEmpty()
                .allSatisfy(r -> assertThat(r.status()).isNotIn(401, 403))
                .allSatisfy(r -> assertThat(r.wwwAuthenticate()).isFalse())
                .allSatisfy(r -> assertThat(r.host()).isEqualTo("mcp"));
        assertTraceClean(client, secrets);
    }

    /**
     * Characterization, the realm's answer to an authorization request that adds one more scope to the two of the
     * connector. The README's statement that a scope the client may not use is dropped silently is checked here.
     */
    @Test
    void theRealmAnswersAnAuthorizationWithAnAdditionalScopeAsPinned() throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        KeycloakAdmin.TestUser user = admin.createUser();
        List<String> secrets = new ArrayList<>(List.of(user.password()));
        Map<String, String> observed = new LinkedHashMap<>();
        for (String extra : List.of("openid", "offline_access", "profile", "email")) {
            String verifier = Pkce.newVerifier();
            Map<String, String> params = client.authorizationParams(CALLBACK, BOTH + " " + extra, resource, verifier);
            secrets.addAll(List.of(verifier, params.get("state")));
            ConnectorClient.AuthorizationOutcome o = client.authorize(authorizationEndpoint, params, user);
            if (o.redirect() == null) {
                observed.put(extra, "page " + o.status());
                continue;
            }
            Map<String, String> q = query(o.redirect());
            if (q.containsKey("error")) {
                observed.put(extra, "error " + q.get("error"));
                continue;
            }
            secrets.add(q.get("code"));
            HttpResponse<String> t = client.token(tokenEndpoint, tokenForm(q.get("code"), verifier));
            assertThat(t.statusCode()).isEqualTo(200);
            Tokens tokens = tokens(t, secrets);
            List<String> granted = new ArrayList<>(scopes(claims(tokens.access())));
            granted.removeAll(List.of("imap-mcp:read", "imap-mcp:write"));
            observed.put(extra, "accepted, further scopes in the token: " + granted);
        }
        assertThat(observed).containsExactlyInAnyOrderEntriesOf(Map.of(
                "openid", "accepted, further scopes in the token: [openid]", "offline_access", "error invalid_scope",
                "profile", "error invalid_scope", "email", "error invalid_scope"));
        assertTraceClean(client, secrets);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** The tokens of one grant; secrets never reach {@code toString}. */
    private record Tokens(String access, String refresh, int expiresIn) {
        @Override
        public String toString() {
            return "Tokens[expiresIn=" + expiresIn + "]";
        }
    }

    private static Tokens obtain(ConnectorClient client, KeycloakAdmin.TestUser user, String scope,
            List<String> secrets) throws Exception {
        String verifier = Pkce.newVerifier();
        Map<String, String> params = client.authorizationParams(CALLBACK, scope, resource, verifier);
        secrets.addAll(List.of(verifier, params.get("state")));
        ConnectorClient.AuthorizationOutcome outcome = client.authorize(authorizationEndpoint, params, user);
        assertThat(outcome.redirect()).as("authorization outcome " + outcome).isNotNull();
        Map<String, String> q = query(outcome.redirect());
        assertThat(q).containsKey("code").doesNotContainKey("error");
        secrets.add(q.get("code"));
        secrets.addAll(client.cookieValues());
        HttpResponse<String> response = client.token(tokenEndpoint, tokenForm(q.get("code"), verifier));
        assertThat(response.statusCode()).isEqualTo(200);
        return tokens(response, secrets);
    }

    private static Tokens tokens(HttpResponse<String> response, List<String> secrets) {
        JsonNode body = JSON.readTree(response.body());
        Tokens t = new Tokens(body.get("access_token").asString(), body.get("refresh_token").asString(),
                body.get("expires_in").asInt());
        secrets.addAll(List.of(t.access(), t.refresh()));
        return t;
    }

    private static List<HttpResponse<String>> parallelRefresh(ConnectorClient client, String refreshToken)
            throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return client.token(tokenEndpoint, refreshForm(refreshToken));
                }));
            }
            go.countDown();
            List<HttpResponse<String>> out = new ArrayList<>();
            for (Future<HttpResponse<String>> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }

    private static Map<String, String> tokenForm(String code, String verifier) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("code", code);
        f.put("redirect_uri", CALLBACK);
        f.put("client_id", ConnectorClient.CLIENT_ID);
        f.put("code_verifier", verifier);
        f.put("resource", resource);
        return f;
    }

    private static Map<String, String> refreshForm(String refreshToken) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("refresh_token", refreshToken);
        f.put("client_id", ConnectorClient.CLIENT_ID);
        f.put("resource", resource);
        return f;
    }

    private static String invalidTokenChallenge() {
        return "Bearer realm=\"imap-mcp\", error=\"invalid_token\", resource_metadata=\"" + metadataUrl
                + "\", scope=\"" + BOTH + "\"";
    }

    /**
     * A tenant for the token's subject and one account, written with SQL into the deployed database (the linking
     * surface needs a reachable IMAP server, which the stack does not have). The account is never connected to: the
     * scope check refuses before any I/O. Returns the account handle (looked up by column, tenant-scoped).
     */
    private static String seedTenantWithAccount(String subject) throws Exception {
        String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
        StringBuilder handle = new StringBuilder("acc_");
        for (int i = 0; i < 26; i++) {
            handle.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        String tenant = UUID.randomUUID().toString();
        String sql = "INSERT INTO tenant (id, issuer, subject) VALUES ('" + tenant + "', '" + issuer + "', '"
                + subject.replace("'", "''") + "'); INSERT INTO mailbox_account (tenant_id, id, handle, label, address,"
                + " host, port, security, flavor, username, auth, read_only, allow_purge) VALUES ('" + tenant
                + "', 'lifecycle', '" + handle + "', 'Lifecycle', 'user@example.test', 'imap.example.test', 993,"
                + " 'IMPLICIT_TLS', 'GENERIC', 'user', 'password', false, false)";
        DeployedCompose.ExecResult r = stack.exec("postgres", List.of(), "psql", "-U", "postgres", "-d", "imapmcp",
                "-v", "ON_ERROR_STOP=1", "-c", sql);
        assertThat(r.exitCode()).as("seeding the tenant: " + r).isZero();
        return handle.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("error");
    }

    private static void assertTraceClean(ConnectorClient client, List<String> secrets) {
        List<String> all = new ArrayList<>(secrets);
        all.addAll(client.cookieValues());
        String text = client.trace().text();
        for (String s : all) {
            assertThat(text.contains(s)).as("a secret of this scenario is in the trace").isFalse();
        }
        assertThat(text.contains("eyJ")).as("a JWT is in the trace").isFalse();
    }

    private static void sleepUntil(long epochMillis) throws InterruptedException {
        long wait = epochMillis - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    private static List<String> scopes(JsonNode claims) {
        return List.of(claims.get("scope").asString().split(" "));
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
