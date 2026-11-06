package de.alexanderlindhorst.imapmcp.transport.http.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A scripted OpenID Provider on a loopback port: discovery, JWKS and a token endpoint that signs ID tokens. */
final class FakeIdp implements AutoCloseable {

    static final String CLIENT_ID = "imap-mcp-web";
    static final String CLIENT_SECRET = "web-client-secret-canary";

    /** One call of the token endpoint, as the client sent it. */
    record TokenRequest(String authorization, Map<String, String> form) {
    }

    private final HttpServer server;
    final RSAKey key;
    final String issuer;
    final List<TokenRequest> tokenRequests = new ArrayList<>();
    /** Claims customizer for the next ID token; the default is a valid token for subject "alice". */
    volatile Consumer<JWTClaimsSet.Builder> claims = b -> { };
    /** The nonce the token must carry; tests set it from the authorization redirect. */
    volatile String nonce;
    volatile int tokenStatus = 200;
    volatile boolean omitIdToken;
    volatile RSAKey signingKey;

    FakeIdp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("idp-1").generate();
        signingKey = key;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test";
        server.createContext("/realms/test/.well-known/openid-configuration", ex -> json(ex, 200,
                "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer
                        + "/protocol/openid-connect/auth\",\"token_endpoint\":\"" + issuer
                        + "/protocol/openid-connect/token\",\"jwks_uri\":\"" + issuer + "/certs\"}"));
        server.createContext("/realms/test/certs", ex -> json(ex, 200, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext("/realms/test/protocol/openid-connect/token", this::token);
        server.start();
    }

    String authorizationEndpoint() {
        return issuer + "/protocol/openid-connect/auth";
    }

    private void token(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        synchronized (tokenRequests) {
            tokenRequests.add(new TokenRequest(ex.getRequestHeaders().getFirst("Authorization"), form));
        }
        if (tokenStatus != 200) {
            json(ex, tokenStatus, "{\"error\":\"invalid_grant\",\"error_description\":\"idp-detail-canary\"}");
            return;
        }
        String idToken = omitIdToken ? "" : idToken();
        json(ex, 200, "{\"access_token\":\"at-canary\",\"refresh_token\":\"rt-canary\",\"token_type\":\"Bearer\""
                + (omitIdToken ? "" : ",\"id_token\":\"" + idToken + "\"") + "}");
    }

    String idToken() {
        long now = System.currentTimeMillis();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(issuer).audience(CLIENT_ID).subject("alice")
                .issueTime(new Date(now)).expirationTime(new Date(now + 300_000)).claim("nonce", nonce)
                .claim("auth_time", now / 1000)
                .claim("realm_access", Map.of("roles", List.of("imap-user", "offline_access")));
        claims.accept(b);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID())
                    .type(JOSEObjectType.JWT).build(), b.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    TokenRequest lastTokenRequest() {
        synchronized (tokenRequests) {
            return tokenRequests.get(tokenRequests.size() - 1);
        }
    }

    int tokenRequestCount() {
        synchronized (tokenRequests) {
            return tokenRequests.size();
        }
    }

    static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
