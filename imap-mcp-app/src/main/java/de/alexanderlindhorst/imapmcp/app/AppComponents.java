package de.alexanderlindhorst.imapmcp.app;

import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
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
import de.alexanderlindhorst.imapmcp.core.search.SearchCursorCodec;
import de.alexanderlindhorst.imapmcp.core.search.SearchTermMapper;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolExecutor;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.GetMessageTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListAccountsTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListAttachmentsTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListFoldersTool;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import de.alexanderlindhorst.imapmcp.core.tools.SearchMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.CopyMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.CreateFolderTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.DeleteMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.FolderGroupRunner;
import de.alexanderlindhorst.imapmcp.core.tools.write.MoveMessagesTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.SetFlagsTool;
import de.alexanderlindhorst.imapmcp.core.tools.write.WriteCallLog;
import de.alexanderlindhorst.imapmcp.core.tools.write.WritePreflight;
import de.alexanderlindhorst.imapmcp.store.file.ConfiguredAccountRepository;
import de.alexanderlindhorst.imapmcp.store.secret.BinaryProbe;
import de.alexanderlindhorst.imapmcp.store.secret.CommandRunner;
import de.alexanderlindhorst.imapmcp.store.secret.Platform;
import de.alexanderlindhorst.imapmcp.store.secret.ProcessCommandRunner;
import de.alexanderlindhorst.imapmcp.store.secret.RefCredentialStore;
import de.alexanderlindhorst.imapmcp.store.secret.SecretResolutionException;
import de.alexanderlindhorst.imapmcp.store.state.StateDirectory;
import de.alexanderlindhorst.imapmcp.store.state.StateDirectoryException;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/**
 * Manual wiring (SPEC 5.1) of config, secret store, pool, metrics and the tools: the five v1 tools and, unless every account is read-only
 * ({@link AppConfig#writeToolsUsable()}), the five v2 write tools. Transport-agnostic: it
 * never starts a server and never references the HTTP transport; the HTTP launcher passes its account resolver to
 * {@link #statelessToolSpecs}. Single-tenant modes use the fixed tenant {@link #TENANT}, but every component is
 * built against {@link AccountContext} and the repository seam.
 */
public final class AppComponents implements AutoCloseable {

    /** The tenant of all single-tenant modes. */
    public static final TenantId TENANT = new TenantId("local");

    private static final AccountContext LOCAL = new AccountContext(TENANT);
    private static final int LIST_ACCOUNTS_PARALLELISM = 4;

    private final ImapSessionFactory sessions;
    private final StorePool pool;
    private final Admission admission;
    private final InMemoryMetricsRecorder metrics;
    private final ToolExecutor executor;
    private final StateDirectory state;
    private final Map<ToolDefinition, ToolHandler> tools;
    private boolean closed;

    private AppComponents(ImapSessionFactory sessions, StorePool pool, Admission admission,
            InMemoryMetricsRecorder metrics, ToolExecutor executor, StateDirectory state, Map<ToolDefinition, ToolHandler> tools) {
        this.sessions = sessions;
        this.pool = pool;
        this.admission = admission;
        this.metrics = metrics;
        this.executor = executor;
        this.state = state;
        this.tools = tools;
    }

    public static AppComponents create(AppConfig config, AppPaths paths, Clock clock, Map<String, String> env) {
        return create(config, paths, clock, env, p -> p, Platform.current(), new ProcessCommandRunner(),
                BinaryProbe.system(env));
    }

