package de.alexanderlindhorst.imapmcp.core.account;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import org.junit.jupiter.api.Test;

class AccountHandlesTest {

    private static final byte[] SALT = new byte[16];
    static {
        for (int i = 0; i < 16; i++) {
            SALT[i] = (byte) i;
        }
    }

    private static String derive(byte[] salt, String tenant, String account) {
        return AccountHandles.derive(salt, new TenantId(tenant), new AccountId(account));
    }

    @Test
    void sameInputsGiveSameHandle() {
        assertThat(derive(SALT, "t1", "work")).isEqualTo(derive(SALT.clone(), "t1", "work"));
    }

    @Test
    void tenantAndSaltSeparateHandles() {
        byte[] other = SALT.clone();
        other[0] ^= 1;
        assertThat(derive(SALT, "t1", "work")).isNotEqualTo(derive(SALT, "t2", "work"));
        assertThat(derive(SALT, "t1", "work")).isNotEqualTo(derive(other, "t1", "work"));
    }

    @Test
    void outputMatchesFormat() {
        for (int i = 0; i < 200; i++) {
            assertThat(derive(SALT, "t" + i, "a" + i)).matches("^acc_[a-z2-7]{26}$");
        }
    }

    // Expected values computed independently with Python 3:
    //   d = hashlib.sha256(b'imap-mcp:acct' + bytes(range(16)) + lp(tenant) + lp(account)).digest()
    //   'acc_' + base64.b32encode(d).decode().lower().rstrip('=')[:26]   (lp = 4-byte big-endian length + UTF-8)
    @Test
    void knownAnswer() {
        assertThat(derive(SALT, "tenant-a", "work")).isEqualTo("acc_6sukopm74oxzxcu3ki3gw3u4pt");
        assertThat(derive(SALT, "ab", "c")).isEqualTo("acc_m2fn556bwnpmk6k3b2b2ajwgw2");
    }

    @Test
    void lengthPrefixPreventsAmbiguity() {
        assertThat(derive(SALT, "ab", "c")).isNotEqualTo(derive(SALT, "a", "bc"));
    }

    @Test
    void validatorAcceptsDerivedAndRejectsMalformed() {
        assertThat(AccountHandles.isValid(derive(SALT, "t1", "work"))).isTrue();
        String ok = "acc_6sukopm74oxzxcu3ki3gw3u4pt";
        assertThat(AccountHandles.isValid(ok)).isTrue();
        for (String bad : new String[] {null, "", "acx_6sukopm74oxzxcu3ki3gw3u4pt", "6sukopm74oxzxcu3ki3gw3u4pt",
                "acc_6sukopm74oxzxcu3ki3gw3u4p", "acc_6sukopm74oxzxcu3ki3gw3u4ptt", "acc_6SUKOPM74OXZXCU3KI3GW3U4PT",
                "acc_1sukopm74oxzxcu3ki3gw3u4pt", "acc_8sukopm74oxzxcu3ki3gw3u4pt", "acc_6sukopm74oxzxcu3ki3gw3u4p-",
                ok + "\n"}) {
            assertThat(AccountHandles.isValid(bad)).as("%s", bad).isFalse();
        }
    }

    @Test
    void rejectsWrongSaltLength() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> derive(new byte[15], "t", "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
