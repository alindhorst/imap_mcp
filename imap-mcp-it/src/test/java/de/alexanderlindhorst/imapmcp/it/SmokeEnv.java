package de.alexanderlindhorst.imapmcp.it;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Configuration of the opt-in smoke harness (#258, SPEC 5.8) from environment variables only. Per provider
 * {@code IMAP_MCP_SMOKE_<P>_USER} and {@code IMAP_MCP_SMOKE_<P>_PASSWORD} (an app password), optionally
 * {@code _HOST} and {@code _PORT}; {@code <P>} is {@code GMAIL} or {@code M365}. The Gmail write checks need the
 * extra {@code IMAP_MCP_SMOKE_WRITE=1}. Nothing here ever returns, logs or prints a secret: reasons name variables
 * only and {@link Settings#toString()} is redacted.
 */
public final class SmokeEnv {

    static final String WRITE_VARIABLE = "IMAP_MCP_SMOKE_WRITE";

    public enum Provider {
        GMAIL("imap.gmail.com", 993, "Gmail"),
        M365("outlook.office365.com", 993, "Microsoft 365");

        final String defaultHost;
        final int defaultPort;
        final String title;

        Provider(String defaultHost, int defaultPort, String title) {
            this.defaultHost = defaultHost;
            this.defaultPort = defaultPort;
            this.title = title;
        }

        String variable(String suffix) {
            return "IMAP_MCP_SMOKE_" + name() + "_" + suffix;
        }
    }

    /**
     * One test account. {@code trustStore} exists for the local dry run against Dovecot (the same code path with a
     * test CA); the environment never sets it.
     */
    public record Settings(Provider provider, String user, String password, String host, int port, Path trustStore) {

        public Settings withLocalFixture(String fixtureHost, int fixturePort, Path truststore) {
            return new Settings(provider, user, password, fixtureHost, fixturePort, truststore);
        }

        @Override
        public String toString() {
            return "Settings[" + provider + ", " + host + ":" + port + ", credentials redacted]";
        }
    }

    private SmokeEnv() {
    }

    /** Empty when configured; otherwise the reason a test of this provider is skipped. */
    public static Optional<String> skipReason(Provider p, Map<String, String> env) {
        if (present(env, p.variable("USER")) && present(env, p.variable("PASSWORD"))) {
            return Optional.empty();
        }
        return Optional.of("smoke test skipped: set " + p.variable("USER") + " and " + p.variable("PASSWORD")
                + " to run it against a real " + p.title + " account");
    }

    /** Empty when the write checks must be skipped, with the reason; write checks exist for Gmail only. */
    public static Optional<String> writeSkipReason(Provider p, Map<String, String> env) {
        Optional<String> configured = skipReason(p, env);
        if (configured.isPresent()) {
            return configured;
        }
        if (p != Provider.GMAIL) {
            return Optional.of("smoke write checks are defined for Gmail only");
        }
        if (!"1".equals(env.get(WRITE_VARIABLE))) {
            return Optional.of("smoke write checks skipped: set " + WRITE_VARIABLE
                    + "=1 to run them (they create and delete a run-specific test label)");
        }
        return Optional.empty();
    }

    public static Optional<Settings> settings(Provider p, Map<String, String> env) {
        if (skipReason(p, env).isPresent()) {
            return Optional.empty();
        }
        String host = present(env, p.variable("HOST")) ? env.get(p.variable("HOST")).strip() : p.defaultHost;
        int port = p.defaultPort;
        if (present(env, p.variable("PORT"))) {
            try {
                port = Integer.parseInt(env.get(p.variable("PORT")).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(p.variable("PORT") + " is not a port number");
            }
        }
        return Optional.of(new Settings(p, env.get(p.variable("USER")).strip(), env.get(p.variable("PASSWORD")),
                host, port, null));
    }

    /** A label name unique per run; letters, digits and hyphens only so no hierarchy delimiter is involved. */
    public static String runLabel() {
        return "imap-mcp-smoke-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static boolean present(Map<String, String> env, String name) {
        String v = env.get(name);
        return v != null && !v.isBlank();
    }
}
