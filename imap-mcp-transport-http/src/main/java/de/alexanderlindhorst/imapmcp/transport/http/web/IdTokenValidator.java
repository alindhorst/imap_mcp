package de.alexanderlindhorst.imapmcp.transport.http.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import de.alexanderlindhorst.imapmcp.transport.http.InvalidTokenException;
import de.alexanderlindhorst.imapmcp.transport.http.JwtValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * ID token validation of the linking surface (OIDC Core 3.1.3.7), on Nimbus like the access tokens: allowlisted
 * asymmetric algorithms (the same set as {@link JwtValidator}), {@code iss} equal to the issuer, {@code aud}
 * containing the web client id (and {@code azp} equal to it when there are several audiences), {@code exp},
 * {@code nonce} equal to the one of the login, and the subject claim present, with 60 s skew. A failure carries a
 * fixed category only, never token content.
 */
public final class IdTokenValidator {

    static final int MAX_TOKEN_CHARS = 32 * 1024;
    private static final long SKEW_SECONDS = 60;

    private final String issuer;
    private final String clientId;
    private final String subjectClaim;
    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public IdTokenValidator(String issuer, String clientId, String subjectClaim, JWKSource<SecurityContext> keys) {
        this.issuer = issuer;
        this.clientId = clientId;
        this.subjectClaim = subjectClaim;
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, null));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JwtValidator.ALLOWED_ALGORITHMS, keys));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(clientId,
                new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("exp", "iat", "aud", "iss", "nonce"));
        claims.setMaxClockSkew((int) SKEW_SECONDS);
        processor.setJWTClaimsSetVerifier(claims);
    }

    public IdToken validate(String token, String expectedNonce) throws InvalidTokenException {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_CHARS || expectedNonce == null) {
            throw new InvalidTokenException(InvalidTokenException.MALFORMED);
        }
        JWTClaimsSet claims;
        try {
            claims = processor.process(token, null);
        } catch (ParseException e) {
            throw new InvalidTokenException(InvalidTokenException.MALFORMED);
        } catch (BadJOSEException e) {
            throw new InvalidTokenException(InvalidTokenException.REJECTED);
        } catch (KeySourceException e) { // KeysUnavailableException (D16): the key decision cannot be made, not the token's fault
            throw new InvalidTokenException(InvalidTokenException.KEYS_UNAVAILABLE);
        } catch (JOSEException | RuntimeException e) {
            throw new InvalidTokenException(InvalidTokenException.REJECTED);
        }
        Object nonce = claims.getClaim("nonce");
        if (!(nonce instanceof String n) || !MessageDigest.isEqual(n.getBytes(StandardCharsets.UTF_8),
                expectedNonce.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidTokenException(InvalidTokenException.REJECTED);
        }
        List<String> audience = claims.getAudience();
        if (audience.size() > 1 && !clientId.equals(claims.getClaim("azp"))) {
            throw new InvalidTokenException(InvalidTokenException.REJECTED);
        }
        Object subject = claims.getClaim(subjectClaim);
        if (!(subject instanceof String s) || s.isBlank()) {
            throw new InvalidTokenException(InvalidTokenException.REJECTED);
        }
        Instant authTime = claims.getClaim("auth_time") instanceof Number num ? Instant.ofEpochSecond(num.longValue())
                : null;
        return new IdToken(claims.getIssuer(), s, authTime,
                Collections.unmodifiableMap(new LinkedHashMap<>(claims.getClaims())));
    }
}
