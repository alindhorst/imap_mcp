package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Read checks of the smoke harness (#258, SPEC 5.8) through the shaded jar: {@code list_accounts} status and
 * capabilities, {@code list_folders} roles, one {@code search_messages}, one {@code get_message}, and that reading
 * leaves {@code \Seen} unchanged (PEEK). The account is read-only. Skipped (assumption) with the reason when the
 * subclass has no configuration. Assertions never include mail data.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractReadSmoke {

    private SmokeClient client;
    private SmokeEnv.Settings settings;
    private String handle;

    /** The account to test; empty skips every test of the class. */
    abstract Optional<SmokeEnv.Settings> settings();

    /** Why {@link #settings()} is empty. */
    abstract String skipReason();

    /** Special-use roles {@code list_folders} must report for this provider. */
    List<String> expectedRoles() {
        return List.of();
    }

    /** Whether {@code list_accounts} must report the Gmail flavor. */
    boolean expectGmail() {
        return false;
    }

    @BeforeAll
    void start() throws Exception {
        Optional<SmokeEnv.Settings> s = settings();
        assumeTrue(s.isPresent(), () -> skipReason());
        settings = s.get();
        client = SmokeClient.start(settings, false);
    }

    @AfterAll
    void stop() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void listAccountsReportsStatusOkAndCapabilities() {
        Map<String, Object> account = client.ok("list_accounts").list("accounts").get(0);
        assertThat(account).containsEntry("status", "ok");
        Map<String, Object> caps = (Map<String, Object>) account.get("capabilities");
        assertThat(caps).containsKeys("move", "uidplus", "special_use", "sort", "gmail");
        assertThat(account.get("provider")).isEqualTo(expectGmail() ? "gmail" : "generic");
        assertThat(caps.get("gmail")).isEqualTo(expectGmail());
        if (expectGmail()) {
            assertThat(caps).containsEntry("move", true).containsEntry("uidplus", true);
        }
    }

    @Test
    @Order(2)
    void listFoldersReportsInboxAndTheSpecialUseRoles() {
        List<Map<String, Object>> folders = client.ok("list_folders").list("folders");
        assertThat(folders).extracting(f -> f.get("folder")).contains("INBOX");
        Map<String, String> byRole = new LinkedHashMap<>();
        for (Map<String, Object> f : folders) {
            if (f.get("role") != null) {
                byRole.put((String) f.get("role"), (String) f.get("folder"));
            }
        }
        assertThat(byRole).containsKeys(expectedRoles().toArray(String[]::new));
    }

    @Test
    @Order(3)
    void searchMessagesPagesTheInbox() {
        Map<String, Object> page = client.ok("search_messages", "folder", "INBOX", "page_size", 5).structured();
        int total = ((Number) page.get("total")).intValue();
        assumeTrue(total > 0, "INBOX of the smoke account is empty: put at least one message (one unread is "
                + "best) into it to run the search, get_message and PEEK checks");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) page.get("messages");
        assertThat(messages).isNotEmpty().allSatisfy(m -> assertThat(m).containsKeys("handle", "flags", "received_at"));
    }

    @Test
    @Order(4)
    void getMessageReturnsTheMessageForAHandleFromSearch() {
        handle = pickHandle();
        assumeTrue(handle != null, "INBOX of the smoke account is empty");
        Map<String, Object> m = client.ok("get_message", "message", handle).structured();
        assertThat(m).containsEntry("handle", handle).containsEntry("folder", "INBOX").containsKeys("untrusted",
                "flags");
    }

    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void readingLeavesTheSeenFlagUnchanged() {
        // Prefer an unread message: a message that is already read proves nothing.
        List<Map<String, Object>> unread = client.search("folder", "INBOX", "seen", false, "page_size", 25);
        assumeTrue(!unread.isEmpty(), "no unread message in the INBOX of the smoke account: the PEEK check needs "
                + "one (the check never marks anything read, it only proves that reading does not)");
        String target = (String) unread.get(0).get("handle");

        client.ok("get_message", "message", target);

        List<Map<String, Object>> after = client.search("folder", "INBOX", "seen", false, "page_size", 25);
        assertThat(after).extracting(m -> m.get("handle")).contains(target);
        Map<String, Object> still = after.stream().filter(m -> target.equals(m.get("handle"))).findFirst()
                .orElseThrow();
        assertThat((List<String>) still.get("flags")).doesNotContain("\\Seen");
    }

    private String pickHandle() {
        List<Map<String, Object>> page = client.search("folder", "INBOX", "page_size", 1);
        return page.isEmpty() ? null : (String) page.get(0).get("handle");
    }
}
