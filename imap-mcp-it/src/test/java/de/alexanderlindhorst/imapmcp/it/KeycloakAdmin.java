package de.alexanderlindhorst.imapmcp.it;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates test users in the deployed realm with kcadm.sh inside the keycloak container. The admin password is read
 * there from the container's own environment ({@code KC_BOOTSTRAP_ADMIN_PASSWORD}) by a shell, so it is never on a
 * host command line or in a log; a user's password travels as an exec environment variable, not as an argument of the
 * host process. Command output is never printed. The only realm setting that can be changed is the access token lifespan
 * ({@link #setAccessTokenLifespan}), by a test that restores it.
 */
final class KeycloakAdmin {

    private static final String KCADM = "/opt/keycloak/bin/kcadm.sh";
    private static final String CONFIG = "/tmp/kcadm-it.config";
    private static final String REALM = "imap-mcp";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A realm user; the password is generated per run and never part of {@code toString}. */
    record TestUser(String username, String password) {
        @Override
        public String toString() {
            return "TestUser[" + username + "]";
        }
    }

    private final DeployedStack stack;
    private boolean loggedIn;

    KeycloakAdmin(DeployedStack stack) {
        this.stack = stack;
    }

    /** A user with the role {@code imap-mcp-user}, a complete profile (no required action) and a fresh password. */
    synchronized TestUser createUser() throws Exception {
        return create(true);
    }

    /** As {@link #createUser()} but without the role: the linking surface refuses it at the callback (D2). */
    synchronized TestUser createUserWithoutRole() throws Exception {
        return create(false);
    }

    /** The Keycloak id of the user, which is the {@code sub} of its tokens. */
    synchronized String userId(TestUser user) throws Exception {
        login();
        String out = run("get users -r " + REALM + " -q exact=true -q username=" + user.username() + " --fields id",
                List.of());
        return JsonMapper.builder().build().readTree(out).get(0).get("id").asString();
    }

    private TestUser create(boolean withRole) throws Exception {
        login();
        String name = "connector-" + random(6).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "x");
        String password = newPassword();
        run("create users -r " + REALM + " -s username=" + name + " -s enabled=true -s emailVerified=true"
                + " -s email=" + name + "@example.test -s firstName=Test -s lastName=Connector", List.of());
        run("set-password -r " + REALM + " --username " + name + " --new-password \"$IT_USER_PASSWORD\"",
                List.of("IT_USER_PASSWORD=" + password));
        if (withRole) {
            run("add-roles -r " + REALM + " --uusername " + name + " --rolename imap-mcp-user", List.of());
        }
        return new TestUser(name, password);
    }

    /** The realm's {@code accessTokenLifespan} in seconds. */
    synchronized int accessTokenLifespan() throws Exception {
        login();
        String out = run("get realms/" + REALM + " --fields accessTokenLifespan", List.of());
        return JsonMapper.builder().build().readTree(out).get("accessTokenLifespan").asInt();
    }

    /** Sets the realm's {@code accessTokenLifespan} (seconds); the caller restores it. */
    synchronized void setAccessTokenLifespan(int seconds) throws Exception {
        login();
        run("update realms/" + REALM + " -s accessTokenLifespan=" + seconds, List.of());
    }

    private void login() throws Exception {
        if (loggedIn) {
            return;
        }
        run("config credentials --server http://localhost:8080 --realm master --user \"$KC_BOOTSTRAP_ADMIN_USERNAME\""
                + " --password \"$KC_BOOTSTRAP_ADMIN_PASSWORD\"", List.of());
        loggedIn = true;
    }

    private String run(String kcadmArgs, List<String> env) throws Exception {
        DeployedCompose.ExecResult r = stack.exec("keycloak", env, "sh", "-c",
                KCADM + " " + kcadmArgs + " --config " + CONFIG);
        if (r.exitCode() != 0) {
            // the command's first word only: output and arguments can hold secrets
            throw new IllegalStateException("kcadm " + kcadmArgs.split(" ")[0] + " failed: " + r + " (" + classify(r.stderr()) + ")");
        }
        return r.stdout();
    }

    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * Alphanumeric only: kcadm treats a value starting with '-' (1 in 64 for url-safe base64) as an option and exits 2,
     * and 24 characters satisfy the realm policy length(14); username and email cannot occur in it.
     */
    static String newPassword() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    /** A fixed failure class from kcadm's stderr; the text itself is never returned, it can hold secrets. */
    static String classify(String stderr) {
        String s = stderr == null ? "" : stderr.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("invalid user credentials") || s.contains("401") || s.contains("unauthorized")
                || s.contains("invalid_grant") || s.contains("session not active")) {
            return "unauthorized";
        }
        if (s.contains("password") && (s.contains("invalidpassword") || s.contains("policy"))) {
            return "policy";
        }
        if (s.contains("unknown option") || s.contains("missing required") || s.contains("usage")) {
            return "usage";
        }
        return "other";
    }

    private static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