    /** Test seam: JavaMail property hook (GreenMail trust) and keyring fakes. Not reachable from config. */
    static AppComponents create(AppConfig config, AppPaths paths, Clock clock, Map<String, String> env,
            UnaryOperator<Properties> sessionHook, Platform platform, CommandRunner runner, BinaryProbe probe) {
        List<MailAccount> accounts = config.accounts().stream().map(AppConfig.Account::toMailAccount).toList();

        RefCredentialStore credentials = RefCredentialStore.withEnvAndKeyring(TENANT, accounts, env, platform,
                runner, probe, config.secrets().keyringTimeout());
        try {
            credentials.validateAll();
        } catch (SecretResolutionException e) {
            throw new AppStartupException("Invalid secret reference in " + paths.configFile() + ": "
                    + e.getMessage(), e);
        }
        char[] handleKey = resolveChars(credentials, config.security().handleKey(), "security.handleKey");
        char[] token = resolveChars(credentials, config.http().token(), "http.token");
        StateDirectory state = new StateDirectory(paths.stateDir(), token, handleKey);
        wipe(handleKey);
        wipe(token);

        byte[] salt;
        KeyRing keys;
        try {
            keys = new KeyRing(1, Map.of(1, state.handleKey()));
            salt = state.installationSalt();
        } catch (StateDirectoryException | IllegalArgumentException e) {
            throw new AppStartupException("Cannot set up the key material under " + paths.stateDir() + ": "
                    + e.getMessage(), e);
        }
        LogIds logIds = new LogIds(keys);
        AccountRepository repository = new ConfiguredAccountRepository(TENANT, accounts, salt);
        Map<String, MailAccount> byId = accounts.stream()
                .collect(Collectors.toMap(a -> a.id().value(), a -> a));

        AppConfig.Imap imap = config.imap();
        AppConfig.Pool p = config.pool();
        PoolSettings settings = new PoolSettings(p.maxPerMailbox(), p.maxPerTenant(), p.maxTotal(), p.idleEviction(),
                p.validateAfterIdle(), p.maxLifetime(), p.acquireTimeout(), PoolSettings.DEFAULTS.evictionInterval(),
                abandonedTimeout(config.tools().deadline()));

        ImapSessionFactory sessions = new ImapSessionFactory(
                new ImapTimeouts(imap.connectTimeout(), imap.readTimeout(), imap.writeTimeout()), sessionHook,
                imap.trace());
        StorePool pool = null;
        InMemoryMetricsRecorder metrics = null;
        try {
            CapabilityCache capabilities = new CapabilityCache(p.capabilityTtl(), clock);
            pool = new StorePool(sessions, credentials, capabilities,
                    key -> TENANT.equals(key.tenantId()) ? byId.get(key.accountId().value()) : null, settings);
            metrics = new InMemoryMetricsRecorder(logIds, clock);
            metrics.attachPool(pool);
            Admission admission = new Admission(settings, Admission.recording(metrics));
            StoreLeases leases = new StoreLeases(pool, settings.acquireTimeout(), admission, metrics);
            AppConfig.Tools t = config.tools();
            ToolExecutor executor = new ToolExecutor(t.deadline(), logIds, metrics, AppComponents::logExecutorOutcome);

            AccountArgument account = new AccountArgument(repository);
            MessageLocator locator = new MessageLocator(new MessageHandles(keys));
            Map<ToolDefinition, ToolHandler> tools = new LinkedHashMap<>();
            tools.put(ListAccountsTool.DEFINITION,
                    new ListAccountsTool(repository, capabilities, leases, LIST_ACCOUNTS_PARALLELISM));
            tools.put(ListFoldersTool.DEFINITION, new ListFoldersTool(account, leases, t.maxFolders()));
            tools.put(SearchMessagesTool.DEFINITION, new SearchMessagesTool(account, leases, new SearchTermMapper(),
                    new SearchCursorCodec(keys), new MessageHandles(keys), clock, SearchMessagesTool.SINCE_OVERLAP,
                    t.sinceMaxCandidates(), capabilities));
            tools.put(GetMessageTool.definition(t.maxCharsCap()), new GetMessageTool(account, locator, leases,
                    new BodyWindowReader(t.maxBodyPartBytes()), t.maxCharsCap()));
            tools.put(ListAttachmentsTool.DEFINITION, new ListAttachmentsTool(account, locator, leases));

            boolean writeTools = config.writeToolsUsable();
            long readOnlyAccounts = config.accounts().stream().filter(AppConfig.Account::readOnly).count();
            LoggerFactory.getLogger("imapmcp.app").info("write tools registered={}, read-only accounts={} of {}",
                    writeTools, readOnlyAccounts, config.accounts().size());
            if (writeTools) {
                WritePreflight preflight = new WritePreflight(account, locator);
                MessageHandles handles = new MessageHandles(keys);
                tools.put(SetFlagsTool.DEFINITION, new SetFlagsTool(account, locator, leases, clock));
                tools.put(MoveMessagesTool.DEFINITION, new MoveMessagesTool(preflight, leases, handles, clock));
                tools.put(CopyMessagesTool.DEFINITION,
                        new CopyMessagesTool(preflight, leases, handles, new FolderGroupRunner(clock)));
                tools.put(DeleteMessagesTool.DEFINITION, new DeleteMessagesTool(preflight, leases, handles, clock));
                tools.put(CreateFolderTool.DEFINITION, new CreateFolderTool(account, leases));
            }

            metrics.start();
            return new AppComponents(sessions, pool, admission, metrics, executor, state,
                    java.util.Collections.unmodifiableMap(tools));
        } catch (RuntimeException e) {
            if (metrics != null) {
                metrics.close();
            }
            if (pool != null) {
                pool.close();
            }
            sessions.close();
            throw e;
        }
    }

