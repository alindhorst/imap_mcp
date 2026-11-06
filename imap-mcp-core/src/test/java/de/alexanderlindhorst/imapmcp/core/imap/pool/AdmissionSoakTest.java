package de.alexanderlindhorst.imapmcp.core.imap.pool;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Soak of the admission maps with many synthetic tenants (SPEC 5.5, 5.8): an entry exists only while a permit is held. */
class AdmissionSoakTest {

    private static final int TENANTS = 20_000;

    private static PoolKey key(int i) {
        TenantId t = new TenantId("tenant-" + i);
        MailAccount a = new MailAccount(new AccountId("acc" + i), "l", null, "h", 993, Security.IMPLICIT_TLS,
                Flavor.GENERIC, "u", new SecretRef("env:X"));
        return PoolKey.of(new AccountContext(t), new ResolvedAccount(t, a, "acc_" + i));
    }

    @Test
    void entriesGoAwayWithTheirLastPermitNoMatterHowManyTenantsPassed() {
        Admission admission = new Admission(PoolSettings.DEFAULTS);
        for (int i = 0; i < TENANTS; i++) {
            try (Admission.Permit p = admission.tryAcquire(key(i))) {
                assertThat(admission.tenantEntries()).isEqualTo(1);
                assertThat(admission.mailboxEntries()).isEqualTo(1);
            }
        }
        assertThat(admission.tenantEntries()).isZero();
        assertThat(admission.mailboxEntries()).isZero();
    }

    @Test
    void heldPermitsBoundTheEntriesAndRefusedCallsLeaveNothing() {
        PoolSettings s = PoolSettings.DEFAULTS;
        Admission admission = new Admission(s);
        List<Admission.Permit> held = new ArrayList<>();
        int refused = 0;
        for (int i = 0; i < TENANTS; i++) {
            try {
                held.add(admission.tryAcquire(key(i)));
            } catch (ServerBusyException e) {
                refused++;
            }
            assertThat(admission.tenantEntries()).isLessThanOrEqualTo(s.maxTotal());
            assertThat(admission.mailboxEntries()).isLessThanOrEqualTo(s.maxTotal());
        }
        assertThat(held).hasSize(s.maxTotal());
        assertThat(refused).isEqualTo(TENANTS - s.maxTotal());
        held.forEach(Admission.Permit::close);
        assertThat(admission.tenantEntries()).isZero();
        assertThat(admission.mailboxEntries()).isZero();
        assertThat(admission.availableGlobal()).isEqualTo(s.maxTotal());
    }
}
