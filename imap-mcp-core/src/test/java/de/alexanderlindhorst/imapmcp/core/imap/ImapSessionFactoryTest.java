package de.alexanderlindhorst.imapmcp.core.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import de.alexanderlindhorst.imapmcp.core.ExcludeFixtures;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ImapSessionFactoryTest {

    private static final TenantId TENANT = new TenantId("t1");
    private static final String SECRET_MARKER = "env:PASSWORD_MARKER_9f3";

    private final ImapSessionFactory factory = new ImapSessionFactory(ImapTimeouts.DEFAULTS);

    @AfterEach
    void close() {
        factory.close();
    }

    private static MailAccount account(String id, Security security, Flavor flavor) {
        return new MailAccount(new AccountId(id), "label", null, "imap.example.org",
                security == Security.IMPLICIT_TLS ? 993 : 143, security, flavor, "user", new SecretRef(SECRET_MARKER));
    }

    private static PoolKey key(MailAccount a) {
        return PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, a, "acc_test"));
    }

    private Properties props(MailAccount a) {
        return factory.sessionFor(key(a), a).getProperties();
    }

    @Test
    void prefixFollowsProtocol() {
        assertPrefixOnly(account("a", Security.IMPLICIT_TLS, Flavor.GENERIC), "imaps");
        assertPrefixOnly(account("b", Security.IMPLICIT_TLS, Flavor.GMAIL), "gimaps");
        assertPrefixOnly(account("c", Security.STARTTLS, Flavor.GENERIC), "imap");
    }

    private void assertPrefixOnly(MailAccount a, String protocol) {
        assertThat(ImapSessionFactory.protocolFor(a)).isEqualTo(protocol);
        Properties p = props(a);
        String prefix = "mail." + protocol + ".";
        assertThat(p.stringPropertyNames().stream().filter(k -> k.startsWith("mail.imap")
                || k.startsWith("mail.gimap"))).allMatch(k -> k.startsWith(prefix));
        assertThat(p.keySet().stream().map(Object::toString).filter(k -> k.startsWith(prefix))).isNotEmpty();
    }

    @Test
    void gmailWithStarttlsIsRejected() {
        MailAccount a = account("g", Security.STARTTLS, Flavor.GMAIL);
        assertThatThrownBy(() -> ImapSessionFactory.protocolFor(a)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.sessionFor(key(a), a)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactPropertyValuesForImaps() {
        Properties p = props(account("a", Security.IMPLICIT_TLS, Flavor.GENERIC));
        Map<String, Object> expected = Map.of(
                "mail.imaps.connectiontimeout", "10000",
                "mail.imaps.timeout", "60000",
                "mail.imaps.writetimeout", "60000",
                "mail.imaps.connectionpoolsize", "1",
                "mail.imaps.ssl.checkserveridentity", "true",
                "mail.imaps.peek", "true",
                "mail.imaps.throwsearchexception", "true",
                "mail.imaps.sasl.enable", "false",
                "mail.imaps.auth.mechanisms", "LOGIN PLAIN",
                "mail.debug.auth", "false");
        expected.forEach((k, v) -> assertThat(p.get(k)).as(k).hasToString(v.toString()));
        assertThat(p.get("mail.debug.auth.username")).hasToString("false");
        assertThat(p.get("mail.debug.auth.password")).hasToString("false");
        assertThat(p.get("mail.imaps.executor.writetimeout")).isInstanceOf(ScheduledExecutorService.class);
        assertThat(p.keySet().stream().map(Object::toString)).noneMatch(k -> k.contains("starttls"));
    }

    @Test
    void timeoutsComeFromConfig() {
        try (ImapSessionFactory f = new ImapSessionFactory(
                new ImapTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3)))) {
            MailAccount a = account("a", Security.IMPLICIT_TLS, Flavor.GENERIC);
            Properties p = f.sessionFor(key(a), a).getProperties();
            assertThat(p.get("mail.imaps.connectiontimeout")).hasToString("1000");
            assertThat(p.get("mail.imaps.timeout")).hasToString("2000");
            assertThat(p.get("mail.imaps.writetimeout")).hasToString("3000");
        }
    }

    @Test
    void starttlsRequiredOnImapOnly() {
        Properties p = props(account("c", Security.STARTTLS, Flavor.GENERIC));
        assertThat(p.get("mail.imap.starttls.enable")).hasToString("true");
        assertThat(p.get("mail.imap.starttls.required")).hasToString("true");
        assertThat(p.keySet().stream().map(Object::toString)).noneMatch(k -> k.startsWith("mail.imaps."));
        Properties tls = props(account("a", Security.IMPLICIT_TLS, Flavor.GENERIC));
        assertThat(tls.keySet().stream().map(Object::toString)).noneMatch(k -> k.contains("starttls"));
    }

    @Test
    void sessionCachedPerKey() {
        MailAccount a = account("a", Security.IMPLICIT_TLS, Flavor.GENERIC);
        MailAccount b = account("b", Security.IMPLICIT_TLS, Flavor.GENERIC);
        Session sa = factory.sessionFor(key(a), a);
        assertThat(factory.sessionFor(key(a), a)).isSameAs(sa);
        assertThat(factory.sessionFor(key(b), b)).isNotSameAs(sa);
        // same account id under another tenant is another key
        TenantId other = new TenantId("t2");
        PoolKey otherKey = PoolKey.of(new AccountContext(other), new ResolvedAccount(other, a, "acc_test"));
        assertThat(factory.sessionFor(otherKey, a)).isNotSameAs(sa);
    }

    @Test
    void noPasswordOrSecretRefInProperties() {
        for (MailAccount a : new MailAccount[]{account("a", Security.IMPLICIT_TLS, Flavor.GENERIC),
                account("c", Security.STARTTLS, Flavor.GENERIC)}) {
            Properties p = props(a);
            assertThat(p.values().stream().map(Object::toString)).noneMatch(v -> v.contains("PASSWORD_MARKER"));
            assertThat(p.keySet().stream().map(Object::toString)).noneMatch(k -> k.endsWith(".password")
                    && !k.equals("mail.debug.auth.password"));
        }
    }

    @Test
    void gimapsProviderResolves() throws NoSuchProviderException {
        MailAccount g = account("g", Security.IMPLICIT_TLS, Flavor.GMAIL);
        assertThat(factory.sessionFor(key(g), g).getStore("gimaps")).isNotNull();
        MailAccount a = account("a", Security.IMPLICIT_TLS, Flavor.GENERIC);
        assertThat(factory.sessionFor(key(a), a).getStore("imaps")).isNotNull();
    }

    @Test
    void testHookCanAdjustProperties() {
        try (ImapSessionFactory f = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            return p;
        })) {
            MailAccount a = account("a", Security.IMPLICIT_TLS, Flavor.GENERIC);
            assertThat(f.sessionFor(key(a), a).getProperties().get("mail.imaps.ssl.trust")).hasToString("*");
        }
    }

    @Test
    void neverUsesGetDefaultInstance() {
        var classes = new ClassFileImporter().withImportOption(new ExcludeFixtures())
                .importPackages("de.alexanderlindhorst.imapmcp");
        DescribedPredicate<JavaCall<?>> callsDefault = DescribedPredicate.describe(
                "call Session.getDefaultInstance",
                c -> c.getTargetOwner().isEquivalentTo(Session.class)
                        && c.getName().equals("getDefaultInstance"));
        ArchRuleDefinition.noClasses().should().callCodeUnitWhere(callsDefault).check(classes);
    }
}
