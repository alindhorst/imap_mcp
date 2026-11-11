package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** #430: PKCE S256 (RFC 7636) for the connector test client. No Docker. */
class PkceTest {

    @Test
    void theS256ChallengeIsTheRfc7636AppendixBVector() {
        assertThat(Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void aGeneratedVerifierIsUnreservedAndLongEnoughAndFresh() {
        String a = Pkce.newVerifier();
        assertThat(a).matches("[A-Za-z0-9\\-._~]{43,128}");
        assertThat(Pkce.newVerifier()).isNotEqualTo(a);
    }

    @Test
    void aStateCarriesAtLeast128BitsOfRandomnessAndIsUrlSafe() {
        String s = Pkce.newState();
        assertThat(s).matches("[A-Za-z0-9_-]{22,}");
        assertThat(Pkce.newState()).isNotEqualTo(s);
    }
}
