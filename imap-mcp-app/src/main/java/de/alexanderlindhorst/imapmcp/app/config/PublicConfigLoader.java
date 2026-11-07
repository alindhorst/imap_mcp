package de.alexanderlindhorst.imapmcp.app.config;

import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader.Section;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.store.crypto.VaultAuth;
import de.alexanderlindhorst.imapmcp.store.crypto.VaultConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads the YAML config of {@code http-public} (SPEC 5.7, v0.20) into {@link PublicConfig}: the same tree-binding
 * discipline as {@link ConfigLoader} (every error names the key path and never a value; unknown keys are rejected
 * at every level), with the multi-tenant rules: no accounts (a configured {@code accounts} list is ignored, the rows
 * are in the database), no static token, an explicit shared key ring, an SSRF allowlist, a key-wrapping provider, and
 * OAuth settings. Secret references are checked syntactically only.
 */
public final class PublicConfigLoader {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Pattern KEK_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Duration MIN_DEADLINE = Duration.ofSeconds(30);
    private static final Duration PURGE_MIN_DEADLINE = Duration.ofSeconds(60);
    private static final long INLINE_DEFAULT = 5L * 1024 * 1024;
    private static final long INLINE_MAX = 10L * 1024 * 1024;

    private PublicConfigLoader() {
    }

    public static PublicConfig load(Path file, Map<String, String> env) {
        String text;
        try {
            text = Files.readString(file);
        } catch (NoSuchFileException e) {
            throw new ConfigException("config file not found: " + file);
        } catch (IOException e) {
            throw new ConfigException("config file cannot be read: " + file);
        }
        return bindYaml(text, env);
    }

    static PublicConfig bindYaml(String yaml, Map<String, String> env) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (JacksonException e) {
            var loc = e.getLocation();
            throw new ConfigException("config: invalid YAML syntax" + (loc == null ? "" : " near line " + loc.getLineNr()));
        }
        Section top = new Section(root, "config");
        if (top.take("accounts") != null) {
            throw new ConfigException("config.accounts: not available in http-public "
                    + "(accounts are linked in the linking surface)");
        }
        AppConfig.Imap imap = ConfigLoader.imap(top.section("imap"));
        if (imap.trace()) {
            throw new ConfigException("config.imap.trace: not available in http-public "
                    + "(the trace would write every tenant's mail content into the operator log)");
        }
        AppConfig.Pool pool = ConfigLoader.pool(top.section("pool"));
        AppConfig.Secrets secrets = new AppConfig.Secrets(
                top.section("secrets").duration("keyringTimeout", Duration.ofSeconds(5)));
        PublicConfig.Db db = db(top.section("db"));
        PublicConfig.Oauth oauth = oauth(top.section("oauth"));
        PublicConfig.Public pub = pub(top.section("public"));
        AppConfig.Security security = security(top.section("security"));
        PublicConfig.Ssrf ssrf = ssrf(top.section("ssrf"));
        PublicConfig.Kms kms = kms(top.section("kms"));
        Section purge = top.section("purge");
        boolean purgeEnabled = purge.bool("enabled", false);
        purge.finish();
        PublicConfig.Http http = http(top.section("http"), pub);
        PublicConfig.Tools tools = tools(top.section("tools"));
        top.finish();

