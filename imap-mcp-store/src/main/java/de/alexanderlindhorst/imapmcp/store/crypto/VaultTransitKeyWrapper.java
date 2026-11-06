package de.alexanderlindhorst.imapmcp.store.crypto;

import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vault Transit {@link KeyWrapper} (D6, SPEC 5.3 and 5.7). Wrap is {@code <mount>/encrypt/<keyName>}, unwrap is
 * {@code <mount>/decrypt/<kek_id of the row>}; {@code kek_id} is the key name and the wrapped DEK is Vault's
 * {@code vault:v<n>:...} ciphertext. Tenant and account are not sent: Transit's {@code context} exists only for
 * derived keys, and {@code associated_data} would make {@code rewrap} impossible (Vault's rewrap takes none, so it
 * answers {@code 400} for such a ciphertext). The binding to tenant and account is the AAD of the credential
 * ciphertext itself ({@link CredentialCipher}), which a copied row still fails.
 *
 * <p>Failure semantics: a {@code 400} on decrypt (and a malformed key name or ciphertext in the row) is an
 * integrity failure ({@link KeyWrapException}); every other failure (connect, TLS, timeout, any other non-2xx, a
 * malformed answer, {@code 401}/{@code 403} after one re-authentication) is {@link KeyServiceUnavailableException}.
 * There is never a fallback. Messages never contain tokens, ids, URLs or response text; redirects are not followed.
 */
public final class VaultTransitKeyWrapper implements KeyWrapper {

    private static final String PROVIDER = "vault";
    private static final Pattern KEK_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern CIPHERTEXT = Pattern.compile("vault:v[0-9]{1,9}:[A-Za-z0-9+/=]{1,16384}");
    private static final int MAX_RESPONSE = 1 << 20;
    /** Share of the lease after which the client token is replaced. */
    private static final double RENEW_AT = 0.8;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final VaultConfig config;
    private final Function<SecretRef, Secret> resolver;
    private final Clock clock;
    private final HttpClient http;
    private final URI base;

    private final Object tokenLock = new Object();
    private String token; // guarded by tokenLock
    private Instant tokenRenewAt; // guarded by tokenLock; null = no expiry

    public VaultTransitKeyWrapper(VaultConfig config, Function<SecretRef, Secret> resolver, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.clock = Objects.requireNonNull(clock, "clock");
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(config.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1);
        if (config.caCert() != null) {
            b.sslContext(sslContext(config.caCert()));
        }
        this.http = b.build();
        this.base = URI.create(config.address().toString().replaceAll("/+$", ""));
    }

    @Override
    public WrappedKey wrap(byte[] dek, String tenantId, String accountId) {
        String body = json(Map.of("plaintext", Base64.getEncoder().encodeToString(dek)));
        JsonNode data = post("/v1/" + config.transitMount() + "/encrypt/" + config.keyName(), body).path("data");
        String ciphertext = data.path("ciphertext").asString("");
        if (!CIPHERTEXT.matcher(ciphertext).matches()) {
            throw unavailable("bad_response");
        }
        return new WrappedKey(config.keyName(), ciphertext.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public byte[] unwrap(String kekId, byte[] wrapped, String tenantId, String accountId) {
        String ciphertext = new String(wrapped, StandardCharsets.ISO_8859_1);
        if (kekId == null || !KEK_ID.matcher(kekId).matches() || !CIPHERTEXT.matcher(ciphertext).matches()) {
            throw new KeyWrapException("key unwrap failed"); // a row that is no Vault wrap: integrity, no request
        }
        String body = json(Map.of("ciphertext", ciphertext));
        JsonNode data = post("/v1/" + config.transitMount() + "/decrypt/" + kekId, body, true).path("data");
        String plaintext = data.path("plaintext").asString("");
        try {
            byte[] dek = Base64.getDecoder().decode(plaintext);
            if (dek.length == 0) {
                throw unavailable("bad_response");
            }
            return dek;
        } catch (IllegalArgumentException e) {
            throw unavailable("bad_response");
        }
    }

    // ---- HTTP ----

    private JsonNode post(String path, String body) {
        return post(path, body, false);
    }

    /** POST with the client token; one re-authentication on 401/403. {@code badRequestIsIntegrity}: decrypt only. */
    private JsonNode post(String path, String body, boolean badRequestIsIntegrity) {
        String used = currentToken();
        Response r = send(path, body, used);
        if (r.status == 401 || r.status == 403) {
            used = refreshedToken(used);
            r = send(path, body, used);
        }
        if (r.status == 400 && badRequestIsIntegrity) {
            throw new KeyWrapException("key unwrap failed");
        }
        if (r.status < 200 || r.status > 299) {
            throw unavailable("http_" + r.status);
        }
        return r.json();
    }

    private record Response(int status, byte[] body) {
        JsonNode json() {
            try {
                JsonNode n = JSON.readTree(body);
                if (n == null || !n.isObject()) {
                    throw new KeyServiceUnavailableException(PROVIDER, "bad_response");
                }
                return n;
            } catch (RuntimeException e) {
                throw new KeyServiceUnavailableException(PROVIDER, "bad_response");
            }
        }
    }

    private Response send(String path, String body, String clientToken) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(base.resolve(path)).timeout(config.requestTimeout())
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (clientToken != null) {
            rb.header("X-Vault-Token", clientToken);
        }
        try {
            HttpResponse<InputStream> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                byte[] bytes = in.readNBytes(MAX_RESPONSE + 1);
                if (bytes.length > MAX_RESPONSE) {
                    throw unavailable("bad_response");
                }
                return new Response(resp.statusCode(), bytes);
            }
        } catch (HttpTimeoutException e) {
            throw unavailable("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("interrupted");
        } catch (IOException e) {
            throw unavailable(isTls(e) ? "tls" : "unreachable");
        }
    }

    private static boolean isTls(Throwable t) {
        for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
            if (t instanceof SSLException) {
                return true;
            }
        }
        return false;
    }

