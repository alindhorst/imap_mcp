package de.alexanderlindhorst.imapmcp.transport.http.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import de.alexanderlindhorst.imapmcp.transport.http.InvalidTokenException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class IdTokenValidatorTest {

    private static final String ISSUER = "https://idp.example/realms/test";
    private static final String CLIENT = "imap-mcp-web";
    private static final String NONCE = "nonce-123";

    private final RSAKey key = key("k1");
    private final RSAKey other = key("k1"); // same kid, other key
    private final IdTokenValidator validator =
            new IdTokenValidator(ISSUER, CLIENT, "sub", new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())));

    private static RSAKey key(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JWTClaimsSet.Builder claims(Consumer<JWTClaimsSet.Builder> c) {
        long now = System.currentTimeMillis();
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer(ISSUER).audience(CLIENT).subject("alice")
                .issueTime(new Date(now)).expirationTime(new Date(now + 300_000)).claim("nonce", NONCE)
                .claim("auth_time", now / 1000 - 5);
        c.accept(b);
        return b;
    }

    private String sign(RSAKey k, Consumer<JWTClaimsSet.Builder> c) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(k.getKeyID()).build(),
                claims(c).build());
        jwt.sign(new RSASSASigner(k));
        return jwt.serialize();
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> validator.validate(token, NONCE)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aValidTokenGivesIssuerSubjectAuthTimeAndClaims() throws Exception {
        IdToken t = validator.validate(sign(key, b -> b.claim("realm_access", Map.of("roles", List.of("r")))), NONCE);

        assertThat(t.issuer()).isEqualTo(ISSUER);
        assertThat(t.subject()).isEqualTo("alice");
        assertThat(t.authTime()).isNotNull();
        assertThat(t.claims()).containsKey("realm_access");
    }

    @Test
    void theConfiguredSubjectClaimIsUsed() throws Exception {
        IdTokenValidator oid = new IdTokenValidator(ISSUER, CLIENT, "oid",
                new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())));

        assertThat(oid.validate(sign(key, b -> b.claim("oid", "object-1")), NONCE).subject()).isEqualTo("object-1");
        assertThatThrownBy(() -> oid.validate(sign(key, b -> { }), NONCE)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void aWrongNonceOrNoNonceIsRejected() throws Exception {
        assertThatThrownBy(() -> validator.validate(sign(key, b -> b.claim("nonce", "other")), NONCE))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> validator.validate(sign(key, b -> b.claim("nonce", null)), NONCE))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void wrongAudienceIssuerOrExpiryIsRejected() throws Exception {
        assertRejected(sign(key, b -> b.audience("someone-else")));
        assertRejected(sign(key, b -> b.audience(List.of(CLIENT, "second"))));
        assertRejected(sign(key, b -> b.issuer("https://evil.example/realms/test")));
        assertRejected(sign(key, b -> b.expirationTime(new Date(System.currentTimeMillis() - 120_000))));
        assertRejected(sign(key, b -> b.expirationTime(null)));
    }

    @Test
    void aTokenWithoutIssuedAtIsRejected() throws Exception {
        assertRejected(sign(key, b -> b.issueTime(null)));
    }

    @Test
    void aMultiValuedAudienceNeedsAnAuthorizedPartyThatIsTheClient() throws Exception {
        assertThat(validator.validate(sign(key, b -> b.audience(List.of(CLIENT, "account")).claim("azp", CLIENT)),
                NONCE).subject()).isEqualTo("alice");
        assertRejected(sign(key, b -> b.audience(List.of(CLIENT, "account")).claim("azp", "account")));
    }

    @Test
    void aBadSignatureNoneAndHmacAreRejected() throws Exception {
        assertRejected(sign(other, b -> { }));
        assertRejected(new PlainJWT(claims(b -> { }).build()).serialize());
        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(),
                claims(b -> { }).build());
        hs.sign(new MACSigner(new byte[32]));
        assertRejected(hs.serialize());
        assertRejected("not.a.jwt");
        assertRejected("");
        assertRejected(null);
    }

    @Test
    void failuresCarryOnlyAFixedCategory() throws Exception {
        String token = sign(other, b -> b.subject("secret-subject"));

        assertThatThrownBy(() -> validator.validate(token, NONCE)).isInstanceOf(InvalidTokenException.class)
                .hasMessageNotContaining("secret-subject").hasNoCause();
    }
}
