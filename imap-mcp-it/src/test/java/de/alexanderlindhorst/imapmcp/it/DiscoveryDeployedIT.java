package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RFC 9728 discovery and the SPEC 5.3 challenge against the deployed stack, every request through the forward proxy
 * and Caddy with its local CA (#427). The expected values come from the config that setup.sh rendered in the copy.
 */
class DiscoveryDeployedIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static DeployedStack stack;
    private static String resource;
    private static String issuer;

    @BeforeAll
    static void start() {
        stack = DeployedStack.instance();
        resource = stack.configValue("oauth", "resource");
        issuer = stack.configValue("oauth", "issuer");
    }

    @Test
    void protectedResourceMetadataNamesTheResourceAndTheIssuer() throws Exception {
        HttpResponse<String> r = stack.get(stack.mcpOrigin() + "/.well-known/oauth-protected-resource/mcp");
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode doc = JSON.readTree(r.body());
        assertThat(doc.get("resource").asString()).isEqualTo(resource);
        assertThat(doc.get("authorization_servers").valueStream().map(JsonNode::asString).toList())
                .isEqualTo(List.of(issuer));
    }

    @Test
    void theRootMetadataPathReturnsTheIdenticalDocument() throws Exception {
        String pathed = stack.get(stack.mcpOrigin() + "/.well-known/oauth-protected-resource/mcp").body();
        HttpResponse<String> root = stack.get(stack.mcpOrigin() + "/.well-known/oauth-protected-resource");
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(root.body())).isEqualTo(JSON.readTree(pathed));
    }

    @Test
    void unauthenticatedPostGetsTheVerbatimChallenge() throws Exception {
        HttpResponse<String> r = stack.postJson(resource,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().allValues("WWW-Authenticate")).containsExactly("Bearer realm=\"imap-mcp\", "
                + "resource_metadata=\"" + stack.mcpOrigin() + "/.well-known/oauth-protected-resource/mcp\", "
                + "scope=\"imap-mcp:read imap-mcp:write\"");
    }

    @Test
    void internalEndpointsAreNotReachableThroughCaddy() throws Exception {
        assertThat(stack.get(stack.mcpOrigin() + "/health").statusCode()).isEqualTo(404);
        for (String path : List.of("/admin/", "/realms/master/", "/realms/imap-mcp/clients-registrations/")) {
            assertThat(stack.get(stack.idpOrigin() + path).statusCode()).as(path).isEqualTo(404);
        }
    }

    @Test
    void theIssuerMetadataNamesTheConfiguredIssuer() throws Exception {
        HttpResponse<String> r = stack.get(issuer + "/.well-known/openid-configuration");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(r.body()).get("issuer").asString()).isEqualTo(issuer);
    }
}
