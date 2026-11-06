package de.alexanderlindhorst.imapmcp.app;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.crypto.Macs;
import de.alexanderlindhorst.imapmcp.core.download.InlineLimiter;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.Admission;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.mcp.McpToolAdapter;
import de.alexanderlindhorst.imapmcp.core.metrics.InMemoryMetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader;
import de.alexanderlindhorst.imapmcp.core.net.PinnedSocketFactory;
import de.alexanderlindhorst.imapmcp.core.net.SsrfPolicy;
import de.alexanderlindhorst.imapmcp.core.search.SearchCursorCodec;
import de.alexanderlindhorst.imapmcp.core.search.SearchTermMapper;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolExecutor;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.DownloadAttachmentTool;
import de.alexanderlindhorst.imapmcp.core.tools.GetMessageTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListAccountsTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListAttachmentsTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListFoldersTool;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import de.alexanderlindhorst.imapmcp.core.tools.SearchMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.CopyMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.CreateFolderTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.DeleteMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.ElicitingPurgeConfirmation;
import de.alexanderlindhorst.imapmcp.core.tools.write.PurgeMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.FolderGroupRunner;
import de.alexanderlindhorst.imapmcp.core.tools.write.MoveMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.SetFlagsTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.WritePreflight;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import de.alexanderlindhorst.imapmcp.store.crypto.KeyServiceUnavailableException;
import de.alexanderlindhorst.imapmcp.store.crypto.KeyWrapper;
import de.alexanderlindhorst.imapmcp.store.crypto.LocalKeyWrapper;
import de.alexanderlindhorst.imapmcp.store.crypto.VaultTransitKeyWrapper;
import de.alexanderlindhorst.imapmcp.store.jdbc.AuditRetention;
import de.alexanderlindhorst.imapmcp.store.jdbc.Database;
import de.alexanderlindhorst.imapmcp.store.jdbc.DbConfig;
import de.alexanderlindhorst.imapmcp.store.jdbc.ExpiredRowCleanup;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAccountRepository;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcAuditLog;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcCredentialStore;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcLoginOutcomes;
import de.alexanderlindhorst.imapmcp.store.jdbc.JdbcTenantDirectory;
import de.alexanderlindhorst.imapmcp.store.secret.BinaryProbe;
import de.alexanderlindhorst.imapmcp.store.secret.CommandRunner;
import de.alexanderlindhorst.imapmcp.store.secret.Platform;
import de.alexanderlindhorst.imapmcp.store.secret.ProcessCommandRunner;
import de.alexanderlindhorst.imapmcp.store.secret.RefCredentialStore;
import de.alexanderlindhorst.imapmcp.store.secret.SecretResolutionException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual wiring of {@code http-public} (SPEC 5.1, 5.3, 5.7): the multi-tenant counterpart of {@link AppComponents}.
 * Accounts, credentials, tenants and the audit log are rows of the database; the key ring and the key-encryption keys
 * come only from explicit configuration (no state file, no ephemeral fallback); the IMAP sessions use the pinning
 * socket factory of the SSRF policy. One fixed tool list is registered; restrictions are call-time refusals
 * ({@code account_read_only}, {@code insufficient_scope}, ...). Transport-agnostic: it never starts a server and never
 * references the HTTP transport (see {@code HttpPublicServer}).
 */
