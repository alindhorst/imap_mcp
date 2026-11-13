package de.alexanderlindhorst.imapmcp.app.config;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads the YAML config (SPEC 5.7) into {@link AppConfig}. The YAML is read to a tree and bound by hand so that
 * every error names the key path and no value (Jackson messages may echo the offending value, so none is
 * passed through). Unknown keys are rejected at every level. Secret references are checked syntactically only;
 * resolving them is the startup wiring's job. The file path comes from {@link AppPaths}; nothing else is searched.
 */
public final class ConfigLoader {

    public static final String ENV_MODE = "IMAP_MCP_MODE";
    public static final String ENV_LOG_LEVEL = "IMAP_MCP_LOG_LEVEL";
    public static final String ENV_HTTP_BIND = "IMAP_MCP_HTTP_BIND";
    public static final String ENV_HTTP_PORT = "IMAP_MCP_HTTP_PORT";
    public static final String ENV_HTTP_ALLOW_NON_LOOPBACK_BIND = "IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND";

    /** The only environment variables that override file values (compared against the README by #60). */
    public static final Set<String> ENV_OVERRIDES = Set.of(ENV_MODE, ENV_LOG_LEVEL, ENV_HTTP_BIND, ENV_HTTP_PORT,
            ENV_HTTP_ALLOW_NON_LOOPBACK_BIND);

    private static final Pattern ACCOUNT_ID = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern DURATION = Pattern.compile("(\\d{1,9})(ms|s|m|h)");
    private static final Pattern SIZE = Pattern.compile("(\\d{1,12})(B|KiB|MiB|GiB)");
    private static final Set<String> GMAIL_HOSTS = Set.of("imap.gmail.com", "imap.googlemail.com");
    private static final Set<String> MODES = Set.of("stdio", "http-local");
    static final Set<String> LOG_LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final Duration WRITE_MIN_DEADLINE = Duration.ofSeconds(30);
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private ConfigLoader() {
    }

    public static AppConfig load(Path file, Map<String, String> env) {
        String text;
        try {
            text = Files.readString(file);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new ConfigException("config file not found: " + file);
        } catch (IOException e) {
            throw new ConfigException("config file cannot be read: " + file);
        }
        JsonNode root;
        try {
            root = YAML.readTree(text);
        } catch (JacksonException e) {
            var loc = e.getLocation();
            throw new ConfigException("config: invalid YAML syntax" + (loc == null ? "" : " near line " + loc.getLineNr())
                    + " in " + file);
        }
        return bind(root, env);
    }

    static AppConfig bind(JsonNode root, Map<String, String> env) {
        Section top = new Section(root, "config");
        JsonNode oauth = top.take("oauth");
        if (oauth != null) {
            if (oauth.isObject() && oauth.has("upstream")) {
                throw new ConfigException("oauth.upstream: not available in stdio and http-local "
                        + "(upstream OAuth is http-public only)");
            }
            throw new ConfigException("oauth: unknown key");
        }
        List<AppConfig.Account> accounts = accounts(top);
        AppConfig.Imap imap = imap(top.section("imap"));
        AppConfig.Pool pool = pool(top.section("pool"));
        AppConfig.Tools tools = tools(top.section("tools"));
        AppConfig.Secrets secrets = new AppConfig.Secrets(top.section("secrets").duration("keyringTimeout", Duration.ofSeconds(5)));
        AppConfig.Http http = http(top.section("http"));
        AppConfig.Security security = security(top.section("security"));
        top.finish();
        http = envHttp(http, env);
        AppConfig config = new AppConfig(accounts, imap, pool, tools, secrets, http, security, envMode(env),
                envLogLevel(env));
        if (config.writeToolsUsable() && tools.deadline().compareTo(WRITE_MIN_DEADLINE) < 0) {
            throw new ConfigException("tools.deadline: must be at least 30s while any account is not readOnly");
        }
        return config;
    }

    // ---- sections ----

