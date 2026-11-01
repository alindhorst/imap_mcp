package de.alexanderlindhorst.imapmcp.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void accountIdAcceptsConfigIds() {
        assertThat(new AccountId("work-1").value()).isEqualTo("work-1");
        assertThat(new AccountId("a".repeat(32)).value()).hasSize(32);
    }

    @Test
    void accountIdRejectsInvalid() {
        for (String bad : new String[] {"", "A", "a b", "a_b", "a".repeat(33), "\u00e4"}) {
            assertThatThrownBy(() -> new AccountId(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AccountId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void tenantIdRejectsBlank() {
        assertThatThrownBy(() -> new TenantId(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new TenantId("t1")).isEqualTo(new TenantId("t1"));
    }

    @Test
    void accountHandleParseIsLenient() {
        assertThat(AccountHandle.parse("acc_AbC-123_x")).isPresent();
        assertThat(AccountHandle.parse("acc_AbC-123_x").get().value()).isEqualTo("acc_AbC-123_x");
        for (String bad : new String[] {null, "", "acc_", "xyz", "acc_a b", "acc_" + "a".repeat(200)}) {
            assertThat(AccountHandle.parse(bad)).as("%s", bad).isEmpty();
        }
    }

    @Test
    void contextCarriesTenant() {
        assertThat(new AccountContext(new TenantId("t1")).tenantId()).isEqualTo(new TenantId("t1"));
        assertThatThrownBy(() -> new AccountContext(null)).isInstanceOf(NullPointerException.class);
    }
}
