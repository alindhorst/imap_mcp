package de.alexanderlindhorst.imapmcp.core.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ServerBusyException;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InlineLimiterTest {

    @Test
    void grantsAtMostMaxAndFreesOnRelease() {
        InlineLimiter l = new InlineLimiter(2);
        assertThat(l.tryAcquire()).isTrue();
        assertThat(l.tryAcquire()).isTrue();
        assertThat(l.tryAcquire()).isFalse();
        l.release();
        assertThat(l.tryAcquire()).isTrue();
        assertThat(l.tryAcquire()).isFalse();
    }

    @Test
    void aNonPositiveMaxIsRefused() {
        assertThatThrownBy(() -> new InlineLimiter(0)).isInstanceOf(IllegalArgumentException.class);
    }

    static PoolKey key(String tenant) {
        TenantId t = new TenantId(tenant);
        MailAccount a = new MailAccount(new AccountId("a"), "l", null, "127.0.0.1", 993, Security.IMPLICIT_TLS,
                Flavor.GENERIC, "u", new SecretRef("env:X"));
        return PoolKey.of(new AccountContext(t), new ResolvedAccount(t, a, "acc_x"));
    }

    final List<String> rejected = new ArrayList<>();

    InlineLimiter limiter(int max, int perTenant) {
        return new InlineLimiter(max, perTenant, (k, scope) -> rejected.add(k.tenantId().value() + ":" + scope));
    }

    @Test
    void oneTenantIsCappedWhileAnotherTenantStillGetsReplicaPermits() {
        InlineLimiter l = limiter(4, 2);
        InlineLimiter.Permit a1 = l.tryAcquire(key("a"));
        InlineLimiter.Permit a2 = l.tryAcquire(key("a"));
        assertThatThrownBy(() -> l.tryAcquire(key("a"))).isInstanceOf(ServerBusyException.class);
        assertThat(rejected).containsExactly("a:download_tenant");
        InlineLimiter.Permit b1 = l.tryAcquire(key("b")); // the replica still has room for others
        a1.close();
        l.tryAcquire(key("a")).close();
        a2.close();
        b1.close();
    }

    @Test
    void aMissingReplicaPermitGivesTheTenantPermitBackAndIsReportedAsReplica() {
        InlineLimiter l = limiter(1, 1);
        InlineLimiter.Permit a = l.tryAcquire(key("a"));
        assertThatThrownBy(() -> l.tryAcquire(key("b"))).isInstanceOf(ServerBusyException.class);
        assertThat(rejected).containsExactly("b:download_replica");
        assertThat(l.tenantEntries()).isEqualTo(1); // only a's entry: b's permit was given back
        a.close();
        l.tryAcquire(key("b")).close();
    }

    @Test
    void theTenantPermitIsTakenFirstSoBothCapsFullIsReportedAsTenant() {
        InlineLimiter l = limiter(1, 1);
        InlineLimiter.Permit a = l.tryAcquire(key("a"));
        assertThatThrownBy(() -> l.tryAcquire(key("a"))).isInstanceOf(ServerBusyException.class);
        assertThat(rejected).containsExactly("a:download_tenant");
        a.close();
    }

    @Test
    void tenantEntriesExistOnlyWhileAPermitIsHeldAndCloseIsIdempotent() {
        InlineLimiter l = limiter(2, 2);
        for (int i = 0; i < 1000; i++) {
            l.tryAcquire(key("tenant-" + i)).close();
        }
        assertThat(l.tenantEntries()).isZero();
        InlineLimiter.Permit p = l.tryAcquire(key("a"));
        assertThat(l.tenantEntries()).isEqualTo(1);
        p.close();
        p.close(); // a second close must not free a permit of someone else
        assertThat(l.tenantEntries()).isZero();
        InlineLimiter.Permit x = l.tryAcquire(key("a"));
        InlineLimiter.Permit y = l.tryAcquire(key("b"));
        assertThatThrownBy(() -> l.tryAcquire(key("c"))).isInstanceOf(ServerBusyException.class);
        x.close();
        y.close();
    }

    @Test
    void thePerTenantCapMustBeBetweenOneAndTheReplicaCap() {
        assertThatThrownBy(() -> new InlineLimiter(4, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InlineLimiter(2, 3)).isInstanceOf(IllegalArgumentException.class);
        new InlineLimiter(2, 2).tryAcquire(key("a")).close();
    }
}