    private static List<AppConfig.Account> accounts(Section top) {
        JsonNode list = top.take("accounts");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new ConfigException("accounts: must be a non-empty list");
        }
        List<AppConfig.Account> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String p = "accounts[" + i + "]";
            Section s = new Section(list.get(i), p);
            JsonNode auth = s.take("auth");
            if (auth != null) {
                throw new ConfigException(p + ".auth: " + (auth.isString() && "xoauth2".equals(auth.stringValue())
                        ? "xoauth2 is not available in stdio and http-local (upstream OAuth is http-public only)"
                        : "unknown key"));
            }
            String id = s.string("id", null);
            if (!ACCOUNT_ID.matcher(id).matches()) {
                throw new ConfigException(p + ".id: must match [a-z0-9-]{1,32}");
            }
            if (!ids.add(id)) {
                throw new ConfigException(p + ".id: duplicate account id");
            }
            String label = s.string("label", id);
            String address = s.optionalString("address");
            String host = s.string("host", null);
            Security security = s.enumValue("security", Security.IMPLICIT_TLS,
                    Map.of("implicit-tls", Security.IMPLICIT_TLS, "starttls", Security.STARTTLS));
            int port = s.integer("port", security == Security.STARTTLS ? 143 : 993, 1, 65535);
            String flavorKey = s.string("flavor", "auto");
            Flavor flavor = switch (flavorKey) {
                case "auto" -> GMAIL_HOSTS.contains(host.toLowerCase(Locale.ROOT)) ? Flavor.GMAIL : Flavor.GENERIC;
                case "generic" -> Flavor.GENERIC;
                case "gmail" -> Flavor.GMAIL;
                default -> throw new ConfigException(p + ".flavor: must be one of auto, generic, gmail");
            };
            if (flavor == Flavor.GMAIL && security == Security.STARTTLS) {
                throw new ConfigException(p + ".security: Gmail requires implicit-tls");
            }
            String username = s.string("username", null);
            SecretRef password = s.requiredRef("password");
            boolean readOnly = s.bool("readOnly", false);
            boolean allowPurge = s.bool("allowPurge", false);
            if (allowPurge && readOnly) {
                throw new ConfigException(p + ".allowPurge: cannot be true together with readOnly");
            }
            s.finish();
            result.add(new AppConfig.Account(id, label, address, host, port, security, flavor, username, password,
                    readOnly, allowPurge));
        }
        return List.copyOf(result);
    }

    private static AppConfig.Security security(Section s) {
        SecretRef handleKey = s.optionalRef("handleKey");
        List<AppConfig.HandleKey> keys = new ArrayList<>();
        JsonNode list = s.take("handleKeys");
        if (list != null && !list.isArray()) {
            throw new ConfigException(s.path("handleKeys") + ": must be a list");
        }
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; list != null && i < list.size(); i++) {
            Section e = new Section(list.get(i), s.path("handleKeys") + "[" + i + "]");
            int id = e.integer("id", null, 0, 255);
            if (!ids.add(id)) {
                throw new ConfigException(e.path("id") + ": duplicate key id");
            }
            if (id == 1 && handleKey != null) {
                throw new ConfigException(e.path("id") + ": id 1 is already set by security.handleKey");
            }
            SecretRef key = e.requiredRef("key");
            e.finish();
            keys.add(new AppConfig.HandleKey(id, key));
        }
        int signing = s.integer("signingKeyId", 1, 0, 255);
        s.finish();
        return new AppConfig.Security(handleKey, List.copyOf(keys), signing);
    }

    static AppConfig.Imap imap(Section s) {
        var r = new AppConfig.Imap(s.duration("connectTimeout", Duration.ofSeconds(10)),
                s.duration("readTimeout", Duration.ofSeconds(60)), s.duration("writeTimeout", Duration.ofSeconds(60)),
                s.bool("trace", false));
        s.finish();
        return r;
    }

    static AppConfig.Pool pool(Section s) {
        var r = new AppConfig.Pool(s.integer("maxPerMailbox", 3, 1, 1000), s.integer("maxPerTenant", 8, 1, 10000),
                s.integer("maxTotal", 64, 1, 100000), s.duration("idleEviction", Duration.ofMinutes(5)),
                s.duration("validateAfterIdle", Duration.ofSeconds(60)),
                s.duration("maxLifetime", Duration.ofMinutes(30)), s.duration("acquireTimeout", Duration.ofSeconds(10)),
                s.duration("capabilityTtl", Duration.ofMinutes(10)));
        s.finish();
        return r;
    }

    private static AppConfig.Tools tools(Section s) {
        var r = new AppConfig.Tools(s.duration("deadline", Duration.ofSeconds(90), Duration.ofMinutes(10)),
                s.integer("maxCharsCap", 100000, 1, 100000),
                s.size("maxBodyPartBytes", 4L * 1024 * 1024, BodyWindowReader.MAX_BODY_PART_BYTES),
                s.integer("maxFolders", 100, 1, 100000),
                s.integer("sinceMaxCandidates", 10000, 1, 10_000_000), download(s.section("download")));
        s.finish();
        return r;
    }

    private static final long DOWNLOAD_DEFAULT_BYTES = 25L * 1024 * 1024;
    private static final long DOWNLOAD_MAX_BYTES = 100L * 1024 * 1024;

    private static AppConfig.Download download(Section s) {
        Path dir = s.optionalAbsolutePath("dir");
        long maxBytes = s.size("maxBytes", DOWNLOAD_DEFAULT_BYTES, DOWNLOAD_MAX_BYTES);
        Path display = s.optionalAbsolutePath("displayDir");
        s.finish();
        if (dir == null && (display != null || maxBytes != DOWNLOAD_DEFAULT_BYTES)) {
            throw new ConfigException(s.path("dir") + ": required when other tools.download keys are set");
        }
        return new AppConfig.Download(dir, maxBytes, display);
    }

    private static AppConfig.Http http(Section s) {
        var r = new AppConfig.Http(s.nonBlank("bind", "127.0.0.1"), s.integer("port", 8080, 1, 65535),
                s.bool("allowNonLoopbackBind", false), s.optionalRef("token"),
                s.stringList("allowedHosts", List.of("localhost:*", "127.0.0.1:*", "[::1]:*")),
                s.stringList("allowedOrigins", List.of()), s.size("maxRequestBytes", 1024L * 1024, Long.MAX_VALUE),
                s.bool("stateful", false));
        s.finish();
        return r;
    }

    // ---- named env overrides ----

    private static AppConfig.Http envHttp(AppConfig.Http h, Map<String, String> env) {
        String bind = h.bind();
        int port = h.port();
        boolean nonLoopback = h.allowNonLoopbackBind();
        String v = set(env, ENV_HTTP_BIND);
        if (v != null) {
            bind = v;
        }
        v = set(env, ENV_HTTP_PORT);
        if (v != null) {
            try {
                port = Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                throw new ConfigException(ENV_HTTP_PORT + ": must be an integer from 1 to 65535");
            }
            if (port < 1 || port > 65535) {
                throw new ConfigException(ENV_HTTP_PORT + ": must be an integer from 1 to 65535");
            }
        }
        v = set(env, ENV_HTTP_ALLOW_NON_LOOPBACK_BIND);
        if (v != null) {
            if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                throw new ConfigException(ENV_HTTP_ALLOW_NON_LOOPBACK_BIND + ": must be true or false");
            }
            nonLoopback = Boolean.parseBoolean(v);
        }
        return new AppConfig.Http(bind, port, nonLoopback, h.token(), h.allowedHosts(), h.allowedOrigins(),
                h.maxRequestBytes(), h.stateful());
    }

    private static String envMode(Map<String, String> env) {
        String v = set(env, ENV_MODE);
        // exact match, like Main's --mode switch
        if (v != null && !MODES.contains(v)) {
            throw new ConfigException(ENV_MODE + ": must be one of stdio, http-local");
        }
        return v;
    }

    static String envLogLevel(Map<String, String> env) {
        String v = set(env, ENV_LOG_LEVEL);
        if (v != null && !LOG_LEVELS.contains(v.toUpperCase(Locale.ROOT))) {
            throw new ConfigException(ENV_LOG_LEVEL + ": must be one of TRACE, DEBUG, INFO, WARN, ERROR");
        }
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    static String set(Map<String, String> env, String name) {
        String v = env.get(name);
        return v == null || v.isBlank() ? null : v;
    }

    // ---- binding helper ----

    /** A YAML mapping being bound; tracks consumed keys so leftovers are reported as unknown. */
    static final class Section {
        private final JsonNode node;
        private final String path;
        private final Set<String> used = new HashSet<>();

        Section(JsonNode node, String path) {
            this.path = path;
            if (node == null || node.isNull() || node.isMissingNode()) {
                this.node = null;
            } else if (node.isObject()) {
                this.node = node;
            } else {
                throw new ConfigException(path + ": must be a mapping");
            }
        }

        String path(String key) {
            return path.equals("config") ? key : path + "." + key;
        }

        JsonNode take(String key) {
            used.add(key);
            JsonNode n = node == null ? null : node.get(key);
            return n == null || n.isNull() ? null : n;
        }

        Section section(String key) {
            return new Section(take(key), path(key));
        }

        /** The keys present, in file order (for maps keyed by an id). */
        List<String> keys() {
            return node == null ? List.of() : new ArrayList<>(node.propertyNames());
        }

        void finish() {
            if (node == null) {
                return;
            }
            for (String k : node.propertyNames()) {
                if (!used.contains(k)) {
                    throw new ConfigException(path(k) + ": unknown key");
                }
            }
        }

        private String text(String key, JsonNode n) {
            if (!n.isString()) {
                throw new ConfigException(path(key) + ": must be a string");
            }
            return n.stringValue();
        }

        /** Required when def is null. */
        String string(String key, String def) {
            JsonNode n = take(key);
            if (n == null) {
                if (def == null) {
                    throw new ConfigException(path(key) + ": required");
                }
                return def;
            }
            String v = text(key, n);
            if (v.isBlank()) {
                throw new ConfigException(path(key) + ": must not be blank");
            }
            return v;
        }

        String nonBlank(String key, String def) {
            return string(key, def);
        }

        String optionalString(String key) {
            JsonNode n = take(key);
            return n == null ? null : text(key, n);
        }

        List<String> stringList(String key, List<String> def) {
            JsonNode n = take(key);
            if (n == null) {
                return def;
            }
            if (!n.isArray()) {
                throw new ConfigException(path(key) + ": must be a list of strings");
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < n.size(); i++) {
                if (!n.get(i).isString()) {
                    throw new ConfigException(path(key) + "[" + i + "]: must be a string");
                }
                out.add(n.get(i).stringValue());
            }
            return List.copyOf(out);
        }

        boolean bool(String key, boolean def) {
            JsonNode n = take(key);
            if (n == null) {
                return def;
            }
            if (!n.isBoolean()) {
                throw new ConfigException(path(key) + ": must be true or false");
            }
            return n.booleanValue();
        }

        /** A null default makes the key required. */
        int integer(String key, Integer def, int min, int max) {
            JsonNode n = take(key);
            if (n == null) {
                if (def == null) {
                    throw new ConfigException(path(key) + ": required");
                }
                return def;
            }
            if (!n.isIntegralNumber() || !n.canConvertToInt()) {
                throw new ConfigException(path(key) + ": must be an integer from " + min + " to " + max);
            }
            int v = n.intValue();
            if (v < min || v > max) {
                throw new ConfigException(path(key) + ": must be an integer from " + min + " to " + max);
            }
            return v;
        }

        <T> T enumValue(String key, T def, Map<String, T> values) {
            JsonNode n = take(key);
            if (n == null) {
                return def;
            }
            T v = n.isString() ? values.get(n.stringValue()) : null;
            if (v == null) {
                throw new ConfigException(path(key) + ": must be one of " + String.join(", ", new java.util.TreeSet<>(values.keySet())));
            }
            return v;
        }

        Duration duration(String key, Duration def) {
            return duration(key, def, null);
        }

        Duration duration(String key, Duration def, Duration max) {
            JsonNode n = take(key);
            if (n == null) {
                return def;
            }
            Matcher m = n.isString() ? DURATION.matcher(n.stringValue()) : null;
            if (m == null || !m.matches() || Long.parseLong(m.group(1)) == 0) {
                throw new ConfigException(path(key) + ": must be a positive duration like 500ms, 10s, 5m or 1h");
            }
            long v = Long.parseLong(m.group(1));
            Duration d = switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(v);
                case "s" -> Duration.ofSeconds(v);
                case "m" -> Duration.ofMinutes(v);
                default -> Duration.ofHours(v);
            };
            if (max != null && d.compareTo(max) > 0) {
                throw new ConfigException(path(key) + ": must not exceed " + max.toMinutes() + "m");
            }
            return d;
        }

        long size(String key, long def, long max) {
            JsonNode n = take(key);
            if (n == null) {
                return def;
            }
            Matcher m = n.isString() ? SIZE.matcher(n.stringValue()) : null;
            if (m == null || !m.matches() || Long.parseLong(m.group(1)) == 0) {
                throw new ConfigException(path(key) + ": must be a positive size like 512B, 64KiB, 1MiB or 1GiB");
            }
            long v = Long.parseLong(m.group(1));
            long bytes;
            try {
                bytes = Math.multiplyExact(v, switch (m.group(2)) {
                    case "B" -> 1L;
                    case "KiB" -> 1024L;
                    case "MiB" -> 1024L * 1024;
                    default -> 1024L * 1024 * 1024;
                });
            } catch (ArithmeticException e) {
                throw new ConfigException(path(key) + ": size is too large");
            }
            if (bytes > max) {
                throw new ConfigException(path(key) + ": must not exceed " + (max / (1024 * 1024)) + "MiB");
            }
            return bytes;
        }

        /** An absolute path, not normalised and with no {@code ~} expansion; the value is not echoed. */
        Path optionalAbsolutePath(String key) {
            JsonNode n = take(key);
            if (n == null) {
                return null;
            }
            String v = text(key, n);
            try {
                Path p = v.isBlank() ? null : Path.of(v);
                if (p != null && p.isAbsolute()) {
                    return p;
                }
            } catch (java.nio.file.InvalidPathException ignored) {
                // reported below without the value
            }
            throw new ConfigException(path(key) + ": must be an absolute path (~ is not expanded)");
        }

        SecretRef requiredRef(String key) {
            SecretRef r = optionalRef(key);
            if (r == null) {
                throw new ConfigException(path(key) + ": required");
            }
            return r;
        }

        /** Like {@link #requiredRef(String)}, but only the given prefixes (each with a colon) are references. */
        SecretRef requiredRef(String key, String... prefixes) {
            SecretRef r = optionalRef(key, prefixes);
            if (r == null) {
                throw new ConfigException(path(key) + ": required");
            }
            return r;
        }

        /** Syntactic check only: the value is never echoed, and it is not resolved here. */
        SecretRef optionalRef(String key) {
            return optionalRef(key, "env:", "file:", "keyring:");
        }

        private SecretRef optionalRef(String key, String... prefixes) {
            JsonNode n = take(key);
            if (n == null) {
                return null;
            }
            String v = n.isString() ? n.stringValue() : "";
            boolean ok = false;
            for (String prefix : prefixes) {
                if (v.startsWith(prefix) && v.length() > prefix.length()) {
                    ok = true;
                }
            }
            if (!ok) {
                throw new ConfigException(path(key) + ": must be a secret reference ("
                        + String.join(", ", java.util.Arrays.stream(prefixes).toList()).replaceAll(", ([^,]*)$", " or $1")
                        + ")");
            }
            return new SecretRef(v);
        }
    }
}
