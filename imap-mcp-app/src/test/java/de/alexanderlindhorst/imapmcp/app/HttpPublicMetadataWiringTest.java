package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import de.alexanderlindhorst.imapmcp.transport.http.OAuthResourceConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The metadata wiring of {@code http-public} for a resource behind a path prefix (SPEC 5.3). */
class HttpPublicMetadataWiringTest {

    @Test
    void aPrefixedResourceIsServedUnderItsPathAndAtTheRootWithResourceEqualToIt() throws Exception {
        String resource = "https://mail.example.com/x/mcp";
        OAuthResourceConfig oauth = new OAuthResourceConfig("https://idp.example.com/realms/t", resource, "sub",
                List.of("imap-mcp:read", "imap-mcp:write"));
        HttpLocalServer server = HttpPublicServer.metadataServlets(HttpLocalServer.builder().port(0)
                .bearerFilter((rq, rs, ch) -> ch.doFilter(rq, rs))
                .principalFilter((rq, rs, ch) -> ch.doFilter(rq, rs)), oauth).build();
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            for (String path : List.of("/.well-known/oauth-protected-resource/x/mcp",
                    "/.well-known/oauth-protected-resource")) {
                HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(r.statusCode()).as(path).isEqualTo(200);
                assertThat(r.body()).as(path).contains("\"resource\":\"" + resource + "\"");
            }
            assertThat(oauth.metadataUrl())
                    .isEqualTo("https://mail.example.com/.well-known/oauth-protected-resource/x/mcp");
        } finally {
            server.stop();
        }
    }
}
