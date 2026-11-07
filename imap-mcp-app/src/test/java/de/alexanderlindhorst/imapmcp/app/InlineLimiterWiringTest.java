package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.app.config.PublicConfig;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.download.InlineLimiter;
import de.alexanderlindhorst.imapmcp.core.error.ServerBusyException;
import de.alexanderlindhorst.imapmcp.core.metrics.MetricsRecorder;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D17: the embedded download's limiter takes both caps from the configuration and reports refusals as metrics. */
class InlineLimiterWiringTest {

    private static PublicConfig.Tools tools(int inlineConcurrency, int perTenant) {
        return new PublicConfig.Tools(Duration.ofSeconds(90), 100000, 4L * 1024 * 1024, 100, 10000, 25L * 1024 * 1024,
                inlineConcurrency, perTenant);
    }

    private static PoolKey key(String tenant) {
        TenantId t = new TenantId(tenant);
        MailAccount a = new MailAccount(new AccountId("a"), "l", null, "127.0.0.1", 993, Security.IMPLICIT_TLS,
                Flavor.GENERIC, "u", new SecretRef("env:X"));
        return PoolKey.of(new AccountContext(t), new ResolvedAccount(t, a, "acc_x"));
    }

    private final List<String> busy = new ArrayList<>();
    private final MetricsRecorder metrics = new MetricsRecorder() {
        @Override
        public void busyRejection(PoolKey key, String scope) {
            busy.add(key.tenantId().value() + ":" + scope);
        }
    };

    @Test
    void theTenantCapComesFromTheConfigurationAndAnotherTenantIsUnaffected() {
        InlineLimiter l = PublicComponents.inlineLimiter(tools(4, 2), metrics);
        l.tryAcquire(key("a"));
        l.tryAcquire(key("a"));
        assertThatThrownBy(() -> l.tryAcquire(key("a"))).isInstanceOf(ServerBusyException.class);
        l.tryAcquire(key("b"));
        assertThat(busy).containsExactly("a:download_tenant");
    }

    @Test
    void theReplicaCapRefusalIsRecordedWithTheReplicaScope() {
        InlineLimiter l = PublicComponents.inlineLimiter(tools(2, 2), metrics);
        l.tryAcquire(key("a"));
        l.tryAcquire(key("b"));
        assertThatThrownBy(() -> l.tryAcquire(key("c"))).isInstanceOf(ServerBusyException.class);
        assertThat(busy).containsExactly("c:download_replica");
    }
}
