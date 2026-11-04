package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Gmail label effects of SPEC 5.4/5.8 against a real account (#258). Runs only with
 * {@code IMAP_MCP_SMOKE_WRITE=1} on top of the Gmail credentials. Everything happens in two run-specific labels on
 * messages this class appended itself ({@link SmokeWriteFixture}); each test seeds its own message, undoes its
 * label changes where it can, and the fixture trashes the test messages and deletes both labels at the end.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GmailWriteLiveSmoke {

    private SmokeEnv.Settings settings;
    private SmokeWriteFixture fx;

    @BeforeAll
    void start() throws Exception {
        Optional<String> skip = SmokeEnv.writeSkipReason(SmokeEnv.Provider.GMAIL, System.getenv());
        assumeTrue(skip.isEmpty(), () -> skip.orElse(""));
        settings = SmokeEnv.settings(SmokeEnv.Provider.GMAIL, System.getenv()).orElseThrow();
        fx = new SmokeWriteFixture(settings, List.of("all", "junk"));
    }

    @AfterAll
    void stop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    private String role(String role) {
        for (Map<String, Object> f : fx.client.ok("list_folders").list("folders")) {
            if (role.equals(f.get("role"))) {
                return (String) f.get("folder");
            }
        }
        return null;
    }

    @Test
    void copyToAnotherLabelReturnsTheCopyUidAndAHandleForThatLabel() throws Exception {
        String h = fx.seed("copy");
        SmokeClient.Result r = fx.client.ok("copy_messages", "handles", List.of(h), "target_folder", fx.label2);
        assertThat(r.itemStatuses()).containsExactly("ok");
        assertThat(r.newHandle(0)).isNotNull();
        assertThat(fx.client.ok("get_message", "message", r.newHandle(0)).structured())
                .containsEntry("folder", fx.label2);
        assertThat(fx.find(fx.label, "copy")).hasSize(1); // a copy adds the label, the source keeps its own
    }

    @Test
    void copyIntoALabelTheMessageAlreadyHasAddsNoDuplicate() throws Exception {
        String h = fx.seed("copy-again");
        SmokeClient.Result r = fx.client.ok("copy_messages", "handles", List.of(h), "target_folder", fx.label);
        assertThat(r.itemStatuses()).hasSize(1).first().isIn("ok", "already_in_target");
        if (r.newHandle(0) != null) {
            assertThat(fx.client.ok("get_message", "message", r.newHandle(0)).structured())
                    .containsEntry("folder", fx.label); // COPYUID points at the existing UID
        }
        assertThat(fx.find(fx.label, "copy-again")).hasSize(1);
    }

    @Test
    void moveIntoAllArchivesAndMoveBackRestoresTheLabel() throws Exception {
        String h = fx.seed("all");
        SmokeClient.Result in = fx.client.ok("move_messages", "handles", List.of(h), "target_folder_role", "all");
        assertThat(in.itemStatuses()).containsExactly("ok");
        assertThat(fx.client.ok("get_message", "message", in.newHandle(0)).structured())
                .containsEntry("folder", role("all"));
        assertThat(fx.find(fx.label, "all")).isEmpty(); // move replaced the source label

        SmokeClient.Result out = fx.client.ok("move_messages", "handles", List.of(in.newHandle(0)),
                "target_folder", fx.label);
        assertThat(out.itemStatuses()).containsExactly("ok");
        assertThat(fx.find(fx.label, "all")).hasSize(1);
    }

    @Test
    void moveOutOfTrashUntrashes() throws Exception {
        String h = fx.seed("untrash");
        SmokeClient.Result del = fx.client.ok("delete_messages", "handles", List.of(h));
        assertThat(del.itemStatuses()).containsExactly("ok");
        assertThat(fx.client.ok("get_message", "message", del.newHandle(0)).structured())
                .containsEntry("folder", role("trash"));

        SmokeClient.Result back = fx.client.ok("move_messages", "handles", List.of(del.newHandle(0)),
                "target_folder", fx.label);
        assertThat(back.itemStatuses()).containsExactly("ok");
        assertThat(fx.find(fx.label, "untrash")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"junk", "flagged", "important"})
    void copyIntoJunkStarredAndImportantHasThatLabelEffectAndCanBeUndone(String role) throws Exception {
        String folder = role(role);
        assumeTrue(folder != null, "the Gmail account exposes no \\" + role + " folder over IMAP (label visibility "
                + "setting); enable it in the Gmail IMAP settings to run this check");
        String tag = "effect-" + role;
        String h = fx.seed(tag);
        SmokeClient.Result in = fx.client.ok("copy_messages", "handles", List.of(h), "target_folder_role", role);
        assertThat(in.itemStatuses()).containsExactly("ok");
        assertThat(fx.find(folder, tag)).as("message carries the %s label", role).hasSize(1);

        // Undo: moving back from the effect folder removes that label again.
        SmokeClient.Result back = fx.client.ok("move_messages", "handles", List.of(fx.only(folder, tag)),
                "target_folder", fx.label);
        assertThat(back.itemStatuses()).containsExactly("ok");
        assertThat(fx.find(folder, tag)).as("%s label removed again", role).isEmpty();
        assertThat(fx.find(fx.label, tag)).hasSize(1);
    }

    @Test
    void deleteFromALabelTrashesTheMessageAndRemovesAllItsLabels() throws Exception {
        String h = fx.seed("delete");
        fx.client.ok("copy_messages", "handles", List.of(h), "target_folder", fx.label2);
        assertThat(fx.find(fx.label2, "delete")).hasSize(1);

        SmokeClient.Result del = fx.client.ok("delete_messages", "handles", List.of(h));
        assertThat(del.itemStatuses()).containsExactly("ok");
        assertThat(fx.client.ok("get_message", "message", del.newHandle(0)).structured())
                .containsEntry("folder", role("trash"));
        assertThat(fx.find(fx.label, "delete")).isEmpty();
        assertThat(fx.find(fx.label2, "delete")).isEmpty();
    }

    @Test
    void keywordsPersistAcrossSessions() throws Exception {
        String keyword = "$Smoke" + fx.runId.substring(fx.runId.length() - 8).replace("-", "");
        String h = fx.seed("keyword");
        SmokeClient.Result r = fx.client.ok("set_flags", "handles", List.of(h), "add_keywords", List.of(keyword));
        assertThat(r.itemStatuses()).containsExactly("ok");

        try (SmokeClient other = SmokeClient.start(settings, false)) {
            List<Map<String, Object>> found = other.search("folder", fx.label, "subject", fx.subject("keyword"));
            assertThat(found).hasSize(1);
            @SuppressWarnings("unchecked")
            List<String> flags = (List<String>) found.get(0).get("flags");
            assertThat(flags).as("keyword persisted").anyMatch(keyword::equalsIgnoreCase);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"drafts", "sent"})
    void moveIntoDraftsOrSentIsRefused(String role) throws Exception {
        String tag = "refuse-" + role;
        String h = fx.seed(tag);
        SmokeClient.Result r = fx.client.tool("move_messages", "handles", List.of(h), "target_folder_role", role);
        boolean refused = r.isError() || !r.itemStatuses().equals(List.of("ok"));
        assertThat(refused).as("move into %s refused (UPSTREAM_ERROR expected, got %s)", role,
                r.isError() ? r.errorCode() : r.itemStatuses()).isTrue();
        assertThat(fx.find(fx.label, tag)).as("message stays in the test label").hasSize(1);
    }
}
