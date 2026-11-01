package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.search.SearchTermMapper;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** #18 AC7: a RuntimeException inside the lease scope of a tool invalidates the Store (does not return it). */
class LeaseRuntimeDiscardTest {

    private static final TenantId T = new TenantId("t1");

    @Test
    void searchMessagesDiscardsStoreOnIllegalState() throws Exception {
        try (SearchEnv env = new SearchEnv()) {
            env.deliver(1);
            SearchTermMapper mapper = mock(SearchTermMapper.class);
            when(mapper.map(any())).thenThrow(new IllegalStateException("boom"));
            SearchMessagesTool tool = new SearchMessagesTool(new AccountArgument(env.repo), env.leases, mapper,
                    env.cursors, env.handles, env.clock);
            ToolCallContext ctx = new ToolCallContext(new AccountContext(SearchEnv.T1), "rid");
            assertThatThrownBy(() -> tool.handle(ctx, Map.of("account", env.handleT1)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("boom");
            assertThat(env.pool.destroyedCount()).isEqualTo(1);
            assertThat(env.pool.numActive(poolKey(env))).isZero();
            assertThat(env.pool.numIdle(poolKey(env))).isZero();
        }
    }

    private static PoolKey poolKey(SearchEnv env) {
        return PoolKey.of(new AccountContext(SearchEnv.T1), env.repo.findByTenant(SearchEnv.T1).get(0));
    }

    @Test
    void listFoldersDiscardsStoreOnIllegalState() throws Exception {
        try (SearchEnv env = new SearchEnv()) {
            ListFoldersTool tool = new ListFoldersTool(new AccountArgument(env.repo), env.leases, 100,
                    (lease, c, entries) -> {
                        throw new IllegalStateException("boom");
                    });
            ToolCallContext ctx = new ToolCallContext(new AccountContext(SearchEnv.T1), "rid");
            assertThatThrownBy(() -> tool.handle(ctx, Map.of("account", env.handleT1)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("boom");
            assertThat(env.pool.destroyedCount()).isEqualTo(1);
            assertThat(env.pool.numIdle(poolKey(env))).isZero();
        }
    }

    @Test
    void listAccountsProbeDiscardsStoreOnIllegalState() throws Exception {
        GreenMail gm = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        gm.start();
        gm.setUser("u@localhost", "u", "pw");
        ImapSessionFactory sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        MailAccount a = new MailAccount(new AccountId("a"), "A", null, "127.0.0.1", gm.getImaps().getPort(),
                Security.IMPLICIT_TLS, Flavor.GENERIC, "u", new SecretRef("env:A"));
        InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(T, List.of(a)));
        PoolKey key = PoolKey.of(new AccountContext(T), repo.findByTenant(T).get(0));
        CapabilityCache cache = mock(CapabilityCache.class);
        when(cache.get(any())).thenReturn(new CapabilityCache.Lookup.Missing());
        doThrow(new IllegalStateException("boom")).when(cache).put(any(), any());
        StorePool pool = new StorePool(sessions, (t, s) -> Optional.of(new Secret("pw".toCharArray())),
                new CapabilityCache(), k -> a, PoolSettings.DEFAULTS);
        try {
            ListAccountsTool tool = new ListAccountsTool(repo, cache, new StoreLeases(pool, Duration.ofSeconds(10)), 2);
            // the probe reports the account as unreachable (never throws); the Store must not be reused
            tool.handle(new ToolCallContext(new AccountContext(T), "rid"), Map.of());
            assertThat(pool.destroyedCount()).isEqualTo(1);
            assertThat(pool.numIdle(key)).isZero();
        } finally {
            pool.close();
            sessions.close();
            gm.stop();
        }
    }
}
