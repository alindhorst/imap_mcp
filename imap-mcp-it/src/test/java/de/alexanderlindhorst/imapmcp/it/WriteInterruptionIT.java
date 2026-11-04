package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import de.alexanderlindhorst.imapmcp.app.AppComponents;
import de.alexanderlindhorst.imapmcp.app.AppComponentsAccess;
import de.alexanderlindhorst.imapmcp.app.config.AppConfig;
import de.alexanderlindhorst.imapmcp.app.config.AppPaths;
import de.alexanderlindhorst.imapmcp.app.config.ConfigLoader;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.mail.Folder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;

/**
 * Interrupted writes (SPEC 5.5 "Write calls (v2)", 5.8): the real write tools run in-process ({@link AppComponents},
 * the call handlers of the stdio transport) against Dovecot through Toxiproxy, {@code tools.deadline: 30s} (the
 * minimum for a writable account).
 *
 * <p>The faults must start exactly between two IMAP commands, which a fixed latency cannot do (every earlier answer
 * would be slow too). The opt-in IMAP trace ({@code imap.trace}) is written on the worker thread, and Angus Mail
 * traces a command before its bytes are written to the socket. A {@link TraceScript} on the trace logger therefore
 * installs or removes a toxic synchronously at a defined command, and the toxic is in place before that command
 * reaches the proxy: no sleeps, no race with the call. The trace content (test data only) never leaves the test JVM.
 */
class WriteInterruptionIT {

    private static final String TRACE_LOGGER = "imapmcp.imap.trace";
    private static final AtomicInteger USERS = new AtomicInteger();

    private static Network network;
    private static DovecotContainer dovecot;
    private static ToxiproxyFixture toxiproxy;
    private static ToxiproxyFixture.Proxy proxy;

    @TempDir
    Path dir;

    private final List<AppComponents> created = new ArrayList<>();
    private TraceScript script;

    @BeforeAll
    static void start() throws Exception {
        network = Network.newNetwork();
        dovecot = new DovecotContainer().withNetwork(network);
        toxiproxy = new ToxiproxyFixture(network);
        dovecot.start();
        toxiproxy.start();
        proxy = toxiproxy.proxyTo("imap-write-fault", DovecotContainer.NETWORK_ALIAS, DovecotContainer.IMAPS_PORT);
    }

    @AfterAll
    static void stop() {
        toxiproxy.stop();
        dovecot.stop();
        network.close();
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (script != null) {
            script.detach();
        }
        clearToxics();
        created.forEach(AppComponents::close);
    }

    private static void clearToxics() throws Exception {
        proxy.enable();
        for (var t : proxy.toxics().toxics().getAll()) {
            t.remove();
        }
    }

    // ---- trace script ----

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private record Step(Predicate<String> match, Action action) {
    }

    /** Runs the actions at the successive IMAP commands that match, on the thread that writes the command. */
    private static final class TraceScript extends AppenderBase<ILoggingEvent> {
        private final Deque<Step> steps = new ConcurrentLinkedDeque<>();
        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TRACE_LOGGER);

        TraceScript attach() {
            setContext(logger.getLoggerContext());
            start();
            logger.setLevel(Level.DEBUG);
            logger.setAdditive(false); // keep the (test data) trace out of the console
            logger.addAppender(this);
            return this;
        }

        void detach() {
            logger.detachAppender(this);
            logger.setLevel(null);
            logger.setAdditive(true);
            stop();
        }

        TraceScript on(String command, Action action) {
            steps.add(new Step(line -> line.matches("[A-Z]\\d+ " + command + "\\b.*"), action));
            return this;
        }

        boolean done() {
            return steps.isEmpty();
        }

