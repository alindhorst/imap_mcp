package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.management.HotSpotDiagnosticMXBean;
import de.alexanderlindhorst.imapmcp.app.AppComponents;
import de.alexanderlindhorst.imapmcp.app.AppComponentsAccess;
import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

/**
 * Fault injection (SPEC 5.5, 5.8): the core components run in-process ({@link AppComponents}) against Dovecot
 * through a Toxiproxy proxy, with short timeouts and {@code maxPerMailbox=1}. Tools are invoked through the same
 * call handlers the stdio transport uses.
 */
class ToxiproxyFaultIT {

    private static final Duration DEADLINE = Duration.ofSeconds(2);
    private static final AtomicInteger USERS = new AtomicInteger();

    private static Network network;
    private static DovecotContainer dovecot;
    private static ToxiproxyFixture toxiproxy;
    private static ToxiproxyFixture.Proxy proxy;

    @TempDir
    Path dir;

    private final List<AppComponents> created = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        network = Network.newNetwork();
        dovecot = new DovecotContainer().withNetwork(network);
        toxiproxy = new ToxiproxyFixture(network);
        dovecot.start();
        toxiproxy.start();
        proxy = toxiproxy.proxyTo("imap-fault", DovecotContainer.NETWORK_ALIAS, DovecotContainer.IMAPS_PORT);
    }

    @AfterAll
    static void stop() {
        toxiproxy.stop();
        dovecot.stop();
        network.close();
    }

    @AfterEach
    void cleanUp() throws Exception {
        clearToxics();
        created.forEach(AppComponents::close);
    }

    private static void clearToxics() throws Exception {
        proxy.enable();
        for (var t : proxy.toxics().toxics().getAll()) {
            t.remove();
        }
    }

    /** One tenant/account/mailbox against the proxy, with the tool handlers of the components. */
    private final class Fixture {
        final AppComponents components;
        final StorePool pool;
        final PoolKey key;
        final String account;
        private final Map<String, McpServerFeatures.SyncToolSpecification> specs = new HashMap<>();
        private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

        Fixture(String validateAfterIdle) throws Exception {
            this(validateAfterIdle, "2s");
        }

        Fixture(String validateAfterIdle, String deadline) throws Exception {
            String user = "fault" + USERS.incrementAndGet();
            try (MailSeeder seeder = dovecot.seeder(user)) {
                seeder.appendPlain("INBOX", "fault test", "body");
            }
            Path config = dir.resolve(user + ".yaml");
            Files.writeString(config, """
                    accounts:
                      - id: work
                        host: %s
                        port: %d
                        username: %s
                        password: "env:IT_PW"
                    imap:
                      readTimeout: 30s
                    pool:
                      maxPerMailbox: 1
                      acquireTimeout: 2s
                      validateAfterIdle: %s
                    tools:
                      deadline: %s
                    """.formatted(proxy.host(), proxy.port(), user, validateAfterIdle, deadline));
            AppConfig cfg = ConfigLoader.load(config, Map.of());
            AppPaths paths = new AppPaths(config, dir.resolve("state-" + user), dir.resolve("logs-" + user));
            // Test-only trust: the proxied host name need not match the Dovecot test certificate.
            components = AppComponentsAccess.create(cfg, paths, Map.of("IT_PW", DovecotContainer.PASSWORD), p -> {
                p.put("mail.imaps.ssl.trust", "*");
                p.put("mail.imaps.ssl.checkserveridentity", "false");
                return p;
            });
            created.add(components);
            pool = AppComponentsAccess.pool(components);
            components.statefulToolSpecs().forEach(s -> specs.put(s.tool().name(), s));
            when(exchange.transportContext()).thenReturn(McpTransportContext.EMPTY);
            key = PoolKey.of(components.accountContext(),
                    new ResolvedAccount(AppComponents.TENANT, cfg.accounts().get(0).toMailAccount(), "acc_unused"));
            account = accountHandle();
        }

        @SuppressWarnings("unchecked")
        private String accountHandle() {
            // list_accounts never fails: it also yields the handle while the upstream is down.
            Map<String, Object> out = (Map<String, Object>) call("list_accounts", Map.of()).getStructured();
            return (String) ((List<Map<String, Object>>) out.get("accounts")).get(0).get("account");
        }

        Result call(String tool, Map<String, Object> args) {
            long t0 = System.nanoTime();
            McpSchema.CallToolResult r = specs.get(tool).callHandler().apply(exchange,
                    new McpSchema.CallToolRequest(tool, args));
            return new Result(r, Duration.ofNanos(System.nanoTime() - t0));
        }

        Result search() {
            return call("search_messages", Map.of("account", account, "folder", "INBOX"));
        }

        int active() {
            return pool.numActive(key);
        }
    }

    private record Result(McpSchema.CallToolResult raw, Duration took) {
        Object getStructured() {
            return raw.structuredContent();
        }

        boolean ok() {
            return !Boolean.TRUE.equals(raw.isError());
        }

        @SuppressWarnings("unchecked")
        String errorCode() {
            return (String) ((Map<String, Object>) ((Map<String, Object>) raw.structuredContent()).get("error"))
                    .get("code");
        }

        @SuppressWarnings("unchecked")
        boolean retryable() {
            return Boolean.TRUE.equals(((Map<String, Object>) ((Map<String, Object>) raw.structuredContent())
                    .get("error")).get("retryable"));
        }
    }

    private static void eventually(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for: " + what);
            }
            Thread.sleep(50);
        }
    }

    private boolean threadAlive(String name) throws Exception {
        Path dump = dir.resolve("dump-" + System.nanoTime() + ".json");
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                .dumpThreads(dump.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON);
        return Files.readString(dump).contains("\"" + name + "\"");
    }

    private void assertHealthy(Fixture f) throws Exception {
        clearToxics();
        Result r = f.search();
        assertThat(r.ok()).as("call after recovery").isTrue();
        eventually("no active store", () -> f.active() == 0);
    }

    // AC1

    @Test
    void resetPeerYieldsUpstreamUnavailableDestroysTheStoreAndTheNextCallReconnects() throws Exception {
        Fixture f = new Fixture("60s");
        long createdBefore = f.pool.createdCount();
        long destroyedBefore = f.pool.destroyedCount();
        proxy.toxics().toxics().resetPeer("reset", ToxicDirection.DOWNSTREAM, 0);

        Result r = f.search();

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(r.retryable()).isTrue();
        eventually("store destroyed", () -> f.pool.destroyedCount() == destroyedBefore + 1);
        assertThat(f.active()).isZero();

        clearToxics();
        assertThat(f.search().ok()).isTrue();
        assertThat(f.pool.createdCount()).as("fresh connection").isGreaterThan(createdBefore);
    }

    // #134 AC1, AC2: a connection reset during LIST must not return the broken Store to the pool

    @Test
    void resetPeerDuringListFoldersDiscardsTheStoreAndTheNextCallReconnects() throws Exception {
        Fixture f = new Fixture("60s");
        long createdBefore = f.pool.createdCount();
        long destroyedBefore = f.pool.destroyedCount();
        assertThat(f.pool.numIdle(f.key)).as("warm Store from the account probe").isEqualTo(1);
        proxy.toxics().toxics().resetPeer("reset", ToxicDirection.DOWNSTREAM, 0);

        Result r = f.call("list_folders", Map.of("account", f.account));

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(f.pool.destroyedCount()).isEqualTo(destroyedBefore + 1);
        assertThat(f.pool.numIdle(f.key)).isZero();
        assertThat(f.active()).isZero();

        clearToxics();
        Result next = f.call("list_folders", Map.of("account", f.account));
        assertThat(next.ok()).as("call after recovery, no spurious failure").isTrue();
        assertThat(f.pool.createdCount()).isEqualTo(createdBefore + 1);
    }

    // AC2

    @Test
    void latencyBeyondTheDeadlineTimesOutDestroysTheStoreAndUnblocksTheWorker() throws Exception {
        Fixture f = new Fixture("60s");
        long destroyedBefore = f.pool.destroyedCount();
        proxy.toxics().toxics().latency("slow", ToxicDirection.DOWNSTREAM, 20_000);

        AtomicReference<Result> result = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> result.set(f.search()));
        eventually("worker blocked in the call", () -> {
            try {
                return threadAlive("tool-search_messages");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        caller.join(DEADLINE.plusSeconds(1).toMillis());

        Result r = result.get();
        assertThat(r).as("call returned within deadline + 1s").isNotNull();
        assertThat(r.errorCode()).isEqualTo("TIMEOUT");
        assertThat(r.took()).isLessThan(DEADLINE.plusSeconds(1));
        eventually("store destroyed, not returned", () -> f.pool.destroyedCount() == destroyedBefore + 1);
        assertThat(f.pool.numIdle(f.key)).isZero();
        // The interrupt unblocks the virtual worker stuck in the SSL socket read well before the 20 s latency or
        // the 30 s read timeout would.
        eventually("worker thread gone", () -> {
            try {
                return !threadAlive("tool-search_messages");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertHealthy(f);
    }

    // AC3

    @Test
    void blackholeDuringConnectFailsWithinTheAcquireTimeout() throws Exception {
        // Deadline above the acquire timeout, so the lease (not the tool deadline) is what ends the call.
        Fixture f = new Fixture("1s", "10s");
        long createdBefore = f.pool.createdCount();
        long destroyedBefore = f.pool.destroyedCount();
        proxy.disable(); // kills the warm connection; the next borrow validates it (idle > 1s), fails and reconnects
        proxy.enable();
        Thread.sleep(1500);
        proxy.toxics().toxics().timeout("blackhole", ToxicDirection.DOWNSTREAM, 0);

        Result r = f.search();

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(r.took()).as("acquire timeout + 1s, not the 30 s read timeout")
                .isLessThan(Duration.ofSeconds(3));
        eventually("dead store destroyed", () -> f.pool.destroyedCount() == destroyedBefore + 1);
        assertThat(f.pool.createdCount()).as("no half-open store was created").isEqualTo(createdBefore);
        assertThat(f.active()).isZero();
        assertHealthy(f);
    }

    // AC4

    @Test
    void secondCallToTheSameMailboxFailsFastWithServerBusy() throws Exception {
        Fixture f = new Fixture("60s");
        assertThat(f.search().ok()).as("warm-up").isTrue();
        proxy.toxics().toxics().latency("slow", ToxicDirection.DOWNSTREAM, 20_000);
        AtomicReference<Result> first = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> first.set(f.search()));
        Thread.sleep(500);

        Result second = f.search();

        assertThat(second.errorCode()).isEqualTo("SERVER_BUSY");
        assertThat(second.retryable()).isTrue();
        assertThat(second.took()).isLessThan(Duration.ofMillis(100));
        caller.join(DEADLINE.plusSeconds(2).toMillis());
        assertThat(first.get().errorCode()).isEqualTo("TIMEOUT");
        assertHealthy(f);
    }

    // AC5

    @Test
    void idleConnectionKilledByTheProxyIsReplacedTransparently() throws Exception {
        Fixture f = new Fixture("1s");
        long createdBefore = f.pool.createdCount();
        long destroyedBefore = f.pool.destroyedCount();
        assertThat(f.pool.numIdle(f.key)).isEqualTo(1);
        proxy.disable(); // closes the established connections
        proxy.enable();
        Thread.sleep(1500); // idle > validateAfterIdle: the borrow validates with NOOP

        Result r = f.search();

        assertThat(r.ok()).isTrue();
        assertThat(f.pool.createdCount()).isEqualTo(createdBefore + 1);
        eventually("dead store destroyed", () -> f.pool.destroyedCount() == destroyedBefore + 1);
        assertThat(f.active()).isZero();
    }

    // AC6

    @Test
    void afterAllScenariosNothingLeaks() throws Exception {
        Fixture f = new Fixture("60s");
        proxy.toxics().toxics().resetPeer("reset", ToxicDirection.DOWNSTREAM, 0);
        assertThat(f.search().errorCode()).isEqualTo("UPSTREAM_UNAVAILABLE");
        clearToxics();
        proxy.toxics().toxics().latency("slow", ToxicDirection.DOWNSTREAM, 20_000);
        assertThat(f.search().errorCode()).isEqualTo("TIMEOUT");
        clearToxics();
        proxy.toxics().toxics().timeout("blackhole", ToxicDirection.DOWNSTREAM, 0);
        assertThat(f.search().ok()).isFalse();
        clearToxics();

        // maxPerMailbox=1: a leaked admission permit or pool slot would make this SERVER_BUSY / fail.
        assertThat(f.search().ok()).isTrue();
        assertThat(f.search().ok()).isTrue();
        eventually("pool back to baseline", () -> f.active() == 0);
        assertThat(f.pool.numIdle(f.key)).isLessThanOrEqualTo(1);
        assertThat(f.pool.createdCount() - f.pool.destroyedCount()).isEqualTo(f.pool.numIdle(f.key));
    }
}
