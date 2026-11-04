package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The audit rules themselves (#207), on synthetic traces, so a green wire audit cannot be vacuous. No Docker. */
class ImapWireAuditTest {

    @Test
    void clientCommandStripsTheTagAndIgnoresServerLines() {
        assertThat(ImapWireAudit.clientCommand("A5 UID MOVE 1 \"X\"")).contains("UID MOVE 1 \"X\"");
        assertThat(ImapWireAudit.clientCommand("A5 OK Move completed")).isEmpty();
        assertThat(ImapWireAudit.clientCommand("A5 NO [TRYCREATE] nope")).isEmpty();
        assertThat(ImapWireAudit.clientCommand("A5 BAD x")).isEmpty();
        assertThat(ImapWireAudit.clientCommand("* 3 EXISTS")).isEmpty();
        assertThat(ImapWireAudit.clientCommand("+ go ahead")).isEmpty();
    }

    @Test
    void aCleanWriteTraceHasNoViolation() {
        List<String> trace = List.of("LOGIN <redacted>", "SELECT \"A\"", "UID FETCH 1:2 (UID FLAGS)",
                "UID MOVE 1 \"T\"", "UNSELECT", "EXAMINE \"A\"", "UID FETCH 1 (BODY.PEEK[1])", "CLOSE",
                "SELECT \"B\"", "UID COPY 1 \"T\"", "UID STORE 1 +FLAGS.SILENT (\\Deleted)", "UID EXPUNGE 1",
                "EXAMINE \"B\"", "CLOSE", "LOGOUT");
        assertThat(ImapWireAudit.violations(trace)).isEmpty();
    }

    @Test
    void nonUidCopyMoveStoreAreViolations() {
        assertThat(ImapWireAudit.violations(List.of("COPY 1 \"T\""))).hasSize(1);
        assertThat(ImapWireAudit.violations(List.of("MOVE 1 \"T\""))).hasSize(1);
        assertThat(ImapWireAudit.violations(List.of("STORE 1 +FLAGS (\\Seen)"))).hasSize(1);
    }

    @Test
    void plainExpungeIsAViolationButUidExpungeIsNot() {
        assertThat(ImapWireAudit.violations(List.of("EXPUNGE"))).hasSize(1);
        assertThat(ImapWireAudit.violations(List.of("UID EXPUNGE 4"))).isEmpty();
    }

    @Test
    void closeWhileReadWriteSelectedIsAViolationButAfterExamineIsNot() {
        assertThat(ImapWireAudit.violations(List.of("SELECT \"A\"", "CLOSE"))).hasSize(1);
        assertThat(ImapWireAudit.violations(List.of("SELECT \"A\"", "EXAMINE \"A\"", "CLOSE"))).isEmpty();
        assertThat(ImapWireAudit.violations(List.of("SELECT \"A\"", "UNSELECT", "CLOSE"))).isEmpty();
        assertThat(ImapWireAudit.violations(List.of("EXAMINE \"A\"", "CLOSE"))).isEmpty();
    }

    @Test
    void bodyWithoutPeekIsAViolation() {
        assertThat(ImapWireAudit.violations(List.of("UID FETCH 1 (BODY[1])"))).hasSize(1);
        assertThat(ImapWireAudit.violations(List.of("UID FETCH 1 (BODY.PEEK[1])"))).isEmpty();
        assertThat(ImapWireAudit.violations(List.of("UID FETCH 1 (BODYSTRUCTURE)"))).isEmpty();
    }

    @Test
    void strictEnableRuleForbidsAnyEnable() {
        assertThat(ImapWireAudit.enableViolations(List.of("ENABLE UTF8=ACCEPT"), true)).hasSize(1);
        assertThat(ImapWireAudit.enableViolations(List.of("UID FETCH 1 (UID)"), true)).isEmpty();
    }

    @Test
    void relaxedEnableRuleAllowsOnlyUtf8AcceptAndNeverCondstoreOrQresync() {
        assertThat(ImapWireAudit.enableViolations(List.of("ENABLE UTF8=ACCEPT"), false)).isEmpty();
        assertThat(ImapWireAudit.enableViolations(List.of("ENABLE CONDSTORE"), false)).hasSize(1);
        assertThat(ImapWireAudit.enableViolations(List.of("ENABLE QRESYNC"), false)).hasSize(1);
        assertThat(ImapWireAudit.enableViolations(List.of("ENABLE UTF8=ACCEPT CONDSTORE"), false)).hasSize(1);
    }

    @Test
    void selectingOrExaminingATargetIsAViolation() {
        List<String> trace = List.of("SELECT \"Src\"", "UID MOVE 1 \"Tgt\"", "UNSELECT");
        assertThat(ImapWireAudit.selectedFolders(trace, List.of("Tgt"))).isEmpty();
        assertThat(ImapWireAudit.selectedFolders(List.of("EXAMINE \"Tgt\""), List.of("Tgt"))).hasSize(1);
        assertThat(ImapWireAudit.selectedFolders(List.of("SELECT Tgt"), List.of("Tgt"))).hasSize(1);
    }

    @Test
    void firstFolderCommandFindsTheFirstSelectOrExamine() {
        assertThat(ImapWireAudit.firstFolderCommand(List.of("NOOP", "EXAMINE \"A\"", "SELECT \"B\"")))
                .isEqualTo(Optional.of("EXAMINE \"A\""));
        assertThat(ImapWireAudit.firstFolderCommand(List.of("NOOP"))).isEmpty();
    }
}
