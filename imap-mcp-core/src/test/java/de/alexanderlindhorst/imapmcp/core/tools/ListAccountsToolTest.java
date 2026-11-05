package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.AccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.Admission;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ListAccountsToolTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");
    static final Map<String, String> PASSWORDS = Map.of("zeta", "pw-z", "alpha", "pw-a", "bad", "wrong-pw");

    GreenMail greenMail;
    ImapSessionFactory sessions;
    StorePool pool;
    Admission admission;
    CapabilityCache cache;
    final de.alexanderlindhorst.imapmcp.core.crypto.LogIds logIds = new de.alexanderlindhorst.imapmcp.core.crypto.LogIds(
            new de.alexanderlindhorst.imapmcp.core.crypto.KeyRing(1, Map.of(1, new byte[32])));
    final de.alexanderlindhorst.imapmcp.core.metrics.InMemoryMetricsRecorder recorder =
            new de.alexanderlindhorst.imapmcp.core.metrics.InMemoryMetricsRecorder(logIds, Clock.systemUTC());
    ListAccountsTool tool;
    InMemoryAccountRepository repo;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    final ListAppender<ILoggingEvent> trace = new ListAppender<>();
    final ListAppender<ILoggingEvent> toolLog = new ListAppender<>();
    Logger traceLogger;
    Logger toolLogger;

    MailAccount account(String id, String label, String user, Flavor flavor, String address) {
        return new MailAccount(new AccountId(id), label, address, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, flavor, user, new SecretRef("env:" + id));
    }

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("zeta@localhost", "zeta", "pw-z");
        greenMail.setUser("alpha@localhost", "alpha", "pw-a");
        greenMail.setUser("bad@localhost", "bad", "right-pw");
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        cache = new CapabilityCache(new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        });
        traceLogger = (Logger) LoggerFactory.getLogger("imapmcp.imap.trace");
        traceLogger.setLevel(Level.DEBUG);
        traceLogger.addAppender(trace);
        trace.start();
        toolLogger = (Logger) LoggerFactory.getLogger("de.alexanderlindhorst.imapmcp");
        toolLogger.addAppender(toolLog);
        toolLog.start();
    }

    /** Builds repo, pool and tool for the given accounts of tenant A (and optionally tenant B). */
    void wire(Map<TenantId, List<MailAccount>> byTenant) {
        wire(byTenant, PoolSettings.DEFAULTS, null, null);
    }

    /**
     * Account ids (sets may be mutated later, null for none): {@code missingSecrets} resolve to an empty Optional,
     * {@code unreadableSecrets} throw like a deleted {@code file:} secret does in production.
     */
    void wire(Map<TenantId, List<MailAccount>> byTenant, PoolSettings s, Set<String> missingSecrets,
            Set<String> unreadableSecrets) {
        repo = new InMemoryAccountRepository(byTenant);
        Map<PoolKey, MailAccount> byKey = new java.util.HashMap<>();
        byTenant.forEach((t, list) -> list.forEach(a -> byKey.put(
                PoolKey.of(new AccountContext(t), new ResolvedAccount(t, a, "acc_x")), a)));
        pool = new StorePool(sessions,
                (t, a) -> {
                    if (unreadableSecrets != null && unreadableSecrets.contains(a.value())) {
                        throw new IllegalStateException("secret file cannot be read");
                    }
                    return missingSecrets != null && missingSecrets.contains(a.value()) ? Optional.empty()
                        : Optional.of(new Secret(PASSWORDS.get(a.value()).toCharArray()));
                }, cache,
                byKey::get, s, recorder);
        admission = new Admission(s);
        tool = new ListAccountsTool(repo, cache, new StoreLeases(pool, Duration.ofSeconds(10), admission, recorder), 4);
    }

    void wireDefault() {
        wire(Map.of(A, List.of(
                account("zeta", "Zeta mail", "zeta", Flavor.GENERIC, "zeta@example.org"),
                account("alpha", "Alpha mail", "alpha", Flavor.GENERIC, null)),
                B, List.of(account("zeta", "B only", "zeta", Flavor.GENERIC, "b@example.org"))));
    }

    @AfterEach
    void tearDown() {
        traceLogger.detachAppender(trace);
        toolLogger.detachAppender(toolLog);
        if (pool != null) {
            pool.close();
        }
        sessions.close();
        greenMail.stop();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(TenantId t) {
        ToolOutcome o = tool.handle(new ToolCallContext(new AccountContext(t), "rid"), Map.of());
        assertThat(o).isInstanceOf(ToolOutcome.Success.class);
        return (List<Map<String, Object>>) ((Map<String, Object>) ((ToolOutcome.Success) o).structured()).get("accounts");
    }

    // AC1

    @Test
    void twoAccountsSortedWithHandlesOkAndCapabilities() {
        wireDefault();
        List<Map<String, Object>> accounts = list(A);
        assertThat(accounts).extracting(m -> m.get("label")).containsExactly("Alpha mail", "Zeta mail");
        assertThat(accounts).extracting(m -> m.get("account")).containsExactly(
                InMemoryAccountRepository.handle(A, new AccountId("alpha")),
                InMemoryAccountRepository.handle(A, new AccountId("zeta")));
        assertThat(accounts).extracting(m -> m.get("status")).containsOnly("ok");
        assertThat(accounts).extracting(m -> m.get("provider")).containsOnly("generic");
        assertThat(accounts.get(0)).containsEntry("address", null);
        assertThat(accounts.get(1)).containsEntry("address", "zeta@example.org");
        for (Map<String, Object> m : accounts) {
            assertThat(m.get("account").toString()).matches("acc_[a-z2-7]{26}");
            assertThat(m).containsEntry("capabilities_detected_at", "2026-01-01T00:00:00Z");
            @SuppressWarnings("unchecked") Map<String, Object> caps = (Map<String, Object>) m.get("capabilities");
            assertThat(caps.keySet()).containsExactlyInAnyOrder("move", "uidplus", "special_use", "sort", "gmail");
            assertThat(caps).containsEntry("gmail", false);
        }
        CapabilityCache.Lookup.Fresh fresh = (CapabilityCache.Lookup.Fresh) cache.get(
                PoolKey.of(new AccountContext(A), repo.findByTenant(A).stream()
                        .filter(r -> r.account().id().value().equals("zeta")).findFirst().orElseThrow()));
        @SuppressWarnings("unchecked") Map<String, Object> zeta = (Map<String, Object>) accounts.get(1).get("capabilities");
        assertThat(zeta).containsEntry("move", fresh.capabilities().move()).containsEntry("uidplus", fresh.capabilities().uidplus())
                .containsEntry("special_use", fresh.capabilities().specialUse()).containsEntry("sort", fresh.capabilities().sort());
    }

    // read_only (#176)

    MailAccount readOnlyAccount(String id, String label, String user) {
        return new MailAccount(new AccountId(id), label, null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, user, new SecretRef("env:" + id), true);
    }

    @Test
    void readOnlyIsReportedPerAccount() {
        wire(Map.of(A, List.of(readOnlyAccount("alpha", "Alpha", "alpha"),
                account("zeta", "Zeta", "zeta", Flavor.GENERIC, null))));
        List<Map<String, Object>> accounts = list(A);
        assertThat(accounts.get(0)).containsEntry("read_only", true);
        assertThat(accounts.get(1)).containsEntry("read_only", false);
    }

    @Test
    void readOnlyIsReportedIndependentlyOfStatus() {
        wire(Map.of(A, List.of(readOnlyAccount("bad", "Bad", "bad"))));
        assertThat(list(A).get(0)).containsEntry("status", "auth_failed").containsEntry("read_only", true);
    }

    // allow_purge (#282)

    MailAccount purgeAccount(String id, String label, String user) {
        return new MailAccount(new AccountId(id), label, null, "127.0.0.1", greenMail.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, user, new SecretRef("env:" + id), false, true);
    }

    @Test
    void allowPurgeIsReportedPerAccountAndDefaultsToFalse() {
        wire(Map.of(A, List.of(purgeAccount("alpha", "Alpha", "alpha"),
                account("zeta", "Zeta", "zeta", Flavor.GENERIC, null))));
        List<Map<String, Object>> accounts = list(A);
        assertThat(accounts.get(0)).containsEntry("allow_purge", true);
        assertThat(accounts.get(1)).containsEntry("allow_purge", false);
    }

    @Test
    void allowPurgeIsRequiredInTheOutputSchema() {
        @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) ListAccountsTool.OUTPUT_SCHEMA.get("properties")).get("accounts")).get("items");
        assertThat(((Map<?, ?>) item.get("properties")).get("allow_purge")).isEqualTo(Map.of("type", "boolean"));
        assertThat(((List<?>) item.get("required")).contains("allow_purge")).isTrue();
    }

    @Test
    void readOnlyIsRequiredInTheOutputSchema() {
        @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) ListAccountsTool.OUTPUT_SCHEMA.get("properties")).get("accounts")).get("items");
        assertThat(((Map<?, ?>) item.get("properties")).get("read_only")).isEqualTo(Map.of("type", "boolean"));
        assertThat(((List<?>) item.get("required")).contains("read_only")).isTrue();
    }

    @Test
    void equalLabelsAreOrderedByHandle() {
        wire(Map.of(A, List.of(
                account("zeta", "Same", "zeta", Flavor.GENERIC, null),
                account("alpha", "Same", "alpha", Flavor.GENERIC, null))));
        List<String> handles = new ArrayList<>(List.of(InMemoryAccountRepository.handle(A, new AccountId("zeta")),
                InMemoryAccountRepository.handle(A, new AccountId("alpha"))));
        java.util.Collections.sort(handles);
        assertThat(list(A)).extracting(m -> m.get("account")).containsExactlyElementsOf(handles);
    }

    @Test
    void labelsAreOrderedByCodePointNotUtf16Unit() {
        // U+FF5E < U+1F600 by code point; by UTF-16 unit the surrogate pair (D83D) would sort first.
        wire(Map.of(A, List.of(
                account("zeta", "\uD83D\uDE00", "zeta", Flavor.GENERIC, null),
                account("alpha", "\uFF5E", "alpha", Flavor.GENERIC, null))));
        assertThat(list(A)).extracting(m -> m.get("label")).containsExactly("\uFF5E", "\uD83D\uDE00");
    }

    @Test
    void gmailFlavourReportsGmailProvider() {
        wire(Map.of(A, List.of(account("alpha", "G", "alpha", Flavor.GMAIL, null))));
        assertThat(list(A).get(0)).containsEntry("provider", "gmail");
    }

    // AC2

    @Test
    void wrongPasswordGivesAuthFailedForThatAccountOnly() {
        wire(Map.of(A, List.of(account("alpha", "Alpha", "alpha", Flavor.GENERIC, null),
                account("bad", "Bad", "bad", Flavor.GENERIC, null))));
        List<Map<String, Object>> accounts = list(A);
        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0)).containsEntry("status", "ok");
        assertThat(accounts.get(1)).containsEntry("status", "auth_failed").containsEntry("capabilities", null)
                .containsEntry("capabilities_detected_at", null);
    }

    @Test
    void failedProbeLoginCountsOneAuthFailureForTheTenantWhileTheCallSucceeds() {
        wire(Map.of(A, List.of(account("alpha", "Alpha", "alpha", Flavor.GENERIC, null),
                account("bad", "Bad", "bad", Flavor.GENERIC, null)),
                B, List.of(account("alpha", "Alpha", "alpha", Flavor.GENERIC, null))));
        assertThat(list(A)).hasSize(2); // returns normally
        list(B);
        var tenants = recorder.snapshot().tenants();
        assertThat(tenants).filteredOn(t -> t.tenant().equals(logIds.tenant(A.value()))).singleElement()
                .satisfies(t -> assertThat(t.authFailures()).isEqualTo(1));
        assertThat(tenants).noneMatch(t -> t.tenant().equals(logIds.tenant(B.value())) && t.authFailures() > 0);
    }

    @Test
    void unreachableServerGivesUnreachableAndStaleCapabilitiesWhenCached() {
        Map<TenantId, List<MailAccount>> accts = Map.of(A, List.of(account("alpha", "Alpha", "alpha", Flavor.GENERIC, null)));
        wire(accts);
        assertThat(list(A).get(0)).containsEntry("status", "ok");
        greenMail.stop();
        pool.close(); // drop idle store so the next probe must connect
        wire(accts);
        now.set(now.get().plus(Duration.ofHours(1))); // expired, stale value kept
        Map<String, Object> m = list(A).get(0);
        assertThat(m).containsEntry("status", "unreachable").containsEntry("capabilities_detected_at", "2026-01-01T00:00:00Z");
        assertThat(m.get("capabilities")).isNotNull();
    }

    @Test
    void unreachableWithoutCacheHasNullCapabilities() {
        wire(Map.of(A, List.of(account("alpha", "Alpha", "alpha", Flavor.GENERIC, null))));
        greenMail.stop();
        Map<String, Object> m = list(A).get(0);
        assertThat(m).containsEntry("status", "unreachable").containsEntry("capabilities", null);
    }

    @Test
    void exhaustedAdmissionGivesUnknownAndTheCallSucceeds() {
        Map<TenantId, List<MailAccount>> accts = Map.of(A, List.of(account("alpha", "Alpha", "alpha", Flavor.GENERIC, null),
                account("zeta", "Zeta", "zeta", Flavor.GENERIC, null)));
        PoolSettings one = new PoolSettings(1, 8, 64, Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofMinutes(30), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(120));
        wire(accts, one, null, null);
        ResolvedAccount alpha = repo.findByTenant(A).stream()
                .filter(r -> r.account().id().value().equals("alpha")).findFirst().orElseThrow();
        try (Admission.Permit held = admission.tryAcquire(PoolKey.of(new AccountContext(A), alpha))) {
            List<Map<String, Object>> accounts = list(A);
            assertThat(accounts.get(0)).containsEntry("status", "unknown").containsEntry("capabilities", null);
            assertThat(accounts.get(1)).containsEntry("status", "ok");
        }
    }

    @Test
    void deletedSecretGivesUnknownAndOtherAccountsAreUnaffected() {
        Set<String> missing = new HashSet<>();
        wire(Map.of(A, List.of(account("alpha", "Alpha", "alpha", Flavor.GENERIC, null),
                account("zeta", "Zeta", "zeta", Flavor.GENERIC, null))), PoolSettings.DEFAULTS, missing, null);
        missing.add("alpha");
        List<Map<String, Object>> accounts = list(A);
        assertThat(accounts.get(0)).containsEntry("status", "unknown").containsEntry("capabilities", null);
        assertThat(accounts.get(1)).containsEntry("status", "ok");
    }

    @Test
    void unreadableSecretFileGivesUnknownAndOtherAccountsAreUnaffected() {
        Set<String> unreadable = new HashSet<>();
        wire(Map.of(A, List.of(account("alpha", "Alpha", "alpha", Flavor.GENERIC, null),
                account("zeta", "Zeta", "zeta", Flavor.GENERIC, null))), PoolSettings.DEFAULTS, null, unreadable);
        unreadable.add("alpha");
        List<Map<String, Object>> accounts = list(A);
        assertThat(accounts.get(0)).containsEntry("status", "unknown").containsEntry("capabilities", null);
        assertThat(accounts.get(1)).containsEntry("status", "ok");
    }

    @Test
    void statusMappingIsPinnedPerErrorCode() {
        for (ErrorCode c : ErrorCode.values()) {
            String expected = switch (c) {
                case AUTH_FAILED -> "auth_failed";
                case UPSTREAM_UNAVAILABLE, TIMEOUT, UPSTREAM_ERROR -> "unreachable";
                default -> "unknown";
            };
            assertThat(ListAccountsTool.statusFor(c)).as(c.name()).isEqualTo(expected);
        }
    }

    // AC3

    @Test
    void warmCacheMakesNoConnection() {
        wireDefault();
        list(A);
        long created = pool.createdCount();
        long connections = trace.list.size();
        assertThat(list(A)).hasSize(2);
        assertThat(pool.createdCount()).isEqualTo(created);
        assertThat(trace.list).hasSize((int) connections);
    }

    @Test
    void expiredCacheIsRefreshedEvenWhenAPooledStoreIsReused() {
        wireDefault();
        list(A);
        now.set(now.get().plus(CapabilityCache.DEFAULT_TTL).plusSeconds(1));
        List<Map<String, Object>> accounts = list(A);
        assertThat(accounts).extracting(m -> m.get("status")).containsOnly("ok");
        assertThat(accounts).extracting(m -> m.get("capabilities_detected_at")).containsOnly("2026-01-01T00:10:01Z");
    }

    // AC4

    @Test
    void tenantsSeeOnlyTheirOwnAccounts() {
        wireDefault();
        assertThat(list(A)).hasSize(2);
        List<Map<String, Object>> b = list(B);
        assertThat(b).hasSize(1).extracting(m -> m.get("label")).containsExactly("B only");
        assertThat(b.get(0).get("account")).isEqualTo(InMemoryAccountRepository.handle(B, new AccountId("zeta")));
        assertThat(list(new TenantId("nobody"))).isEmpty();
    }

    // AC5

    @Test
    void noSelectExamineOrFlagCommandIsIssued() {
        wireDefault();
        list(A);
        now.set(now.get().plus(Duration.ofHours(1)));
        list(A);
        assertThat(trace.list.stream().map(ILoggingEvent::getFormattedMessage)).anyMatch(l -> l.contains("CAPABILITY"));
        assertThat(trace.list.stream().map(ILoggingEvent::getFormattedMessage))
                .noneMatch(l -> l.matches("(?s)^\\w+ (UID )?(SELECT|EXAMINE|STORE|EXPUNGE|COPY|APPEND|MOVE|FETCH|SEARCH|CREATE|DELETE|RENAME)\\b.*"));
    }

    @Test
    void addressesAreNeverLogged() {
        wireDefault();
        list(A);
        assertThat(toolLog.list.stream().map(ILoggingEvent::getFormattedMessage))
                .noneMatch(l -> l.contains("example.org"));
        assertThat(trace.list.stream().map(ILoggingEvent::getFormattedMessage)).noneMatch(l -> l.contains("example.org"));
    }
}
