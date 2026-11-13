package de.alexanderlindhorst.imapmcp.app;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.app.config.UpstreamProvider;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.linking.UpstreamProviderInfo;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The mapping of {@code oauth.upstream.providers} (SPEC 5.7) to what the linking flow needs (X1-2). */
class UpstreamProviderCatalogTest {

    private static final String REDIRECT = "https://mcp.example/accounts/oauth/%s/callback";

    private static UpstreamProvider google() {
        return new UpstreamProvider("google", UpstreamProvider.Kind.GOOGLE, "gid", "Google", new SecretRef("env:G"),
                null, "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
                List.of("https://mail.google.com/"), "imap.gmail.com", 993, MailAccount.Security.IMPLICIT_TLS,
                REDIRECT.formatted("google"));
    }

    private static UpstreamProvider microsoft() {
        return new UpstreamProvider("ms", UpstreamProvider.Kind.MICROSOFT, "mid", "Microsoft",
                new SecretRef("file:/run/secrets/ms"), "organizations",
                "https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize",
                "https://login.microsoftonline.com/organizations/oauth2/v2.0/token",
                List.of("https://outlook.office.com/IMAP.AccessAsUser.All", "offline_access"),
                "outlook.office365.com", 993, MailAccount.Security.IMPLICIT_TLS, REDIRECT.formatted("ms"));
    }

    private static UpstreamProvider generic() {
        return new UpstreamProvider("corp", UpstreamProvider.Kind.GENERIC, "cid", "Corp", new SecretRef("env:C"),
                null, "https://login.corp.example.net/authorize?x=1", "https://login.corp.example.net/token",
                List.of("scope-a"), "imap.corp.example.net", 143, MailAccount.Security.STARTTLS,
                REDIRECT.formatted("corp"));
    }

    @Test
    void googleIsAGmailAccountThatAsksForOfflineConsent() {
        UpstreamProviderInfo i = new UpstreamProviderCatalog(List.of(google())).find("google").orElseThrow();

        assertThat(i.id()).isEqualTo("google");
        assertThat(i.label()).isEqualTo("Google");
        assertThat(i.authorizationEndpoint()).isEqualTo(URI.create("https://accounts.google.com/o/oauth2/v2/auth"));
        assertThat(i.clientId()).isEqualTo("gid");
        assertThat(i.scopes()).containsExactly("https://mail.google.com/");
        assertThat(i.redirectUri()).isEqualTo(REDIRECT.formatted("google"));
        assertThat(i.imapHost()).isEqualTo("imap.gmail.com");
        assertThat(i.imapPort()).isEqualTo(993);
        assertThat(i.imapSecurity()).isEqualTo(MailAccount.Security.IMPLICIT_TLS);
        assertThat(i.flavor()).isEqualTo(MailAccount.Flavor.GMAIL);
        assertThat(i.offlineConsent()).isTrue();
    }

    @Test
    void microsoftAndGenericAreGenericFlavorWithoutGoogleExtras() {
        UpstreamProviderCatalog c = new UpstreamProviderCatalog(List.of(microsoft(), generic()));

        UpstreamProviderInfo ms = c.find("ms").orElseThrow();
        assertThat(ms.flavor()).isEqualTo(MailAccount.Flavor.GENERIC);
        assertThat(ms.offlineConsent()).isFalse();
        assertThat(ms.scopes()).containsExactly("https://outlook.office.com/IMAP.AccessAsUser.All",
                "offline_access");
        UpstreamProviderInfo corp = c.find("corp").orElseThrow();
        assertThat(corp.flavor()).isEqualTo(MailAccount.Flavor.GENERIC);
        assertThat(corp.offlineConsent()).isFalse();
        assertThat(corp.imapPort()).isEqualTo(143);
        assertThat(corp.imapSecurity()).isEqualTo(MailAccount.Security.STARTTLS);
        assertThat(corp.authorizationEndpoint().getRawQuery()).isEqualTo("x=1");
    }

    @Test
    void allKeepsTheConfigurationOrderAndUnknownIdsAreEmpty() {
        UpstreamProviderCatalog c = new UpstreamProviderCatalog(List.of(microsoft(), google(), generic()));

        assertThat(c.all()).extracting(UpstreamProviderInfo::id).containsExactly("ms", "google", "corp");
        assertThat(c.find("nope")).isEmpty();
        assertThat(c.find(null)).isEmpty();
        assertThat(new UpstreamProviderCatalog(List.of()).all()).isEmpty();
    }

    @Test
    void theInfoCarriesNoSecretReferenceOrTokenEndpoint() {
        String text = new UpstreamProviderCatalog(List.of(google())).all().toString();

        assertThat(text).doesNotContain("env:G").doesNotContain("oauth2.googleapis.com");
    }
}
