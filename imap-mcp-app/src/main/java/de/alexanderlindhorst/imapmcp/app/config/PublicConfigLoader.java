package de.alexanderlindhorst.imapmcp.app.config;

import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader.Section;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
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
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamReadException;
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

    private static final YAMLMapper YAML = YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
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
        } catch (StreamReadException e) {
            String duplicate = duplicateKeyPath(e);
            if (duplicate != null) {
                throw new ConfigException(duplicate + ": duplicate key");
            }
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
        Section oauthSection = top.section("oauth");
        JsonNode upstream = oauthSection.take("upstream");
        PublicConfig.Oauth oauth = oauth(oauthSection);
        PublicConfig.Public pub = pub(top.section("public"));
        AppConfig.Security security = security(top.section("security"));
        PublicConfig.Ssrf ssrf = ssrf(top.section("ssrf"));
        oauth = oauth.withUpstreamProviders(upstreamProviders(new Section(upstream, "oauth.upstream"), pub, ssrf));
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

    private static final Pattern DUPLICATE = Pattern.compile("Duplicate Object property \"([A-Za-z0-9._-]{1,64})\"");

    /** {@code a.b.key} of a duplicated YAML key (a key, never a value), or null if the failure is another one. */
    private static String duplicateKeyPath(StreamReadException e) {
        var m = DUPLICATE.matcher(String.valueOf(e.getMessage()));
        if (!m.find()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        if (e.processor() != null && e.processor().streamReadContext() != null) {
            for (String segment : e.processor().streamReadContext().pathAsPointer().toString().split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                path.append(segment.chars().allMatch(Character::isDigit) ? "[" + segment + "]"
                        : (path.isEmpty() ? "" : ".") + segment);
            }
        }
        // the context already carries the duplicated key as its last segment
        return path.isEmpty() ? "config." + m.group(1) : path.toString();
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

    /** Raw path of {@code oauth.resource} (SPEC 5.3): ASCII path characters, no empty or dot segments, ends in /mcp. */
    private static final Pattern RESOURCE_PATH = Pattern.compile("^(/[A-Za-z0-9._~!$&'()+,=:@-]+)*/mcp$");

    private static boolean hasMcpPath(String rawPath) {
        if (rawPath == null || !RESOURCE_PATH.matcher(rawPath).matches()) {
            return false;
        }
        for (String segment : rawPath.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static PublicConfig.Oauth oauth(Section s) {
        String issuer = httpsUri(s, "issuer");
        String resource = httpsUri(s, "resource");
        URI resourceUri = URI.create(resource);
        if (resourceUri.getRawAuthority().endsWith(":") || !hasMcpPath(resourceUri.getRawPath())) {
            throw new ConfigException(s.path("resource") + ": path must end with /mcp (no trailing slash)");
        }
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

    // ---- oauth.upstream (SPEC 5.7, v0.29) ----

    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9-]{1,32}");
    private static final Pattern GUID = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Pattern IPV4_LIKE = Pattern.compile("[0-9.]+");
    private static final String PORT_RULE = ": must be 993 with implicit-tls or 143 with starttls";
    private static final String GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    private static final String MS_BASE = "https://login.microsoftonline.com/";

    private static List<UpstreamProvider> upstreamProviders(Section s, PublicConfig.Public pub,
            PublicConfig.Ssrf ssrf) {
        Section providers = s.section("providers");
        s.finish();
        // an allowlist entry that is itself malformed fails later, in the wiring, naming ssrf.allowedHosts
        SsrfPolicy hostPolicy = null;
        if (!ssrf.allowedHosts().isEmpty()) {
            try {
                hostPolicy = SsrfPolicy.of(ssrf.allowedHosts(), false, List.of());
            } catch (IllegalArgumentException ignored) {
                // reported by the wiring
            }
        }
        List<UpstreamProvider> out = new ArrayList<>();
        for (String id : providers.keys()) {
            if (!PROVIDER_ID.matcher(id).matches()) {
                throw new ConfigException("oauth.upstream.providers: malformed provider id (must match [a-z0-9-]{1,32})");
            }
            out.add(upstreamProvider(providers.section(id), id, pub, hostPolicy));
        }
        providers.finish();
        return List.copyOf(out);
    }

    private static UpstreamProvider upstreamProvider(Section p, String id, PublicConfig.Public pub,
            SsrfPolicy hostPolicy) {
        String kindName = p.string("kind", null);
        UpstreamProvider.Kind kind = switch (kindName) {
            case "google" -> UpstreamProvider.Kind.GOOGLE;
            case "microsoft" -> UpstreamProvider.Kind.MICROSOFT;
            case "generic" -> UpstreamProvider.Kind.GENERIC;
            default -> throw new ConfigException(p.path("kind") + ": must be one of generic, google, microsoft");
        };
        String clientId = p.string("clientId", null);
        SecretRef clientSecret = p.requiredRef("clientSecret", "env:", "file:");
        String label = p.string("label", id);
        String redirect = pub.baseUrl() + "/accounts/oauth/" + id + "/callback";
        if (kind != UpstreamProvider.Kind.GENERIC) {
            for (String key : new String[] {"authorizationEndpoint", "tokenEndpoint", "scopes", "imap"}) {
                if (p.take(key) != null) {
                    throw new ConfigException(p.path(key) + ": not allowed with kind " + kindName);
                }
            }
        }
        if (kind == UpstreamProvider.Kind.GOOGLE) {
            forbidTenant(p, kindName);
            p.finish();
            requireAllowedHost(p.path("kind"), "imap.gmail.com", hostPolicy);
            return new UpstreamProvider(id, kind, clientId, label, clientSecret, null, GOOGLE_AUTH, GOOGLE_TOKEN,
                    List.of("https://mail.google.com/"), "imap.gmail.com", 993, Security.IMPLICIT_TLS, redirect);
        }
        if (kind == UpstreamProvider.Kind.MICROSOFT) {
            String tenant = p.string("tenant", "organizations");
            if (!Set.of("common", "organizations", "consumers").contains(tenant) && !GUID.matcher(tenant).matches()) {
                throw new ConfigException(p.path("tenant") + ": must be one of common, consumers, organizations "
                        + "or a GUID");
            }
            p.finish();
            requireAllowedHost(p.path("kind"), "outlook.office365.com", hostPolicy);
            return new UpstreamProvider(id, kind, clientId, label, clientSecret, tenant,
                    MS_BASE + tenant + "/oauth2/v2.0/authorize", MS_BASE + tenant + "/oauth2/v2.0/token",
                    List.of("https://outlook.office.com/IMAP.AccessAsUser.All", "offline_access"),
                    "outlook.office365.com", 993, Security.IMPLICIT_TLS, redirect);
        }
        forbidTenant(p, kindName);
        String authEndpoint = httpsEndpoint(p, "authorizationEndpoint");
        String tokenEndpoint = httpsEndpoint(p, "tokenEndpoint");
        String tokenHost = URI.create(tokenEndpoint).getHost();
        if (tokenHost.startsWith("[") || tokenHost.indexOf(':') >= 0 || IPV4_LIKE.matcher(tokenHost).matches()) {
            throw new ConfigException(p.path("tokenEndpoint") + ": host must be a DNS name, not an IP literal");
        }
        List<String> scopes = scopes(p);
        Section imap = p.section("imap");
        if (!p.keys().contains("imap")) {
            throw new ConfigException(p.path("imap") + ": required");
        }
        String host = imap.string("host", null);
        Security security = imap.enumValue("security", Security.IMPLICIT_TLS,
                Map.of("implicit-tls", Security.IMPLICIT_TLS, "starttls", Security.STARTTLS));
        int port = imap.integer("port", security == Security.STARTTLS ? 143 : 993, 1, 65535);
        imap.finish();
        if (port != (security == Security.STARTTLS ? 143 : 993)) {
            throw new ConfigException(imap.path("port") + PORT_RULE);
        }
        host = requireAllowedHost(imap.path("host"), host, hostPolicy);
        p.finish();
        return new UpstreamProvider(id, kind, clientId, label, clientSecret, null, authEndpoint, tokenEndpoint, scopes,
                host, port, security, redirect);
    }

    /**
     * The IMAP host of every kind must match {@code ssrf.allowedHosts} (SPEC 5.7; skipped with
     * {@code allowAnyPublicHost}); returns the normalised host. {@code path} is the config path named on failure.
     */
    private static String requireAllowedHost(String path, String host, SsrfPolicy hostPolicy) {
        if (hostPolicy == null) {
            return host;
        }
        try {
            return hostPolicy.normalizeHost(host);
        } catch (IllegalArgumentException e) { // HostRejectedException; its text may carry the host
            throw new ConfigException(path + ": IMAP host not matched by ssrf.allowedHosts");
        }
    }

    private static void forbidTenant(Section p, String kindName) {
        if (p.take("tenant") != null) {
            throw new ConfigException(p.path("tenant") + ": not allowed with kind " + kindName);
        }
    }

    /** A required {@code https} endpoint with a host, without user info or fragment (a query is allowed). */
    private static String httpsEndpoint(Section s, String key) {
        String v = s.string(key, null);
        try {
            URI u = new URI(v);
            if ("https".equals(u.getScheme()) && u.getHost() != null && u.getRawUserInfo() == null
                    && u.getRawFragment() == null) {
                return v;
            }
        } catch (URISyntaxException ignored) {
            // reported below without the value
        }
        throw new ConfigException(s.path(key) + ": must be an https URI without user info or fragment");
    }

    private static List<String> scopes(Section s) {
        List<String> scopes = s.stringList("scopes", null);
        if (scopes == null) {
            throw new ConfigException(s.path("scopes") + ": required");
        }
        if (scopes.isEmpty()) {
            throw new ConfigException(s.path("scopes") + ": must not be empty");
        }
        for (int i = 0; i < scopes.size(); i++) {
            if (scopes.get(i).isBlank()) {
                throw new ConfigException(s.path("scopes") + "[" + i + "]: must not be blank");
            }
        }
        return scopes;
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
        // D17: the per-tenant cap defaults to min(2, inlineConcurrency); an explicit value may not exceed the replica cap
        boolean perTenantSet = d.take("inlineConcurrencyPerTenant") != null;
        int perTenant = d.integer("inlineConcurrencyPerTenant", Math.min(2, inlineConcurrency), 1, 1000);
        if (perTenantSet && perTenant > inlineConcurrency) {
            throw new ConfigException(d.path("inlineConcurrencyPerTenant") + ": must not exceed "
                    + d.path("inlineConcurrency") + " (" + inlineConcurrency + ")");
        }
        d.finish();
        s.finish();
        return new PublicConfig.Tools(deadline, maxChars, maxBody, maxFolders, since, inlineMax, inlineConcurrency,
                perTenant);
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
