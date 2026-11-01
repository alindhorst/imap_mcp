package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.Admission;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.metrics.InMemoryMetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsSnapshot;
import de.alexanderlindhorst.imapmcp.core.mime.BodyWindowReader;
import de.alexanderlindhorst.imapmcp.core.search.SearchCursorCodec;
import de.alexanderlindhorst.imapmcp.core.search.SearchTermMapper;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolDefinition;
import de.alexanderlindhorst.imapmcp.core.tool.ToolExecutor;
import de.alexanderlindhorst.imapmcp.core.tool.ToolHandler;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import de.alexanderlindhorst.imapmcp.core.tool.ValidatingToolHandler;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.GetMessageTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListAccountsTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListAttachmentsTool;
import de.alexanderlindhorst.imapmcp.core.tools.ListFoldersTool;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import de.alexanderlindhorst.imapmcp.core.tools.SearchMessagesTool;
import de.alexanderlindhorst.imapmcp.store.file.ConfiguredAccountRepository;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

/**
 * Tenant isolation (SPEC 5.3, 5.8) on the v1 seam: two tenants wired in-process from core and store classes, sharing
 * ONE salt, key ring, pool, admission, capability cache and metrics, with the same account id {@code work} pointing
 * at two different Dovecot users. This simulates the v3 multi-tenant shape. Tools are invoked through the executor
 * and validation wrappers the transports use.
 */
class TenantIsolationIT {

    private static final TenantId T_A = new TenantId("t-a");
    private static final TenantId T_B = new TenantId("t-b");
    private static final String SUFFIX = Long.toString(System.nanoTime() % 100_000);
    private static final String USER_A = "isoa" + SUFFIX;
    private static final String USER_A2 = "isoa2" + SUFFIX;
    private static final String USER_B = "isob" + SUFFIX;
    private static final int MAX_PER_TENANT = 2;

    private static Network network;
    private static DovecotContainer dovecot;
    private static ToxiproxyFixture toxiproxy;
    private static ToxiproxyFixture.Proxy proxy;

    private static ImapSessionFactory sessions;
    private static StorePool pool;
    private static InMemoryMetricsRecorder metrics;
    private static Admission admission;
    private static LogIds logIds;
    private static final Map<String, ToolHandler> HANDLERS = new HashMap<>();
    private static final Map<TenantId, List<ResolvedAccount>> ACCOUNTS = new HashMap<>();

    @BeforeAll
    static void start() throws Exception {
        network = Network.newNetwork();
        dovecot = new DovecotContainer().withNetwork(network);
        toxiproxy = new ToxiproxyFixture(network);
        dovecot.start();
        toxiproxy.start();
        proxy = toxiproxy.proxyTo("imap-iso", DovecotContainer.NETWORK_ALIAS, DovecotContainer.IMAPS_PORT);

        try (MailSeeder s = dovecot.seeder(USER_A)) {
            s.appendPlain("INBOX", "a-one", "body one");
            s.appendPlain("INBOX", "a-two", "body two");
            s.appendPlain("INBOX", "a-three", "body three");
        }
        try (MailSeeder s = dovecot.seeder(USER_A2)) {
            s.appendPlain("INBOX", "a2-one", "body");
        }
        try (MailSeeder s = dovecot.seeder(USER_B)) {
            s.appendPlain("INBOX", "b-one", "body");
            s.appendPlain("INBOX", "b-two", "body");
        }

        // Tenant A reaches Dovecot through Toxiproxy (AC6), tenant B directly.
        byte[] salt = new byte[16];
        byte[] master = new byte[32];
        new SecureRandom().nextBytes(salt);
        new SecureRandom().nextBytes(master);
        KeyRing keys = new KeyRing(1, Map.of(1, master));
        logIds = new LogIds(keys);

        MailAccount aWork = account("work", proxy.host(), proxy.port(), USER_A);
        MailAccount aOther = account("other", proxy.host(), proxy.port(), USER_A2);
        MailAccount bWork = account("work", dovecot.getHost(), dovecot.imapsPort(), USER_B);
        Map<TenantId, Map<String, MailAccount>> byTenant = Map.of(
                T_A, Map.of("work", aWork, "other", aOther), T_B, Map.of("work", bWork));
        Map<TenantId, AccountRepository> repos = Map.of(
                T_A, new ConfiguredAccountRepository(T_A, List.of(aWork, aOther), salt),
                T_B, new ConfiguredAccountRepository(T_B, List.of(bWork), salt));
        repos.forEach((t, r) -> ACCOUNTS.put(t, r.findByTenant(t)));
        // Combined facade; every lookup is still tenant-scoped (only the owning repository ever answers).
        AccountRepository repository = new AccountRepository() {
            @Override
            public List<ResolvedAccount> findByTenant(TenantId tenant) {
                return repos.getOrDefault(tenant, null) == null ? List.of() : repos.get(tenant).findByTenant(tenant);
            }

            @Override
            public Optional<ResolvedAccount> findByTenantAndHandle(TenantId tenant, String handle) {
                AccountRepository r = repos.get(tenant);
                return r == null ? Optional.empty() : r.findByTenantAndHandle(tenant, handle);
            }
        };

        PoolSettings settings = new PoolSettings(MAX_PER_TENANT, MAX_PER_TENANT, 64, Duration.ofMinutes(5),
                Duration.ofSeconds(60), Duration.ofMinutes(30), Duration.ofSeconds(30),
                PoolSettings.DEFAULTS.evictionInterval(), PoolSettings.DEFAULTS.abandonedTimeout());
        sessions = new ImapSessionFactory(new ImapTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(30),
                Duration.ofSeconds(30)), p -> {
                    // Test-only trust: the proxied host name need not match the Dovecot test certificate.
                    p.put("mail.imaps.ssl.trust", "*");
                    p.put("mail.imaps.ssl.checkserveridentity", "false");
                    return p;
                });
        CredentialStore credentials = (tenant, id) -> Optional.of(new Secret(DovecotContainer.PASSWORD.toCharArray()));
        CapabilityCache capabilities = new CapabilityCache();
        pool = new StorePool(sessions, credentials, capabilities,
                key -> byTenant.getOrDefault(key.tenantId(), Map.of()).get(key.accountId().value()), settings);
        metrics = new InMemoryMetricsRecorder(logIds, Clock.systemUTC());
        metrics.attachPool(pool);
        admission = new Admission(settings, Admission.recording(metrics));
        StoreLeases leases = new StoreLeases(pool, settings.acquireTimeout(), admission, metrics);
        ToolExecutor executor = new ToolExecutor(Duration.ofSeconds(60), logIds, metrics);

