package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.alexanderlindhorst.imapmcp.it.OAuthSmokeStack.Box;
import io.modelcontextprotocol.client.McpSyncClient;
import java.time.Duration;
import java.util.ArrayList;
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
 * XOAUTH2 against a real provider over {@code http-public} (#328, SPEC 5.5, 5.8; the provider assumptions of 5.5 are
 * unverified until the owner has run this). Subclasses name the provider. The class is skipped, with a reason, unless
 * the variables of {@link SmokeOAuthEnv} are set; Docker is then needed for the Postgres of the stack only.
 * Everything reads with PEEK, the one write is reversible and opt-in, and the purge runs only for the messages in
 * Trash whose exact subject the owner named. Failure messages carry tool names and error codes only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractOAuthLiveSmoke {

    abstract SmokeEnv.Provider provider();

    private SmokeOAuthEnv.Settings settings;
    private OAuthSmokeStack stack;
    private Box box;

    @BeforeAll
    void start() throws Exception {
        Optional<String> skip = SmokeOAuthEnv.skipReason(provider(), System.getenv());
        assumeTrue(skip.isEmpty(), () -> skip.orElse(""));
        settings = SmokeOAuthEnv.settings(provider(), System.getenv()).orElseThrow();
        stack = OAuthSmokeStack.start(settings);
        boolean purge = settings.purgeSubject().isPresent();
        box = stack.seed(settings, settings.refreshToken(), !settings.writeEnabled() && !purge, purge);
    }

    @AfterAll
    void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    private Map<String, Object> ok(String tool, Object... kv) throws Exception {
        Map<String, Object> r = stack.call(box, tool, kv);
        assertThat(OAuthSmokeStack.errorOf(r)).as("%s result", tool).isEqualTo("none");
        return r;
    }

    private String folderOfRole(String role) throws Exception {
        for (Map<String, Object> f : OAuthSmokeStack.list(ok("list_folders"), "folders")) {
            if (role.equals(f.get("role"))) {
                return (String) f.get("folder");
            }
        }
        return null;
    }

    private List<Map<String, Object>> messages(String folder, Object... kv) throws Exception {
        List<Object> args = new ArrayList<>(List.of("folder", folder, "page_size", 100));
        args.addAll(List.of(kv));
        return OAuthSmokeStack.list(ok("search_messages", args.toArray()), "messages");
    }

    private static String subjectOf(Map<String, Object> m) {
        return (String) OAuthSmokeStack.map(m, "untrusted").get("subject");
    }

    private static boolean flagged(Map<String, Object> m) {
        return flags(m).stream().anyMatch(f -> f.toLowerCase(java.util.Locale.ROOT).contains("flagged"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> flags(Map<String, Object> m) {
        return (List<String>) m.get("flags");
    }

    @Test
    @Order(1)
    void aFirstCallRefreshesTheTokenAndTheReadToolsWorkWithPeek() throws Exception {
        long seeded = Xoauth2Db.refreshedAtMillis(stack.ds(), box.seeded());
        Thread.sleep(50);

        List<Map<String, Object>> folders = OAuthSmokeStack.list(ok("list_folders"), "folders");
        assertThat(folders.stream().map(f -> (String) f.get("folder")).anyMatch("INBOX"::equalsIgnoreCase))
                .as("INBOX listed").isTrue();
        assertThat(Xoauth2Db.refreshedAtMillis(stack.ds(), box.seeded())).as("the grant was refreshed")
                .isGreaterThan(seeded);

        List<Map<String, Object>> before = messages("INBOX");
        assumeTrue(!before.isEmpty(), "INBOX of the test account is empty; nothing to read with PEEK");
        for (Map<String, Object> m : before.subList(0, Math.min(2, before.size()))) {
            ok("get_message", "message", m.get("handle"));
        }
        Map<String, List<String>> flagsBefore = new java.util.HashMap<>();
        before.forEach(m -> flagsBefore.put((String) m.get("handle"), flags(m)));
        for (Map<String, Object> m : messages("INBOX")) {
            if (flagsBefore.containsKey(m.get("handle"))) {
                assertThat(flags(m)).as("flags of a message read with PEEK").isEqualTo(flagsBefore.get(m.get("handle")));
            }
        }
    }

    @Test
    @Order(2)
    void oneReversibleWriteSetsAndClearsTheFlaggedFlag() throws Exception {
        assumeTrue(settings.writeEnabled(), "smoke write check skipped: set " + SmokeOAuthEnv.WRITE_VARIABLE
                + "=1 to run it (it sets and clears the flagged flag on one INBOX message)");
        Optional<Map<String, Object>> target = messages("INBOX").stream().filter(m -> !flagged(m)).findFirst();
        assumeTrue(target.isPresent(), "no unflagged message in INBOX to toggle");
        Object handle = target.get().get("handle");
        try {
            Map<String, Object> set = ok("set_flags", "handles", List.of(handle), "flagged", true);
            assertThat(itemStatuses(set)).containsExactly("ok");
            assertThat(messages("INBOX").stream().filter(m -> handle.equals(m.get("handle"))).allMatch(
                    AbstractOAuthLiveSmoke::flagged)).as("flag set").isTrue();
        } finally {
            Map<String, Object> clear = stack.call(box, "set_flags", "handles", List.of(handle), "flagged", false);
            assertThat(OAuthSmokeStack.errorOf(clear)).as("restoring the flag").isEqualTo("none");
        }
        assertThat(messages("INBOX").stream().noneMatch(m -> handle.equals(m.get("handle")) && flagged(m)))
                .as("flag cleared again").isTrue();
    }

    @Test
    @Order(3)
    void purgeFromTrashRemovesTheNamedMessageForGood() throws Exception {
        assumeTrue(settings.purgeSubject().isPresent(), "smoke purge check skipped: set "
                + SmokeOAuthEnv.PURGE_VARIABLE + " to the exact subject of a throwaway message you put in Trash");
        String subject = settings.purgeSubject().get();
        String trash = folderOfRole("trash");
        assertThat(trash).as("a Trash folder").isNotNull();
        List<Map<String, Object>> hits = messages(trash, "subject", subject).stream()
                .filter(m -> subject.equals(subjectOf(m))).toList();
        assumeTrue(!hits.isEmpty(), "no message with the configured subject in Trash");
        assertThat(hits.size()).as("messages in Trash with that subject (refusing to purge more than 3)")
                .isLessThanOrEqualTo(3);

        try (McpSyncClient c = stack.client(box, true)) {
            Map<String, Object> r = OAuthSmokeStack.call(c, box, "purge_messages", "handles",
                    hits.stream().map(m -> m.get("handle")).toList());
            assertThat(OAuthSmokeStack.errorOf(r)).as("purge_messages result").isEqualTo("none");
            assertThat(itemStatuses(r)).containsOnly("ok");
        }
        assertThat(messages(trash, "subject", subject).stream().filter(m -> subject.equals(subjectOf(m))))
                .as("left in Trash").isEmpty();
        String all = folderOfRole("all"); // Gmail: purged for good means gone from All Mail too
        if (all != null) {
            assertThat(messages(all, "subject", subject).stream().filter(m -> subject.equals(subjectOf(m))))
                    .as("left in All Mail").isEmpty();
        }
    }

    @Test
    @Order(4)
    void aForcedRefreshGetsANewAccessTokenAndTheNextCallStillWorks() throws Exception {
        long before = Xoauth2Db.refreshedAtMillis(stack.ds(), box.seeded());
        Thread.sleep(50);
        stack.advance(Duration.ofHours(2)); // past any access token lifetime: the cache must refresh

        ok("list_folders");

        assertThat(Xoauth2Db.refreshedAtMillis(stack.ds(), box.seeded())).as("the grant was refreshed again")
                .isGreaterThan(before);
    }

    @Test
    @Order(5)
    void aRevokedRefreshTokenGivesRelinkRequiredAndFlipsTheAccount() throws Exception {
        String token = settings.revokedRefreshToken().orElse("smoke-revoked-" + java.util.UUID.randomUUID());
        Box revoked = stack.seed(settings, token, true, false);

        Map<String, Object> first = stack.call(revoked, "list_folders");
        assertThat(OAuthSmokeStack.errorOf(first)).as("first call with a revoked token")
                .isEqualTo("AUTH_FAILED/relink_required");
        assertThat(Xoauth2Db.status(stack.ds(), revoked.seeded())).isEqualTo("needs_relink");

        assertThat(OAuthSmokeStack.errorOf(stack.call(revoked, "list_folders"))).as("second call")
                .isEqualTo("AUTH_FAILED/relink_required");
    }

    @SuppressWarnings("unchecked")
    private static List<String> itemStatuses(Map<String, Object> r) {
        return OAuthSmokeStack.list(r, "results").stream().map(i -> (String) i.get("status")).toList();
    }
}
