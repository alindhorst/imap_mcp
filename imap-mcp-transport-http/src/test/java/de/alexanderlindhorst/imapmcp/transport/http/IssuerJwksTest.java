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
    volatile boolean jwksDown;
    volatile boolean jwksStall;
    volatile boolean discoveryStall;
    volatile boolean hugeJwks;
    volatile int jwksStatus = 200;
    final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    volatile long jwksDelayMs;
    final java.util.concurrent.CopyOnWriteArrayList<ch.qos.logback.classic.spi.ILoggingEvent> logs =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.concurrent.atomic.AtomicInteger stale = new AtomicInteger();
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
            if (discoveryStall) {
                stall(ex);
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
            if (jwksDelayMs > 0) {
                try {
                    Thread.sleep(jwksDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (jwksDown) {
                ex.sendResponseHeaders(503, -1);
                ex.close();
                return;
            }
            if (jwksStall) {
                stall(ex);
                return;
            }
            if (hugeJwks) {
                ex.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[64 * 1024];
                java.util.Arrays.fill(chunk, (byte) ' ');
                try {
                    for (int i = 0; i < 8; i++) {
                        ex.getResponseBody().write(chunk);
                    }
                    ex.getResponseBody().write(jwks.get().getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the client gave up reading, as intended
                }
                ex.close();
                return;
            }
            if (jwksStatus != 200) {
                byte[] b = jwks.get().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(jwksStatus, b.length);
                ex.getResponseBody().write(b);
                ex.close();
                return;
            }
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
        idp.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); // a stalled handler must not block others
        idp.start();
    }

    /** Sends the headers and a first byte, then stalls until the test ends. */
    void stall(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(200, 0);
        ex.getResponseBody().write(' ');
        ex.getResponseBody().flush();
        try {
            release.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ex.close();
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

    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;

    @org.junit.jupiter.api.BeforeEach
    void captureLogs() {
        appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(IssuerJwks.class)).addAppender(appender);
    }

    @AfterEach
    void stop() {
        release.countDown();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(IssuerJwks.class)).detachAppender(appender);
        if (idp != null) {
            idp.stop(0);
        }
    }

    IssuerJwks source() {
        return IssuerJwks.createForTest(issuer, Duration.ofMinutes(5), Duration.ofSeconds(30), null, true, clock::get,
                new de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder() {
                    @Override
                    public void oauthKeysStale() {
                        stale.incrementAndGet();
                    }
                });
    }

    /** A token signed with the test RSA key but naming another {@code kid}. */
    String tokenWithKid(String kid) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(),
                TestTokens.claims(b -> b.issuer(issuer)).build());
        jwt.sign(new RSASSASigner(keys.rsa));
        return jwt.serialize();
    }

    void awaitJwksHits(int n) throws Exception {
        for (int i = 0; i < 100 && jwksHits.get() < n; i++) {
            Thread.sleep(50);
        }
        assertThat(jwksHits.get()).isEqualTo(n);
        Thread.sleep(100); // let the refresh publish its result
    }

    void assertUnavailable(JwtValidator v, String token, int retryAfter) {
        assertThatThrownBy(() -> v.validate(token)).isInstanceOfSatisfying(InvalidTokenException.class, e -> {
            assertThat(e.category()).isEqualTo(InvalidTokenException.KEYS_UNAVAILABLE);
            assertThat(e.retryAfterSeconds()).isEqualTo(retryAfter);
        });
    }

    void assertRejected(JwtValidator v, String token) {
        assertThatThrownBy(() -> v.validate(token)).isInstanceOfSatisfying(InvalidTokenException.class,
                e -> assertThat(e.category()).isEqualTo(InvalidTokenException.REJECTED));
    }

    long count(ch.qos.logback.classic.Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).count();
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
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(31_000);
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
                    // a request that arrived after the refresh had started may not join it (SPEC: 503)
                    assertThat(e.category()).isEqualTo(InvalidTokenException.KEYS_UNAVAILABLE);
                }
            }));
        }
        for (Thread th : threads) {
            th.join();
        }
        assertThat(ok.get()).isGreaterThanOrEqualTo(1);
        assertThat(discoveryHits.get()).isEqualTo(1);
        assertThat(jwksHits.get()).isEqualTo(1);
    }

    @Test
    void unreachableIdpIsKeysUnavailableNotAServerError() throws Exception {
        startIdp();
        idp.stop(0);
        assertKeysUnavailable(validator(source()));
    }

    // ---- D16 key state machine ----

    @Test
    void aKnownKidNeverWaitsForADueRefresh() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(5 * 60_000 + 1);
        jwksDelayMs = 1500;
        long t0 = System.nanoTime();
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1000));
        awaitJwksHits(2);
    }

    @Test
    void noRefreshIsDueBeforeFiveMinutes() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(5 * 60_000 - 1000);
        v.validate(token(b -> { }));
        Thread.sleep(200);
        assertThat(jwksHits.get()).isEqualTo(1);
    }

    @Test
    void afterAFailedRefreshTheLastGoodSetStaysUsableFor60MinutesThenKeysAreUnavailable() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        jwksDown = true;
        clock.addAndGet(5 * 60_000 + 1);
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1"); // triggers the failing refresh
        awaitJwksHits(2);
        assertThat(count(ch.qos.logback.classic.Level.WARN)).isEqualTo(1);
        clock.addAndGet(54 * 60_000); // 59 min after the fetch
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
        awaitJwksHits(3); // the refresh this request started has failed again
        assertThat(stale.get()).isEqualTo(1);
        clock.addAndGet(2 * 60_000); // 61 min
        assertUnavailable(v, token(b -> { }), 30);
        assertThat(count(ch.qos.logback.classic.Level.ERROR)).isEqualTo(1);
        assertUnavailable(v, token(b -> { }), 30);
        assertThat(count(ch.qos.logback.classic.Level.ERROR)).isEqualTo(1); // once
    }

    @Test
    void recoveryAfterAnOutageIsLoggedAtInfoAndTheSetIsReplaced() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        jwksDown = true;
        clock.addAndGet(5 * 60_000 + 1);
        v.validate(token(b -> { }));
        awaitJwksHits(2);
        jwksDown = false;
        clock.addAndGet(31_000);
        v.validate(token(b -> { }));
        awaitJwksHits(3);
        long infos = appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                && e.getFormattedMessage().contains("recovered")).count();
        assertThat(infos).isEqualTo(1);
        int staleBefore = stale.get();
        v.validate(token(b -> { }));
        assertThat(stale.get()).isEqualTo(staleBefore); // no longer stale
    }

    @Test
    void refreshAttemptsStartAtMostEvery30SecondsAlsoInTheStaleWindow() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        jwksDown = true;
        clock.addAndGet(5 * 60_000 + 1);
        for (int i = 0; i < 5; i++) {
            v.validate(token(b -> { }));
            Thread.sleep(100);
        }
        assertThat(jwksHits.get()).isEqualTo(2);
        clock.addAndGet(30_000);
        v.validate(token(b -> { }));
        awaitJwksHits(3);
    }

    @Test
    void anUnknownKidAfterASuccessfulLaterRefreshIs401() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(31_000);
        assertRejected(v, tokenWithKid("nope"));
        assertThat(jwksHits.get()).isEqualTo(2);
    }

    @Test
    void anUnknownKidWhenNoRefreshMayStartYetIs503WithTheRestOfTheInterval() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(10_000);
        assertUnavailable(v, tokenWithKid("nope"), 20);
        clock.addAndGet(19_500);
        assertUnavailable(v, tokenWithKid("nope"), 1); // 0.5 s left: whole seconds, at least 1
        assertThat(jwksHits.get()).isEqualTo(1);
    }

    @Test
    void anUnknownKidWhoseRefreshFailedIs503EvenWithAStaleSet() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        jwksDown = true;
        clock.addAndGet(31_000);
        assertUnavailable(v, tokenWithKid("rotated-in-during-the-outage"), 30);
        assertThat(jwksHits.get()).isEqualTo(2);
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1"); // the known kid still works
    }

    @Test
    void aKnownKidWhoseKeyDoesNotFitTheTokenIs401WithoutARefresh() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(31_000);
        assertRejected(v, tokenWithKid("ec-1")); // an EC key carries that kid; the token says RS256
        assertThat(jwksHits.get()).isEqualTo(1);
    }

    @Test
    void aTokenWithoutKidUsesTheFittingKeysAndAnUnfittingSetCountsAsUnknownKid() throws Exception {
        startIdp();
        jwks.set(new JWKSet(List.of(keys.ec.toPublicJWK())).toString());
        JwtValidator v = validator(source());
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(),
                TestTokens.claims(b -> b.issuer(issuer)).build());
        jwt.sign(new RSASSASigner(keys.rsa));
        assertRejected(v, jwt.serialize()); // first use: refresh succeeded, no RS256 key: 401
        assertUnavailable(v, jwt.serialize(), 30); // now the interval holds the next refresh back
    }

    @Test
    void concurrentUnknownKidRequestsShareOneRefresh() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(31_000);
        jwksDelayMs = 400;
        String t = tokenWithKid("nope");
        List<Thread> threads = new ArrayList<>();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < 6; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    v.validate(t);
                } catch (InvalidTokenException e) {
                    if (InvalidTokenException.REJECTED.equals(e.category())) {
                        rejected.incrementAndGet();
                    }
                }
            }));
        }
        for (Thread th : threads) {
            th.join();
        }
        assertThat(jwksHits.get()).isEqualTo(2);
        assertThat(rejected.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void aRefreshThatStartedBeforeTheRequestArrivedIsNotJoined() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(5 * 60_000 + 1);
        jwksDelayMs = 1500;
        v.validate(token(b -> { })); // starts the due refresh in the background
        long t0 = System.nanoTime();
        assertUnavailable(v, tokenWithKid("nope"), 30); // arrived after it started: not joined, none may start
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1000));
    }

    @Test
    void noKeyMaterialOrTokenIsLogged() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        String token = token(b -> { });
        v.validate(token);
        jwksDown = true;
        clock.addAndGet(61 * 60_000);
        assertUnavailable(v, token, 30); // past the age ceiling: waits for the refresh, which fails
        Thread.sleep(300);
        for (var e : appender.list) {
            assertThat(e.getFormattedMessage()).doesNotContain(token).doesNotContain(keys.rsa.getKeyID())
                    .doesNotContain("\"n\"");
        }
    }

    @Test
    void aJwksResponseThatStallsAfterTheHeadersFailsTheFetchWithinTheDeadline() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(31_000);
        jwksStall = true;
        long t0 = System.nanoTime();
        assertUnavailable(v, tokenWithKid("nope"), 30);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(8));
        assertThat(count(ch.qos.logback.classic.Level.WARN)).isEqualTo(1); // the outage began: failed is set
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1"); // known kid: stale set, no wait
        // nothing is stuck in flight: the next refresh starts after 30 s and succeeds
        jwksStall = false;
        clock.addAndGet(30_000);
        assertRejected(v, tokenWithKid("nope"));
        assertThat(jwksHits.get()).isEqualTo(3);
    }

    @Test
    void aDiscoveryResponseThatStallsAfterTheHeadersFailsWithinTheDeadlineAndIsRetriedAfter10Seconds() throws Exception {
        startIdp();
        discoveryStall = true;
        JwtValidator v = validator(source());
        long t0 = System.nanoTime();
        assertKeysUnavailable(v);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(8));
        discoveryStall = false;
        clock.addAndGet(10_000);
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
    }

    @Test
    void aJwksStatusOtherThan200IsAFailedFetchEvenWithAValidBody() throws Exception {
        startIdp();
        jwksStatus = 203;
        assertKeysUnavailable(validator(source()));
    }

    @Test
    void aJwksBodyAbove256KiBIsAFailedFetch() throws Exception {
        startIdp();
        hugeJwks = true;
        assertKeysUnavailable(validator(source()));
    }

    @Test
    void theWarnWithoutAKeySetDoesNotPromiseAStaleWindow() throws Exception {
        startIdp();
        down = true;
        assertKeysUnavailable(validator(source()));
        assertThat(appender.list).filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).doesNotContain("stays usable"));
    }

    @Test
    void theRetryAfterIsClampedToTheIntervalEvenIfTheClockMovesBack() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(-5_000);
        assertUnavailable(v, tokenWithKid("nope"), 30);
        clock.set(Long.MIN_VALUE / 2);
        assertUnavailable(v, tokenWithKid("nope"), 30);
    }

    // ---- D16 v0.24: the age ceiling ----

    @Test
    void aSetPastTheCeilingIsNotUsedEvenWithoutAFailedRefreshAndConcurrentRequestsCauseOneFetch() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(61 * 60_000);
        jwksDelayMs = 400;
        List<Thread> threads = new ArrayList<>();
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger waited = new AtomicInteger();
        for (int i = 0; i < 6; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    long t0 = System.nanoTime();
                    v.validate(token(b -> { }));
                    if (Duration.ofNanos(System.nanoTime() - t0).toMillis() >= 300) {
                        waited.incrementAndGet(); // the old set was not used: every request waited for the fetch
                    }
                    ok.incrementAndGet();
                } catch (InvalidTokenException e) {
                    // counted below
                }
            }));
        }
        for (Thread th : threads) {
            th.join();
        }
        assertThat(ok.get()).isEqualTo(6);
        assertThat(waited.get()).isEqualTo(6);
        assertThat(jwksHits.get()).isEqualTo(2); // exactly one fetch for the six
        assertThat(count(ch.qos.logback.classic.Level.ERROR)).isZero();
    }

    @Test
    void afterTheCeilingRefreshTheDecisionIsMadeOnTheFreshSet() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(61 * 60_000);
        jwks.set(new JWKSet(List.of(keys.ec.toPublicJWK())).toString()); // rsa-1 is gone
        assertRejected(v, token(b -> { })); // 401, not 503
    }

    @Test
    void aFailedCeilingRefreshIs503DropsTheSetOnceWithTheErrorLineAndNoStaleWarn() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(61 * 60_000);
        jwksDown = true;
        assertUnavailable(v, token(b -> { }), 30);
        assertThat(count(ch.qos.logback.classic.Level.ERROR)).isEqualTo(1);
        assertThat(count(ch.qos.logback.classic.Level.WARN)).isZero();
        // within 30 s of the attempt: 503 with the rest of the interval, without waiting or fetching
        clock.addAndGet(10_000);
        long t0 = System.nanoTime();
        assertUnavailable(v, token(b -> { }), 20);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(500));
        assertThat(jwksHits.get()).isEqualTo(2);
        // after 30 s a request waits for a new refresh: 200 with the stub back, no further ERROR
        jwksDown = false;
        clock.addAndGet(20_000);
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
        assertThat(count(ch.qos.logback.classic.Level.ERROR)).isEqualTo(1);
    }

    @Test
    void aKnownKidJoinsARefreshInFlightThatStartedBeforeItButAnUnknownKidDoesNot() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        clock.addAndGet(61 * 60_000);
        jwksDelayMs = 1500;
        jwksDown = true; // the refresh in flight fails after its delay
        AtomicInteger first = new AtomicInteger();
        Thread a = Thread.ofVirtual().start(() -> {
            try {
                v.validate(token(b -> { }));
                first.incrementAndGet();
            } catch (InvalidTokenException e) {
                // asserted below
            }
        });
        for (int i = 0; i < 100 && jwksHits.get() < 2; i++) {
            Thread.sleep(20); // the refresh of A is in flight now
        }
        long t0 = System.nanoTime();
        assertUnavailable(v, tokenWithKid("nope"), 30); // unknown kid: does not join the older refresh, none may start
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(800));
        assertUnavailable(v, token(b -> { }), 30); // a known kid joins it, waits, and the failure is its answer too
        a.join();
        assertThat(first.get()).isZero();
        assertThat(jwksHits.get()).isEqualTo(2);
        assertThat(count(ch.qos.logback.classic.Level.ERROR)).isEqualTo(1);
    }

    @Test
    void aRequestThatReadTheOldSetBeforeAConcurrentRefreshPublishedANewOneGets200() throws Exception {
        startIdp();
        IssuerJwks src = source();
        JwtValidator v = validator(src);
        v.validate(token(b -> { }));
        clock.addAndGet(61 * 60_000);
        AtomicInteger calls = new AtomicInteger();
        src.afterStateReadForTest = () -> {
            if (calls.getAndIncrement() == 0) { // only the outer request: a second request refreshes meanwhile
                try {
                    assertThat(v.validate(token(b -> b.subject("other"))).subject()).isEqualTo("other");
                } catch (InvalidTokenException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
        assertThat(jwksHits.get()).isEqualTo(2); // one refresh served both
        assertThat(count(ch.qos.logback.classic.Level.ERROR)).isZero();
    }

    @Test
    void aWaitThatEndsEarlyIs503AndDoesNotDropTheSet() throws Exception {
        startIdp();
        IssuerJwks src = source();
        src.waitMsForTest = 300;
        JwtValidator v = validator(src);
        v.validate(token(b -> { }));
        clock.addAndGet(61 * 60_000);
        jwksDelayMs = 2000;
        assertUnavailable(v, token(b -> { }), 30);
        assertThat(count(ch.qos.logback.classic.Level.ERROR)).isZero(); // the refresh's own outcome decides
        awaitJwksHits(2);
        Thread.sleep(2300);
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1"); // the refresh succeeded on its own
        assertThat(count(ch.qos.logback.classic.Level.ERROR)).isZero();
    }

    @Test
    void atFirstUseDiscoveryAndKeysEach3SecondsLateStillGive200() throws Exception {
        startIdp();
        discoveryDelayMs = 3000;
        jwksDelayMs = 3000;
        JwtValidator v = validator(source());
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
    }

    @Test
    void afterAnEarlierFailedRefreshAKnownKidAtTheCeilingStillJoinsARefreshInFlight() throws Exception {
        startIdp();
        JwtValidator v = validator(source());
        v.validate(token(b -> { }));
        jwksDown = true;
        clock.addAndGet(5 * 60_000 + 1);
        v.validate(token(b -> { }));
        awaitJwksHits(2); // the refresh failed: the stale window runs
        clock.addAndGet(54 * 60_000);
        jwksDelayMs = 1500;
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1"); // below the ceiling; refresh in flight
        awaitJwksHits(3);
        clock.addAndGet(2 * 60_000); // now at the ceiling, the refresh started before this request
        jwksDown = false; // the refresh in flight will succeed
        assertThat(v.validate(token(b -> { })).subject()).isEqualTo("user-1");
    }
}
