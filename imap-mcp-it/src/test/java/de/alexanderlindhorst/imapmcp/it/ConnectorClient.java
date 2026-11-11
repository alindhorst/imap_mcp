package de.alexanderlindhorst.imapmcp.it;

import io.modelcontextprotocol.client.McpSyncClient;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.Map;

/** Placeholder; see the class comment of the implementation. */
final class ConnectorClient {

    static final String CLIENT_ID = "claude-connector";
    static final String CLAUDE_AI_CALLBACK = "https://claude.ai/api/mcp/auth_callback";
    static final String CLAUDE_COM_CALLBACK = "https://claude.com/api/mcp/auth_callback";

    /** Where a browser-side authorization ended: a redirect that was read (not followed) or a page. */
    record AuthorizationOutcome(int status, URI redirect, boolean consentShown, String body) {
    }

    ConnectorClient(DeployedStack stack) {
    }

    ConnectorTrace trace() {
        throw new UnsupportedOperationException();
    }

    HttpResponse<String> initializeWithoutToken() throws Exception {
        throw new UnsupportedOperationException();
    }

    HttpResponse<String> getMcpWithoutToken() throws Exception {
        throw new UnsupportedOperationException();
    }

    static AuthParams.Challenge bearerChallenge(HttpResponse<?> response) {
        throw new UnsupportedOperationException();
    }

    HttpResponse<String> get(String url) throws Exception {
        throw new UnsupportedOperationException();
    }

    HttpResponse<String> head(String url) throws Exception {
        throw new UnsupportedOperationException();
    }

    Map<String, String> authorizationParams(String redirectUri, String scope, String resource, String verifier) {
        throw new UnsupportedOperationException();
    }

    AuthorizationOutcome authorize(String authorizationEndpoint, Map<String, String> params,
            KeycloakAdmin.TestUser user) throws Exception {
        throw new UnsupportedOperationException();
    }

    HttpResponse<String> token(String tokenEndpoint, Map<String, String> form) throws Exception {
        throw new UnsupportedOperationException();
    }

    McpSyncClient mcp(String accessToken) {
        throw new UnsupportedOperationException();
    }

    /** Cookie values the browser side held, for the canary scan of the trace. */
    Collection<String> cookieValues() {
        throw new UnsupportedOperationException();
    }
}
