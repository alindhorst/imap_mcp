package de.alexanderlindhorst.imapmcp.it;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes a server config (SPEC 5.7) plus state and log directories below a root dir (normally a {@code @TempDir}).
 * Passwords are never written to the file: each account gets {@code password: "env:NAME"} and the value goes into
 * {@link ServerConfig#env()}. TLS trust is a test truststore given to the child JVM, never a server setting.
 */
public final class ServerConfigWriter {

    private record Account(String id, String host, int port, MailAccount.Security security, String username) {
    }

    private final Path root;
    private final List<Account> accounts = new ArrayList<>();
    private final Map<String, String> env = new LinkedHashMap<>();
    private final Set<String> readOnly = new LinkedHashSet<>();
    private Path trustStore;
    private String extraYaml = "";

    public ServerConfigWriter(Path root) {
        this.root = root;
    }

    public ServerConfigWriter account(String id, String host, int port, MailAccount.Security security,
            String username, String password) {
        accounts.add(new Account(id, host, port, security, username));
        env.put(passwordVariable(id), password);
        return this;
    }

    /** Marks an already added account {@code readOnly: true} (SPEC 5.3); write tools stay enabled by default. */
    public ServerConfigWriter readOnly(String accountId) {
        if (accounts.stream().noneMatch(a -> a.id().equals(accountId))) {
            throw new IllegalArgumentException("no such account: " + accountId);
        }
        readOnly.add(accountId);
        return this;
    }

    public ServerConfigWriter trustStore(Path truststore) {
        this.trustStore = truststore;
        return this;
    }

    /** Raw top-level YAML appended after the accounts (imap, pool, tools, http, ...). */
    public ServerConfigWriter extraYaml(String yaml) {
        this.extraYaml = yaml;
        return this;
    }

    public ServerConfig write() throws IOException {
        Path state = Files.createDirectories(root.resolve("state"));
        Path logs = Files.createDirectories(root.resolve("logs"));
        StringBuilder yaml = new StringBuilder("accounts:\n");
        for (Account a : accounts) {
            yaml.append("  - id: ").append(a.id()).append('\n')
                    .append("    host: ").append(a.host()).append('\n')
                    .append("    port: ").append(a.port()).append('\n')
                    .append("    security: ").append(a.security() == MailAccount.Security.STARTTLS
                            ? "starttls" : "implicit-tls").append('\n')
                    .append("    username: ").append(a.username()).append('\n')
                    .append("    password: \"env:").append(passwordVariable(a.id())).append("\"\n");
            if (readOnly.contains(a.id())) {
                yaml.append("    readOnly: true\n");
            }
        }
        yaml.append(extraYaml);
        Path file = root.resolve("config.yaml");
        Files.writeString(file, yaml.toString());

        Map<String, String> childEnv = new LinkedHashMap<>(env);
        childEnv.put("IMAP_MCP_STATE_DIR", state.toString());
        childEnv.put("IMAP_MCP_LOG_DIR", logs.toString());
        List<String> jvmArgs = new ArrayList<>();
        if (trustStore != null) {
            jvmArgs.add("-Djavax.net.ssl.trustStore=" + trustStore);
            jvmArgs.add("-Djavax.net.ssl.trustStorePassword=" + TestTrust.PASSWORD);
            jvmArgs.add("-Djavax.net.ssl.trustStoreType=PKCS12");
        }
        return new ServerConfig(file, state, logs, Map.copyOf(childEnv), List.copyOf(jvmArgs));
    }

    private static String passwordVariable(String id) {
        return "IMAP_MCP_IT_PW_" + id.toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
