package de.alexanderlindhorst.imapmcp.store.oauth;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javax.net.ssl.SSLContext;

/** A JDK {@link HttpsServer} on a dynamic loopback port standing in for an upstream token endpoint. */
final class StubTokenServer implements AutoCloseable {

    record Request(String method, String path, String contentType, String body) {
    }

    record Reply(int status, Map<String, String> headers, byte[] body, boolean chunked) {

        static Reply json(int status, String json) {
            return new Reply(status, Map.of("Content-Type", "application/json;charset=UTF-8"),
                    json.getBytes(StandardCharsets.UTF_8), false);
        }

        Reply with(String name, String value) {
            java.util.HashMap<String, String> h = new java.util.HashMap<>(headers);
            h.put(name, value);
            return new Reply(status, h, body, chunked);
        }
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final HttpsServer server;
    private volatile Function<Request, Reply> handler = r -> Reply.json(500, "{}");

    StubTokenServer(SSLContext ssl) throws IOException {
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            Request req = new Request(ex.getRequestMethod(), ex.getRequestURI().toString(),
                    ex.getRequestHeaders().getFirst("Content-Type"), new String(in, StandardCharsets.UTF_8));
            requests.add(req);
            Reply reply = handler.apply(req);
            reply.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(reply.status(), reply.chunked() ? 0 : reply.body().length == 0 ? -1
                    : reply.body().length);
            if (reply.body().length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(reply.body());
                } catch (IOException ignored) {
                    // the client gave up (slow or oversized case)
                }
            }
            ex.close();
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    void respond(Function<Request, Reply> next) {
        this.handler = next;
    }

    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }
}
