package de.alexanderlindhorst.imapmcp.transport.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtValidatorTest {

    final TestTokens keys = new TestTokens();
    final JwtValidator validator = new JwtValidator(TestTokens.config(), new ImmutableJWKSet<SecurityContext>(keys.publicJwks()));

    void rejects(String token) {
        assertThatThrownBy(() -> validator.validate(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void validRs256TokenYieldsPrincipal() throws Exception {
        JwtPrincipal p = validator.validate(keys.rs256(b -> b.claim("scope", "mcp:read")));
        assertThat(p.issuer()).isEqualTo(TestTokens.ISSUER);
        assertThat(p.subject()).isEqualTo("user-1");
        assertThat(p.claims()).containsEntry("scope", "mcp:read");
    }

    @Test
    void es256AndPs256AreAccepted() throws Exception {
        assertThat(validator.validate(keys.es256(b -> { })).subject()).isEqualTo("user-1");
        assertThat(validator.validate(keys.with(JWSAlgorithm.PS256, keys.rsa, b -> { })).subject()).isEqualTo("user-1");
    }

    @Test
    void algorithmOutsideTheAllowlistIsRejected() {
        rejects(keys.with(JWSAlgorithm.RS384, keys.rsa, b -> { }));
        rejects(keys.with(JWSAlgorithm.RS512, keys.rsa, b -> { }));
    }

    @Test
    void algNoneIsRejected() {
        rejects(TestTokens.none(b -> { }));
    }

    @Test
    void hmacIsRejectedEvenWithTheRsaPublicKeyAsSecret() {
        rejects(keys.hs256(keys.rsa.toPublicJWK().toJSONString().getBytes(), b -> { }));
        rejects(keys.hs256("0123456789abcdef0123456789abcdef".getBytes(), b -> { }));
    }

    @Test
    void signatureByAnotherKeyWithTheSameKidIsRejected() {
        rejects(keys.with(JWSAlgorithm.RS256, keys.otherRsa, b -> { }));
    }

    @Test
    void tamperedPayloadIsRejected() {
        String t = keys.rs256(b -> { });
        String[] parts = t.split("\\.");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"https://idp.example.com/realms/test\",\"aud\":\"https://mail.example.com/mcp\",\"sub\":\"admin\",\"exp\":9999999999}"
                        .getBytes());
        rejects(parts[0] + "." + forged + "." + parts[2]);
    }

    @Test
    void issuerMustMatchExactly() {
        rejects(keys.rs256(b -> b.issuer("https://idp.example.com/realms/other")));
        rejects(keys.rs256(b -> b.issuer(TestTokens.ISSUER + "/")));
        rejects(keys.rs256(b -> b.issuer(null)));
    }

    @Test
    void audienceMustContainTheResource() throws Exception {
        rejects(keys.rs256(b -> b.audience("https://other.example.com/mcp")));
        rejects(keys.rs256(b -> b.audience((String) null)));
        rejects(keys.rs256(b -> b.audience("https://mail.example.com/mcp/")));
        assertThat(validator.validate(keys.rs256(b -> b.audience(List.of("account", TestTokens.RESOURCE)))).subject())
                .isEqualTo("user-1");
    }

    @Test
    void expiredBeyondTheSkewIsRejectedWithinTheSkewAccepted() throws Exception {
        rejects(keys.rs256(b -> b.expirationTime(TestTokens.in(-120))));
        assertThat(validator.validate(keys.rs256(b -> b.expirationTime(TestTokens.in(-30)))).subject())
                .isEqualTo("user-1");
    }

    @Test
    void notBeforeInTheFutureBeyondTheSkewIsRejected() throws Exception {
        rejects(keys.rs256(b -> b.notBeforeTime(TestTokens.in(120))));
        assertThat(validator.validate(keys.rs256(b -> b.notBeforeTime(TestTokens.in(30)))).subject())
                .isEqualTo("user-1");
    }

    @Test
    void expirationIsRequired() {
        rejects(keys.rs256(b -> b.expirationTime((Date) null)));
    }

    @Test
    void subjectClaimMustBePresentAndNonBlank() {
        rejects(keys.rs256(b -> b.subject(null)));
        rejects(keys.rs256(b -> b.subject("")));
    }

    @Test
    void configurableSubjectClaimIsUsed() throws Exception {
        JwtValidator oid = new JwtValidator(new OAuthResourceConfig(TestTokens.ISSUER, TestTokens.RESOURCE, "oid",
                List.of()), new ImmutableJWKSet<SecurityContext>(keys.publicJwks()));
        assertThat(oid.validate(keys.rs256(b -> b.claim("oid", "obj-7"))).subject()).isEqualTo("obj-7");
        assertThatThrownBy(() -> oid.validate(keys.rs256(b -> { }))).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void malformedAndOversizedInputIsRejected() {
        rejects("");
        rejects("not-a-jwt");
        rejects("a.b.c");
        rejects(keys.rs256(b -> b.claim("pad", "x".repeat(20_000))));
    }

    @Test
    void exceptionNeverCarriesTokenContent() {
        String token = keys.rs256(b -> b.issuer("https://leak-marker.example"));
        assertThatThrownBy(() -> validator.validate(token)).isInstanceOf(InvalidTokenException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("leak-marker").doesNotContain(token);
                    assertThat(e.getCause()).isNull();
                });
    }
}
