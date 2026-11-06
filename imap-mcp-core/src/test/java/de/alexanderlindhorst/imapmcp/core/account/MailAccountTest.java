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
    void readOnlyDefaultsToFalseAndToStringStaysCleanWhenSet() {
        MailAccount writable = new MailAccount(new AccountId("work"), "Work", ADDRESS, "imap.example.org", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, USER, new SecretRef(SECRET));
        MailAccount readOnly = new MailAccount(new AccountId("work"), "Work", ADDRESS, "imap.example.org", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, USER, new SecretRef(SECRET), true);
        assertThat(writable.readOnly()).isFalse();
        assertThat(readOnly.readOnly()).isTrue();
        assertThat(readOnly.toString()).doesNotContain(USER, ADDRESS, SECRET, "9f3", "Work");
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

    @Test
    void authStatusAndRevisionDefaultForOldConstructors() {
        MailAccount account = new MailAccount(new AccountId("w"), "W", null, "h", 143,
                MailAccount.Security.STARTTLS, MailAccount.Flavor.GMAIL, "u", new SecretRef("env:X"), true, true);
        assertThat(account.auth()).isEqualTo(MailAccount.Auth.PASSWORD);
        assertThat(account.status()).isEqualTo(MailAccount.Status.ACTIVE);
        assertThat(account.revision()).isZero();
    }

    @Test
    void fullConstructorCarriesAuthStatusAndRevision() {
        MailAccount account = new MailAccount(new AccountId("w"), "W", null, "h", 143,
                MailAccount.Security.STARTTLS, MailAccount.Flavor.GMAIL, "u", new SecretRef("db:"), false, false,
                MailAccount.Auth.XOAUTH2, MailAccount.Status.NEEDS_RELINK, 7L);
        assertThat(account.auth()).isEqualTo(MailAccount.Auth.XOAUTH2);
        assertThat(account.status()).isEqualTo(MailAccount.Status.NEEDS_RELINK);
        assertThat(account.revision()).isEqualTo(7L);
        assertThat(account.toString()).contains("revision=7").doesNotContain("db:");
    }

    @Test
    void negativeRevisionAndNullAuthOrStatusAreRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MailAccount(new AccountId("w"), "W", null, "h",
                143, MailAccount.Security.STARTTLS, MailAccount.Flavor.GMAIL, "u", new SecretRef("env:X"), false,
                false, MailAccount.Auth.PASSWORD, MailAccount.Status.ACTIVE, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MailAccount(new AccountId("w"), "W", null, "h",
                143, MailAccount.Security.STARTTLS, MailAccount.Flavor.GMAIL, "u", new SecretRef("env:X"), false,
                false, null, MailAccount.Status.ACTIVE, 0L)).isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MailAccount(new AccountId("w"), "W", null, "h",
                143, MailAccount.Security.STARTTLS, MailAccount.Flavor.GMAIL, "u", new SecretRef("env:X"), false,
                false, MailAccount.Auth.PASSWORD, null, 0L)).isInstanceOf(NullPointerException.class);
    }
}
