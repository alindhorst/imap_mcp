package de.alexanderlindhorst.imapmcp.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KeycloakAdminTest {

    /**
     * kcadm parses a value that starts with '-' after {@code --new-password} as another option and exits 2 (usage). A
     * url-safe base64 password starts with '-' in 1 of 64 runs, which was the flake of #519.
     */
    @Test
    void generatedPasswordsNeverLookLikeAnOptionAndMeetThePolicyLength() {
        for (int i = 0; i < 20_000; i++) {
            String p = KeycloakAdmin.newPassword();
            assertTrue(p.matches("[A-Za-z0-9]{24}"), "password shape");
        }
    }

    @Test
    void failureReasonIsAFixedClassWithoutKcadmOutput() {
        assertEquals("unauthorized", KeycloakAdmin.classify("Invalid user credentials secret-x"));
        assertEquals("unauthorized", KeycloakAdmin.classify("HTTP 401 Unauthorized"));
        assertEquals("policy", KeycloakAdmin.classify("error: invalidPasswordMinLengthMessage"));
        assertEquals("usage", KeycloakAdmin.classify("Unknown option: '-abc'"));
        assertEquals("other", KeycloakAdmin.classify("whatever"));
        assertEquals("other", KeycloakAdmin.classify(""));
    }
}
