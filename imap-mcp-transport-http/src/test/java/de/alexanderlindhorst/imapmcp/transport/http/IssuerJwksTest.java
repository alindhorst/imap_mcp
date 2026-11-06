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
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IssuerJwksTest {

    final TestTokens keys = new TestTokens();
    final AtomicInteger discoveryHits = new AtomicInteger();
    final AtomicInteger jwksHits = new AtomicInteger();
    final AtomicReference<String> jwks = new AtomicReference<>();
    final AtomicReference<String> discoveryIssuer = new AtomicReference<>();
    final AtomicReference<String> jwksUriOverride = new AtomicReference<>();
    volatile boolean down;
    HttpServer idp;
    String issuer;

    void startIdp() throws Exception {
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + idp.getAddress().getPort() + "/realms/test";
        discoveryIssuer.set(issuer);
        jwks.set(keys.publicJwks().toString());
        idp.createContext("/realms/test/.well-known/openid-configuration", ex -> {
            discoveryHits.incrementAndGet();
            if (down) {
                ex.sendResponseHeaders(503, -1);
                ex.close();
                return;
            }
            String uri = jwksUriOverride.get() != null ? jwksUriOverride.get() : issuer + "/protocol/certs";
            reply(ex, "{\"issuer\":\"" + discoveryIssuer.get() + "\",\"jwks_uri\":\"" + uri + "\"}");
        });
        idp.createContext("/realms/test/protocol/certs", ex -> {
            jwksHits.incrementAndGet();
            reply(ex, jwks.get());
        });
        idp.start();
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, String body) throws java.io.IOException {
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

    JwtValidator validator(IssuerJwks source) {
        return new JwtValidator(new OAuthResourceConfig(issuer, TestTokens.RESOURCE, "sub", List.of()), source);
    }

    String token(Consumer<JWTClaimsSet.Builder> c) {
        return keys.rs256(b -> {
            b.issuer(issuer);
            c.accept(b);
        });
    }

    @Test
    void keysComeFromTheDiscoveredJwksUriAndAreCached() throws Exception {
        startIdp();
        JwtValidator v = validator(IssuerJwks.create(issuer));
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
        assertThat(v.validate(token(b -> b.subject("user-2"))).subject()).isEqualTo("user-2");
        assertThat(discoveryHits.get()).isEqualTo(1);
        assertThat(jwksHits.get()).isEqualTo(1);
    }

    @Test
    void nothingIsFetchedBeforeTheFirstToken() throws Exception {
        startIdp();
        IssuerJwks.create(issuer);
        assertThat(discoveryHits.get()).isZero();
        assertThat(jwksHits.get()).isZero();
    }

    @Test
    void unknownKidRefreshesTheCacheOnRotation() throws Exception {
        startIdp();
        JwtValidator v = validator(IssuerJwks.create(issuer, Duration.ofMinutes(5), Duration.ofMillis(1)));
        v.validate(token(b -> { }));
        RSAKey fresh = new RSAKeyGenerator(2048).keyID("rsa-2").generate();
        jwks.set(new JWKSet(List.of(fresh.toPublicJWK())).toString());
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rsa-2").build(),
                TestTokens.claims(b -> b.issuer(issuer)).build());
        jwt.sign(new RSASSASigner(fresh));
        String t = jwt.serialize();
        assertThat(v.validate(t).subject()).isEqualTo("user-1");
        assertThat(jwksHits.get()).isEqualTo(2);
    }

    @Test
    void discoveryWithAnotherIssuerIsRefused() throws Exception {
        startIdp();
        discoveryIssuer.set("http://evil.example/realms/test");
        JwtValidator v = validator(IssuerJwks.create(issuer));
        assertThatThrownBy(() -> v.validate(token(b -> { }))).isInstanceOfSatisfying(InvalidTokenException.class,
                e -> assertThat(e.category()).isEqualTo(InvalidTokenException.KEYS_UNAVAILABLE));
        assertThat(jwksHits.get()).isZero();
    }

    @Test
    void jwksUriWithAnotherSchemeIsRefused() throws Exception {
        startIdp();
        jwksUriOverride.set("ftp://127.0.0.1/certs");
        JwtValidator v = validator(IssuerJwks.create(issuer));
        assertThatThrownBy(() -> v.validate(token(b -> { }))).isInstanceOfSatisfying(InvalidTokenException.class,
                e -> assertThat(e.category()).isEqualTo(InvalidTokenException.KEYS_UNAVAILABLE));
    }

    @Test
    void failedDiscoveryIsNotRememberedAndRecovers() throws Exception {
        startIdp();
        down = true;
        JwtValidator v = validator(IssuerJwks.create(issuer));
        assertThatThrownBy(() -> v.validate(token(b -> { }))).isInstanceOfSatisfying(InvalidTokenException.class,
                e -> assertThat(e.category()).isEqualTo(InvalidTokenException.KEYS_UNAVAILABLE));
        down = false;
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
    }

    @Test
    void unreachableIdpIsKeysUnavailableNotAServerError() throws Exception {
        startIdp();
        idp.stop(0);
        JwtValidator v = validator(IssuerJwks.create(issuer));
        assertThatThrownBy(() -> v.validate(token(b -> { }))).isInstanceOfSatisfying(InvalidTokenException.class,
                e -> assertThat(e.category()).isEqualTo(InvalidTokenException.KEYS_UNAVAILABLE));
    }
}
