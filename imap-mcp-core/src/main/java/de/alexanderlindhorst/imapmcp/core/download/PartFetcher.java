package de.alexanderlindhorst.imapmcp.core.download;

import jakarta.mail.MessagingException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.BODY;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;
import org.eclipse.angus.mail.util.BASE64DecoderStream;
import org.eclipse.angus.mail.util.QPDecoderStream;

/**
 * The raw reads of {@code download_attachment} on an EXAMINEd folder (SPEC 5.4 "Downloads"): one
 * {@code FETCH (BODYSTRUCTURE)} and the part as {@code BODY.PEEK[section]<offset.length>} blocks of 1 MiB, never
 * {@code BODY[...]} and never {@code BINARY}. The encoded octets are decoded while streaming into a
 * {@link DecodedSink}; memory is bounded by the block size. Errors are mapped by origin: the IMAP source throws
 * {@link MessagingException} (or {@link BlockMissingException}), the decoder {@link DecodeFailedException}, and the
 * sink its own unchecked exceptions, which pass through untouched. Only sequence numbers of the open folder are
 * used, as the caller resolved them from the UID inside the same call.
 */
public final class PartFetcher {

    /** Fixed block size of the partial fetches. */
    public static final int BLOCK = 1024 * 1024;

    private static final int COPY_BUFFER = 16 * 1024;

    /** The decoder rejected the content (truncated base64 quantum, invalid padding, bad quoted-printable escape). */
    public static final class DecodeFailedException extends RuntimeException {
        DecodeFailedException(Throwable cause) {
            super("decode failed", cause);
        }
    }

    /** A block came back as NO, NIL or without a BODY item; {@code atStart}: at offset 0 (the message is gone). */
    public static final class BlockMissingException extends RuntimeException {
        private final boolean atStart;

        BlockMissingException(boolean atStart) {
            super(atStart ? "body missing at start" : "body missing");
            this.atStart = atStart;
        }

        public boolean atStart() {
            return atStart;
        }
    }

    /** Carries a failure of the IMAP source through the decoder's {@link IOException} signature. */
    private static final class SourceFailure extends IOException {
        SourceFailure(Throwable cause) {
            super(cause);
        }
    }

    private PartFetcher() {
    }

    /** {@code FETCH n (BODYSTRUCTURE)}; null when the server answered NO (Angus drops the text). */
    public static BODYSTRUCTURE structure(IMAPFolder openFolder, int seq) throws MessagingException {
        return (BODYSTRUCTURE) openFolder.doCommand(p -> p.fetchBodyStructure(seq));
    }

    /**
     * Streams the part into {@code sink}, decoded by {@code encoding} (lower case; base64, quoted-printable, or
     * anything else as is). Ends at the first block shorter than requested or empty.
     */
    public static void stream(IMAPFolder openFolder, int seq, String section, String encoding, DecodedSink sink)
            throws MessagingException {
        InputStream source = new Source(openFolder, seq, section);
        String enc = encoding == null ? "" : encoding.toLowerCase(Locale.ROOT);
        InputStream decoded = switch (enc) {
            case "base64" -> new BASE64DecoderStream(source);
            case "quoted-printable" -> new QPDecoderStream(source);
            default -> source;
        };
        byte[] buf = new byte[COPY_BUFFER];
        try {
            int n;
            while ((n = decoded.read(buf, 0, buf.length)) != -1) {
                sink.write(buf, 0, n);
            }
        } catch (SourceFailure e) {
            if (e.getCause() instanceof MessagingException me) {
                throw me;
            }
            throw (RuntimeException) e.getCause();
        } catch (IOException e) {
            throw new DecodeFailedException(e);
        }
    }

    /** One {@code BODY.PEEK[section]<start.BLOCK>}; NO, NIL and a missing item are {@link BlockMissingException}. */
    private static byte[] fetchBlock(IMAPFolder folder, int seq, String section, long start) throws MessagingException {
        Object result = folder.doCommand(p -> {
            BODY body = p.peekBody(seq, section, (int) Math.min(start, Integer.MAX_VALUE), BLOCK);
            return body == null || body.getByteArray() == null ? null : body.getByteArray().getNewBytes();
        });
        if (result == null) {
            throw new BlockMissingException(start == 0);
        }
        return (byte[]) result;
    }

    /** The encoded octets of the part, one fetched block at a time. */
    private static final class Source extends InputStream {
        private final IMAPFolder folder;
        private final int seq;
        private final String section;
        private byte[] block = new byte[0];
        private int pos;
        private long offset;
        private boolean end;

        Source(IMAPFolder folder, int seq, String section) {
            this.folder = folder;
            this.seq = seq;
            this.section = section;
        }

        @Override
        public int read() throws IOException {
            if (!fill()) {
                return -1;
            }
            return block[pos++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int n = Math.min(len, block.length - pos);
            System.arraycopy(block, pos, b, off, n);
            pos += n;
            return n;
        }

        private boolean fill() throws IOException {
            while (pos >= block.length) {
                if (end) {
                    return false;
                }
                byte[] next;
                try {
                    next = fetch();
                } catch (MessagingException | RuntimeException e) {
                    throw new SourceFailure(e);
                }
                offset += next.length;
                end = next.length < BLOCK; // a short or empty block ends the part
                block = next;
                pos = 0;
            }
            return true;
        }

        private byte[] fetch() throws MessagingException {
            return fetchBlock(folder, seq, section, offset);
        }
    }
}