        @Override
        protected void append(ILoggingEvent event) {
            Step next = steps.peekFirst();
            if (next != null && next.match().test(event.getFormattedMessage())) {
                steps.pollFirst();
                try {
                    next.action().run();
                } catch (Exception e) {
                    throw new IllegalStateException("trace script action failed", e);
                }
            }
        }
    }

    /** Delays every answer from now on; installed while the trigger command is traced, before it is sent. */
    private static Action delayAnswers(int ms) {
        return () -> proxy.toxics().toxics().latency("slow", ToxicDirection.DOWNSTREAM, ms);
    }

    /** Resets the connection at once (reset_peer drops what the client has not read yet). */
    private static Action resetNow() {
        return () -> proxy.toxics().toxics().resetPeer("reset", ToxicDirection.DOWNSTREAM, 0);
    }

    // ---- fixture ----

    /** One tenant, one writable account, two source folders plus an empty target, against the proxy. */
    private final class Fixture {
        final String user = "winterrupt" + USERS.incrementAndGet();
        final AppComponents components;
        final StorePool pool;
        final PoolKey key;
        final String account;
        private final Map<String, McpServerFeatures.SyncToolSpecification> specs = new HashMap<>();
        private final McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

        Fixture(String... sourceFolders) throws Exception {
            try (MailSeeder seeder = dovecot.seeder(user)) {
                for (String f : sourceFolders) {
                    seeder.appendPlain(f, "write interruption " + f, "body");
                }
                seeder.store().getFolder("Target").create(Folder.HOLDS_MESSAGES);
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
                      readTimeout: 60s
                      trace: true
                    tools:
                      deadline: 30s
                    """.formatted(proxy.host(), proxy.port(), user));
            AppConfig cfg = ConfigLoader.load(config, Map.of());
            AppPaths paths = new AppPaths(config, dir.resolve("state-" + user), dir.resolve("logs-" + user));
            components = AppComponentsAccess.create(cfg, paths, Map.of("IT_PW", DovecotContainer.PASSWORD), p -> {
                p.put("mail.imaps.ssl.trust", "*");
                p.put("mail.imaps.ssl.checkserveridentity", "false");
                return p;
            });
            created.add(components);
            pool = AppComponentsAccess.pool(components);
            key = PoolKey.of(components.accountContext(),
                    new ResolvedAccount(AppComponents.TENANT, cfg.accounts().get(0).toMailAccount(), "acc_unused"));
            components.statefulToolSpecs().forEach(s -> specs.put(s.tool().name(), s));
            when(exchange.transportContext()).thenReturn(McpTransportContext.EMPTY);
            account = account();
        }

        @SuppressWarnings("unchecked")
        private String account() {
            Map<String, Object> out = (Map<String, Object>) call("list_accounts", Map.of()).structured();
            return (String) ((List<Map<String, Object>>) out.get("accounts")).get(0).get("account");
        }

        Result call(String tool, Map<String, Object> args) {
            long t0 = System.nanoTime();
            McpSchema.CallToolResult r = specs.get(tool).callHandler().apply(exchange,
                    new McpSchema.CallToolRequest(tool, args));
            return new Result(r, Duration.ofNanos(System.nanoTime() - t0));
        }

        @SuppressWarnings("unchecked")
        String handleIn(String folder) {
            Map<String, Object> out = (Map<String, Object>) call("search_messages",
                    Map.of("account", account, "folder", folder)).structured();
            return (String) ((List<Map<String, Object>>) out.get("messages")).get(0).get("handle");
        }

        Result moveToTarget(String... handles) {
            return call("move_messages", Map.of("account", account, "handles", List.of(handles),
                    "target_folder", "Target"));
        }

        Result copyToTarget(String... handles) {
            return call("copy_messages", Map.of("account", account, "handles", List.of(handles),
                    "target_folder", "Target"));
        }

        MailboxSnapshot snapshot(String... folders) throws Exception {
            try (MailSeeder seeder = dovecot.seeder(user)) {
                return MailboxSnapshot.of(seeder.store(), folders);
            }
        }
    }

    private record Result(McpSchema.CallToolResult raw, Duration took) {
        Object structured() {
            return raw.structuredContent();
        }

        boolean isError() {
            return Boolean.TRUE.equals(raw.isError());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> error() {
            return (Map<String, Object>) ((Map<String, Object>) raw.structuredContent()).get("error");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> details() {
            return (Map<String, Object>) error().get("details");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items() {
            return (List<Map<String, Object>>) ((Map<String, Object>) raw.structuredContent()).get("results");
        }

        @SuppressWarnings("unchecked")
        List<String> notes() {
            return (List<String>) ((Map<String, Object>) raw.structuredContent()).get("notes");
        }

        List<String> statuses() {
            return items().stream().map(i -> (String) i.get("status")).toList();
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

    // ---- AC1: move whose UID MOVE answer misses the deadline ----

    @Test
    void moveWithLateAnswerTimesOutUnknownAndTheRetryFindsItAlreadyGone() throws Exception {
        Fixture f = new Fixture("INBOX");
        String handle = f.handleIn("INBOX");
        MailboxSnapshot before = f.snapshot("INBOX", "Target");
        long destroyedBefore = f.pool.destroyedCount();
        script = new TraceScript().attach().on("UID MOVE", delayAnswers(40_000));

        Result r = f.moveToTarget(handle);

        assertThat(script.done()).as("the fault was injected after SELECT and the pre-check").isTrue();
        assertThat(r.isError()).isTrue();
        assertThat(r.error().get("code")).isEqualTo("TIMEOUT");
        assertThat(r.error().get("retryable")).isEqualTo(true);
        assertThat(r.details()).containsEntry("outcome", "unknown");
        assertThat(r.took()).as("the deadline ended the call").isBetween(Duration.ofSeconds(29),
                Duration.ofSeconds(35));
        eventually("store discarded on timeout", () -> f.pool.destroyedCount() > destroyedBefore);
        assertThat(f.pool.numActive(f.key)).isZero();

        // Dovecot completed the command: the message is in Target exactly once, INBOX lost it.
        clearToxics();
        MailboxSnapshot after = f.snapshot("INBOX", "Target");
        MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, after);
        assertThat(diff.removed("INBOX")).hasSize(1);
        assertThat(diff.added("Target")).hasSize(1);
        assertThat(after.folder("INBOX").flags()).isEmpty();
        assertThat(after.folder("Target").flags()).hasSize(1);

        Result retry = f.moveToTarget(handle);

        assertThat(retry.isError()).isFalse();
        assertThat(retry.statuses()).containsExactly("already_gone");
        assertThat(MailboxSnapshot.diff(after, f.snapshot("INBOX", "Target")).isEmpty())
                .as("the retry changed nothing: no loss, no duplicate").isTrue();
    }

    // ---- AC2: copy with the same setup ----

    @Test
    void copyWithLateAnswerYieldsOutcomeUnknownNotRetryable() throws Exception {
        Fixture f = new Fixture("INBOX");
        String handle = f.handleIn("INBOX");
        MailboxSnapshot before = f.snapshot("INBOX", "Target");
        script = new TraceScript().attach().on("UID COPY", delayAnswers(40_000));

        Result r = f.copyToTarget(handle);

        assertThat(script.done()).isTrue();
        assertThat(r.isError()).isTrue();
        assertThat(r.error().get("code")).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(r.error().get("retryable")).isEqualTo(false);
        clearToxics();
        MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, f.snapshot("INBOX", "Target"));
        assertThat(diff.added("Target")).as("the server did copy once").hasSize(1);
        assertThat(diff.removed("INBOX")).isEmpty();
        assertThat(diff.flagChanged("INBOX")).isEmpty();
    }

    // ---- AC3: connection reset between two folder groups ----

    @Test
    void connectionResetBetweenGroupsReturnsAResultAndDiscardsTheStore() throws Exception {
        Fixture f = new Fixture("INBOX", "Second");
        String first = f.handleIn("INBOX");
        String second = f.handleIn("Second");
        MailboxSnapshot before = f.snapshot("INBOX", "Second", "Target");
        long createdBefore = f.pool.createdCount();
        long destroyedBefore = f.pool.destroyedCount();
        script = new TraceScript().attach().on("UNSELECT", resetNow()); // the close of the first group, after its MOVE answer was read;

        Result r = f.moveToTarget(first, second);

        assertThat(script.done()).isTrue();
        assertThat(r.isError()).as("a result, not an error").isFalse();
        assertThat(r.items().get(0)).containsEntry("handle", first).containsEntry("status", "ok");
        assertThat(r.items().get(0).get("new_handle")).isNotNull();
        assertThat(r.items().get(1)).containsEntry("handle", second);
        assertThat(r.items().get(1).get("status")).isIn("not_attempted", "unknown");
        eventually("broken store discarded", () -> f.pool.destroyedCount() > destroyedBefore);

        clearToxics();
        MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, f.snapshot("INBOX", "Second", "Target"));
        assertThat(diff.removed("INBOX")).hasSize(1);
        assertThat(diff.added("Target")).hasSizeGreaterThanOrEqualTo(1);
        assertThat(f.handleIn("Second")).as("next call works on a fresh connection").isNotNull();
        assertThat(f.pool.createdCount()).isGreaterThan(createdBefore);
    }

    // ---- AC4: write budget before the second group ----

    @Test
    void lessThanFifteenSecondsLeftBeforeTheSecondGroupMakesItNotAttempted() throws Exception {
        Fixture f = new Fixture("INBOX", "Second");
        String first = f.handleIn("INBOX");
        String second = f.handleIn("Second");
        MailboxSnapshot before = f.snapshot("INBOX", "Second", "Target");
        // The answer to the second group's pre-check (UID FETCH) takes 17 s, so less than 15 s of the 30 s are left when
        // the group would send its first mutating command. The close of that group (UNSELECT) is not delayed again.
        script = new TraceScript().attach()
                .on("UID FETCH", () -> { }) // first group
                .on("UID FETCH", delayAnswers(17_000))
                .on("UNSELECT", IT_CLEAR);

        Result r = f.moveToTarget(first, second);

        assertThat(script.done()).isTrue();
        assertThat(r.isError()).as("a normal result, not TIMEOUT").isFalse();
        assertThat(r.statuses()).containsExactly("ok", "not_attempted");
        assertThat(r.notes()).contains("The call stopped before some messages were processed; nothing was changed "
                + "for them, so repeat the call with their handles.");
        MailboxSnapshot.Diff diff = MailboxSnapshot.diff(before, f.snapshot("INBOX", "Second", "Target"));
        assertThat(diff.removed("Second")).as("nothing was changed for the second group").isEmpty();
        assertThat(diff.removed("INBOX")).hasSize(1);
        assertThat(diff.added("Target")).hasSize(1);
    }

    private static final Action IT_CLEAR = () -> {
        for (var t : proxy.toxics().toxics().getAll()) {
            t.remove();
        }
    };
}
