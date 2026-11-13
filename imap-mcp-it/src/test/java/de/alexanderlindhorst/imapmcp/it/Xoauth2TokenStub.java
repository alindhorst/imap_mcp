package de.alexanderlindhorst.imapmcp.it;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * A JDK {@link HttpsServer} on a dynamic loopback port standing in for an upstream token endpoint (#498, SPEC 5.8),
 * with strict refresh-token rotation like Keycloak with "Revoke Refresh Token" and max reuse 0: each refresh token
 * works once and is replaced; presenting a retired or unknown token answers {@code invalid_grant} (and is counted).
 * Access tokens are Nimbus JWTs ({@link Xoauth2Jwt}) that the Dovecot fixture validates. Holds tokens in memory
 * only, logs nothing.
 */
final class Xoauth2TokenStub implements AutoCloseable {

    /** One rotation chain: the subject its access tokens are for and its currently valid refresh token. */
    private static final class Lineage {
        final String subject;
        String current;
        boolean revoked;

        Lineage(String subject, String current) {
            this.subject = subject;
            this.current = current;
        }
    }

    private final HttpsServer server;
    private final Xoauth2Jwt jwt;
    private final Object lock = new Object();
    private final Map<String, Lineage> live = new HashMap<>();
    private final Map<String, Lineage> retired = new HashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger exchanges = new AtomicInteger();
    private final AtomicInteger reuses = new AtomicInteger();
    private final List<String> presented = new CopyOnWriteArrayList<>();
    private final List<String> issuedRefresh = new CopyOnWriteArrayList<>();
    private final List<String> issuedAccess = new CopyOnWriteArrayList<>();
    private volatile BiFunction<String, Integer, String> accessTokens;
    private volatile Duration delay = Duration.ZERO;

    Xoauth2TokenStub(Xoauth2Pki pki, Xoauth2Jwt jwt) throws IOException {
        this.jwt = jwt;
        this.accessTokens = (sub, n) -> jwt.token(sub, Duration.ofMinutes(5));
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(pki.serverContext()));
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/token", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.incrementAndGet();
            Duration d = delay;
            if (!d.isZero()) {
                try {
                    Thread.sleep(d.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status;
            String json;
            synchronized (lock) {
                String refresh = form(body).get("refresh_token");
                presented.add(refresh);
                Lineage l = refresh == null ? null : live.get(refresh);
                if (l == null || l.revoked) {
                    if (refresh != null && retired.containsKey(refresh)) {
                        reuses.incrementAndGet();
                    }
                    status = 400;
                    json = "{\"error\":\"invalid_grant\"}";
                } else {
                    String next = "rt-" + UUID.randomUUID();
                    live.remove(refresh);
                    retired.put(refresh, l);
                    l.current = next;
                    live.put(next, l);
                    int n = exchanges.incrementAndGet();
                    String access = accessTokens.apply(l.subject, n);
                    issuedAccess.add(access);
                    issuedRefresh.add(next);
                    status = 200;
                    json = "{\"access_token\":\"" + access + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
                            + "\"refresh_token\":\"" + next + "\",\"scope\":\"mail\"}";
                }
            }
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
            ex.close();
        });
        server.start();
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    int port() {
        return server.getAddress().getPort();
    }

    /** Starts a rotation lineage: {@code refreshToken} is currently valid and its access tokens are for {@code subject}. */
    void register(String refreshToken, String subject) {
        synchronized (lock) {
            live.put(refreshToken, new Lineage(subject, refreshToken));
        }
    }

    /** The provider revokes the lineage of {@code refreshToken}: its next use answers {@code invalid_grant}. */
    void revoke(String refreshToken) {
        synchronized (lock) {
            Lineage l = live.get(refreshToken);
            if (l != null) {
                l.revoked = true;
            }
        }
    }

    /** Access tokens always claim {@code expires_in} 3600; this replaces how the JWT itself is made ({@code (subject, exchange number) -> access token}). */
    void accessTokens(BiFunction<String, Integer, String> maker) {
        this.accessTokens = maker;
    }

    /** Access tokens signed with the fixture key, valid for {@code ttl} (negative: already expired). */
    void accessTokenTtl(Duration ttl) {
        this.accessTokens = (sub, n) -> jwt.token(sub, ttl);
    }

    void delay(Duration d) {
        this.delay = d;
    }

    int requests() {
        return requests.get();
    }

    /** Successful refresh-token exchanges. */
    int exchanges() {
        return exchanges.get();
    }

    /** Presentations of an already retired refresh token. */
    int reuses() {
        return reuses.get();
    }

    List<String> presentedRefreshTokens() {
        return new ArrayList<>(presented);
    }

    List<String> issuedRefreshTokens() {
        return new ArrayList<>(issuedRefresh);
    }

    List<String> issuedAccessTokens() {
        return new ArrayList<>(issuedAccess);
    }

    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }
}
