package de.alexanderlindhorst.imapmcp.core.error;

import de.alexanderlindhorst.imapmcp.core.net.AddressBlockedException;
import de.alexanderlindhorst.imapmcp.core.net.RefusalMarker;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FolderClosedException;
import jakarta.mail.MessagingException;
import jakarta.mail.StoreClosedException;
import jakarta.mail.search.SearchException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.eclipse.angus.mail.iap.BadCommandException;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.iap.ConnectionException;
import org.eclipse.angus.mail.util.MailConnectException;

/**
 * Maps exceptions from Jakarta Mail / Angus to tool errors (SPEC 4, 5.5). The exception chain
 * ({@code getCause()} and {@code MessagingException.getNextException()}) is walked outermost first and the
 * first recognised type decides. Messages, hosts and class names of non-upstream exceptions are never copied
 * into the payload; only NO/BAD server text is, sanitized by {@link ToolError#upstreamError}.
 *
 * <p>BADCHARSET detection: a {@link SearchException}, or a NO/BAD protocol exception, whose message contains
 * "BADCHARSET" (case-insensitive, the IMAP response code of RFC 3501) maps to UNSUPPORTED utf8_search.
 */
public final class ImapErrorMapper {

    private static final int MAX_CHAIN = 16;

    public record MappedError(ToolError error, boolean discardConnection) {
    }

    private ImapErrorMapper() {
    }

    public static MappedError map(Throwable t, String requestId) {
        // Angus wraps the refusal in a MailConnectException, which would otherwise read as "unreachable"; on folder
        // connections it drops the cause and throws a bare "connection failure" (see RefusalMarker).
        boolean refused = RefusalMarker.takeRecent();
        if (causedBy(t, AddressBlockedException.class) || (refused && hasBareConnectionFailure(t))) {
            return new MappedError(ToolError.addressBlocked(requestId), true);
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> queue = new java.util.ArrayDeque<>();
        queue.add(t);
        while (!queue.isEmpty() && seen.size() < MAX_CHAIN) {
            Throwable cur = queue.poll();
            if (!seen.add(cur)) {
                continue;
            }
            MappedError m = classify(cur, requestId);
            if (m != null) {
                return m;
            }
            if (cur.getCause() != null) {
                queue.add(cur.getCause());
            }
            if (cur instanceof MessagingException me && me.getNextException() != null) {
                queue.add(me.getNextException());
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            return new MappedError(ToolError.timeout(requestId), true);
        }
        return new MappedError(ToolError.internalError(requestId), true);
    }

    /** Angus\' bare {@code MessagingException("connection failure")} (cause dropped) anywhere in the chain. */
    public static boolean hasBareConnectionFailure(Throwable t) {
        return anyInChain(t, cur -> cur instanceof MessagingException me && "connection failure".equals(me.getMessage())
                && me.getCause() == null && me.getNextException() == null);
    }

    private static boolean causedBy(Throwable t, Class<? extends Throwable> type) {
        return anyInChain(t, type::isInstance);
    }

    private static boolean anyInChain(Throwable t, java.util.function.Predicate<Throwable> test) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> queue = new java.util.ArrayDeque<>();
        queue.add(t);
        while (!queue.isEmpty() && seen.size() < MAX_CHAIN) {
            Throwable cur = queue.poll();
            if (!seen.add(cur)) {
                continue;
            }
            if (test.test(cur)) {
                return true;
            }
            if (cur.getCause() != null) {
                queue.add(cur.getCause());
            }
            queue.addAll(Arrays.asList(cur.getSuppressed()));
            if (cur instanceof MessagingException me && me.getNextException() != null) {
                queue.add(me.getNextException());
            }
        }
        return false;
    }

    private static MappedError classify(Throwable t, String rid) {
        if (t instanceof ServerBusyException) {
            return new MappedError(ToolError.serverBusy(rid), false);
        }
        if (t instanceof AuthenticationFailedException) {
            return new MappedError(ToolError.authFailed(rid), true);
        }
        boolean charset = (t instanceof SearchException || t instanceof CommandFailedException
                || t instanceof BadCommandException) && mentionsBadCharset(t.getMessage());
        if (charset) {
            return new MappedError(ToolError.unsupported("utf8_search", rid), false);
        }
        if (t instanceof CommandFailedException || t instanceof BadCommandException) {
            return new MappedError(ToolError.upstreamError(t.getMessage(), rid), false);
        }
        if (t instanceof FolderClosedException || t instanceof StoreClosedException
                || t instanceof MailConnectException || t instanceof ConnectionException
                || t instanceof ConnectException || t instanceof UnknownHostException
                || t instanceof SSLException) {
            return new MappedError(ToolError.upstreamUnavailable(rid), true);
        }
        if (t instanceof SocketTimeoutException || t instanceof InterruptedException
                || t instanceof InterruptedIOException) {
            return new MappedError(ToolError.timeout(rid), true);
        }
        return null;
    }

    private static boolean mentionsBadCharset(String message) {
        return message != null && message.toUpperCase(Locale.ROOT).contains("BADCHARSET");
    }
}
