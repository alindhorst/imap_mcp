package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The pure parts of {@link AccountsClient}: cookie jar, Set-Cookie attributes, page scraping (no stack needed). */
class AccountsClientTest {

    @Test
    void theJarKeepsCookiesPerHostAndSendsOnlyThoseOfTheHost() {
        AccountsClient.CookieJar jar = new AccountsClient.CookieJar();
        jar.store("mail.example.test", List.of("__Host-oidc=abc; Path=/; Secure; HttpOnly; SameSite=Lax"));
        jar.store("idp.example.test", List.of("AUTH_SESSION_ID=xyz; Path=/; Secure"));

        assertThat(jar.header("mail.example.test")).isEqualTo("__Host-oidc=abc");
        assertThat(jar.header("idp.example.test")).isEqualTo("AUTH_SESSION_ID=xyz");
        assertThat(jar.header("other.example.test")).isNull();
    }

    @Test
    void anExpiredOrEmptyCookieRemovesItAndANewValueReplacesTheOldOne() {
        AccountsClient.CookieJar jar = new AccountsClient.CookieJar();
        jar.store("h", List.of("a=1; Path=/", "b=2; Path=/"));
        jar.store("h", List.of("a=; Path=/; Max-Age=0", "b=3; Path=/"));

        assertThat(jar.header("h")).isEqualTo("b=3");
        jar.store("h", List.of("b=4; Path=/; Max-Age=0"));
        assertThat(jar.header("h")).isNull();
    }

    @Test
    void setCookieAttributesAreSplitIntoNameValueAndFlags() {
        AccountsClient.SetCookie c = AccountsClient.SetCookie.parse(
                "__Host-session=v1; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=3600");

        assertThat(c.name()).isEqualTo("__Host-session");
        assertThat(c.value()).isEqualTo("v1");
        assertThat(c.hasFlag("secure")).isTrue();
        assertThat(c.hasFlag("HttpOnly")).isTrue();
        assertThat(c.attribute("samesite")).isEqualTo("Strict");
        assertThat(c.attribute("Path")).isEqualTo("/");
        assertThat(c.attribute("Domain")).isNull();
        assertThat(c.hasFlag("Domain")).isFalse();
    }

    @Test
    void theCsrfTokenAndTheLoginFormActionAreScrapedFromThePages() {
        String home = "<form action=\"/accounts/logout\" method=\"post\"><input type=\"hidden\" name=\"csrf\" "
                + "value=\"tok-_9\"><button>Sign out</button></form>";
        String login = "<form id=\"kc-form-login\" onsubmit=\"x\" action=\"https://idp.example.test/a?x=1&amp;y=2\""
                + " method=\"post\"><input name=\"username\"><input name=\"password\" type=\"password\"></form>";

        assertThat(AccountsClient.csrfToken(home)).isEqualTo("tok-_9");
        assertThat(AccountsClient.csrfToken("<p>nothing</p>")).isNull();
        assertThat(AccountsClient.loginFormAction(login)).isEqualTo("https://idp.example.test/a?x=1&y=2");
        assertThat(AccountsClient.loginFormAction(home)).isNull();
    }
}