    /**
     * The write-call INFO line (SPEC 5.6) for an outcome the executor produced (deadline, cancel, unchecked
     * failure): the handler never got to log it. The executor binds the MDC (req, tool, tenant, acct) around it.
     */
    private static void logExecutorOutcome(String tool, String requestId,
            de.alexanderlindhorst.imapmcp.core.error.ErrorCode code, Duration duration) {
        WriteCallLog.error(tool, code, duration);
    }

    private static char[] resolveChars(RefCredentialStore store, SecretRef ref, String key) {
        if (ref == null) {
            return null;
        }
        try (Secret s = store.resolve(ref)) {
            return s.chars().clone();
        } catch (SecretResolutionException e) {
            throw new AppStartupException("Cannot resolve " + key + ": " + e.getMessage(), e);
        }
    }

    private static void wipe(char[] c) {
        if (c != null) {
            java.util.Arrays.fill(c, '\0');
        }
    }

    public AccountContext accountContext() {
        return LOCAL;
    }

    /** Stdio flavour: there is no principal, so every call runs as the fixed local tenant. */
    public List<McpServerFeatures.SyncToolSpecification> statefulToolSpecs() {
        McpToolAdapter adapter = adapter(ctx -> LOCAL);
        return tools.entrySet().stream().map(e -> adapter.stateful(e.getKey(), e.getValue())).toList();
    }

    /** HTTP flavour: the launcher supplies the resolver (principal to {@link AccountContext}). */
    public List<McpStatelessServerFeatures.SyncToolSpecification> statelessToolSpecs(
            Function<McpTransportContext, AccountContext> resolver) {
        McpToolAdapter adapter = adapter(resolver);
        return tools.entrySet().stream().map(e -> adapter.stateless(e.getKey(), e.getValue())).toList();
    }

    private McpToolAdapter adapter(Function<McpTransportContext, AccountContext> resolver) {
        return new McpToolAdapter(resolver, McpJsonDefaults.getMapper(), () -> UUID.randomUUID().toString(),
                executor);
    }

    /** The bearer token for http-local: the configured {@code http.token}, else the persisted one. */
    public char[] httpToken() {
        return state.bearerToken();
    }

    ToolExecutor executor() {
        return executor;
    }

    List<ToolDefinition> definitions() {
        return List.copyOf(tools.keySet());
    }

    StorePool pool() {
        return pool;
    }

    Admission admission() {
        return admission;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        metrics.close();
        pool.close();
        sessions.close();
    }

    /** A leased Store must outlive the longest tool call, or the evictor would destroy it mid-use. */
    static Duration abandonedTimeout(Duration toolDeadline) {
        Duration derived = toolDeadline.plusSeconds(30);
        return derived.compareTo(PoolSettings.DEFAULTS.abandonedTimeout()) > 0
                ? derived : PoolSettings.DEFAULTS.abandonedTimeout();
    }
}