        if (purgeEnabled && !http.stateful()) {
            throw new ConfigException("purge.enabled: requires http.stateful (confirmation needs server-to-client requests)");
        }
        if (tools.deadline().compareTo(MIN_DEADLINE) < 0) {
            throw new ConfigException("tools.deadline: must be at least 30s in http-public (the write tools are always registered)");
        }
        if (purgeEnabled && tools.deadline().compareTo(PURGE_MIN_DEADLINE) < 0) {
            throw new ConfigException("tools.deadline: must be at least 60s while purge.enabled is true");
        }
        http = envHttp(http, env);
        return new PublicConfig(imap, pool, secrets, tools, http, db, oauth, pub, ssrf, kms, purgeEnabled, security,
                ConfigLoader.envLogLevel(env));
    }

    // ---- sections ----

    private static PublicConfig.Db db(Section s) {
        String url = s.string("url", null);
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new ConfigException(s.path("url") + ": must be a jdbc:postgresql: URL");
        }
        String query = url.indexOf('?') < 0 ? "" : url.substring(url.indexOf('?') + 1).toLowerCase(Locale.ROOT);
        for (String param : query.split("[&;]")) {
            String name = param.split("=", 2)[0].trim();
            if (name.equals("password") || name.equals("sslpassword")) {
                throw new ConfigException(s.path("url") + ": must not carry a password (use db.password)");
            }
        }
        String username = s.string("username", null);
        SecretRef password = s.requiredRef("password");
        int max = s.integer("maxPoolSize", 10, 1, 100);
        s.finish();
        return new PublicConfig.Db(url, username, password, max);
    }

    private static PublicConfig.Oauth oauth(Section s) {
        String issuer = httpsUri(s, "issuer");
        String resource = httpsUri(s, "resource");
        String subjectClaim = s.string("subjectClaim", "sub");
        String requiredRole = s.optionalString("requiredRole");
        if (requiredRole != null && requiredRole.isBlank()) {
            throw new ConfigException(s.path("requiredRole") + ": must not be blank");
        }
        String roleClaim = s.string("roleClaim", "realm_access.roles");
        boolean allowWithoutRole = s.bool("allowWithoutRole", false);
        // the linking surface is part of http-public: its confidential web client is required (SPEC 5.7)
        String webClientId = s.string("webClientId", null);
        SecretRef webClientSecret = s.requiredRef("webClientSecret");
        s.finish();
        if (requiredRole == null && !allowWithoutRole) {
            throw new ConfigException(s.path("requiredRole")
                    + ": required unless oauth.allowWithoutRole is true (development)");
        }
        return new PublicConfig.Oauth(issuer, resource, subjectClaim, requiredRole, roleClaim, allowWithoutRole,
                webClientId, webClientSecret);
    }

    private static PublicConfig.Public pub(Section s) {
        String baseUrl = baseUrl(s);
        int max = s.integer("maxAccountsPerTenant", 10, 1, 1000);
        s.finish();
        return new PublicConfig.Public(baseUrl, max);
    }

    /**
     * {@code public.baseUrl} (SPEC 5.7): an {@code https} origin without path, query, fragment or user info. It is
     * normalised to the form browsers send in {@code Origin}: lower-case host, an explicit {@code :443} dropped, no
     * trailing slash. The value is not echoed.
     */
    private static String baseUrl(Section s) {
        String v = s.string("baseUrl", null);
        try {
            URI u = new URI(v);
            String path = u.getRawPath();
            if ("https".equals(u.getScheme()) && u.getHost() != null && u.getRawUserInfo() == null
                    && u.getRawQuery() == null && u.getRawFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path))) {
                int port = u.getPort();
                return "https://" + u.getHost().toLowerCase(Locale.ROOT) + (port == -1 || port == 443 ? "" : ":" + port);
            }
        } catch (URISyntaxException ignored) {
            // reported below
        }
        throw new ConfigException(s.path("baseUrl")
                + ": must be an https URI without path, user info, query or fragment");
    }

    /** A required {@code https} URI with a host and without user info, query or fragment; the value is not echoed. */
    private static String httpsUri(Section s, String key) {
        String v = s.string(key, null);
        try {
            URI u = new URI(v);
            if ("https".equals(u.getScheme()) && u.getHost() != null && u.getRawUserInfo() == null
                    && u.getRawQuery() == null && u.getRawFragment() == null) {
                return v;
            }
        } catch (URISyntaxException ignored) {
            // reported below
        }
        throw new ConfigException(s.path(key) + ": must be an https URI without user info, query or fragment");
    }

    private static AppConfig.Security security(Section s) {
        if (s.take("handleKey") != null) {
            throw new ConfigException(s.path("handleKey")
                    + ": not available in http-public (use security.handleKeys; the state file is not used)");
        }
        JsonNode list = s.take("handleKeys");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new ConfigException(s.path("handleKeys") + ": required, a non-empty list of {id, key}");
        }
        List<AppConfig.HandleKey> keys = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Section e = new Section(list.get(i), s.path("handleKeys") + "[" + i + "]");
            int id = e.integer("id", null, 0, 255);
            if (!ids.add(id)) {
                throw new ConfigException(e.path("id") + ": duplicate key id");
            }
            SecretRef key = e.requiredRef("key");
            e.finish();
            keys.add(new AppConfig.HandleKey(id, key));
        }
        int signing = s.integer("signingKeyId", null, 0, 255);
        s.finish();
        if (!ids.contains(signing)) {
            throw new ConfigException(s.path("signingKeyId") + ": no key with this id in security.handleKeys");
        }
        return new AppConfig.Security(null, List.copyOf(keys), signing);
    }

    private static PublicConfig.Ssrf ssrf(Section s) {
        List<String> hosts = s.stringList("allowedHosts", List.of());
        boolean any = s.bool("allowAnyPublicHost", false);
        List<String> cidrs = s.stringList("extraBlockedCidrs", List.of());
        s.finish();
        if (hosts.isEmpty() && !any) {
            throw new ConfigException(s.path("allowedHosts") + ": required unless ssrf.allowAnyPublicHost is true");
        }
        return new PublicConfig.Ssrf(hosts, any, cidrs);
    }

    private static PublicConfig.Kms kms(Section s) {
        String provider = s.string("provider", null);
        if (!Set.of("local", "vault").contains(provider)) {
            throw new ConfigException(s.path("provider") + ": must be one of local, vault");
        }
        JsonNode vault = s.take("vault");
        JsonNode local = s.take("local");
        if ("local".equals(provider)) {
            if (vault != null) {
                throw new ConfigException(s.path("vault") + ": not allowed with kms.provider local");
            }
            PublicConfig.Kms kms = localKeks(new Section(local, s.path("local")));
            s.finish();
            return kms;
        }
        if (local != null) {
            throw new ConfigException(s.path("local") + ": not allowed with kms.provider vault");
        }
        if (vault == null) {
            throw new ConfigException(s.path("vault") + ": required with kms.provider vault");
        }
        PublicConfig.Kms kms = new PublicConfig.Kms("vault", null, List.of(), vault(new Section(vault, s.path("vault"))));
        s.finish();
        return kms;
    }

    /** {@code kms.vault.*} (SPEC 5.7): the grammar rules of {@link VaultConfig} apply; its messages carry no value. */
    private static VaultConfig vault(Section v) {
        String address = v.string("address", null);
        boolean allowInsecure = v.bool("allowInsecureHttp", false);
        String mount = v.string("transitMount", "transit");
        String keyName = v.string("keyName", null);
        Section a = v.section("auth");
        String method = a.string("method", null);
        VaultAuth auth;
        if ("token".equals(method)) {
            forbid(a, "roleId", "token");
            forbid(a, "secretId", "token");
            forbid(a, "path", "token");
            auth = new VaultAuth.Token(a.requiredRef("token"));
        } else if ("approle".equals(method)) {
            forbid(a, "token", "approle");
            String path = a.string("path", "approle");
            SecretRef roleId = a.requiredRef("roleId");
            SecretRef secretId = a.requiredRef("secretId");
            try {
                auth = new VaultAuth.AppRole(path, roleId, secretId);
            } catch (IllegalArgumentException e) { // fixed text without the value
                throw new ConfigException(a.path("path") + ": malformed");
            }
        } else {
            throw new ConfigException(a.path("method") + ": must be one of approle, token");
        }
        a.finish();
        Path caCert = v.optionalAbsolutePath("caCert");
        Duration timeout = v.duration("requestTimeout", VaultConfig.DEFAULT_REQUEST_TIMEOUT);
        v.finish();
        URI uri;
        try {
            uri = new URI(address);
        } catch (URISyntaxException e) { // its message would echo the value
            throw new ConfigException(v.path("address") + ": must be a base URL");
        }
        try {
            return new VaultConfig(uri, mount, keyName, auth, caCert, timeout, allowInsecure);
        } catch (IllegalArgumentException e) { // fixed texts of VaultConfig, none carries a value
            throw new ConfigException(e.getMessage());
        }
    }

    private static void forbid(Section s, String key, String method) {
        if (s.take(key) != null) {
            throw new ConfigException(s.path(key) + ": not allowed with kms.vault.auth.method " + method);
        }
    }

    private static PublicConfig.Kms localKeks(Section s) {
        String current = s.string("currentKekId", null);
        JsonNode list = s.take("keks");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new ConfigException(s.path("keks") + ": required, a non-empty list of {id, key}");
        }
        List<PublicConfig.Kek> keks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Section e = new Section(list.get(i), s.path("keks") + "[" + i + "]");
            String id = e.string("id", null);
            if (!KEK_ID.matcher(id).matches()) {
                throw new ConfigException(e.path("id") + ": must match [A-Za-z0-9._-]{1,64}");
            }
            if (!ids.add(id)) {
                throw new ConfigException(e.path("id") + ": duplicate id");
            }
            SecretRef key = e.requiredRef("key");
            e.finish();
            keks.add(new PublicConfig.Kek(id, key));
        }
        s.finish();
        if (!ids.contains(current)) {
            throw new ConfigException(s.path("currentKekId") + ": not one of kms.local.keks");
        }
        return new PublicConfig.Kms("local", current, List.copyOf(keks), null);
    }

    /** D12, proposed default. */
    private static final int DEFAULT_INITIALIZE_RATE = 10;

    private static PublicConfig.Http http(Section s, PublicConfig.Public pub) {
        if (s.take("token") != null) {
            throw new ConfigException(s.path("token") + ": not available in http-public (the static token does not exist there)");
        }
        String bind = s.string("bind", "127.0.0.1");
        int port = s.integer("port", 8080, 1, 65535);
        boolean nonLoopback = s.bool("allowNonLoopbackBind", false);
        String publicHost = URI.create(pub.baseUrl()).getHost().toLowerCase(Locale.ROOT);
        List<String> hosts = s.stringList("allowedHosts", List.of(publicHost + ":*"));
        List<String> origins = s.stringList("allowedOrigins", List.of());
        long maxBytes = s.size("maxRequestBytes", 1024L * 1024, Long.MAX_VALUE);
        boolean stateful = s.bool("stateful", false);
        boolean rateSet = s.take("initializeRatePerMinute") != null;
        int rate = s.integer("initializeRatePerMinute", DEFAULT_INITIALIZE_RATE, 1, 600);
        s.finish();
        return new PublicConfig.Http(bind, port, nonLoopback, hosts, origins, maxBytes, stateful, rate, rateSet);
    }

    private static PublicConfig.Tools tools(Section s) {
        Duration deadline = s.duration("deadline", Duration.ofSeconds(90), Duration.ofMinutes(10));
        int maxChars = s.integer("maxCharsCap", 100000, 1, 100000);
        long maxBody = s.size("maxBodyPartBytes", 4L * 1024 * 1024,
                de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader.MAX_BODY_PART_BYTES);
        int maxFolders = s.integer("maxFolders", 100, 1, 100000);
        int since = s.integer("sinceMaxCandidates", 10000, 1, 10_000_000);
        Section d = s.section("download");
        for (String local : new String[] {"dir", "maxBytes", "displayDir"}) {
            if (d.take(local) != null) {
                throw new ConfigException(d.path(local) + ": not available in http-public (the download is embedded, see inlineMaxBytes)");
            }
        }
        long inlineMax = d.size("inlineMaxBytes", INLINE_DEFAULT, INLINE_MAX);
        int inlineConcurrency = d.integer("inlineConcurrency", 4, 1, 1000);
        d.finish();
        s.finish();
        return new PublicConfig.Tools(deadline, maxChars, maxBody, maxFolders, since, inlineMax, inlineConcurrency);
    }

    // ---- named env overrides (the same names as the single-tenant modes) ----

    private static PublicConfig.Http envHttp(PublicConfig.Http h, Map<String, String> env) {
        String bind = h.bind();
        int port = h.port();
        boolean nonLoopback = h.allowNonLoopbackBind();
        String v = ConfigLoader.set(env, ConfigLoader.ENV_HTTP_BIND);
        if (v != null) {
            bind = v;
        }
        v = ConfigLoader.set(env, ConfigLoader.ENV_HTTP_PORT);
        if (v != null) {
            try {
                port = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                throw new ConfigException(ConfigLoader.ENV_HTTP_PORT + ": must be an integer from 1 to 65535");
            }
            if (port < 1 || port > 65535) {
                throw new ConfigException(ConfigLoader.ENV_HTTP_PORT + ": must be an integer from 1 to 65535");
            }
        }
        v = ConfigLoader.set(env, ConfigLoader.ENV_HTTP_ALLOW_NON_LOOPBACK_BIND);
        if (v != null) {
            if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                throw new ConfigException(ConfigLoader.ENV_HTTP_ALLOW_NON_LOOPBACK_BIND + ": must be true or false");
            }
            nonLoopback = Boolean.parseBoolean(v);
        }
        return new PublicConfig.Http(bind, port, nonLoopback, h.allowedHosts(), h.allowedOrigins(),
                h.maxRequestBytes(), h.stateful(), h.initializeRatePerMinute(), h.initializeRateSet());
    }
}
