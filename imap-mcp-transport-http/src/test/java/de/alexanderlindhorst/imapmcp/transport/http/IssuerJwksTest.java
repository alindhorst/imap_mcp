package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IssuerJwksTest {

    final TestTokens keys = new TestTokens();
    final AtomicInteger discoveryHits = new AtomicInteger();
    final AtomicInteger jwksHits = new AtomicInteger();
    final AtomicInteger redirectTargetHits = new AtomicInteger();
    final AtomicReference<String> jwks = new AtomicReference<>();
    final AtomicReference<String> discoveryIssuer = new AtomicReference<>();
    /** Whole discovery document when set; otherwise issuer and jwks_uri are filled in. */
    final AtomicReference<String> discoveryBody = new AtomicReference<>();
    final AtomicReference<String> jwksUriOverride = new AtomicReference<>();
    final AtomicLong clock = new AtomicLong(1_000_000);
    volatile boolean down;
    volatile boolean redirectJwks;
    volatile boolean redirectDiscovery;
    volatile boolean hugeDiscovery;
    volatile long discoveryDelayMs;
    HttpServer idp;
    String issuer;

    void startIdp() throws Exception {
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + idp.getAddress().getPort() + "/realms/test";
        discoveryIssuer.set(issuer);
        jwks.set(keys.publicJwks().toString());
        idp.createContext("/realms/test/.well-known/openid-configuration", ex -> {
            discoveryHits.incrementAndGet();
            if (discoveryDelayMs > 0) {
                try {
                    Thread.sleep(discoveryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (down) {
                ex.sendResponseHeaders(503, -1);
                ex.close();
                return;
            }
            if (redirectDiscovery) {
                redirect(ex, "/realms/test/target");
                return;
            }
            if (hugeDiscovery) {
                ex.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[64 * 1024];
                java.util.Arrays.fill(chunk, (byte) ' ');
                try {
                    for (int i = 0; i < 160; i++) {
                        ex.getResponseBody().write(chunk);
                    }
                } catch (IOException ignored) {
                    // the client gave up reading, as intended
                }
                ex.close();
                return;
            }
            String uri = jwksUriOverride.get() != null ? jwksUriOverride.get() : issuer + "/protocol/certs";
            reply(ex, discoveryBody.get() != null ? discoveryBody.get()
                    : "{\"issuer\":\"" + discoveryIssuer.get() + "\",\"jwks_uri\":\"" + uri + "\"}");
        });
        idp.createContext("/realms/test/protocol/certs", ex -> {
            jwksHits.incrementAndGet();
            if (redirectJwks) {
                redirect(ex, "/realms/test/target");
                return;
            }
            reply(ex, jwks.get());
        });
        idp.createContext("/realms/test/target", ex -> {
            redirectTargetHits.incrementAndGet();
            reply(ex, jwks.get());
        });
        idp.start();
    }

    static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    static void reply(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @AfterEach
    void stop() {
        if (idp != null) {
            idp.stop(0);
        }
    }

    IssuerJwks source() {
        return IssuerJwks.createForTest(issuer, Duration.ofMinutes(5), Duration.ofSeconds(30), null, true, clock::get);
    }

    JwtValidator validator(IssuerJwks source) {
        return new JwtValidator(new OAuthResourceConfig(issuer, TestTokens.RESOURCE, "sub", List.of()), source);
    }

    String token(Consumer<JWTClaimsSet.Builder> c) {
        return keys.rs256(b -> {
            b.issuer(issuer);
            c.accept(b);
        });
    }

    void assertKeysUnavailable(JwtValidator v) {
        assertThatThrownBy(() -> v.validate(token(b -> { }))).isInstanceOfSatisfying(InvalidTokenException.class,
                e -> assertThat(e.category()).isEqualTo(InvalidTokenException.KEYS_UNAVAILABLE));
    }

    @Test
    void keysComeFromTheDiscoveredJwksUriAndAreCached() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
        assertThat(v.validate(token(b -> b.subject("user-2"))).subject()).isEqualTo("user-2");
        assertThat(discoveryHits.get()).isEqualTo(1);
        assertThat(jwksHits.get()).isEqualTo(1);
    }

    @Test
    void nothingIsFetchedBeforeTheFirstToken() throws Exception {
        startIdp();
        source();
        assertThat(discoveryHits.get()).isZero();
        assertThat(jwksHits.get()).isZero();
    }

    @Test
    void unknownKidRefreshesTheCacheOnRotation() throws Exception {
        startIdp();
        JwtValidator v = validator(IssuerJwks.createForTest(issuer, Duration.ofMinutes(5), Duration.ofMillis(1), null,
                true, clock::get));
        v.validate(token(b -> { }));
        RSAKey fresh = new RSAKeyGenerator(2048).keyID("rsa-2").generate();
        jwks.set(new JWKSet(List.of(fresh.toPublicJWK())).toString());
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rsa-2").build(),
                TestTokens.claims(b -> b.issuer(issuer)).build());
        jwt.sign(new RSASSASigner(fresh));
        assertThat(v.validate(jwt.serialize()).subject()).isEqualTo("user-1");
        assertThat(jwksHits.get()).isEqualTo(2);
    }

    @Test
    void discoveryWithAnotherIssuerIsRefused() throws Exception {
        startIdp();
        discoveryIssuer.set("http://evil.example/realms/test");
        assertKeysUnavailable(validator(source()));
        assertThat(jwksHits.get()).isZero();
    }

    @Test
    void jwksUriWithAnotherSchemeIsRefused() throws Exception {
        startIdp();
        jwksUriOverride.set("ftp://127.0.0.1/certs");
        assertKeysUnavailable(validator(source()));
    }

    @Test
    void jwksUriMissingOrNotAStringIsRefused() throws Exception {
        startIdp();
        discoveryBody.set("{\"issuer\":\"" + issuer + "\"}");
        assertKeysUnavailable(validator(source()));
        discoveryBody.set("{\"issuer\":\"" + issuer + "\",\"jwks_uri\":42}");
        assertKeysUnavailable(validator(source()));
        discoveryBody.set("{\"issuer\":\"" + issuer + "\",\"jwks_uri\":null}");
        assertKeysUnavailable(validator(source()));
        assertThat(jwksHits.get()).isZero();
    }

    @Test
    void httpJwksUriUnderAnHttpsIssuerIsRefused() throws Exception {
        startIdp();
        String httpsIssuer = "https://idp.example.com/realms/test";
        discoveryBody.set("{\"issuer\":\"" + httpsIssuer + "\",\"jwks_uri\":\"" + issuer + "/protocol/certs\"}");
        IssuerJwks s = IssuerJwks.createForTest(httpsIssuer, Duration.ofMinutes(5), Duration.ofSeconds(30),
                URI.create(issuer + "/.well-known/openid-configuration"), false, clock::get);
        JwtValidator v = new JwtValidator(
                new OAuthResourceConfig(httpsIssuer, TestTokens.RESOURCE, "sub", List.of()), s);
        assertThatThrownBy(() -> v.validate(keys.rs256(b -> { }))).isInstanceOfSatisfying(InvalidTokenException.class,
                e -> assertThat(e.category()).isEqualTo(InvalidTokenException.KEYS_UNAVAILABLE));
        assertThat(discoveryHits.get()).isEqualTo(1);
        assertThat(jwksHits.get()).isZero();
    }

    @Test
    void issuerMustBeHttpsWithHostAndNoUserInfoQueryOrFragment() {
        for (String bad : List.of("http://idp.example.com/realms/test", "idp.example.com/realms/test",
                "https:///realms/test", "https://user@idp.example.com/realms/test",
                "https://idp.example.com/realms/test?x=1", "https://idp.example.com/realms/test#f", " ")) {
            assertThatThrownBy(() -> IssuerJwks.create(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(IssuerJwks.create("https://idp.example.com/realms/test")).isNotNull();
    }

    @Test
    void jwksFetchDoesNotFollowRedirects() throws Exception {
        startIdp();
        redirectJwks = true;
        assertKeysUnavailable(validator(source()));
        assertThat(jwksHits.get()).isPositive();
        assertThat(redirectTargetHits.get()).isZero();
    }

    @Test
    void discoveryDoesNotFollowRedirects() throws Exception {
        startIdp();
        redirectDiscovery = true;
        assertKeysUnavailable(validator(source()));
        assertThat(redirectTargetHits.get()).isZero();
    }

    @Test
    void oversizedDiscoveryIsCutOffWhileReading() throws Exception {
        startIdp();
        hugeDiscovery = true;
        long t0 = System.nanoTime();
        assertKeysUnavailable(validator(source()));
        assertThat(jwksHits.get()).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void failedDiscoveryIsRetriedOnlyAfterTheNegativeCacheExpires() throws Exception {
        startIdp();
        down = true;
        JwtValidator v = validator(source());
        assertKeysUnavailable(v);
        down = false;
        assertKeysUnavailable(v);
        clock.addAndGet(4_000);
        assertKeysUnavailable(v);
        assertThat(discoveryHits.get()).isEqualTo(1);
        clock.addAndGet(6_000);
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
        assertThat(discoveryHits.get()).isEqualTo(2);
    }

    @Test
    void concurrentFirstTokensShareOneDiscovery() throws Exception {
        startIdp();
        discoveryDelayMs = 300;
        JwtValidator v = validator(source());
        String t = token(b -> { });
        List<Thread> threads = new ArrayList<>();
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    v.validate(t);
                    ok.incrementAndGet();
                } catch (InvalidTokenException e) {
                    // counted below
                }
            }));
        }
        for (Thread th : threads) {
            th.join();
        }
        assertThat(ok.get()).isEqualTo(8);
        assertThat(discoveryHits.get()).isEqualTo(1);
        assertThat(jwksHits.get()).isEqualTo(1);
    }

    @Test
    void unreachableIdpIsKeysUnavailableNotAServerError() throws Exception {
        startIdp();
        idp.stop(0);
        assertKeysUnavailable(validator(source()));
    }
}
