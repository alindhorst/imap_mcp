package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A connector access token for a test user, by the authorization code flow with PKCE of {@link ConnectorClient}
 * (challenge, metadata, authorization, token). The token is never printed; callers keep it out of logs.
 */
final class ConnectorToken {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCOPE = "imap-mcp:read imap-mcp:write";

    private ConnectorToken() {
    }

    static String obtain(DeployedStack stack, ConnectorClient client, KeycloakAdmin.TestUser user) throws Exception {
        HttpResponse<String> unauthorized = client.initializeWithoutToken();
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        AuthParams.Challenge challenge = ConnectorClient.bearerChallenge(unauthorized);
        JsonNode metadata = JSON.readTree(client.get(challenge.param("resource_metadata")).body());
        String authorizationServer = metadata.get("authorization_servers").get(0).asString();
        JsonNode as = JSON.readTree(client.get(authorizationServer + "/.well-known/openid-configuration").body());
        String verifier = Pkce.newVerifier();
        String resource = stack.configValue("oauth", "resource");
        Map<String, String> params = client.authorizationParams(ConnectorClient.CLAUDE_AI_CALLBACK, SCOPE, resource,
                verifier);
        ConnectorClient.AuthorizationOutcome outcome = client.authorize(as.get("authorization_endpoint").asString(),
                params, user);
        assertThat(outcome.redirect()).isNotNull();
        String code = code(outcome.redirect());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", ConnectorClient.CLAUDE_AI_CALLBACK);
        form.put("client_id", ConnectorClient.CLIENT_ID);
        form.put("code_verifier", verifier);
        form.put("resource", resource);
        HttpResponse<String> token = client.token(as.get("token_endpoint").asString(), form);
        assertThat(token.statusCode()).isEqualTo(200);
        return JSON.readTree(token.body()).get("access_token").asString();
    }

    private static String code(URI redirect) {
        for (String pair : redirect.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals("code")) {
                String code = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                assertThat(code).isNotBlank();
                return code;
            }
        }
        throw new IllegalStateException("no code in the authorization redirect");
    }
}
