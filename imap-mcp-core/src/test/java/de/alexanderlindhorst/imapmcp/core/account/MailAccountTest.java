package de.alexanderlindhorst.imapmcp.core.account;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import org.junit.jupiter.api.Test;

class MailAccountTest {

    private static final String USER = "MARKER-USER-9f3";
    private static final String ADDRESS = "marker-addr-9f3@example.org";
    private static final String SECRET = "MARKER-SECRET-9f3";

    @Test
    void toStringNeverLeaksUsernameAddressOrSecretRef() {
        MailAccount account = new MailAccount(new AccountId("work"), "Work", ADDRESS, "imap.example.org", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, USER, new SecretRef(SECRET));
        assertThat(account.toString()).doesNotContain(USER, ADDRESS, SECRET, "9f3").contains("work");
    }

    @Test
    void secretRefToStringHidesValue() {
        assertThat(new SecretRef(SECRET).toString()).doesNotContain(SECRET, "9f3");
    }

    @Test
    void secretToStringHidesMaterialAndCloseWipes() {
        char[] material = SECRET.toCharArray();
        Secret secret = new Secret(material);
        assertThat(secret.toString()).doesNotContain("9f3");
        secret.close();
        assertThat(material).containsOnly('\0');
    }

    @Test
    void addressIsNullable() {
        MailAccount account = new MailAccount(new AccountId("w"), "W", null, "h", 143,
                MailAccount.Security.STARTTLS, MailAccount.Flavor.GMAIL, "u", new SecretRef("env:X"));
        assertThat(account.address()).isNull();
    }
}
