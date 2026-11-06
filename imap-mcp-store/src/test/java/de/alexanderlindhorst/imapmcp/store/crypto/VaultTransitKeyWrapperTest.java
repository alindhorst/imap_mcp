package de.alexanderlindhorst.imapmcp.store.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Vault Transit wrapper against a scripted HTTP server: request shape, error classes, re-auth, TLS. */
class VaultTransitKeyWrapperTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String ACCOUNT = "acct-one";
    private static final byte[] DEK = new byte[32];

    /** One recorded request. */
    private record Seen(String method, String path, String token, String body) {
    }

    /** Scripted answer. */
    private record Reply(int status, String body, long delayMillis) {
        Reply(int status, String body) {
            this(status, body, 0);
        }
    }

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final List<Reply> script = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readAllBytes();
        }
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                ex.getRequestHeaders().getFirst("X-Vault-Token"), new String(body, StandardCharsets.UTF_8)));
        int i = calls.getAndIncrement();
        Reply r = script.get(Math.min(i, script.size() - 1));
        try {
            if (r.delayMillis() > 0) {
                Thread.sleep(r.delayMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
        if (r.status() == 302) {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
        }
        ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) {
            ex.getResponseBody().write(out);
        }
        ex.close();
    }

    private URI startHttp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private URI startHttps(String keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/vault-tls/" + keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(ctx));
        https.createContext("/", this::handle);
        https.start();
        server = https;
        return URI.create("https://127.0.0.1:" + https.getAddress().getPort());
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    private static final Reply ENCRYPT_OK = new Reply(200, "{\"data\":{\"ciphertext\":\"vault:v2:Zm9v\"}}");

    private static Reply decryptOk(byte[] dek) {
        return new Reply(200, "{\"data\":{\"plaintext\":\"" + b64(dek) + "\"}}");
    }

    private static VaultConfig config(URI address, VaultAuth auth) {
        return new VaultConfig(address, "transit", "imap-mcp", auth, null, Duration.ofSeconds(2), true);
    }

    private static Function<SecretRef, Secret> refs(String... tokens) {
        AtomicInteger n = new AtomicInteger();
        return ref -> new Secret(tokens[Math.min(n.getAndIncrement(), tokens.length - 1)].toCharArray());
    }

    private VaultTransitKeyWrapper wrapper(VaultConfig c, Function<SecretRef, Secret> resolver) {
        return new VaultTransitKeyWrapper(c, resolver, Clock.systemUTC());
    }

    private static VaultAuth token() {
        return new VaultAuth.Token(new SecretRef("env:VAULT_TOKEN"));
    }

    // ---- startup key check ----

    private static final Reply KEY_OK = new Reply(200, "{\"data\":{\"name\":\"imap-mcp\",\"type\":\"aes256-gcm96\"}}");

    @Test
    void checkKeyReadsTheKeyEndpointWithTheTokenAndNoBody() throws Exception {
        script.add(KEY_OK);
        wrapper(config(startHttp(), token()), refs("t-1")).checkKey();
        Seen s = seen.get(0);
        assertThat(s.method()).isEqualTo("GET");
        assertThat(s.path()).isEqualTo("/v1/transit/keys/imap-mcp");
        assertThat(s.token()).isEqualTo("t-1");
        assertThat(s.body()).isEmpty();
        assertThat(seen).hasSize(1);
    }

    @Test
    void checkKeyRefusesAKeyOfAnotherTypeAndAMissingKey() throws Exception {
        script.add(new Reply(200, "{\"data\":{\"type\":\"rsa-4096\"}}"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t"));
        assertThatThrownBy(w::checkKey).isInstanceOf(IllegalStateException.class).hasMessageContaining("type")
                .hasMessageNotContaining("rsa-4096");
        script.clear();
        calls.set(0);
        script.add(new Reply(404, "{\"errors\":[]}"));
        assertThatThrownBy(w::checkKey).isInstanceOf(IllegalStateException.class).hasMessageContaining("not found");
    }

    @Test
    void checkKeyReauthenticatesOnceAndThenIsUnavailable() throws Exception {
        script.add(new Reply(403, "{\"errors\":[\"denied\"]}"));
        script.add(KEY_OK);
        wrapper(config(startHttp(), token()), refs("old", "new")).checkKey();
        assertThat(seen).extracting(Seen::token).containsExactly("old", "new");

        seen.clear();
        calls.set(0);
        script.clear();
        script.add(new Reply(403, "{\"errors\":[\"denied\"]}"));
        assertThatThrownBy(() -> wrapper(config(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                token()), refs("a", "b")).checkKey()).isInstanceOf(KeyServiceUnavailableException.class);
    }

    @Test
    void checkKeyOnAnUnreachableVaultOrAMalformedAnswerIsUnavailableWithoutResponseText() throws Exception {
        script.add(new Reply(200, "{\"data\":{}}"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t"));
        assertThatThrownBy(w::checkKey).isInstanceOf(KeyServiceUnavailableException.class);
        server.stop(0);
        assertThatThrownBy(w::checkKey).isInstanceOf(KeyServiceUnavailableException.class)
                .hasMessageNotContaining("127.0.0.1");
    }

    // ---- request shape ----

    @Test
    void wrapPostsToEncryptWithTheTokenAndThePlaintextOnly() throws Exception {
        script.add(ENCRYPT_OK);
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t-1"));

        WrappedKey wrapped = w.wrap(DEK, TENANT, ACCOUNT);

        assertThat(wrapped.kekId()).isEqualTo("imap-mcp");
        assertThat(new String(wrapped.bytes(), StandardCharsets.UTF_8)).isEqualTo("vault:v2:Zm9v");
        Seen s = seen.get(0);
        assertThat(s.method()).isEqualTo("POST");
        assertThat(s.path()).isEqualTo("/v1/transit/encrypt/imap-mcp");
        assertThat(s.token()).isEqualTo("t-1");
        assertThat(s.body()).contains("\"plaintext\":\"" + b64(DEK) + "\"");
        assertThat(s.body()).doesNotContain("associated_data").doesNotContain(TENANT);
    }

    @Test
    void unwrapPostsToDecryptUnderTheRowsKekIdAndReturnsTheDek() throws Exception {
        byte[] dek = new byte[32];
        dek[0] = 9;
        script.add(decryptOk(dek));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t-1"));

        byte[] out = w.unwrap("old-key", "vault:v1:abc".getBytes(StandardCharsets.UTF_8), TENANT, ACCOUNT);

        assertThat(out).isEqualTo(dek);
        assertThat(seen.get(0).path()).isEqualTo("/v1/transit/decrypt/old-key");
        assertThat(seen.get(0).body()).contains("\"ciphertext\":\"vault:v1:abc\"");
    }

    @Test
    void aMalformedKekIdOrCiphertextInTheRowIsAnIntegrityFailureWithoutAnyRequest() throws Exception {
        script.add(decryptOk(DEK));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t-1"));

        assertThatThrownBy(() -> w.unwrap("../sys/seal", "vault:v1:abc".getBytes(StandardCharsets.UTF_8), TENANT,
                ACCOUNT)).isInstanceOf(KeyWrapException.class).isNotInstanceOf(KeyServiceUnavailableException.class);
        assertThatThrownBy(() -> w.unwrap("imap-mcp", "not-vault".getBytes(StandardCharsets.UTF_8), TENANT, ACCOUNT))
                .isInstanceOf(KeyWrapException.class).isNotInstanceOf(KeyServiceUnavailableException.class);
        assertThat(seen).isEmpty();
    }

    // ---- integrity vs unavailability ----

    @Test
    void aBadRequestOnDecryptIsAnIntegrityFailure() throws Exception {
        script.add(new Reply(400, "{\"errors\":[\"cipher: message authentication failed\"]}"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t-1"));

        assertThatThrownBy(() -> w.unwrap("imap-mcp", "vault:v1:abc".getBytes(StandardCharsets.UTF_8), TENANT,
                ACCOUNT)).isInstanceOf(KeyWrapException.class).isNotInstanceOf(KeyServiceUnavailableException.class)
                .hasMessageNotContaining("authentication");
    }

    @Test
    void everyOtherNon2xxAnswerIsUnavailabilityWithoutResponseText() throws Exception {
        for (int status : new int[] {404, 429, 500, 503, 302}) {
            script.clear();
            seen.clear();
            calls.set(0);
            script.add(new Reply(status, "{\"errors\":[\"secret-detail-" + status + "\"]}"));
            if (server != null) {
                server.stop(0);
            }
            VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t-1"));

            assertThatThrownBy(() -> w.unwrap("imap-mcp", "vault:v1:abc".getBytes(StandardCharsets.UTF_8), TENANT,
                    ACCOUNT)).as("status %d", status).isInstanceOf(KeyServiceUnavailableException.class)
                    .satisfies(e -> {
                        KeyServiceUnavailableException u = (KeyServiceUnavailableException) e;
                        assertThat(u.provider()).isEqualTo("vault");
                        assertThat(u.failureClass()).isEqualTo("http_" + status);
                        assertThat(u.getMessage()).doesNotContain("secret-detail");
                    });
            assertThat(seen).as("no redirect followed for %d", status).hasSize(1);
        }
    }

    @Test
    void wrapNon2xxIncludingBadRequestIsUnavailability() throws Exception {
        script.add(new Reply(400, "{\"errors\":[\"x\"]}"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t-1"));

        assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class);
    }

    @Test
    void aSlowAnswerIsATimeoutUnavailability() throws Exception {
        script.add(new Reply(200, "{}", 3000));
        VaultConfig c = new VaultConfig(startHttp(), "transit", "imap-mcp", token(), null, Duration.ofSeconds(1), true);
        VaultTransitKeyWrapper w = wrapper(c, refs("t-1"));

        assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class)
                .satisfies(e -> assertThat(((KeyServiceUnavailableException) e).failureClass()).isEqualTo("timeout"));
    }

    @Test
    void aClosedPortIsUnreachableUnavailability() throws Exception {
        URI address = startHttp();
        server.stop(0);
        VaultTransitKeyWrapper w = wrapper(config(address, token()), refs("t-1"));

        assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class)
                .satisfies(e -> assertThat(((KeyServiceUnavailableException) e).failureClass())
                        .isEqualTo("unreachable"));
    }

    @Test
    void aMalformedSuccessBodyIsUnavailability() throws Exception {
        script.add(new Reply(200, "<html>not json</html>"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t-1"));

        assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class)
                .satisfies(e -> assertThat(((KeyServiceUnavailableException) e).failureClass())
                        .isEqualTo("bad_response"));
    }

    @Test
    void aCiphertextThatIsNotVaultFormatIsUnavailability() throws Exception {
        script.add(new Reply(200, "{\"data\":{\"ciphertext\":\"plain\"}}"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t-1"));

        assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class);
    }

    // ---- authentication ----

    @Test
    void aRefusedTokenIsReReadOnceAndTheRequestRepeated() throws Exception {
        script.add(new Reply(403, "{\"errors\":[\"permission denied\"]}"));
        script.add(ENCRYPT_OK);
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("stale", "fresh"));

        assertThat(w.wrap(DEK, TENANT, ACCOUNT).kekId()).isEqualTo("imap-mcp");

        assertThat(seen).extracting(Seen::token).containsExactly("stale", "fresh");
        w.wrap(DEK, TENANT, ACCOUNT); // the fresh token is kept
        assertThat(seen).extracting(Seen::token).containsExactly("stale", "fresh", "fresh");
    }

    @Test
    void aSecondRefusalIsUnavailabilityAndThereAreExactlyTwoAttempts() throws Exception {
        script.add(new Reply(401, "{}"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("a", "b"));

        assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class)
                .satisfies(e -> assertThat(((KeyServiceUnavailableException) e).failureClass()).isEqualTo("http_401"));
        assertThat(seen).hasSize(2);
    }

    @Test
    void approleLogsInAtFirstUseReusesTheTokenAndLogsInAgainAfterARefusal() throws Exception {
        script.add(new Reply(200, "{\"auth\":{\"client_token\":\"c-1\",\"lease_duration\":3600}}"));
        script.add(ENCRYPT_OK);
        script.add(new Reply(403, "{}"));
        script.add(new Reply(200, "{\"auth\":{\"client_token\":\"c-2\",\"lease_duration\":3600}}"));
        script.add(ENCRYPT_OK);
        VaultAuth.AppRole role = new VaultAuth.AppRole("approle", new SecretRef("env:ROLE"), new SecretRef("env:SEC"));
        Function<SecretRef, Secret> resolver = ref -> new Secret((ref.value().equals("env:ROLE") ? "role-id"
                : "secret-id").toCharArray());
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), role), resolver);

        w.wrap(DEK, TENANT, ACCOUNT);
        w.wrap(DEK, TENANT, ACCOUNT);

        assertThat(seen).extracting(Seen::path).containsExactly("/v1/auth/approle/login",
                "/v1/transit/encrypt/imap-mcp", "/v1/transit/encrypt/imap-mcp", "/v1/auth/approle/login",
                "/v1/transit/encrypt/imap-mcp");
        assertThat(seen.get(0).body()).contains("\"role_id\":\"role-id\"").contains("\"secret_id\":\"secret-id\"");
        assertThat(seen.get(1).token()).isEqualTo("c-1");
        assertThat(seen.get(4).token()).isEqualTo("c-2");
    }

    @Test
    void approleKeepsTheTokenWithinEightyPercentOfTheLease() throws Exception {
        script.add(new Reply(200, "{\"auth\":{\"client_token\":\"c-1\",\"lease_duration\":100}}"));
        script.add(ENCRYPT_OK);
        VaultAuth.AppRole role = new VaultAuth.AppRole("approle", new SecretRef("env:ROLE"), new SecretRef("env:SEC"));
        MutableClock clock = new MutableClock();
        VaultTransitKeyWrapper w = new VaultTransitKeyWrapper(config(startHttp(), role),
                ref -> new Secret("x".toCharArray()), clock);

        w.wrap(DEK, TENANT, ACCOUNT);
        clock.advance(Duration.ofSeconds(70));
        w.wrap(DEK, TENANT, ACCOUNT);

        assertThat(seen).extracting(Seen::path).containsExactly("/v1/auth/approle/login",
                "/v1/transit/encrypt/imap-mcp", "/v1/transit/encrypt/imap-mcp");
    }

    @Test
    void approleLoginAfterEightyPercentOfTheLeaseHappensBeforeTheRequest() throws Exception {
        script.add(new Reply(200, "{\"auth\":{\"client_token\":\"c-1\",\"lease_duration\":100}}"));
        script.add(ENCRYPT_OK);
        script.add(new Reply(200, "{\"auth\":{\"client_token\":\"c-2\",\"lease_duration\":100}}"));
        script.add(ENCRYPT_OK);
        VaultAuth.AppRole role = new VaultAuth.AppRole("approle", new SecretRef("env:ROLE"), new SecretRef("env:SEC"));
        MutableClock clock = new MutableClock();
        VaultTransitKeyWrapper w = new VaultTransitKeyWrapper(config(startHttp(), role),
                ref -> new Secret("x".toCharArray()), clock);

        w.wrap(DEK, TENANT, ACCOUNT);
        clock.advance(Duration.ofSeconds(85));
        w.wrap(DEK, TENANT, ACCOUNT);

        assertThat(seen).extracting(Seen::path).containsExactly("/v1/auth/approle/login",
                "/v1/transit/encrypt/imap-mcp", "/v1/auth/approle/login", "/v1/transit/encrypt/imap-mcp");
        assertThat(seen.get(3).token()).isEqualTo("c-2");
    }

    @Test
    void aFailedApproleLoginIsUnavailability() throws Exception {
        script.add(new Reply(400, "{\"errors\":[\"invalid secret id\"]}"));
        VaultAuth.AppRole role = new VaultAuth.AppRole("approle", new SecretRef("env:ROLE"), new SecretRef("env:SEC"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), role), ref -> new Secret("x".toCharArray()));

        assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class)
                .hasMessageNotContaining("invalid secret id");
    }

    @Test
    void anUnreadableTokenReferenceIsUnavailabilityNotAnIntegrityFailure() throws Exception {
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), ref -> {
            throw new IllegalStateException("file missing");
        });

        assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class);
    }

    @Test
    void tokensAndSecretsNeverAppearInTextualForms() throws Exception {
        script.add(new Reply(500, "{}"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("tok-canary"));
        Throwable thrown = null;
        try {
            w.wrap(DEK, TENANT, ACCOUNT);
        } catch (KeyServiceUnavailableException e) {
            thrown = e;
        }

        assertThat(thrown).isNotNull().hasNoCause();
        assertThat(String.valueOf(thrown)).doesNotContain("tok-canary").doesNotContain(TENANT);
        assertThat(w.toString()).doesNotContain("tok-canary");
        assertThat(config(URI.create("http://127.0.0.1:1"), token()).toString()).doesNotContain("VAULT_TOKEN");
    }

    // ---- configuration ----

    @Test
    void httpIsRefusedUnlessInsecureIsAllowedAndTheTimeoutIsBounded() {
        URI http = URI.create("http://vault.example:8200");
        assertThatThrownBy(() -> new VaultConfig(http, "transit", "k", token(), null, Duration.ofSeconds(5), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VaultConfig(URI.create("https://v:8200"), "transit", "k", token(), null,
                Duration.ofMillis(999), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VaultConfig(URI.create("https://v:8200"), "transit", "k", token(), null,
                Duration.ofSeconds(31), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VaultConfig(URI.create("https://v:8200"), "transit", "bad/key", token(), null,
                Duration.ofSeconds(5), false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VaultConfig(URI.create("https://v:8200/with/path?x=1"), "transit", "k", token(),
                null, Duration.ofSeconds(5), false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new VaultConfig(URI.create("https://v:8200"), "transit", "k", token(), null,
                Duration.ofSeconds(5), false).transitMount()).isEqualTo("transit");
    }

    // ---- TLS ----

    @Test
    void aCustomCaCertReplacesTheTrustStoreAndTheNameIsVerified() throws Exception {
        script.add(ENCRYPT_OK);
        URI address = startHttps("good.p12");
        Path ca = Path.of(getClass().getResource("/vault-tls/good.pem").toURI());
        VaultConfig c = new VaultConfig(address, "transit", "imap-mcp", token(), ca, Duration.ofSeconds(2), false);

        assertThat(wrapper(c, refs("t")).wrap(DEK, TENANT, ACCOUNT).kekId()).isEqualTo("imap-mcp");
    }

    @Test
    void aCertificateForAnotherNameIsATlsUnavailability() throws Exception {
        script.add(ENCRYPT_OK);
        URI address = startHttps("other.p12");
        Path ca = Path.of(getClass().getResource("/vault-tls/other.pem").toURI());
        VaultConfig c = new VaultConfig(address, "transit", "imap-mcp", token(), ca, Duration.ofSeconds(2), false);

        assertThatThrownBy(() -> wrapper(c, refs("t")).wrap(DEK, TENANT, ACCOUNT))
                .isInstanceOf(KeyServiceUnavailableException.class)
                .satisfies(e -> assertThat(((KeyServiceUnavailableException) e).failureClass()).isEqualTo("tls"));
        assertThat(seen).isEmpty();
    }

    @Test
    void withoutACaCertASelfSignedServerIsRejected() throws Exception {
        script.add(ENCRYPT_OK);
        URI address = startHttps("good.p12");
        VaultConfig c = new VaultConfig(address, "transit", "imap-mcp", token(), null, Duration.ofSeconds(2), false);

        assertThatThrownBy(() -> wrapper(c, refs("t")).wrap(DEK, TENANT, ACCOUNT))
                .isInstanceOf(KeyServiceUnavailableException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void anUnreadableCaCertFailsConstructionWithoutEchoingThePath() {
        VaultConfig c = new VaultConfig(URI.create("https://127.0.0.1:1"), "transit", "k", token(),
                Path.of("/nonexistent/secret-dir/ca.pem"), Duration.ofSeconds(2), false);

        assertThatThrownBy(() -> wrapper(c, refs("t"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-dir");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-06T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }


    // ---- review corrections (S1, S2, S3, N1, N4, N5, N6) ----

    @Test
    void keyNamesOutsideVaultsGrammarAreIntegrityFailuresWithoutARequest() throws Exception {
        script.add(decryptOk(DEK));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t"));

        for (String bad : new String[] {".", "..", "x-", "-x", ".x", "x.", "a".repeat(65), "a b", "a/b", ""}) {
            assertThatThrownBy(() -> w.unwrap(bad, "vault:v1:abc".getBytes(StandardCharsets.UTF_8), TENANT, ACCOUNT))
                    .as("kek_id '%s'", bad).isInstanceOf(KeyWrapException.class)
                    .isNotInstanceOf(KeyServiceUnavailableException.class);
            assertThatThrownBy(() -> new VaultConfig(URI.create("https://v:8200"), "transit", bad, token(), null,
                    Duration.ofSeconds(5), false)).as("keyName '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(seen).isEmpty();
        for (String good : new String[] {"a", "_", "imap-mcp", "k.1", "A_b-c.d", "a".repeat(64)}) {
            new VaultConfig(URI.create("https://v:8200"), "transit", good, token(), null, Duration.ofSeconds(5),
                    false);
        }
    }

    @Test
    void theRequestTimeoutBoundsTheWholeExchangeIncludingAStalledBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, 1000); // headers now, the body never arrives in full
            ex.getResponseBody().write("{\"da".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.start();
        URI address = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        VaultConfig c = new VaultConfig(address, "transit", "imap-mcp", token(), null, Duration.ofSeconds(1), true);
        VaultTransitKeyWrapper w = wrapper(c, refs("t"));
        long start = System.nanoTime();

        assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class)
                .satisfies(e -> assertThat(((KeyServiceUnavailableException) e).failureClass()).isEqualTo("timeout"));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));
    }

    @Test
    void theClientIgnoresTheJvmProxySettings() throws Exception {
        script.add(ENCRYPT_OK);
        URI address = startHttp();
        String oldHost = System.getProperty("http.proxyHost");
        String oldPort = System.getProperty("http.proxyPort");
        String oldNon = System.getProperty("http.nonProxyHosts");
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "9"); // nothing listens there
        System.setProperty("http.nonProxyHosts", "");
        try {
            VaultTransitKeyWrapper w = wrapper(config(address, token()), refs("t"));

            assertThat(w.wrap(DEK, TENANT, ACCOUNT).kekId()).isEqualTo("imap-mcp");
        } finally {
            restore("http.proxyHost", oldHost);
            restore("http.proxyPort", oldPort);
            restore("http.nonProxyHosts", oldNon);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void aDecryptedKeyThatIsNot32BytesIsAnIntegrityFailure() throws Exception {
        script.add(decryptOk(new byte[16]));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t"));

        assertThatThrownBy(() -> w.unwrap("imap-mcp", "vault:v1:abc".getBytes(StandardCharsets.UTF_8), TENANT,
                ACCOUNT)).isInstanceOf(KeyWrapException.class).isNotInstanceOf(KeyServiceUnavailableException.class);
    }

    @Test
    void aBadRequestIsIntegrityOnlyWhenItIsVaultJsonWithAnErrorsArray() throws Exception {
        for (String body : new String[] {"<html>bad gateway</html>", "{}", "{\"errors\":\"x\"}", ""}) {
            script.clear();
            calls.set(0);
            seen.clear();
            script.add(new Reply(400, body));
            if (server != null) {
                server.stop(0);
            }
            VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t"));

            assertThatThrownBy(() -> w.unwrap("imap-mcp", "vault:v1:abc".getBytes(StandardCharsets.UTF_8), TENANT,
                    ACCOUNT)).as("body '%s'", body).isInstanceOf(KeyServiceUnavailableException.class)
                    .satisfies(e -> assertThat(((KeyServiceUnavailableException) e).failureClass())
                            .isEqualTo("bad_response"));
        }
    }

    @Test
    void aBadRequestAfterTheReAuthenticationIsStillIntegrity() throws Exception {
        script.add(new Reply(403, "{}"));
        script.add(new Reply(400, "{\"errors\":[\"cipher: message authentication failed\"]}"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("stale", "fresh"));

        assertThatThrownBy(() -> w.unwrap("imap-mcp", "vault:v1:abc".getBytes(StandardCharsets.UTF_8), TENANT,
                ACCOUNT)).isInstanceOf(KeyWrapException.class).isNotInstanceOf(KeyServiceUnavailableException.class);
        assertThat(seen).extracting(Seen::token).containsExactly("stale", "fresh");
    }

    @Test
    void aResponseOverOneMebibyteIsABadResponse() throws Exception {
        script.add(new Reply(200, "{\"data\":{\"ciphertext\":\"vault:v1:" + "A".repeat((1 << 20) + 10) + "\"}}"));
        VaultTransitKeyWrapper w = wrapper(config(startHttp(), token()), refs("t"));

        assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceUnavailableException.class)
                .satisfies(e -> assertThat(((KeyServiceUnavailableException) e).failureClass())
                        .isEqualTo("bad_response"));
    }

    @Test
    void anInterruptedCallIsNotReportedAsKeyServiceUnavailability() throws Exception {
        script.add(new Reply(200, "{}", 3000));
        VaultConfig c = new VaultConfig(startHttp(), "transit", "imap-mcp", token(), null, Duration.ofSeconds(10), true);
        VaultTransitKeyWrapper w = wrapper(c, refs("t"));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> w.wrap(DEK, TENANT, ACCOUNT)).isInstanceOf(KeyServiceInterruptedException.class)
                    .isInstanceOf(KeyServiceUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); // the flag is restored
        } finally {
            Thread.interrupted();
        }
    }
}
