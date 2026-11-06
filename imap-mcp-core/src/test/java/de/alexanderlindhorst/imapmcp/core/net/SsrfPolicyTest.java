package de.alexanderlindhorst.imapmcp.core.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.net.HostRejectedException.Reason;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfPolicyTest {

    private final SsrfPolicy any = SsrfPolicy.of(List.of(), true, List.of());

    private static Reason reasonOf(Runnable r) {
        try {
            r.run();
        } catch (HostRejectedException e) {
            return e.reason();
        }
        return null;
    }

    @Test
    void idnIsStoredAsALabel() {
        // bücher.example (u-umlaut) and a CJK name written as escapes
        assertThat(any.normalizeHost("bücher.example")).isEqualTo("xn--bcher-kva.example");
        assertThat(any.normalizeHost("例え.example")).startsWith("xn--").endsWith(".example");
        assertThat(any.normalizeHost("Mail.Example.ORG")).isEqualTo("mail.example.org");
    }

    @Test
    void ideographicFullStopSeparatesLabels() {
        assertThat(any.normalizeHost("imap。example。org")).isEqualTo("imap.example.org");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "10.1.2.3", "8.8.8.8", "::1", "[::1]", "2001:4860:4860::8888", "::ffff:10.0.0.1",
            "2130706433", "0x7f000001", "0x7f.1", "127.1", "0177.0.0.1", "1.2.3.4.", "fe80::1%eth0",
            // full-width digits that IDN processing folds to ASCII digits
            "１２７.0.0.1", "１２７。０。０。１"})
    void ipLiteralsAreRefused(String host) {
        assertThat(reasonOf(() -> any.normalizeHost(host))).as(host).isEqualTo(Reason.IP_LITERAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "localhost", "mailserver", "a..b.example", ".example.org", "-bad.example.org",
            "bad-.example.org", "under_score.example.org", "sp ace.example.org", "a/b.example.org",
            "user@example.org", "example.org:993", "example.org/path", "ex\u0000ample.org",
            "a‮b.example.org"})
    void malformedHostsAreRefused(String host) {
        assertThat(reasonOf(() -> any.normalizeHost(host))).as(host).isEqualTo(Reason.INVALID_HOST);
    }

    @Test
    void nullAndOverlongHostsAreRefused() {
        assertThat(reasonOf(() -> any.normalizeHost(null))).isEqualTo(Reason.INVALID_HOST);
        String label = "a".repeat(63);
        String ok = String.join(".", label, label, label, "a".repeat(61)); // 63*3 + 61 + 3 dots = 253
        assertThat(any.normalizeHost(ok)).hasSize(253);
        assertThat(reasonOf(() -> any.normalizeHost(ok + "b"))).isEqualTo(Reason.INVALID_HOST);
        assertThat(reasonOf(() -> any.normalizeHost("a".repeat(64) + ".example.org"))).isEqualTo(Reason.INVALID_HOST);
    }

    @Test
    void allowlistAppliesExactAndSuffixPatterns() {
        SsrfPolicy p = SsrfPolicy.of(List.of("imap.example.org", "*.mail.example.net", "Bücher.example"),
                false, List.of());
        assertThat(p.normalizeHost("imap.example.org")).isEqualTo("imap.example.org");
        assertThat(p.normalizeHost("IMAP.Example.Org")).isEqualTo("imap.example.org");
        assertThat(p.normalizeHost("a.mail.example.net")).isEqualTo("a.mail.example.net");
        assertThat(p.normalizeHost("a.b.mail.example.net")).isEqualTo("a.b.mail.example.net");
        assertThat(p.normalizeHost("xn--bcher-kva.example")).isEqualTo("xn--bcher-kva.example");
        assertThat(reasonOf(() -> p.normalizeHost("mail.example.net"))).isEqualTo(Reason.NOT_ALLOWED);
        assertThat(reasonOf(() -> p.normalizeHost("evilmail.example.net"))).isEqualTo(Reason.NOT_ALLOWED);
        assertThat(reasonOf(() -> p.normalizeHost("imap.example.org.evil.test"))).isEqualTo(Reason.NOT_ALLOWED);
        assertThat(reasonOf(() -> p.normalizeHost("other.example.org"))).isEqualTo(Reason.NOT_ALLOWED);
    }

    @Test
    void emptyAllowlistWithoutAllowAnyFailsStartup() {
        assertThatThrownBy(() -> SsrfPolicy.of(List.of(), false, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SsrfPolicy.of(List.of(), true, List.of())).isNotNull();
    }

    @Test
    void aNonEmptyAllowlistIsEnforcedEvenWithAllowAny() {
        SsrfPolicy p = SsrfPolicy.of(List.of("imap.example.org"), true, List.of());
        assertThat(reasonOf(() -> p.normalizeHost("other.example.org"))).isEqualTo(Reason.NOT_ALLOWED);
    }

    @Test
    void badPatternsFailStartup() {
        for (String bad : List.of("*", "*.", "*example.org", "a.*.example.org", "10.0.0.1", "")) {
            assertThatThrownBy(() -> SsrfPolicy.of(List.of(bad), false, List.of())).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void portsFollowTheTlsRule() {
        assertThat(any.checkEndpoint("imap.example.org", 993, Security.IMPLICIT_TLS)).isEqualTo("imap.example.org");
        assertThat(any.checkEndpoint("IMAP.example.org", 143, Security.STARTTLS)).isEqualTo("imap.example.org");
        assertThat(reasonOf(() -> any.checkEndpoint("imap.example.org", 143, Security.IMPLICIT_TLS)))
                .isEqualTo(Reason.PORT);
        assertThat(reasonOf(() -> any.checkEndpoint("imap.example.org", 993, Security.STARTTLS)))
                .isEqualTo(Reason.PORT);
        for (int port : new int[] {0, 22, 25, 110, 465, 587, 1143, 3306, 6379, 8080, 65535, -1}) {
            assertThat(reasonOf(() -> any.checkEndpoint("imap.example.org", port, Security.IMPLICIT_TLS)))
                    .as("port " + port).isEqualTo(Reason.PORT);
        }
    }

    @Test
    void checkEndpointAppliesHostRulesFirst() {
        assertThat(reasonOf(() -> any.checkEndpoint("127.0.0.1", 993, Security.IMPLICIT_TLS)))
                .isEqualTo(Reason.IP_LITERAL);
    }

    @Test
    void policyUsesExtraCidrsForAddresses() {
        SsrfPolicy p = SsrfPolicy.of(List.of(), true, List.of("93.184.216.0/24"));
        assertThat(p.isBlocked(AddressBlocklistTest.ip("93.184.216.34"))).isTrue();
        assertThat(p.isBlocked(AddressBlocklistTest.ip("93.184.217.34"))).isFalse();
        assertThat(p.isBlocked(AddressBlocklistTest.ip("127.0.0.1"))).isTrue();
    }
}