    // ---- authentication ----

    private String currentToken() {
        synchronized (tokenLock) {
            if (token == null || (tokenRenewAt != null && !clock.instant().isBefore(tokenRenewAt))) {
                authenticate();
            }
            return token;
        }
    }

    /** After a 401/403: a token another thread already replaced is used as is; otherwise authenticate again. */
    private String refreshedToken(String refused) {
        synchronized (tokenLock) {
            if (token == null || token.equals(refused)) {
                authenticate();
            }
            return token;
        }
    }

    private void authenticate() { // tokenLock held
        try {
            switch (config.auth()) {
                case VaultAuth.Token t -> {
                    token = readSecret(t.token());
                    tokenRenewAt = null;
                }
                case VaultAuth.AppRole a -> login(a);
            }
        } catch (KeyServiceUnavailableException e) {
            token = null;
            throw e;
        } catch (RuntimeException e) {
            token = null;
            throw unavailable("auth_unavailable"); // an unreadable reference; no message, no cause
        }
        if (token.isEmpty()) {
            token = null;
            throw unavailable("auth_unavailable");
        }
    }

    private void login(VaultAuth.AppRole a) {
        String roleId = readSecret(a.roleId());
        String secretId = readSecret(a.secretId());
        Response r = send("/v1/auth/" + a.path() + "/login", json(Map.of("role_id", roleId, "secret_id", secretId)),
                null);
        if (r.status < 200 || r.status > 299) {
            throw unavailable("auth_http_" + r.status);
        }
        JsonNode auth = r.json().path("auth");
        String clientToken = auth.path("client_token").asString("");
        if (clientToken.isEmpty()) {
            throw unavailable("bad_response");
        }
        long lease = auth.path("lease_duration").asLong(0);
        token = clientToken;
        tokenRenewAt = lease > 0 ? clock.instant().plus(Duration.ofMillis((long) (lease * 1000 * RENEW_AT))) : null;
    }

    private String readSecret(SecretRef ref) {
        try (Secret s = resolver.apply(ref)) {
            return new String(s.chars()); // the HTTP header needs a String; it is not logged or retained beyond use
        }
    }

    // ---- helpers ----

    private static KeyServiceUnavailableException unavailable(String failureClass) {
        return new KeyServiceUnavailableException(PROVIDER, failureClass);
    }

    private static String json(Map<String, String> fields) {
        try {
            return JSON.writeValueAsString(fields);
        } catch (RuntimeException e) {
            throw new IllegalStateException("request could not be built");
        }
    }

    private static SSLContext sslContext(java.nio.file.Path pem) {
        try (InputStream in = Files.newInputStream(pem)) {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certs.isEmpty()) {
                throw new IllegalArgumentException("kms.vault.caCert holds no certificate");
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : certs) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("kms.vault.caCert cannot be read as a PEM certificate bundle");
        }
    }

    @Override
    public String toString() {
        return "VaultTransitKeyWrapper[" + config + "]";
    }
}
