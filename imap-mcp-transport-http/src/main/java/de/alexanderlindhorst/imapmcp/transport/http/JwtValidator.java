package de.alexanderlindhorst.imapmcp.transport.http;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;

import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.text.ParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Access token validation of the {@code http-public} resource server (SPEC 5.3), on Nimbus JOSE+JWT: signature
 * with an allowlisted asymmetric algorithm only ({@code none}, HMAC and the other RSA/EC variants are
 * rejected, as is JWE), {@code iss} equal to the configured issuer, {@code aud} containing the configured
 * resource, {@code exp} required, {@code exp}/{@code nbf} with 60 s skew, and the subject claim present.
 * A failure carries a fixed category only, never token content or the library's message.
 */
public final class JwtValidator {

    public static final Set<JWSAlgorithm> ALLOWED_ALGORITHMS =
            Set.of(JWSAlgorithm.RS256, JWSAlgorithm.PS256, JWSAlgorithm.ES256);
    /** Jetty's default request header limit (8 KiB for the whole header block) bites first on the wire. */
    static final int MAX_TOKEN_CHARS = 8192;
    static final long SKEW_SECONDS = 60;

    private final OAuthResourceConfig config;
    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public JwtValidator(OAuthResourceConfig config, JWKSource<SecurityContext> keys) {
        this.config = config;
        processor.setJWSTypeVerifier(
                new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALLOWED_ALGORITHMS, keys));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(config.resource(),
                new JWTClaimsSet.Builder().issuer(config.issuer()).build(), Set.of("exp", "aud", "iss"));
        claims.setMaxClockSkew((int) SKEW_SECONDS);
        processor.setJWTClaimsSetVerifier(claims);
    }

    public JwtPrincipal validate(String token) throws InvalidTokenException {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_CHARS) {
            throw new InvalidTokenException(InvalidTokenException.MALFORMED);
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(token, null);
        } catch (ParseException e) {
            throw new InvalidTokenException(InvalidTokenException.MALFORMED);
        } catch (BadJOSEException e) {
            throw new InvalidTokenException(InvalidTokenException.REJECTED);
        } catch (RateLimitReachedException e) {
            // too many unknown-kid refreshes: a flood of tokens with foreign kids, not an outage
            throw new InvalidTokenException(InvalidTokenException.REJECTED);
        } catch (KeySourceException e) {
            throw new InvalidTokenException(InvalidTokenException.KEYS_UNAVAILABLE);
        } catch (JOSEException | RuntimeException e) {
            throw new InvalidTokenException(InvalidTokenException.REJECTED);
        }
        Object subject = claims.getClaim(config.subjectClaim());
        if (!(subject instanceof String s) || s.isBlank()) {
            throw new InvalidTokenException(InvalidTokenException.REJECTED);
        }
        return new JwtPrincipal(claims.getIssuer(), s,
                Collections.unmodifiableMap(new LinkedHashMap<>(claims.getClaims())));
    }
}
