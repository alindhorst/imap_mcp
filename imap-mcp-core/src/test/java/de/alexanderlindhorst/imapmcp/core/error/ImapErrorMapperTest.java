package de.alexanderlindhorst.imapmcp.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FolderClosedException;
import jakarta.mail.MessagingException;
import jakarta.mail.StoreClosedException;
import jakarta.mail.search.SearchException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import org.eclipse.angus.mail.iap.BadCommandException;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.iap.ConnectionException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ImapErrorMapperTest {

    private static final String RID = "req-9";
    private static final String MARKER = "marker-host.example.invalid";

    private static MailConnectException connectEx() {
        return new MailConnectException(new org.eclipse.angus.mail.util.SocketConnectException(
                MARKER, new ConnectException(MARKER), MARKER, 993, 1000));
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                Arguments.of(new AuthenticationFailedException(MARKER), ErrorCode.AUTH_FAILED, true),
                Arguments.of(new de.alexanderlindhorst.imapmcp.core.imap.pool.AccountNeedsRelinkException(),
                        ErrorCode.AUTH_FAILED, false),
                Arguments.of(new FolderClosedException(null, MARKER), ErrorCode.UPSTREAM_UNAVAILABLE, true),
                Arguments.of(new StoreClosedException(null, MARKER), ErrorCode.UPSTREAM_UNAVAILABLE, true),
                Arguments.of(new ConnectException(MARKER), ErrorCode.UPSTREAM_UNAVAILABLE, true),
                Arguments.of(new UnknownHostException(MARKER), ErrorCode.UPSTREAM_UNAVAILABLE, true),
                Arguments.of(new SSLException(MARKER), ErrorCode.UPSTREAM_UNAVAILABLE, true),
                Arguments.of(connectEx(), ErrorCode.UPSTREAM_UNAVAILABLE, true),
                Arguments.of(new ConnectionException(MARKER), ErrorCode.UPSTREAM_UNAVAILABLE, true),
                Arguments.of(new SocketTimeoutException(MARKER), ErrorCode.TIMEOUT, true),
                Arguments.of(new InterruptedException(MARKER), ErrorCode.TIMEOUT, true),
                Arguments.of(new InterruptedIOException(MARKER), ErrorCode.TIMEOUT, true),
                Arguments.of(new RuntimeException(MARKER), ErrorCode.INTERNAL_ERROR, true),
                // wrapped causes
                Arguments.of(new MessagingException(MARKER, new SocketTimeoutException(MARKER)),
                        ErrorCode.TIMEOUT, true),
                Arguments.of(new MessagingException(MARKER, new UnknownHostException(MARKER)),
                        ErrorCode.UPSTREAM_UNAVAILABLE, true),
                Arguments.of(new IllegalStateException(MARKER, new SSLException(MARKER)),
                        ErrorCode.UPSTREAM_UNAVAILABLE, true),
                Arguments.of(new MessagingException(MARKER, new RuntimeException(MARKER)),
                        ErrorCode.INTERNAL_ERROR, true));
    }

    @ParameterizedTest
    @MethodSource("rows")
    void mapsRowAndDiscardFlag(Throwable t, ErrorCode code, boolean discard) {
        ImapErrorMapper.MappedError m = ImapErrorMapper.map(t, RID);
        assertThat(m.error().code()).isEqualTo(code);
        assertThat(m.discardConnection()).isEqualTo(discard);
        assertThat(m.error().requestId()).isEqualTo(RID);
    }

    static Stream<Arguments> loginTexts() {
        return Stream.of(
                Arguments.of("[UNAVAILABLE] Temporary authentication failure", true),
                Arguments.of("  \t[UNAVAILABLE] try later", true),
                Arguments.of("[unavailable] try later", true),
                Arguments.of("[Unavailable]", true),
                Arguments.of("[AUTHENTICATIONFAILED] Invalid credentials", false),
                Arguments.of("Invalid credentials", false),
                Arguments.of("Invalid credentials [UNAVAILABLE]", false),
                Arguments.of("UNAVAILABLE", false),
                Arguments.of("", false),
                Arguments.of(null, false));
    }

    @ParameterizedTest
    @MethodSource("loginTexts")
    void loginRefusalIsUnavailableOnlyWithLeadingCode(String text, boolean unavailable) {
        for (Throwable t : List.of(new AuthenticationFailedException(text),
                new MessagingException("wrapped", new AuthenticationFailedException(text)))) {
            ImapErrorMapper.MappedError m = ImapErrorMapper.map(t, RID);
            if (unavailable) {
                assertThat(m.error().code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
                assertThat(m.error().retryable()).isTrue();
                assertThat(details(m.error())).containsEntry("reason", "login_unavailable");
                assertThat(m.error().hint()).isEqualTo("Retry in a minute or later.");
                assertThat(m.error().toMap().toString()).doesNotContain("Temporary").doesNotContain("UNAVAILABLE]");
            } else {
                assertThat(m.error()).isEqualTo(ToolError.authFailed(RID));
                assertThat(m.error().retryable()).isFalse();
            }
            assertThat(m.discardConnection()).isTrue();
        }
    }

    @Test
    void serverBusyMapsWithoutDiscardAndKeepsCanonicalError() {
        for (Throwable t : List.of(new ServerBusyException(), new IllegalStateException("w", new ServerBusyException()))) {
            ImapErrorMapper.MappedError m = ImapErrorMapper.map(t, RID);
            assertThat(m.error()).isEqualTo(ToolError.serverBusy(RID));
            assertThat(m.discardConnection()).isFalse();
        }
    }

    @Test
    void nextExceptionChainIsUnwrapped() {
        MessagingException outer = new MessagingException("outer");
        outer.setNextException(new SocketTimeoutException("x"));
        assertThat(ImapErrorMapper.map(outer, RID).error().code()).isEqualTo(ErrorCode.TIMEOUT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(ToolError e) {
        return (Map<String, Object>) e.details();
    }

    @Test
    void noAndBadAreUpstreamErrorWithoutDiscard() {
        for (Throwable t : List.of(new CommandFailedException("NO [SERVERBUG] nope"),
                new BadCommandException("BAD nope"),
                new MessagingException("wrapped", new CommandFailedException("NO nope")))) {
            ImapErrorMapper.MappedError m = ImapErrorMapper.map(t, RID);
            assertThat(m.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(m.discardConnection()).isFalse();
            assertThat(details(m.error()).get("server_text").toString()).contains("nope");
        }
    }

    @Test
    void serverTextIsSingleLineBidiFreeAndCapped() {
        String text = "NO bad\r\nline\u202Ereversed " + "y".repeat(500);
        ToolError e = ImapErrorMapper.map(new CommandFailedException(text), RID).error();
        String out = (String) details(e).get("server_text");
        assertThat(out).doesNotContain("\r", "\n", "\u202E");
        assertThat(out.codePointCount(0, out.length())).isLessThanOrEqualTo(200);
    }

    @Test
    void badCharsetSearchIsUnsupportedUtf8Search() {
        for (Throwable t : List.of(new SearchException("[BADCHARSET (US-ASCII)] charset not supported"),
                new MessagingException("x", new SearchException("badcharset")),
                new BadCommandException("BAD [BADCHARSET] nope"))) {
            ImapErrorMapper.MappedError m = ImapErrorMapper.map(t, RID);
            assertThat(m.error().code()).isEqualTo(ErrorCode.UNSUPPORTED);
            assertThat(details(m.error())).containsEntry("capability", "utf8_search");
            assertThat(m.discardConnection()).isFalse();
        }
        // a SearchException without BADCHARSET is not a charset problem
        assertThat(ImapErrorMapper.map(new SearchException("other"), RID).error().code())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void interruptedThreadStateMapsToTimeoutWithoutClearingFlag() {
        Thread.currentThread().interrupt();
        try {
            ImapErrorMapper.MappedError m = ImapErrorMapper.map(new MessagingException("closed"), RID);
            assertThat(m.error().code()).isEqualTo(ErrorCode.TIMEOUT);
            assertThat(m.discardConnection()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void payloadsOfNonUpstreamExceptionsNeverLeakMessageHostOrClass() {
        for (Arguments a : (Iterable<Arguments>) rows()::iterator) {
            Throwable t = (Throwable) a.get()[0];
            String payload = ImapErrorMapper.map(t, RID).error().toMap().toString();
            assertThat(payload).as(t.getClass().getName())
                    .doesNotContain("marker-host", "Exception", "java.", "jakarta.", "org.eclipse", "\tat ");
        }
    }

    @Test
    void cyclicCauseChainTerminates() {
        MessagingException a = new MessagingException("a");
        MessagingException b = new MessagingException("b");
        a.setNextException(b);
        b.setNextException(a);
        assertThat(ImapErrorMapper.map(a, RID).error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void addressBlockedWinsOverTheConnectExceptionThatWrapsIt() {
        MailConnectException wrapped = new MailConnectException(new org.eclipse.angus.mail.util.SocketConnectException(
                MARKER, new de.alexanderlindhorst.imapmcp.core.net.AddressBlockedException(), MARKER, 993, 1000));
        for (Throwable t : List.of(wrapped, new MessagingException(MARKER, wrapped),
                new de.alexanderlindhorst.imapmcp.core.net.AddressBlockedException())) {
            ImapErrorMapper.MappedError m = ImapErrorMapper.map(t, RID);
            assertThat(m.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
            assertThat(m.error().code().retryable()).isFalse();
            assertThat(m.error().details()).containsEntry("reason", "address_blocked");
            assertThat(m.discardConnection()).isTrue();
            assertThat(m.error().toMap().toString()).doesNotContain(MARKER);
        }
    }

    // Angus drops the cause when a folder connection fails ("connection failure"); the refusal is remembered per thread.
    @Test
    void bareConnectionFailureAfterARefusalOnThisThreadIsAddressBlocked() {
        de.alexanderlindhorst.imapmcp.core.net.RefusalMarker.mark();
        ImapErrorMapper.MappedError m = ImapErrorMapper.map(
                new IllegalStateException(MARKER, new MessagingException("connection failure")), RID);
        assertThat(m.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
        assertThat(m.error().details()).containsEntry("reason", "address_blocked");
        // consumed: the same failure again is not attributed to a refusal
        assertThat(ImapErrorMapper.map(new MessagingException("connection failure"), RID).error().code())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void aRefusalMarkerDoesNotRelabelOtherFailures() {
        de.alexanderlindhorst.imapmcp.core.net.RefusalMarker.mark();
        assertThat(ImapErrorMapper.map(new MessagingException("something else"), RID).error().code())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        de.alexanderlindhorst.imapmcp.core.net.RefusalMarker.mark();
        assertThat(ImapErrorMapper.map(new SocketTimeoutException(MARKER), RID).error().code())
                .isEqualTo(ErrorCode.TIMEOUT);
    }

    @Test
    void suppressedRefusalIsFound() {
        MessagingException e = new MessagingException("connection failure");
        e.addSuppressed(new de.alexanderlindhorst.imapmcp.core.net.AddressBlockedException());
        assertThat(ImapErrorMapper.map(e, RID).error().details()).containsEntry("reason", "address_blocked");
    }
}
