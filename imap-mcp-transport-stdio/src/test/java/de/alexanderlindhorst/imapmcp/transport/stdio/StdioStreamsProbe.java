package de.alexanderlindhorst.imapmcp.transport.stdio;

/** Main class for the forked JVM of {@link StdioStreamsTest}. Reports only via stderr and stdout bytes. */
public final class StdioStreamsProbe {

    private StdioStreamsProbe() {
    }

    public static void main(String[] args) throws Exception {
        ProtocolStreams streams = StdioStreams.capture();
        System.out.println("via-system-out");
        streams.out().write("PROTOCOL".getBytes());
        streams.out().flush();
        String in = new String(streams.in().readAllBytes());
        System.err.println("protocol-in=" + in);
        System.err.println("system-in=" + System.in.read());
        System.err.println("same=" + (StdioStreams.capture() == streams));
    }
}
