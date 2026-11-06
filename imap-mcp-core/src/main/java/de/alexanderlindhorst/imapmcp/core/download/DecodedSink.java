package de.alexanderlindhorst.imapmcp.core.download;

/**
 * Receiver of the decoded bytes of one attachment (SPEC 4): the file sink ({@link DownloadSink}) or the in-memory
 * sink ({@link MemorySink}). Implementations count and cap the bytes and throw {@link DownloadTooLargeException}
 * beyond their limit; they never log content.
 */
public interface DecodedSink {

    void write(byte[] b, int off, int len);
}
