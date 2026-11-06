package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.alexanderlindhorst.imapmcp.app.HttpPublicServer;
import de.alexanderlindhorst.imapmcp.app.PublicComponents;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository.NewAccount;
import de.alexanderlindhorst.imapmcp.transport.http.HttpLocalServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The production {@code PublicComponents.create()} path (no test seam): the SSRF policy of the configuration refuses a
 * host outside {@code ssrf.allowedHosts} before any name resolution or connection (SPEC 5.3). The policy needs at
 * least two labels, so {@code localhost} cannot be allowlisted at all.
 */
class HttpPublicProductionPathIT {

    private static final String ISSUER = "https://idp.example.com/realms/test";
    private static final String RESOURCE = "https://mail.example.com/mcp";

    @TempDir
    static Path dir;

    private static PostgresFixture postgres;
    private static PublicComponents components;
    private static HttpLocalServer server;
    private static RSAKey signing;

    private static String key(int seed) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(k);
    }

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgresFixture();
        postgres.start();
        signing = new RSAKeyGenerator(2048).keyID("k1").generate();
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, """
                db:
                  url: %s
                  username: %s
                  password: env:DB_PW
                oauth:
                  issuer: %s
                  resource: %s
                  requiredRole: r
                  webClientId: imap-mcp-web
                  webClientSecret: env:WEB_SECRET
                public:
                  baseUrl: https://mail.example.com
                security:
                  handleKeys:
                    - id: 1
                      key: env:HK
                  signingKeyId: 1
                ssrf:
                  allowedHosts: ["imap.example.com"]
                kms:
                  provider: local
                  local:
                    currentKekId: k
                    keks:
                      - id: k
                        key: env:KEK
                http:
                  allowedHosts: ["127.0.0.1:*"]
                """.formatted(postgres.getJdbcUrl(), postgres.getUsername(), ISSUER, RESOURCE));
        Map<String, String> env = Map.of("DB_PW", postgres.getPassword(), "WEB_SECRET", "web-secret", "HK", key(1), "KEK", key(2));
        PublicConfig config = PublicConfigLoader.load(file, env);
        components = PublicComponents.create(config, AppPaths.resolve(new String[] {"--config", file.toString()},
                Map.of(AppPaths.ENV_STATE_DIR, dir.resolve("state").toString()), dir, dir), Clock.systemUTC(), env);
        server = HttpPublicServer.build(config.withPort(0), components,
                new ImmutableJWKSet<>(new JWKSet(signing.toPublicJWK())));
        server.start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (components != null) {
            components.close();
        }
        postgres.stop();
    }

    @Test
    void aHostOutsideTheAllowlistIsAddressBlockedWithoutAConnectionOnTheProductionPath() throws Exception {
        TenantId tenant = components.tenantDirectory().create(ISSUER, "dave").tenantId();
        var accounts = new JdbcAccountRepository(components.database().dataSource());
        ResolvedAccount a = accounts.create(tenant, new NewAccount("Elsewhere", null, "other.example.net", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "dave", true, false));
        components.credentials().insert(tenant, a.account().id(), 1, "pw".toCharArray());
        long now = System.currentTimeMillis();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1")
                .type(JOSEObjectType.JWT).build(), new JWTClaimsSet.Builder().issuer(ISSUER).audience(RESOURCE)
                .subject("dave").claim("scope", "imap-mcp:read").issueTime(new Date(now))
                .expirationTime(new Date(now + 300_000)).build());
        jwt.sign(new RSASSASigner(signing));
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + jwt.serialize())
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"list_folders\",\"arguments\":{\"account\":\"" + a.handle()
                        + "\"}}}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.body()).contains("UPSTREAM_ERROR").contains("address_blocked");
    }
}