        AccountArgument account = new AccountArgument(repository);
        MessageLocator locator = new MessageLocator(new MessageHandles(keys));
        Map<ToolDefinition, ToolHandler> tools = new LinkedHashMap<>();
        tools.put(ListAccountsTool.DEFINITION, new ListAccountsTool(repository, capabilities, leases, 2));
        tools.put(ListFoldersTool.DEFINITION, new ListFoldersTool(account, leases, 100));
        tools.put(SearchMessagesTool.DEFINITION, new SearchMessagesTool(account, leases, new SearchTermMapper(),
                new SearchCursorCodec(keys), new MessageHandles(keys), Clock.systemUTC(),
                SearchMessagesTool.SINCE_OVERLAP, 10_000, capabilities));
        tools.put(GetMessageTool.definition(20_000), new GetMessageTool(account, locator, leases,
                new BodyWindowReader(4L * 1024 * 1024), 20_000));
        tools.put(ListAttachmentsTool.DEFINITION, new ListAttachmentsTool(account, locator, leases));
        tools.forEach((def, h) -> HANDLERS.put(def.name(), executor.wrap(def.name(),
                new ValidatingToolHandler(def, h))));
    }

    @AfterAll
    static void stop() {
        if (metrics != null) {
            metrics.close();
        }
        if (pool != null) {
            pool.close();
        }
        if (sessions != null) {
            sessions.close();
        }
        toxiproxy.stop();
        dovecot.stop();
        network.close();
    }

    private static MailAccount account(String id, String host, int port, String user) {
        return new MailAccount(new AccountId(id), id, null, host, port, MailAccount.Security.IMPLICIT_TLS,
                MailAccount.Flavor.GENERIC, user, new SecretRef("env:UNUSED"));
    }

    // ---- helpers ----

    private static ToolOutcome call(TenantId tenant, String tool, Map<String, Object> args) {
        return HANDLERS.get(tool).handle(new ToolCallContext(new AccountContext(tenant),
                UUID.randomUUID().toString()), args);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ok(TenantId tenant, String tool, Map<String, Object> args) {
        ToolOutcome o = call(tenant, tool, args);
        assertThat(o).as(tool).isInstanceOf(ToolOutcome.Success.class);
        return (Map<String, Object>) ((ToolOutcome.Success) o).structured();
    }

    /** The error payload with request_id removed, for byte-identical comparisons. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(TenantId tenant, String tool, Map<String, Object> args,
            ErrorCode expected) {
        ToolOutcome o = call(tenant, tool, args);
        assertThat(o).as(tool).isInstanceOf(ToolOutcome.Failure.class);
        ToolOutcome.Failure f = (ToolOutcome.Failure) o;
        assertThat(f.error().code()).isEqualTo(expected);
        Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) f.error().toMap().get("error"));
        e.remove("request_id");
        return e;
    }

    private static String handleOf(TenantId tenant, String id) {
        return ACCOUNTS.get(tenant).stream().filter(r -> r.account().id().value().equals(id)).findFirst()
                .orElseThrow().handle();
    }

    private static PoolKey keyOf(TenantId tenant, String id) {
        ResolvedAccount r = ACCOUNTS.get(tenant).stream().filter(x -> x.account().id().value().equals(id))
                .findFirst().orElseThrow();
        return PoolKey.of(new AccountContext(tenant), r);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> search(TenantId tenant, String account, Map<String, Object> extra) {
        Map<String, Object> args = new HashMap<>(Map.of("account", account, "folder", "INBOX"));
        args.putAll(extra);
        return (List<Map<String, Object>>) ok(tenant, "search_messages", args).get("messages");
    }

    private static String firstHandle(TenantId tenant, String accountId) {
        return (String) search(tenant, handleOf(tenant, accountId), Map.of()).get(0).get("handle");
    }

    private static String cursorOf(TenantId tenant, String accountId) {
        Map<String, Object> page = ok(tenant, "search_messages", Map.of("account", handleOf(tenant, accountId),
                "folder", "INBOX", "page_size", 1));
        assertThat(page.get("next_cursor")).isNotNull();
        return (String) page.get("next_cursor");
    }

    /** Flips bit {@code bit} of byte {@code index} in the base64url part after the first dot. */
    private static String flip(String token, int index, int bit) {
        int dot = token.indexOf('.');
        byte[] raw = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        raw[index] ^= (byte) (1 << bit);
        return token.substring(0, dot + 1) + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static int rawLength(String token) {
        return Base64.getUrlDecoder().decode(token.substring(token.indexOf('.') + 1)).length;
    }

    private static long leases(TenantId tenant, String accountId) {
        PoolKey k = keyOf(tenant, accountId);
        return metrics.snapshot().pools().stream()
                .filter(p -> p.tenant().equals(logIds.tenant(k.tenantId().value()))
                        && p.account().equals(logIds.account(k.accountId().value())))
                .mapToLong(MetricsSnapshot.PoolMetrics::leases).sum();
    }

    // ---- AC1 ----

    @Test
    @SuppressWarnings("unchecked")
    void listAccountsShowsOnlyTheCallersAccounts() {
        List<Map<String, Object>> a = (List<Map<String, Object>>) ok(T_A, "list_accounts", Map.of()).get("accounts");
        List<Map<String, Object>> b = (List<Map<String, Object>>) ok(T_B, "list_accounts", Map.of()).get("accounts");
        assertThat(a).extracting(m -> m.get("account")).containsExactlyInAnyOrder(handleOf(T_A, "work"),
                handleOf(T_A, "other"));
        assertThat(b).extracting(m -> m.get("account")).containsExactly(handleOf(T_B, "work"));
        // same account id, same salt: the handle still differs per tenant
        assertThat(handleOf(T_A, "work")).isNotEqualTo(handleOf(T_B, "work"));
    }

    @Test
    void foreignUnknownAndMalformedAccountHandlesGiveTheSameError() {
        Map<String, Object> foreign = error(T_B, "list_folders", Map.of("account", handleOf(T_A, "work")),
                ErrorCode.ACCOUNT_NOT_FOUND);
        Map<String, Object> unknown = error(T_B, "list_folders",
                Map.of("account", "acc_aaaaaaaaaaaaaaaaaaaaaaaaaa"), ErrorCode.ACCOUNT_NOT_FOUND);
        Map<String, Object> malformed = error(T_B, "list_folders", Map.of("account", "not-a-handle"),
                ErrorCode.ACCOUNT_NOT_FOUND);
        assertThat(foreign).isEqualTo(unknown).isEqualTo(malformed);
        // the same holds for the other account-taking tools
        assertThat(error(T_B, "search_messages", Map.of("account", handleOf(T_A, "work"), "folder", "INBOX"),
                ErrorCode.ACCOUNT_NOT_FOUND)).isEqualTo(foreign);
    }

    // ---- AC2 ----

    @Test
    void messageHandleOfAnotherTenantIsInvalidWithoutAnyImapCommand() {
        String handleA = firstHandle(T_A, "work");
        ok(T_B, "list_folders", Map.of("account", handleOf(T_B, "work"))); // B's pool entry exists and is warm
        long created = pool.createdCount();
        long leasesBefore = leases(T_B, "work");

        Map<String, Object> swapped = error(T_B, "get_message",
                Map.of("account", handleOf(T_B, "work"), "message", handleA), ErrorCode.INVALID_HANDLE);
        Map<String, Object> swappedAtt = error(T_B, "list_attachments",
                Map.of("account", handleOf(T_B, "work"), "message", handleA), ErrorCode.INVALID_HANDLE);
        Map<String, Object> tampered = error(T_A, "get_message",
                Map.of("account", handleOf(T_A, "work"), "message", flip(handleA, 5, 3)), ErrorCode.INVALID_HANDLE);

        assertThat(swapped).isEqualTo(tampered).isEqualTo(swappedAtt);
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(leases(T_B, "work")).isEqualTo(leasesBefore);
        assertThat(leases(T_A, "work")).isPositive();
    }

    // ---- AC3 ----

    @Test
    void cursorReplayedByAnotherTenantOrAnotherAccountIsInvalid() {
        String cursor = cursorOf(T_A, "work");
        Map<String, Object> byB = error(T_B, "search_messages", Map.of("account", handleOf(T_B, "work"),
                "folder", "INBOX", "page_size", 1, "cursor", cursor), ErrorCode.INVALID_CURSOR);
        Map<String, Object> otherAccount = error(T_A, "search_messages", Map.of("account", handleOf(T_A, "other"),
                "folder", "INBOX", "page_size", 1, "cursor", cursor), ErrorCode.INVALID_CURSOR);
        Map<String, Object> flipped = error(T_A, "search_messages", Map.of("account", handleOf(T_A, "work"),
                "folder", "INBOX", "page_size", 1, "cursor", flip(cursor, 3, 0)), ErrorCode.INVALID_CURSOR);
        assertThat(byB).isEqualTo(otherAccount).isEqualTo(flipped);

        // the genuine owner can still use it
        Map<String, Object> next = ok(T_A, "search_messages", Map.of("account", handleOf(T_A, "work"),
                "folder", "INBOX", "page_size", 1, "cursor", cursor));
        assertThat(next.get("returned")).isEqualTo(1);
    }

    // ---- AC4 ----

    @Test
    void anySingleFlippedBitInAHandleOrCursorIsRejected() {
        String work = handleOf(T_A, "work");
        String handle = firstHandle(T_A, "work");
        long created = pool.createdCount();
        for (int i = 0; i < rawLength(handle); i++) {
            error(T_A, "get_message", Map.of("account", work, "message", flip(handle, i, i % 8)),
                    ErrorCode.INVALID_HANDLE);
        }
        assertThat(pool.createdCount()).isEqualTo(created);

        String cursor = cursorOf(T_A, "work");
        for (int i = 0; i < rawLength(cursor); i++) {
            error(T_A, "search_messages", Map.of("account", work, "folder", "INBOX", "page_size", 1,
                    "cursor", flip(cursor, i, i % 8)), ErrorCode.INVALID_CURSOR);
        }
    }

    // ---- AC5 ----

    @Test
    void replayAfterExpungeIsMessageNotFoundForTheOwnerAndInvalidForOthers() throws Exception {
        // expunge from A/other (user A2), whose mailbox no other test depends on
        try (MailSeeder s = dovecot.seeder(USER_A2)) {
            s.appendPlain("INBOX", "to-expunge", "x");
        }
        String other = handleOf(T_A, "other");
        List<Map<String, Object>> found = search(T_A, other, Map.of("subject", "to-expunge"));
        assertThat(found).hasSize(1);
        String handle = (String) found.get(0).get("handle");
        ok(T_A, "get_message", Map.of("account", other, "message", handle));

        try (MailSeeder s = dovecot.seeder(USER_A2)) {
            jakarta.mail.Folder f = s.store().getFolder("INBOX");
            f.open(Folder.READ_WRITE);
            for (Message m : f.getMessages()) {
                if ("to-expunge".equals(m.getSubject())) {
                    m.setFlag(Flags.Flag.DELETED, true);
                }
            }
            f.close(true);
        }

        error(T_A, "get_message", Map.of("account", other, "message", handle), ErrorCode.MESSAGE_NOT_FOUND);
        error(T_A, "list_attachments", Map.of("account", other, "message", handle), ErrorCode.MESSAGE_NOT_FOUND);
        // never for another tenant: B gets the generic invalid-handle error, not a not-found
        error(T_B, "get_message", Map.of("account", handleOf(T_B, "work"), "message", handle),
                ErrorCode.INVALID_HANDLE);
    }

    // ---- AC7 ----

    @Test
    void poolAndMetricsKeysAreDistinctPerTenantEvenWithTheSameAccountId() {
        ok(T_A, "list_folders", Map.of("account", handleOf(T_A, "work")));
        ok(T_B, "list_folders", Map.of("account", handleOf(T_B, "work")));
        PoolKey a = keyOf(T_A, "work");
        PoolKey b = keyOf(T_B, "work");
        assertThat(a.accountId()).isEqualTo(b.accountId());
        assertThat(a).isNotEqualTo(b);
        assertThat(pool.numIdle(a)).isPositive();
        assertThat(pool.numIdle(b)).isPositive();

        MetricsSnapshot snap = metrics.snapshot();
        String tenantA = logIds.tenant("t-a");
        String tenantB = logIds.tenant("t-b");
        String work = logIds.account("work");
        assertThat(tenantA).isNotEqualTo(tenantB);
        assertThat(snap.pools()).filteredOn(p -> p.account().equals(work)).extracting(MetricsSnapshot.PoolMetrics::tenant)
                .contains(tenantA, tenantB).doesNotHaveDuplicates();
        // the raw ids never show up in metrics
        assertThat(snap.pools()).extracting(MetricsSnapshot.PoolMetrics::tenant).doesNotContain("t-a", "t-b");
    }

    // ---- AC6 ----

    @Test
    void aSaturatedTenantDoesNotAffectAnotherTenantsCalls() throws Exception {
        String workA = handleOf(T_A, "work");
        String workB = handleOf(T_B, "work");
        ok(T_A, "list_folders", Map.of("account", workA)); // warm connections for both
        ok(T_B, "list_folders", Map.of("account", workB));
        long busyBefore = busyTotal();

        List<Future<ToolOutcome>> stalled = new ArrayList<>();
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var latency = proxy.toxics().toxics().latency("iso-latency", ToxicDirection.DOWNSTREAM, 3000);
            try {
                for (int i = 0; i < MAX_PER_TENANT; i++) {
                    stalled.add(exec.submit(() -> call(T_A, "list_folders", Map.of("account", workA))));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (admission.availableTenant(T_A) > 0 && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertThat(admission.availableTenant(T_A)).isZero();
                assertThat(admission.availableTenant(T_B)).isEqualTo(MAX_PER_TENANT);

                // A is saturated: one more A call is refused immediately ...
                error(T_A, "list_folders", Map.of("account", workA), ErrorCode.SERVER_BUSY);

                // ... while B's calls, in parallel, all succeed at normal latency.
                List<Future<Long>> bCalls = new ArrayList<>();
                for (int t = 0; t < MAX_PER_TENANT; t++) {
                    bCalls.add(exec.submit(() -> {
                        long worst = 0;
                        for (int i = 0; i < 5; i++) {
                            long t0 = System.nanoTime();
                            ToolOutcome o = call(T_B, "list_folders", Map.of("account", workB));
                            worst = Math.max(worst, System.nanoTime() - t0);
                            assertThat(o).isInstanceOf(ToolOutcome.Success.class);
                        }
                        return worst;
                    }));
                }
                for (Future<Long> f : bCalls) {
                    assertThat(Duration.ofNanos(f.get(30, TimeUnit.SECONDS))).isLessThan(Duration.ofSeconds(2));
                }
                // only A was ever refused
                assertThat(busyRejections(T_B, "work")).isZero();
            } finally {
                latency.remove();
            }
            for (Future<ToolOutcome> f : stalled) {
                assertThat(f.get(60, TimeUnit.SECONDS)).isInstanceOf(ToolOutcome.Success.class);
            }
        } finally {
            exec.shutdownNow();
        }
        assertThat(busyTotal()).isGreaterThan(busyBefore);
        assertThat(admission.availableTenant(T_A)).isEqualTo(MAX_PER_TENANT);
    }

    private static long busyRejections(TenantId tenant, String accountId) {
        PoolKey k = keyOf(tenant, accountId);
        return metrics.snapshot().pools().stream()
                .filter(p -> p.tenant().equals(logIds.tenant(k.tenantId().value()))
                        && p.account().equals(logIds.account(k.accountId().value())))
                .flatMap(p -> p.busyRejections().values().stream()).mapToLong(Long::longValue).sum();
    }

    private static long busyTotal() {
        return metrics.snapshot().pools().stream().flatMap(p -> p.busyRejections().values().stream())
                .mapToLong(Long::longValue).sum();
    }
}
