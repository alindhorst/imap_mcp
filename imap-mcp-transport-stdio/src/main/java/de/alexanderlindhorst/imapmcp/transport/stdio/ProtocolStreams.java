package de.alexanderlindhorst.imapmcp.transport.stdio;

import java.io.InputStream;
import java.io.OutputStream;

/** The real process stdin/stdout, reserved for the MCP protocol in stdio mode. */
public record ProtocolStreams(InputStream in, OutputStream out) {
}