public final class PublicComponents implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("imapmcp.app");
    private static final byte[] FINGERPRINT_LABEL = "imap-mcp:key-fingerprint:v1".getBytes(StandardCharsets.US_ASCII);
    private static final TenantId CONFIG_OWNER = new TenantId("config");
    private static final Duration CLEANUP_PERIOD = Duration.ofMinutes(10);

    private final Database database;
    private final ImapSessionFactory sessions;
    private final StorePool pool;
    private final InMemoryMetricsRecorder metrics;
    private final ToolExecutor executor;
    private final JdbcTenantDirectory tenants;
    private final Map<ToolDefinition, ToolHandler> tools;
    private final ScheduledExecutorService scheduler;
    private final String linkUrl;
    private final KeyRing keys;
    private final JdbcCredentialStore credentials;
    private final ElicitingPurgeConfirmation purgeConfirmation; // null unless purge_messages is registered
    private boolean closed;

    private PublicComponents(Database database, ImapSessionFactory sessions, StorePool pool,
            InMemoryMetricsRecorder metrics, ToolExecutor executor, JdbcTenantDirectory tenants,
            Map<ToolDefinition, ToolHandler> tools, ScheduledExecutorService scheduler, String linkUrl,
            KeyRing keys, JdbcCredentialStore credentials, ElicitingPurgeConfirmation purgeConfirmation) {
        this.credentials = credentials;
        this.purgeConfirmation = purgeConfirmation;
        this.database = database;
        this.sessions = sessions;
        this.pool = pool;
        this.metrics = metrics;
        this.executor = executor;
        this.tenants = tenants;
        this.tools = tools;
        this.scheduler = scheduler;
        this.linkUrl = linkUrl;
        this.keys = keys;
    }

    public static PublicComponents create(PublicConfig config, AppPaths paths, Clock clock, Map<String, String> env) {
        return create(config, paths, clock, env, p -> p, Platform.current(), new ProcessCommandRunner(),
                BinaryProbe.system(env));
    }

    /**
     * Test seam for integration tests against a local IMAP server: a JavaMail property hook (trust) and the factory of
     * the socket factory (so that a test can point the SSRF-checked connect at a loopback server). Never reachable from
     * configuration; production code uses {@link #create(PublicConfig, AppPaths, Clock, Map)}.
     */
    public static PublicComponents createForTest(PublicConfig config, AppPaths paths, Clock clock,
            Map<String, String> env, UnaryOperator<Properties> sessionHook,
            Function<SsrfPolicy, PinnedSocketFactory> socketFactory) {
        return create(config, paths, clock, env, sessionHook, Platform.current(), new ProcessCommandRunner(),
                BinaryProbe.system(env), socketFactory);
    }

    static PublicComponents create(PublicConfig config, AppPaths paths, Clock clock, Map<String, String> env,
            UnaryOperator<Properties> sessionHook, Platform platform, CommandRunner runner, BinaryProbe probe) {
        return create(config, paths, clock, env, sessionHook, platform, runner, probe, PinnedSocketFactory::new);
    }

    private static PublicComponents create(PublicConfig config, AppPaths paths, Clock clock, Map<String, String> env,
            UnaryOperator<Properties> sessionHook, Platform platform, CommandRunner runner, BinaryProbe probe,
            Function<SsrfPolicy, PinnedSocketFactory> socketFactory) {
        if (config.oauth().allowWithoutRole()) {
            LOG.warn("oauth.allowWithoutRole is true: any user the identity provider admits gets a tenant at the first "
                    + "login of the linking surface. Development only.");
        }
        RefCredentialStore refs = RefCredentialStore.withEnvAndKeyring(CONFIG_OWNER, List.of(), env, platform, runner,
                probe, config.secrets().keyringTimeout());
        KeyRing keys = buildKeyRing(config.security(), refs, paths);
        LogIds logIds = new LogIds(keys);
        // the linking surface's web client secret must resolve at startup (fail fast); it is read again per use
        AppComponents.wipe(AppComponents.resolveChars(refs, config.oauth().webClientSecret(), "oauth.webClientSecret",
                paths));
        KeyWrapper wrapper = buildKeyWrapper(config.kms(), refs, clock, paths);
        SsrfPolicy ssrf;
        try {
            ssrf = SsrfPolicy.of(config.ssrf().allowedHosts(), config.ssrf().allowAnyPublicHost(),
                    config.ssrf().extraBlockedCidrs());
        } catch (IllegalArgumentException e) {
            throw new AppStartupException("ssrf.* in " + paths.configFile() + ": " + e.getMessage(), e);
        }
        Database database = openDatabase(config.db(), refs, paths);
        ImapSessionFactory sessions = null;
        StorePool pool = null;
        InMemoryMetricsRecorder metrics = null;
        ScheduledExecutorService scheduler = null;
        try {
            AppConfig.Imap imap = config.imap();
            AppConfig.Pool p = config.pool();
            PoolSettings settings = new PoolSettings(p.maxPerMailbox(), p.maxPerTenant(), p.maxTotal(),
                    p.idleEviction(), p.validateAfterIdle(), p.maxLifetime(), p.acquireTimeout(),
                    PoolSettings.DEFAULTS.evictionInterval(), AppComponents.abandonedTimeout(config.tools().deadline()));
            sessions = new ImapSessionFactory(
                    new ImapTimeouts(imap.connectTimeout(), imap.readTimeout(), imap.writeTimeout()), sessionHook,
                    imap.trace(), socketFactory.apply(ssrf));
            var ds = database.dataSource();
            JdbcAccountRepository repository = new JdbcAccountRepository(ds);
            metrics = new InMemoryMetricsRecorder(logIds, clock);
            JdbcCredentialStore credentials = new JdbcCredentialStore(ds,
                    new CredentialCipher(wrapper, new SecureRandom()), logIds, metrics);
            CapabilityCache capabilities = new CapabilityCache(p.capabilityTtl(), clock);
            pool = new StorePool(sessions, credentials, capabilities, repository, settings, metrics,
                    new JdbcLoginOutcomes(repository));
            metrics.attachPool(pool);
            Admission admission = new Admission(settings, Admission.recording(metrics));
            StoreLeases leases = new StoreLeases(pool, settings.acquireTimeout(), admission, metrics);
            PublicConfig.Tools t = config.tools();
            ToolExecutor executor = new ToolExecutor(t.deadline(), logIds, metrics, AppComponents::logExecutorOutcome,
                    new JdbcAuditLog(ds, keys));

            String linkUrl = config.pub().baseUrl() + "/accounts";
            AccountArgument account = new AccountArgument(repository, linkUrl);
            MessageHandles handles = new MessageHandles(keys);
            MessageLocator locator = new MessageLocator(handles);
            WritePreflight preflight = new WritePreflight(account, locator);
            Map<ToolDefinition, ToolHandler> tools = new LinkedHashMap<>();
            tools.put(ListAccountsTool.DEFINITION, new ListAccountsTool(repository, capabilities, leases,
                    AppComponents.LIST_ACCOUNTS_PARALLELISM, linkUrl));
            tools.put(ListFoldersTool.DEFINITION, new ListFoldersTool(account, leases, t.maxFolders()));
            tools.put(SearchMessagesTool.DEFINITION, new SearchMessagesTool(account, leases, new SearchTermMapper(),
                    new SearchCursorCodec(keys), handles, clock, SearchMessagesTool.SINCE_OVERLAP,
                    t.sinceMaxCandidates(), capabilities));
            tools.put(GetMessageTool.definition(t.maxCharsCap()), new GetMessageTool(account, locator, leases,
                    new BodyWindowReader(t.maxBodyPartBytes()), t.maxCharsCap()));
            tools.put(ListAttachmentsTool.DEFINITION, new ListAttachmentsTool(account, locator, leases));
            // one limiter per replica (not per session or call): at most inlineConcurrency downloads run at once
            tools.put(DownloadAttachmentTool.EMBEDDED_DEFINITION, DownloadAttachmentTool.embedded(account, locator,
                    leases, new DownloadAttachmentTool.InlineSettings(t.inlineMaxBytes()),
                    new InlineLimiter(t.inlineConcurrency())));
            // the five write tools are always registered; each call is gated by the account (read_only) and the token
            tools.put(SetFlagsTool.DEFINITION, new SetFlagsTool(account, locator, leases, clock));
            tools.put(MoveMessagesTool.DEFINITION, new MoveMessagesTool(preflight, leases, handles, clock));
            tools.put(CopyMessagesTool.DEFINITION,
                    new CopyMessagesTool(preflight, leases, handles, new FolderGroupRunner(clock)));
            tools.put(DeleteMessagesTool.DEFINITION, new DeleteMessagesTool(preflight, leases, handles, clock));
            tools.put(CreateFolderTool.DEFINITION, new CreateFolderTool(account, leases));
            // purge_messages (D8): only with purge.enabled over the stateful transport, whose server-to-client requests
            // carry the confirmation; the deployment switch also goes into the tool as its call-time gate
            boolean purge = purgeRegistered(config);
            ElicitingPurgeConfirmation purgeConfirmation = null;
            if (purge) {
                purgeConfirmation = new ElicitingPurgeConfirmation(clock);
                // not registering the tool is the gate; passing the switch on is defence in depth at call time
                tools.put(PurgeMessagesTool.DEFINITION, new PurgeMessagesTool(preflight, leases, clock,
                        purgeConfirmation, config.purgeEnabled()));
            }
            LOG.info("http-public tools registered={}, purge_messages registered={}, download_attachment "
                    + "registered=true (embedded)", tools.size(), purge);

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "imap-mcp-cleanup");
                th.setDaemon(true);
                return th;
            });
            new AuditRetention(ds).schedule(scheduler); // 180 days, daily (D5)
            new ExpiredRowCleanup(ds).schedule(scheduler, CLEANUP_PERIOD);
            metrics.start();
            return new PublicComponents(database, sessions, pool, metrics, executor, new JdbcTenantDirectory(ds),
                    Collections.unmodifiableMap(tools), scheduler, linkUrl, keys, credentials, purgeConfirmation);
        } catch (RuntimeException e) {
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            if (metrics != null) {
                metrics.close();
            }
            if (pool != null) {
                pool.close();
            }
            if (sessions != null) {
                sessions.close();
            }
            database.close();
            throw e;
        }
    }

    static DbConfig dbConfig(PublicConfig.Db db, char[] password) {
        return new DbConfig(db.url(), db.username(), password, db.maxPoolSize());
    }

    private static Database openDatabase(PublicConfig.Db db, RefCredentialStore refs, AppPaths paths) {
        char[] password = AppComponents.resolveChars(refs, db.password(), "db.password", paths);
        try {
            return Database.open(dbConfig(db, password.clone()));
        } catch (RuntimeException e) {
            // the driver or Flyway message may carry the host, user name or SQL; only the class is reported
            throw new AppStartupException("Cannot open the database (db.* in " + paths.configFile() + "): "
                    + e.getClass().getSimpleName(), e);
        } finally {
            AppComponents.wipe(password);
        }
    }

    /** SPEC 5.7: an explicit key ring only; startup logs each key id with a fingerprint, never key material. */
    private static KeyRing buildKeyRing(AppConfig.Security sec, RefCredentialStore refs, AppPaths paths) {
        Map<Integer, byte[]> masters = new TreeMap<>();
        Map<Integer, String> origin = new java.util.HashMap<>();
        for (int i = 0; i < sec.handleKeys().size(); i++) {
            AppConfig.HandleKey k = sec.handleKeys().get(i);
            String path = "security.handleKeys[" + i + "].key";
            char[] chars = AppComponents.resolveChars(refs, k.key(), path, paths);
            try {
                AppComponents.register(masters, origin, k.id(), path,
                        AppComponents.decodeKey(chars, path, paths), paths);
            } finally {
                AppComponents.wipe(chars);
            }
        }
        masters.forEach((id, key) -> LOG.info("handle key id={} fingerprint={}{}", id, fingerprint(key),
                id == sec.signingKeyId() ? " (signing)" : ""));
        try {
            return new KeyRing(sec.signingKeyId(), masters); // the ring keeps its own copies
        } finally {
            masters.values().forEach(k -> Arrays.fill(k, (byte) 0));
        }
    }

    /** SPEC 5.7 (D6): the configured provider; the Vault key is checked before anything else starts. */
    private static KeyWrapper buildKeyWrapper(PublicConfig.Kms kms, RefCredentialStore refs, Clock clock,
            AppPaths paths) {
        if (!"vault".equals(kms.provider())) {
            return buildLocalKeyWrapper(kms, refs, paths);
        }
        if (kms.vault().allowInsecureHttp() && "http".equals(kms.vault().address().getScheme())) {
            LOG.warn("kms.vault.allowInsecureHttp is true: the Vault token and the wrapped keys travel unencrypted. "
                    + "For tests only.");
        }
        VaultTransitKeyWrapper wrapper;
        try {
            wrapper = new VaultTransitKeyWrapper(kms.vault(), refs::resolve, clock);
        } catch (IllegalArgumentException e) { // the CA bundle (fixed text without a path or content)
            throw new AppStartupException("kms.vault.caCert in " + paths.configFile()
                    + ": cannot be read as a PEM certificate bundle", e);
        }
        try {
            wrapper.checkKey();
        } catch (IllegalStateException e) { // missing key or wrong type: fixed texts
            throw new AppStartupException("kms.vault.keyName in " + paths.configFile() + ": " + e.getMessage(), e);
        } catch (KeyServiceUnavailableException e) { // the failure class only: no response text, no token
            throw new AppStartupException("kms.vault in " + paths.configFile() + ": Vault is not available ("
                    + e.failureClass() + ")", e);
        }
        LOG.info("Vault Transit key '{}' checked (aes256-gcm96)", kms.vault().keyName());
        return wrapper;
    }

    /** The {@code local} provider (D6): every KEK is resolved once; a WARN is logged; fingerprints per id. */
    private static LocalKeyWrapper buildLocalKeyWrapper(PublicConfig.Kms kms, RefCredentialStore refs, AppPaths paths) {
        Map<String, byte[]> keks = new LinkedHashMap<>();
        try {
            for (int i = 0; i < kms.keks().size(); i++) {
                PublicConfig.Kek k = kms.keks().get(i);
                String path = "kms.local.keks[" + i + "].key";
                char[] chars = AppComponents.resolveChars(refs, k.key(), path, paths);
                try {
                    byte[] key = AppComponents.decodeKey(chars, path, paths);
                    for (var e : keks.entrySet()) {
                        if (Arrays.equals(e.getValue(), key)) {
                            Arrays.fill(key, (byte) 0);
                            throw new AppStartupException(path + " in " + paths.configFile()
                                    + ": same key as kms.local.keks '" + e.getKey() + "'");
                        }
                    }
                    keks.put(k.id(), key);
                } finally {
                    AppComponents.wipe(chars);
                }
            }
            keks.forEach((id, key) -> LOG.info("local KEK id={} fingerprint={}{}", id, fingerprint(key),
                    id.equals(kms.currentKekId()) ? " (current)" : ""));
            LOG.warn("Credentials are wrapped with a local KEK read from a reference. Use it for self-hosting and"
                    + " tests; a KMS or Vault Transit is the production choice.");
            return new LocalKeyWrapper(kms.currentKekId(), keks);
        } catch (SecretResolutionException e) {
            throw new AppStartupException("Cannot resolve kms.local.keks in " + paths.configFile() + ": "
                    + e.getMessage(), e);
        } finally {
            keks.values().forEach(k -> Arrays.fill(k, (byte) 0));
        }
    }

    /** 12 hex characters of HMAC-SHA256 under the key over a fixed label (so replicas can compare keys). */
    static String fingerprint(byte[] key) {
        return HexFormat.of().formatHex(Macs.hmac(key, FINGERPRINT_LABEL)).substring(0, 12);
    }

    /** The tenant lookup used by the HTTP layer; a read-only view of the tenant directory (no creation). */
    public JdbcTenantDirectory tenantDirectory() {
        return tenants;
    }

    /** The linking surface's account page; the {@code link_url} of tool output and re-link errors. */
    public String linkUrl() {
        return linkUrl;
    }

    /** The encrypted credential rows (the linking surface writes them; tests seed them). */
    public JdbcCredentialStore credentials() {
        return credentials;
    }

    public KeyRing keyRing() {
        return keys;
    }

    /** HTTP flavour: the launcher supplies the resolver (principal to {@link AccountContext}). */
    public List<McpStatelessServerFeatures.SyncToolSpecification> statelessToolSpecs(
            Function<McpTransportContext, AccountContext> resolver) {
        McpToolAdapter adapter = new McpToolAdapter(resolver, McpJsonDefaults.getMapper(),
                () -> UUID.randomUUID().toString(), executor);
        return tools.entrySet().stream().map(e -> adapter.stateless(e.getKey(), e.getValue())).toList();
    }

    /** D8: {@code purge_messages} exists only with {@code purge.enabled} over the stateful transport. */
    static boolean purgeRegistered(PublicConfig config) {
        return config.purgeEnabled() && config.http().stateful();
    }

    /** Stateful HTTP flavour (needed for the purge confirmation); the resolver maps the principal to its tenant. */
    public List<McpServerFeatures.SyncToolSpecification> statefulToolSpecs(
            Function<McpTransportContext, AccountContext> resolver) {
        McpToolAdapter adapter = new McpToolAdapter(resolver, McpJsonDefaults.getMapper(),
                () -> UUID.randomUUID().toString(), executor);
        return tools.entrySet().stream().map(e -> adapter.stateful(e.getKey(), e.getValue())).toList();
    }

    /**
     * Session-end callback of the stateful transport: drops the purge approvals of the ended session (SPEC 4, v3b).
     * Never null; does nothing when {@code purge_messages} is not registered.
     */
    public Consumer<String> sessionEndListener() {
        ElicitingPurgeConfirmation c = purgeConfirmation;
        return c == null ? id -> { } : c::sessionEnded;
    }

    List<ToolDefinition> definitions() {
        return List.copyOf(tools.keySet());
    }

    public Database database() {
        return database;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        scheduler.shutdownNow();
        metrics.close();
        pool.close();
        sessions.close();
        database.close();
    }
}
