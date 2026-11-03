package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import jakarta.mail.MessagingException;
import jakarta.mail.ReadOnlyFolderException;
import java.util.Optional;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.iap.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WriteResponsesTest {

    private static final String RID = "req-1";

    private static ToolError map(String line) {
        return WriteResponses.map(new Response(line), "Archive/2024", RID).orElseThrow();
    }

    @Test
    void tryCreateIsFolderNotFoundForTarget() {
        ToolError e = map("A1 NO [TRYCREATE] no such mailbox");
        assertThat(e.code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(e.details()).containsEntry("folder", "Archive/2024");
    }

    @Test
    void overQuotaIsQuotaExceeded() {
        assertThat(map("A1 NO [OVERQUOTA] mailbox full").code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    void readOnlyResponseIsFolderReadOnly() {
        ToolError e = map("A1 OK [READ-ONLY] Select completed");
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(e.details()).containsEntry("reason", "folder_read_only");
    }

    @Test
    void readOnlyFolderExceptionIsFolderReadOnly() {
        ToolError e = WriteResponses.map(new ReadOnlyFolderException(null, "marker"), "X", RID).orElseThrow();
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(e.details()).containsEntry("reason", "folder_read_only");
        assertThat(e.toMap().toString()).doesNotContain("marker");
    }

    @Test
    void noPermIsServerDenied() {
        ToolError e = map("A1 NO [NOPERM] denied");
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(e.details()).containsEntry("reason", "server_denied");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INUSE", "UNAVAILABLE"})
    void inUseAndUnavailableAreUpstreamUnavailable(String code) {
        assertThat(map("A1 NO [" + code + "] busy").code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANNOT", "LIMIT", "SERVERBUG", "CONTACTADMIN", "ALREADYEXISTS"})
    void knownRfc5530CodesAreUpstreamErrorWithResponseCode(String code) {
        ToolError e = map("A1 NO [" + code + "] nope");
        assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(e.details()).containsEntry("response_code", code);
        assertThat(e.details()).containsKey("server_text");
    }

    @Test
    void responseCodeIsCaseInsensitive() {
        assertThat(map("A1 NO [limit] x").details()).containsEntry("response_code", "LIMIT");
    }

    @Test
    void unknownCodeAndPlainNoAreUpstreamErrorWithoutResponseCode() {
        ToolError unknown = map("A1 NO [X-VENDOR-THING] nope");
        assertThat(unknown.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(unknown.details()).doesNotContainKey("response_code");
        ToolError plain = map("A1 NO copy failed");
        assertThat(plain.code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(plain.details()).doesNotContainKey("response_code");
    }

    @Test
    void expungeIssuedIsIgnored() {
        assertThat(WriteResponses.map(new Response("A1 NO [EXPUNGEISSUED] some messages gone"), "X", RID)).isEmpty();
    }

    @Test
    void okWithoutCodeIsNotAnError() {
        assertThat(WriteResponses.map(new Response("A1 OK done"), "X", RID)).isEmpty();
    }

    @Test
    void serverTextIsSanitizedSingleLineAndCapped() {
        String text = "bad\u0007‮stuff " + "x".repeat(500);
        ToolError e = map("A1 NO " + text);
        String st = (String) e.details().get("server_text");
        assertThat(st.codePointCount(0, st.length())).isLessThanOrEqualTo(200);
        assertThat(st).doesNotContain("\u0007", "‮", "\n");
    }

    @Test
    void commandFailedExceptionUsesItsResponseCodeInChain() {
        Throwable t = new MessagingException("wrapper marker",
                new CommandFailedException(new Response("A1 NO [OVERQUOTA] full")));
        ToolError e = WriteResponses.map(t, "X", RID).orElseThrow();
        assertThat(e.code()).isEqualTo(ErrorCode.QUOTA_EXCEEDED);
        assertThat(e.toMap().toString()).doesNotContain("marker");
    }

    @Test
    void exceptionTextNeverEndsUpInThePayloadUnlessItIsServerText() {
        Optional<ToolError> e = WriteResponses.map(new RuntimeException("secret-marker"), "X", RID);
        assertThat(e).isEmpty();
        ToolError n = WriteResponses.map(new CommandFailedException(new Response("A1 NO [NOPERM] srvtext-marker")),
                "X", RID).orElseThrow();
        assertThat(n.toMap().toString()).doesNotContain("srvtext-marker");
    }
}
