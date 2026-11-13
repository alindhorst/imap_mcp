package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Auth;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Status;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Session properties of XOAUTH2 accounts (SPEC 5.5 "XOAUTH2", IMAP). */
class ImapSessionFactoryXoauth2Test {

    private static final TenantId TENANT = new TenantId("t1");

    private final ImapSessionFactory factory = new ImapSessionFactory(ImapTimeouts.DEFAULTS);

    @AfterEach
    void close() {
        factory.close();
    }

    static MailAccount account(Security security, Flavor flavor, Auth auth) {
        return new MailAccount(new AccountId("a"), "label", null, "imap.example.org",
                security == Security.IMPLICIT_TLS ? 993 : 143, security, flavor, "user@example.org",
                new SecretRef("db:"), false, false, auth, Status.ACTIVE, 0L);
    }

    private Properties props(MailAccount a) {
        PoolKey key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, a, "acc_test"));
        return factory.sessionFor(key, a).getProperties();
    }

    private void assertXoauth2(MailAccount a, String prefix) {
        Properties p = props(a);
        assertThat(p.getProperty("mail." + prefix + ".auth.mechanisms")).isEqualTo("XOAUTH2");
        assertThat(p.getProperty("mail." + prefix + ".sasl.enable")).isEqualTo("false");
        // no other prefix carries an auth setting
        assertThat(p.stringPropertyNames()).filteredOn(k -> k.contains("auth.mechanisms"))
                .containsExactly("mail." + prefix + ".auth.mechanisms");
    }

    @Test
    void imapsAccountUsesXoauth2Only() {
        assertXoauth2(account(Security.IMPLICIT_TLS, Flavor.GENERIC, Auth.XOAUTH2), "imaps");
    }

    @Test
    void gmailAccountUsesXoauth2UnderTheGimapsPrefix() {
        assertXoauth2(account(Security.IMPLICIT_TLS, Flavor.GMAIL, Auth.XOAUTH2), "gimaps");
    }

    @Test
    void starttlsAccountUsesXoauth2UnderTheImapPrefix() {
        assertXoauth2(account(Security.STARTTLS, Flavor.GENERIC, Auth.XOAUTH2), "imap");
    }

    @Test
    void passwordAccountsStayOnLoginPlain() {
        Properties p = props(account(Security.IMPLICIT_TLS, Flavor.GENERIC, Auth.PASSWORD));
        assertThat(p.getProperty("mail.imaps.auth.mechanisms")).isEqualTo("LOGIN PLAIN");
        assertThat(p.getProperty("mail.imaps.sasl.enable")).isEqualTo("false");
    }

    @Test
    void sameKeyAndRevisionWithDifferentAuthDoNotShareASession() {
        MailAccount password = account(Security.IMPLICIT_TLS, Flavor.GENERIC, Auth.PASSWORD);
        MailAccount xoauth2 = account(Security.IMPLICIT_TLS, Flavor.GENERIC, Auth.XOAUTH2);
        PoolKey key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, password, "acc_test"));
        assertThat(factory.sessionFor(key, xoauth2)).isNotSameAs(factory.sessionFor(key, password));
    }
}
