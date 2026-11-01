package de.alexanderlindhorst.imapmcp.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigLoaderTest {

    private static final String ACCOUNT = """
            accounts:
              - id: work
                host: mail.example.org
                username: me
                password: env:MAIL_PW
            """;

    @TempDir
    Path dir;

    private AppConfig load(String yaml, Map<String, String> env) throws IOException {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, yaml);
        return ConfigLoader.load(f, env);
    }

    private AppConfig load(String yaml) throws IOException {
        return load(yaml, Map.of());
    }

    @Test
    void minimalFileGetsAllDefaults() throws IOException {
        AppConfig c = load(ACCOUNT);

        assertThat(c.accounts()).hasSize(1);
        AppConfig.Account a = c.accounts().get(0);
        assertThat(a.id()).isEqualTo("work");
        assertThat(a.label()).isEqualTo("work");
        assertThat(a.address()).isNull();
        assertThat(a.host()).isEqualTo("mail.example.org");
        assertThat(a.port()).isEqualTo(993);
        assertThat(a.security()).isEqualTo(MailAccount.Security.IMPLICIT_TLS);
        assertThat(a.flavor()).isEqualTo(MailAccount.Flavor.GENERIC);
        assertThat(a.username()).isEqualTo("me");

        assertThat(c.imap()).isEqualTo(new AppConfig.Imap(Duration.ofSeconds(10), Duration.ofSeconds(60),
                Duration.ofSeconds(60), false));
        assertThat(c.pool()).isEqualTo(new AppConfig.Pool(3, 8, 64, Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofMinutes(30), Duration.ofSeconds(10), Duration.ofMinutes(10)));
        assertThat(c.tools()).isEqualTo(new AppConfig.Tools(Duration.ofSeconds(90), 25, 100, 20000, 100000,
                4L * 1024 * 1024, 100, 10000));
        assertThat(c.secrets()).isEqualTo(new AppConfig.Secrets(Duration.ofSeconds(5)));
        assertThat(c.http()).isEqualTo(new AppConfig.Http("127.0.0.1", 8080, false, null,
                List.of("localhost:*", "127.0.0.1:*", "[::1]:*"), List.of(), 1024L * 1024));
        assertThat(c.security().handleKey()).isNull();
        assertThat(c.mode()).isNull();
        assertThat(c.logLevel()).isNull();
    }

    @Test
    void explicitValuesAreParsed() throws IOException {
        AppConfig c = load(ACCOUNT.replace("username: me", "username: me\n    port: 143\n    security: starttls\n"
                + "    label: Work\n    address: me@example.org") + """
                imap: {connectTimeout: 500ms, readTimeout: 2m, trace: true}
                pool: {maxTotal: 10, idleEviction: 1h}
                tools: {maxBodyPartBytes: 64KiB, pageSizeMax: 50}
                http: {port: 9000, token: "env:T", allowedOrigins: ["http://x"]}
                security: {handleKey: "file:/run/k"}
                secrets: {keyringTimeout: 2s}
                """);
        AppConfig.Account a = c.accounts().get(0);
        assertThat(a.port()).isEqualTo(143);
        assertThat(a.security()).isEqualTo(MailAccount.Security.STARTTLS);
        assertThat(a.label()).isEqualTo("Work");
        assertThat(a.address()).isEqualTo("me@example.org");
        assertThat(c.imap().connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(c.imap().readTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(c.imap().trace()).isTrue();
        assertThat(c.pool().maxTotal()).isEqualTo(10);
        assertThat(c.pool().idleEviction()).isEqualTo(Duration.ofHours(1));
        assertThat(c.tools().maxBodyPartBytes()).isEqualTo(65536);
        assertThat(c.tools().pageSizeMax()).isEqualTo(50);
        assertThat(c.http().port()).isEqualTo(9000);
        assertThat(c.http().token().value()).isEqualTo("env:T");
        assertThat(c.http().allowedOrigins()).containsExactly("http://x");
        assertThat(c.security().handleKey().value()).isEqualTo("file:/run/k");
        assertThat(c.secrets().keyringTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    static Stream<Arguments> invalid() {
        String acct = "accounts:\n  - id: a\n    host: h\n    username: u\n    password: env:P\n";
        return Stream.of(
                Arguments.of("unknown top-level key", acct + "bogus: 1\n", "bogus"),
                Arguments.of("unknown nested key", acct + "pool:\n  maxTotl: 1\n", "pool.maxTotl"),
                Arguments.of("unknown account key", acct + "    extra: 1\n", "accounts[0].extra"),
                Arguments.of("bad duration", acct + "imap:\n  connectTimeout: 10x\n", "imap.connectTimeout"),
                Arguments.of("zero duration", acct + "imap:\n  readTimeout: 0s\n", "imap.readTimeout"),
                Arguments.of("duration not scalar", acct + "imap:\n  readTimeout: [1]\n", "imap.readTimeout"),
                Arguments.of("bad size", acct + "tools:\n  maxBodyPartBytes: 4MB\n", "tools.maxBodyPartBytes"),
                Arguments.of("bad id", acct.replace("id: a", "id: Bad_Id"), "accounts[0].id"),
                Arguments.of("duplicate id", acct + "  - id: a\n    host: h\n    username: u\n    password: env:P\n",
                        "accounts[1].id"),
                Arguments.of("literal password", acct.replace("env:P", "hunter2-secret"), "accounts[0].password"),
                Arguments.of("empty ref", acct.replace("env:P", "\"env:\""), "accounts[0].password"),
                Arguments.of("literal token", acct + "http:\n  token: hunter2-secret\n", "http.token"),
                Arguments.of("literal handle key", acct + "security:\n  handleKey: hunter2-secret\n",
                        "security.handleKey"),
                Arguments.of("port 0", acct + "    port: 0\n", "accounts[0].port"),
                Arguments.of("port too high", acct + "    port: 65536\n", "accounts[0].port"),
                Arguments.of("port not int", acct + "    port: abc\n", "accounts[0].port"),
                Arguments.of("http port", acct + "http:\n  port: 70000\n", "http.port"),
                Arguments.of("bad security", acct + "    security: ssl\n", "accounts[0].security"),
                Arguments.of("bad flavor", acct + "    flavor: yahoo\n", "accounts[0].flavor"),
                Arguments.of("maxCharsCap", acct + "tools:\n  maxCharsCap: 100001\n", "tools.maxCharsCap"),
                Arguments.of("pageSizeMax", acct + "tools:\n  pageSizeMax: 101\n", "tools.pageSizeMax"),
                Arguments.of("maxFolders", acct + "tools:\n  maxFolders: 0\n", "tools.maxFolders"),
                Arguments.of("pool maxTotal", acct + "pool:\n  maxTotal: 0\n", "pool.maxTotal"),
                Arguments.of("default above max", acct + "tools:\n  pageSizeDefault: 30\n  pageSizeMax: 20\n",
                        "tools.pageSizeDefault"),
                Arguments.of("missing host", acct.replace("    host: h\n", ""), "accounts[0].host"),
                Arguments.of("missing password", acct.replace("    password: env:P\n", ""), "accounts[0].password"),
                Arguments.of("no accounts", "pool: {}\n", "accounts"),
                Arguments.of("accounts not a list", "accounts: x\n", "accounts"),
                Arguments.of("gmail starttls", acct.replace("host: h", "host: Imap.Gmail.com")
                        + "    security: starttls\n", "accounts[0].security"),
                Arguments.of("syntax error", "accounts: [\n", "config"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void invalidConfigNamesKeyPathAndNeverEchoesValues(String name, String yaml, String path) throws IOException {
        Path f = dir.resolve("config.yaml");
        Files.writeString(f, yaml);
        assertThatThrownBy(() -> ConfigLoader.load(f, Map.of()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(path)
                .message().doesNotContain("hunter2-secret");
    }

    @Test
    void literalPasswordMessageExplainsRefSyntax() throws IOException {
        assertThatThrownBy(() -> load(ACCOUNT.replace("env:MAIL_PW", "hunter2-secret")))
                .hasMessageContaining("accounts[0].password")
                .hasMessageContaining("must be a secret reference (env:, file: or keyring:)")
                .message().doesNotContain("hunter2-secret");
    }

    @Test
    void missingFileNamesResolvedPathAndDoesNotSearch() {
        Path missing = dir.resolve("nope").resolve("config.yaml");
        assertThatThrownBy(() -> ConfigLoader.load(missing, Map.of()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    void namedEnvOverridesWinOverFile() throws IOException {
        AppConfig c = load(ACCOUNT + "http: {bind: 10.0.0.1, port: 9000}\n", Map.of(
                "IMAP_MCP_MODE", "http-local",
                "IMAP_MCP_LOG_LEVEL", "debug",
                "IMAP_MCP_HTTP_BIND", "0.0.0.0",
                "IMAP_MCP_HTTP_PORT", "9999",
                "IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND", "true"));
        assertThat(c.mode()).isEqualTo("http-local");
        assertThat(c.logLevel()).isEqualTo("DEBUG");
        assertThat(c.http().bind()).isEqualTo("0.0.0.0");
        assertThat(c.http().port()).isEqualTo(9999);
        assertThat(c.http().allowNonLoopbackBind()).isTrue();
    }

    @Test
    void unrelatedEnvIsIgnored() throws IOException {
        AppConfig c = load(ACCOUNT, Map.of("IMAP_MCP_POOL_MAXTOTAL", "1", "IMAP_MCP_HTTP_TOKEN", "x"));
        assertThat(c.pool().maxTotal()).isEqualTo(64);
    }

    @Test
    void invalidEnvOverrideNamesVariable() throws IOException {
        assertThatThrownBy(() -> load(ACCOUNT, Map.of("IMAP_MCP_HTTP_PORT", "abc")))
                .hasMessageContaining("IMAP_MCP_HTTP_PORT");
        for (String bad : List.of("grpc", "http", "http-public")) {
            assertThatThrownBy(() -> load(ACCOUNT, Map.of("IMAP_MCP_MODE", bad)))
                    .hasMessageContaining("IMAP_MCP_MODE").message().doesNotContain(bad);
        }
        assertThat(load(ACCOUNT, Map.of("IMAP_MCP_MODE", "stdio")).mode()).isEqualTo("stdio");
        assertThatThrownBy(() -> load(ACCOUNT, Map.of("IMAP_MCP_LOG_LEVEL", "loud")))
                .hasMessageContaining("IMAP_MCP_LOG_LEVEL");
    }

    @Test
    void envOverrideListIsExposed() {
        assertThat(ConfigLoader.ENV_OVERRIDES).containsExactlyInAnyOrder("IMAP_MCP_MODE", "IMAP_MCP_LOG_LEVEL",
                "IMAP_MCP_HTTP_BIND", "IMAP_MCP_HTTP_PORT", "IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND");
    }

    @Test
    void flavorAutoResolvesGmailHostsOnly() throws IOException {
        for (String host : List.of("imap.gmail.com", "IMAP.GOOGLEMAIL.COM")) {
            AppConfig c = load(ACCOUNT.replace("mail.example.org", host));
            assertThat(c.accounts().get(0).flavor()).isEqualTo(MailAccount.Flavor.GMAIL);
        }
        assertThat(load(ACCOUNT.replace("mail.example.org", "imap.gmail.com.evil.example")).accounts().get(0)
                .flavor()).isEqualTo(MailAccount.Flavor.GENERIC);
        AppConfig forced = load(ACCOUNT + "    flavor: generic\n".replace("    ", "    "));
        assertThat(forced.accounts().get(0).flavor()).isEqualTo(MailAccount.Flavor.GENERIC);
    }

    @Test
    void mapsToCoreMailAccount() throws IOException {
        AppConfig c = load(ACCOUNT.replace("username: me", "username: me\n    address: me@example.org"));
        MailAccount m = c.accounts().get(0).toMailAccount();
        assertThat(m.id().value()).isEqualTo("work");
        assertThat(m.host()).isEqualTo("mail.example.org");
        assertThat(m.port()).isEqualTo(993);
        assertThat(m.security()).isEqualTo(MailAccount.Security.IMPLICIT_TLS);
        assertThat(m.username()).isEqualTo("me");
        assertThat(m.address()).isEqualTo("me@example.org");
        assertThat(m.secretRef().value()).isEqualTo("env:MAIL_PW");
    }
}
