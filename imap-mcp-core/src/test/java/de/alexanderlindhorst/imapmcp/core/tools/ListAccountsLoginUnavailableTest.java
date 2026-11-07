package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.LoginOutcomes;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.crypto.LogIds;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.Admission;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.metrics.InMemoryMetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.testsupport.FaultingImapProxy;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tool.ToolOutcome;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * D14 in list_accounts (SPEC 4): a probe LOGIN answered {@code NO [UNAVAILABLE]} is {@code unreachable}, not
 * {@code auth_failed}, and never counts as a failed login of the account or as an auth failure in the metrics.
 */
class ListAccountsLoginUnavailableTest {

    private static final TenantId T = new TenantId("tenant-a");

    private final LogIds logIds = new LogIds(new KeyRing(1, Map.of(1, new byte[32])));
    private final InMemoryMetricsRecorder recorder = new InMemoryMetricsRecorder(logIds, Clock.systemUTC());
    private final List<String> refused = new ArrayList<>();
    private GreenMail greenMail;
    private FaultingImapProxy proxy;
    private ImapSessionFactory sessions;
    private StorePool pool;

    @BeforeEach
    void setUp() throws Exception {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser("alpha@localhost", "alpha", "pw");
        proxy = new FaultingImapProxy(greenMail.getImaps().getPort());
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
    }

    @AfterEach
    void tearDown() throws Exception {
        recorder.close();
        pool.close();
        sessions.close();
        proxy.close();
        greenMail.stop();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyAccountFor(String loginAnswer) {
        proxy.rejectWithNo("LOGIN", loginAnswer);
        MailAccount account = new MailAccount(new AccountId("alpha"), "Alpha", null, "127.0.0.1", proxy.port(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, "alpha", new SecretRef("env:alpha"));
        PoolKey key = PoolKey.of(new AccountContext(T), new ResolvedAccount(T, account, "acc_x"));
        LoginOutcomes outcomes = new LoginOutcomes() {
            @Override
            public void refused(TenantId t, AccountId a, long revision) {
                refused.add(a.value());
            }

            @Override
            public void succeeded(TenantId t, AccountId a, long revision) {
            }
        };
        pool = new StorePool(sessions, (t, a) -> Optional.of(new Secret("pw".toCharArray())), new CapabilityCache(),
                k -> k.equals(key) ? account : null, PoolSettings.DEFAULTS, recorder, outcomes);
        ListAccountsTool tool = new ListAccountsTool(new InMemoryAccountRepository(Map.of(T, List.of(account))),
                new CapabilityCache(),
                new StoreLeases(pool, Duration.ofSeconds(10), new Admission(PoolSettings.DEFAULTS), recorder), 4);
        ToolOutcome o = tool.handle(new ToolCallContext(new AccountContext(T), "rid"), Map.of());
        assertThat(o).isInstanceOf(ToolOutcome.Success.class); // a failing account never fails the call
        return ((List<Map<String, Object>>) ((Map<String, Object>) ((ToolOutcome.Success) o).structured())
                .get("accounts")).getFirst();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "[UNAVAILABLE] Temporary authentication failure", "  [unavailable] try later",
            "[Unavailable]"})
    void aLoginAnsweredNoUnavailableIsUnreachableAndCountsNowhere(String answer) {
        assertThat(onlyAccountFor(answer)).containsEntry("status", "unreachable");
        assertThat(refused).isEmpty(); // no failed_logins increment, so never toward needs_relink
        assertThat(recorder.snapshot().tenants()).noneMatch(t -> t.authFailures() > 0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"[AUTHENTICATIONFAILED] Invalid credentials", "Invalid credentials"})
    void everyOtherRefusedLoginStaysAuthFailedAndCounts(String answer) {
        assertThat(onlyAccountFor(answer)).containsEntry("status", "auth_failed");
        assertThat(refused).containsExactly("alpha");
        assertThat(recorder.snapshot().tenants()).anyMatch(t -> t.authFailures() == 1);
    }

    @Test
    void aBareNoStaysAuthFailed() {
        assertThat(onlyAccountFor("NO")).containsEntry("status", "auth_failed");
        assertThat(refused).containsExactly("alpha");
    }
}
