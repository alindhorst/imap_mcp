package de.alexanderlindhorst.imapmcp.transport.http;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/** Locally signed test tokens; no identity provider involved. */
final class TestTokens {

    static final String ISSUER = "https://idp.example.com/realms/test";
    static final String RESOURCE = "https://mail.example.com/mcp";

    final RSAKey rsa;
    final RSAKey otherRsa;
    final ECKey ec;

    TestTokens() {
        try {
            rsa = new RSAKeyGenerator(2048).keyID("rsa-1").generate();
            otherRsa = new RSAKeyGenerator(2048).keyID("rsa-1").generate();
            ec = new ECKeyGenerator(Curve.P_256).keyID("ec-1").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Public parts only, as an IdP publishes them. */
    JWKSet publicJwks() {
        return new JWKSet(List.<JWK>of(rsa.toPublicJWK(), ec.toPublicJWK()));
    }

    static OAuthResourceConfig config() {
        return new OAuthResourceConfig(ISSUER, RESOURCE, "sub", List.of("mcp:read", "mcp:write"));
    }

    /** Valid claims; the customizer may overwrite or remove anything. */
    static JWTClaimsSet.Builder claims(Consumer<JWTClaimsSet.Builder> customizer) {
        long now = System.currentTimeMillis();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(ISSUER).audience(RESOURCE).subject("user-1")
                .issueTime(new Date(now)).expirationTime(new Date(now + 300_000));
        customizer.accept(b);
        return b;
    }

    static Date in(long seconds) {
        return new Date(System.currentTimeMillis() + seconds * 1000);
    }

    String rs256(Consumer<JWTClaimsSet.Builder> c) {
        return sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID()).type(JOSEObjectType.JWT).build(),
                claims(c), rsaSigner(rsa));
    }

    String with(JWSAlgorithm alg, RSAKey key, Consumer<JWTClaimsSet.Builder> c) {
        return sign(new JWSHeader.Builder(alg).keyID(key.getKeyID()).build(), claims(c), rsaSigner(key));
    }

    /** RS256 token with an explicit {@code typ} header (null: no typ). */
    String rs256WithType(String typ) {
        return sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsa.getKeyID())
                .type(typ == null ? null : new JOSEObjectType(typ)).build(), claims(b -> { }), rsaSigner(rsa));
    }

    /** A JWE (RSA-OAEP-256 / A128GCM) to the server's public key wrapping a signed JWT, as nested JWTs are built. */
    String jwe(Consumer<JWTClaimsSet.Builder> c) {
        try {
            SignedJWT inner = SignedJWT.parse(rs256(c));
            com.nimbusds.jose.JWEObject jwe = new com.nimbusds.jose.JWEObject(
                    new com.nimbusds.jose.JWEHeader.Builder(com.nimbusds.jose.JWEAlgorithm.RSA_OAEP_256,
                            com.nimbusds.jose.EncryptionMethod.A128GCM).contentType("JWT").build(),
                    new com.nimbusds.jose.Payload(inner));
            jwe.encrypt(new com.nimbusds.jose.crypto.RSAEncrypter(rsa.toRSAPublicKey()));
            return jwe.serialize();
        } catch (JOSEException | java.text.ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    String es256(Consumer<JWTClaimsSet.Builder> c) {
        try {
            return sign(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(ec.getKeyID()).build(), claims(c),
                    new ECDSASigner(ec));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HS256 with an arbitrary secret, kid pointing at the RSA key (the classic algorithm-confusion probe). */
    String hs256(byte[] secret, Consumer<JWTClaimsSet.Builder> c) {
        try {
            return sign(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(rsa.getKeyID()).build(), claims(c),
                    new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static String none(Consumer<JWTClaimsSet.Builder> c) {
        return new PlainJWT(claims(c).build()).serialize();
    }

    private static RSASSASigner rsaSigner(RSAKey key) {
        try {
            return new RSASSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(JWSHeader header, JWTClaimsSet.Builder claims, com.nimbusds.jose.JWSSigner signer) {
        try {
            SignedJWT jwt = new SignedJWT(header, claims.build());
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
