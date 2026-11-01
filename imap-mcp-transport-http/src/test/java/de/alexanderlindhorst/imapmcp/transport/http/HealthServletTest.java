package de.alexanderlindhorst.imapmcp.transport.http;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HealthServletTest {

    final HttpClient client = HttpClient.newHttpClient();
    HttpLocalServer server;

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    void start() throws Exception {
        // Filters that would reject everything on /mcp prove that /health is exempt.
        jakarta.servlet.Filter reject = (req, res, chain) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401);
        server = HttpLocalServer.builder().port(0).hostFilter(reject).originFilter(reject).bearerFilter(reject)
                .healthServlet(new HealthServlet()).build();
        server.start();
    }

    HttpResponse<String> call(String method, String unused) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/health"))
                .method(method, method.equals("POST") ? BodyPublishers.ofString("{}") : BodyPublishers.noBody());
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void getWithoutTokenAndArbitraryHostIsOk() throws Exception {
        start();
        HttpResponse<String> r = call("GET", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
    }

    @Test
    void arbitraryHostHeaderIsOk() throws Exception {
        start();
        try (java.net.Socket sock = new java.net.Socket("127.0.0.1", server.port())) {
            sock.getOutputStream().write(("GET /health HTTP/1.1\r\nHost: evil.example:1234\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            sock.getOutputStream().flush();
            String raw = new String(sock.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(raw).startsWith("HTTP/1.1 200").endsWith("{\"status\":\"ok\"}");
        }
    }

    @Test
    void shuttingDownIs503() throws Exception {
        start();
        server.beginShutdown();
        HttpResponse<String> r = call("GET", null);
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.body()).isEqualTo("{\"status\":\"shutting_down\"}");
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
    }

    @Test
    void otherMethodsAre405WithAllowGet() throws Exception {
        start();
        for (String m : new String[] {"POST", "PUT", "DELETE"}) {
            HttpResponse<String> r = call(m, null);
            assertThat(r.statusCode()).as(m).isEqualTo(405);
            assertThat(r.headers().firstValue("Allow")).as(m).hasValue("GET");
        }
    }

    @Test
    void healthDoesNotDependOnImap() throws Exception {
        // No IMAP exists in this module's runtime at all; liveness still answers.
        start();
        assertThat(call("GET", null).statusCode()).isEqualTo(200);
        noClasses().that().haveSimpleName("HealthServlet").should().dependOnClassesThat()
                .resideInAnyPackage("..core.imap..", "jakarta.mail..", "org.eclipse.angus..")
                .check(new ClassFileImporter().importPackages("de.alexanderlindhorst.imapmcp.transport.http"));
    }
}
