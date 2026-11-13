package de.alexanderlindhorst.imapmcp.it;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Signs test JWTs (RS256) for the Dovecot oauth2 fixture ({@link DovecotOauth2Container}). The key pair is generated
 * at runtime; only its public half goes into the container. Test code only.
 */
public final class Xoauth2Jwt {

    public static final String ISSUER = "test-issuer";

    private final KeyPair keys;

    public Xoauth2Jwt() {
        this.keys = newKeyPair();
    }

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The public key as PEM (X.509 SubjectPublicKeyInfo), the form Dovecot reads. */
    public String publicKeyPem() {
        return pem(keys);
    }

    private static String pem(KeyPair kp) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    /** A token for {@code sub} signed with the fixture key, valid for {@code ttl} (negative: already expired). */
    public String token(String sub, Duration ttl) {
        return sign(keys, sub, ttl);
    }

    /** A token signed with a freshly generated key the fixture does not know. */
    public String tokenWithWrongKey(String sub, Duration ttl) {
        return sign(newKeyPair(), sub, ttl);
    }

    private static String sign(KeyPair kp, String sub, Duration ttl) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(sub)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(ttl)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) kp.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
